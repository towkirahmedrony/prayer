package io.github.towkirahmedrony.salah.testing

import io.github.towkirahmedrony.salah.core.domain.qibla.CompassAvailability
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassReading
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassSensorFamily
import io.github.towkirahmedrony.salah.core.domain.qibla.CompassSensorSource
import io.github.towkirahmedrony.salah.core.domain.qibla.GeomagneticDeclinationProvider
import io.github.towkirahmedrony.salah.core.domain.qibla.MagneticAccuracy
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * Fixtures for the Qibla compass.
 *
 * The sensor and the declination model are the two parts of the feature that only exist on a device, so
 * both are behind ports and both are faked here. That is what lets the ViewModel's *wiring* — which
 * location it reads, whether a fix is adopted, whether the heading is on the true-north reference, and
 * whether the sensor is released — be checked on the JVM, leaving only the platform's own behaviour to
 * the manual checklist.
 *
 * Location fakes live in `LocationTestFixtures.kt` and are shared with these tests rather than
 * duplicated, because the Qibla write path is the location subsystem's write path.
 */

/**
 * A compass a test drives.
 *
 * Readings are pushed through a [MutableSharedFlow] so a test controls exactly when a sample arrives,
 * and collection is counted so the lifecycle can be asserted: [collections] is how many times the flow
 * was started and [activeCollections] is how many collectors are live *right now*, which is the number
 * that must return to zero when the screen stops.
 *
 * Mirrors the real source's contract in the way that matters — `readings()` is cold, so the count is
 * tied to collection rather than to a separate start/stop call.
 */
class FakeCompassSensorSource(
    private val availability: CompassAvailability = CompassAvailability.Available(
        family = CompassSensorFamily.ROTATION_VECTOR,
        reportsAccuracy = true,
    ),
) : CompassSensorSource {

    private val readings = MutableSharedFlow<CompassReading>(extraBufferCapacity = 64)

    /** How many times [readings] has been collected. */
    var collections: Int = 0
        private set

    /** How many collectors are live now. Must be zero once the screen has stopped. */
    var activeCollections: Int = 0
        private set

    override fun availability(): CompassAvailability = availability

    override fun readings(): Flow<CompassReading> = readings
        .onStart {
            collections++
            activeCollections++
        }
        .onCompletion { activeCollections-- }

    /** Emits one platform sample. */
    fun emit(reading: CompassReading) {
        readings.tryEmit(reading)
    }

    /** Emits one platform sample from its parts. */
    fun emit(
        magneticDegrees: Double,
        accuracy: MagneticAccuracy = MagneticAccuracy.HIGH,
        tiltDegrees: Double = 5.0,
        timestampMillis: Long = 0L,
    ) {
        emit(
            CompassReading(
                magneticAzimuthDegrees = magneticDegrees,
                accuracy = accuracy,
                timestampMillis = timestampMillis,
                tiltDegrees = tiltDegrees,
            ),
        )
    }
}

/**
 * Declination a test fixes.
 *
 * A fixed value rather than a model evaluation, so the test asserts the *step* — that declination is
 * applied to the heading once and to nothing else — independently of what the World Magnetic Model
 * happens to return at a coordinate. `null` models a location the model could not be evaluated at.
 */
class FixedDeclinationProvider(
    private val degrees: Double? = 0.0,
) : GeomagneticDeclinationProvider {

    var calls: Int = 0
        private set

    override fun declinationDegrees(
        location: LocationInfo,
        atEpochMillis: Long,
        altitudeMetres: Double?,
    ): Double? {
        calls++
        return degrees
    }
}
