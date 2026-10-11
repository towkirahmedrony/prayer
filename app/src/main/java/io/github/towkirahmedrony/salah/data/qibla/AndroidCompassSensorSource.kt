package io.github.towkirahmedrony.salah.data.qibla

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassAvailability
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassReading
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassSensorFamily
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassSensorSource
import io.github.towkirahmedrony.salah.core.domain.qibla.DeviceRotation
import io.github.towkirahmedrony.salah.core.domain.qibla.HeadingMath
import io.github.towkirahmedrony.salah.core.domain.qibla.MagneticAccuracy
import io.github.towkirahmedrony.salah.core.domain.qibla.SensorAxis
import io.github.towkirahmedrony.salah.core.domain.qibla.SensorFrames
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs
import kotlin.math.max

/**
 * The device compass, over `SensorManager`.
 *
 * **Sensor selection.** Sensors are probed in descending order of quality and the first usable
 * combination wins:
 *
 * 1. `TYPE_ROTATION_VECTOR` — the fused orientation. Preferred because it is north-referenced,
 *    already tilt-compensated and gyroscope-assisted, so it tracks movement smoothly instead of
 *    jumping whenever the magnetometer is disturbed.
 * 2. `TYPE_GEOMAGNETIC_ROTATION_VECTOR` — a magnetometer-only rotation vector, appreciably cheaper
 *    to run, used when the fused sensor is absent.
 * 3. `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` — the classical pair, combined by
 *    `SensorManager.getRotationMatrix`. The noisiest option, and the last resort for devices that
 *    have no rotation vector at all.
 *
 * If none is present the source reports [CompassAvailability.Unavailable] and the Qibla screen keeps
 * working without a live dial, because the bearing itself needs no sensor.
 *
 * **Why a flow rather than a listener.** Registration happens on collection and unregistration in
 * `awaitClose`, so the sensor's lifetime *is* the screen's lifetime. There is no separate
 * start/stop pair to forget and no way for a listener to outlive the UI that wanted it, which is
 * the easiest way for a compass to drain a battery.
 *
 * **Why the sampling period is explicit and why nothing is logged per sample.** 100 000 µs is
 * 10 Hz: `SENSOR_DELAY_UI` would deliver roughly six times that for a needle that would look no
 * steadier. Correspondingly, a single sensor sample is *not* a diagnostic event — at 10 Hz it would
 * be ten log lines a second, which is noise, not evidence. Only the transitions that matter are
 * logged: registration, failure to register, and teardown.
 *
 * **Devices without a usable magnetic sensor.** Note that some hardware reports
 * `SENSOR_STATUS_UNRELIABLE` continuously. Salah does not try to detect that by second-guessing the
 * platform; the value is passed through, and the *use* of it is gated and dismissible — see
 * [io.github.towkirahmedrony.salah.core.domain.qibla.CompassAccuracyGate] and the Qibla screen.
 */
