package io.github.towkirahmedrony.salah.core.domain.qibla

/**
 * How the screen is rotated relative to the device's natural orientation.
 *
 * Mirrors `android.view.Surface.ROTATION_*`, restated here so the mapping below can be unit-tested
 * without Android.
 */
enum class DeviceRotation { NATURAL, ROTATED_90, ROTATED_180, ROTATED_270 }

/**
 * An axis of the device's own coordinate frame.
 *
 * Mirrors `android.hardware.SensorManager.AXIS_*` so the domain can name the axes without
 * importing them; the Android layer maps these onto the platform constants.
 */
enum class SensorAxis { X, Y, Z, MINUS_X, MINUS_Y, MINUS_Z }

/**
 * The two axes that define the reference frame a heading should be reported in.
 *
 * @param horizontal the device axis that becomes the frame's X axis.
 * @param forward the device axis that becomes the frame's Y axis — this is the axis whose direction
 *   the reported heading describes.
 */
data class HeadingFrame(val horizontal: SensorAxis, val forward: SensorAxis)

/**
 * Chooses the reference frame for the device's current screen rotation.
 *
 * **The problem this solves.** `SensorManager.getOrientation` reports the azimuth of the device's
 * **natural** Y axis, not of whatever edge is currently "up" on screen. On a phone held in
 * landscape, those are different physical edges, so feeding the raw sensor frame straight to the
 * UI makes the compass read 90° out. The fix is to re-express the rotation matrix in a frame whose
 * Y axis follows the display.
 *
 * **The mapping.** The Android documentation for `remapCoordinateSystem` gives the rotated case
 * directly: *"Using the device as a mechanical compass when rotation is `Surface.ROTATION_90`:
 * `remapCoordinateSystem(inR, AXIS_Y, AXIS_MINUS_X, outR)`"*. Successive quarter turns of the
 * screen therefore advance both axes through the same cycle `X → Y → −X → −Y → X`:
 *
 * ```text
 * NATURAL      → ( X,  Y)
 * ROTATED_90   → ( Y, −X)
 * ROTATED_180  → (−X, −Y)
 * ROTATED_270  → (−Y,  X)
 * ```
 *
 * Note that this is required only because the reported *angles* need a different frame. The app's
 * activity declares `configChanges="orientation|screenSize|..."`, so it is not recreated when the
 * screen rotates; the rotation is read per sensor sample instead, which is what keeps the heading
 * correct immediately rather than after a restart.
 */
object SensorFrames {

    /** The frame the heading must be reported in for [rotation]. */
    fun headingFrameFor(rotation: DeviceRotation): HeadingFrame = when (rotation) {
        DeviceRotation.NATURAL -> HeadingFrame(SensorAxis.X, SensorAxis.Y)
        DeviceRotation.ROTATED_90 -> HeadingFrame(SensorAxis.Y, SensorAxis.MINUS_X)
        DeviceRotation.ROTATED_180 -> HeadingFrame(SensorAxis.MINUS_X, SensorAxis.MINUS_Y)
        DeviceRotation.ROTATED_270 -> HeadingFrame(SensorAxis.MINUS_Y, SensorAxis.X)
    }
}