class AndroidCompassSensorSource(
    context: Context,
    private val logger: DiagnosticsLogger,
) : CompassSensorSource {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val displayManager: DisplayManager? =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val rotationVectorSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val geomagneticRotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val accelerometerSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magneticFieldSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** The chosen combination, or `null` when this device has nothing usable. */
    private val family: CompassSensorFamily? = when {
        rotationVectorSensor != null -> CompassSensorFamily.ROTATION_VECTOR
        geomagneticRotationSensor != null -> CompassSensorFamily.GEOMAGNETIC_ROTATION_VECTOR
        accelerometerSensor != null && magneticFieldSensor != null ->
            CompassSensorFamily.ACCELEROMETER_AND_MAGNETIC_FIELD
        else -> null
    }

    override fun availability(): CompassAvailability {
        val chosen = family ?: return CompassAvailability.Unavailable

        val availability = CompassAvailability.Available(family = chosen, reportsAccuracy = true)
        logger.info(
            SalahDiagnosticEvents.QIBLA_SENSOR_AVAILABILITY,
            mapOf("available" to true, "family" to chosen.name),
        )
        return availability
    }

    override fun readings(): Flow<CompassReading> = callbackFlow {
        val manager = sensorManager
        val chosen = family
        if (manager == null || chosen == null) {
            close()
            return@callbackFlow
        }

        val sink = this
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        // Only used by the accelerometer/magnetometer fallback, to isolate gravity from motion.
        val gravity = FloatArray(3)
        val magneticField = FloatArray(3)
        var hasGravity = false
        var hasMagneticField = false
        var magneticAccuracy = MagneticAccuracy.UNKNOWN

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR,
                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
                    -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        emitHeading(
                            sink = sink,
                            rotationMatrix = rotationMatrix,
                            remapped = remappedMatrix,
                            orientationOut = orientation,
                            accuracy = accuracyFrom(event.accuracy),
                            timestampMillis = System.currentTimeMillis(),
                        )
                    }

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        // Raw field values are not needed individually; the accuracy status is what
                        // carries the calibration signal for this sensor family.
                        System.arraycopy(event.values, 0, magneticField, 0, GRAVITY_AXIS_COUNT)
                        hasMagneticField = true
                        magneticAccuracy = accuracyFrom(event.accuracy)

                        // Emitted from the magnetometer sample only. Both sensors run at the same
                        // period, so emitting from both would double the rate for no extra
                        // information — the heading needs a reading from each.
                        if (hasGravity) {
                            emitFromAccelerometerAndMagnetometer(
                                sink = sink,
                                rotationMatrix = rotationMatrix,
                                remapped = remappedMatrix,
                                orientationOut = orientation,
                                gravity = gravity,
                                magneticField = magneticField,
                                accuracy = magneticAccuracy,
                                timestampMillis = System.currentTimeMillis(),
                            )
                        }
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        // Low-pass filter to separate gravity from the user's own movement; the raw
                        // signal would move the derived tilt with every hand tremor.
                        for (axis in 0 until GRAVITY_AXIS_COUNT) {
                            gravity[axis] = GRAVITY_FILTER_ALPHA * gravity[axis] +
                                (1f - GRAVITY_FILTER_ALPHA) * event.values[axis]
                        }
                        hasGravity = true
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Accuracy also arrives with each sample, so nothing extra is required here.
            }
        }

        val registered = register(manager, chosen, listener)
        if (!registered) {
            logger.warn(
                SalahDiagnosticEvents.QIBLA_SENSOR_REGISTRATION_FAILED,
                mapOf("family" to chosen.name),
            )
            close()
            return@callbackFlow
        }

        logger.info(
            SalahDiagnosticEvents.QIBLA_COMPASS_STARTED,
            mapOf("family" to chosen.name, "periodMicros" to SAMPLING_PERIOD_MICROS),
        )

        awaitClose {
            manager.unregisterListener(listener)
            logger.info(
                SalahDiagnosticEvents.QIBLA_COMPASS_STOPPED,
                mapOf("family" to chosen.name),
            )
        }
    }

    /** Registers the listener for [chosen], or returns `false` when the sensor cannot be used. */
    private fun register(
        manager: SensorManager,
        chosen: CompassSensorFamily,
        listener: SensorEventListener,
    ): Boolean {
        val handler = Handler(Looper.getMainLooper())
        return when (chosen) {
            CompassSensorFamily.ROTATION_VECTOR -> {
                val sensor = rotationVectorSensor ?: return false
                manager.registerListener(listener, sensor, SAMPLING_PERIOD_MICROS, handler)
            }

            CompassSensorFamily.GEOMAGNETIC_ROTATION_VECTOR -> {
                val sensor = geomagneticRotationSensor ?: return false
                manager.registerListener(listener, sensor, SAMPLING_PERIOD_MICROS, handler)
            }

            CompassSensorFamily.ACCELEROMETER_AND_MAGNETIC_FIELD -> {
                val accelerometer = accelerometerSensor ?: return false
                val magnetic = magneticFieldSensor ?: return false
                val accelerometerOk =
                    manager.registerListener(listener, accelerometer, SAMPLING_PERIOD_MICROS, handler)
                val magneticOk =
                    manager.registerListener(listener, magnetic, SAMPLING_PERIOD_MICROS, handler)
                accelerometerOk && magneticOk
            }
        }
    }

    private fun emitFromAccelerometerAndMagnetometer(
        sink: ProducerScope<CompassReading>,
        rotationMatrix: FloatArray,
        remapped: FloatArray,
        orientationOut: FloatArray,
        gravity: FloatArray,
        magneticField: FloatArray,
        accuracy: MagneticAccuracy,
        timestampMillis: Long,
    ) {
        // Returns false when the two vectors are degenerate — for instance while the device is in
        // free fall — in which case there is no orientation to report and the sample is dropped.
        val usable = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magneticField)
        if (!usable) return

        emitHeading(
            sink = sink,
            rotationMatrix = rotationMatrix,
            remapped = remapped,
            orientationOut = orientationOut,
            accuracy = accuracy,
            timestampMillis = timestampMillis,
        )
    }

    private fun emitHeading(
        sink: ProducerScope<CompassReading>,
        rotationMatrix: FloatArray,
        remapped: FloatArray,
        orientationOut: FloatArray,
        accuracy: MagneticAccuracy,
        timestampMillis: Long,
    ) {
        val frame = SensorFrames.headingFrameFor(currentDisplayRotation())
        val remappedOk = SensorManager.remapCoordinateSystem(
            rotationMatrix,
            frame.horizontal.toAndroidAxis(),
            frame.forward.toAndroidAxis(),
            remapped,
        )
        if (!remappedOk) return

        SensorManager.getOrientation(remapped, orientationOut)

        // orientationOut is (azimuth, pitch, roll) in radians. The azimuth runs clockwise from
        // **magnetic** north, which is why it is tagged as magnetic here and must pass through
        // HeadingMath.toTrueHeading exactly once before it is compared with the Qibla bearing.
        val azimuth = HeadingMath.normalize(Math.toDegrees(orientationOut[0].toDouble()))
        val tilt = max(
            abs(Math.toDegrees(orientationOut[1].toDouble())),
            abs(Math.toDegrees(orientationOut[2].toDouble())),
        )

        sink.trySend(
            CompassReading(
                magneticAzimuthDegrees = azimuth,
                accuracy = accuracy,
                timestampMillis = timestampMillis,
                tiltDegrees = tilt,
            ),
        )
    }

    /**
     * The screen's rotation right now.
     *
     * Read per sample rather than observed through a listener, because the activity declares
     * `configChanges="orientation|screenSize|..."` and is therefore not recreated when the screen
     * rotates. Reading the current value on each sample means a rotation is reflected in the very
     * next heading, instead of after a restart that will not happen.
     */
    private fun currentDisplayRotation(): DeviceRotation {
        val rotation = runCatching {
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull() ?: Surface.ROTATION_0

        return when (rotation) {
            Surface.ROTATION_90 -> DeviceRotation.ROTATED_90
            Surface.ROTATION_180 -> DeviceRotation.ROTATED_180
            Surface.ROTATION_270 -> DeviceRotation.ROTATED_270
            else -> DeviceRotation.NATURAL
        }
    }

    private fun SensorAxis.toAndroidAxis(): Int = when (this) {
        SensorAxis.X -> SensorManager.AXIS_X
        SensorAxis.Y -> SensorManager.AXIS_Y
        SensorAxis.Z -> SensorManager.AXIS_Z
        SensorAxis.MINUS_X -> SensorManager.AXIS_MINUS_X
        SensorAxis.MINUS_Y -> SensorManager.AXIS_MINUS_Y
        SensorAxis.MINUS_Z -> SensorManager.AXIS_MINUS_Z
    }

    private fun accuracyFrom(sensorStatus: Int): MagneticAccuracy = when (sensorStatus) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> MagneticAccuracy.HIGH
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MagneticAccuracy.MEDIUM
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> MagneticAccuracy.LOW
        SensorManager.SENSOR_STATUS_UNRELIABLE -> MagneticAccuracy.UNRELIABLE
        else -> MagneticAccuracy.UNKNOWN
    }

    private companion object {
        /**
         * Requested sampling period: 100 000 µs, or 10 Hz.
         *
         * Deliberately not `SENSOR_DELAY_UI`, which is roughly six times faster and would spend
         * battery to produce a needle that looks no steadier.
         */
        const val SAMPLING_PERIOD_MICROS: Int = 100_000

        /** Weight kept from the previous gravity estimate, so the derived tilt does not shake. */
        const val GRAVITY_FILTER_ALPHA: Float = 0.8f

        /** Number of axes in a sensor vector. */
        const val GRAVITY_AXIS_COUNT: Int = 3
    }
}
