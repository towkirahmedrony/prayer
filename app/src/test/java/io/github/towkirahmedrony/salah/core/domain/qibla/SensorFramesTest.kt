package io.github.towkirahmedrony.salah.core.domain.qibla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The screen-rotation reference frame.
 *
 * This is the "sensor to UI" transformation the Qibla screen depends on: `SensorManager` reports the
 * azimuth of the device's *natural* Y axis, and on a phone held in landscape that is the wrong
 * physical edge by a quarter turn. The mapping is small, easy to get backwards, and impossible to
 * test without a rotating device — so it is a pure function and it is tested here.
 *
 * The `ROTATED_90` entry is the one stated in the Android documentation for
 * `remapCoordinateSystem`; the other three follow from applying the same quarter turn repeatedly,
 * and that relationship is itself asserted.
 */
class SensorFramesTest {

    @Test
    fun `the natural orientation uses the device axes unchanged`() {
        assertEquals(
            HeadingFrame(horizontal = SensorAxis.X, forward = SensorAxis.Y),
            SensorFrames.headingFrameFor(DeviceRotation.NATURAL),
        )
    }

    @Test
    fun `a screen rotated 90 degrees reports the axis the documentation specifies`() {
        // Android's own example for using a device as a mechanical compass at ROTATION_90:
        // remapCoordinateSystem(inR, AXIS_Y, AXIS_MINUS_X, outR).
        assertEquals(
            HeadingFrame(horizontal = SensorAxis.Y, forward = SensorAxis.MINUS_X),
            SensorFrames.headingFrameFor(DeviceRotation.ROTATED_90),
        )
    }

    @Test
    fun `a screen rotated 180 degrees mirrors both axes`() {
        assertEquals(
            HeadingFrame(horizontal = SensorAxis.MINUS_X, forward = SensorAxis.MINUS_Y),
            SensorFrames.headingFrameFor(DeviceRotation.ROTATED_180),
        )
    }

    @Test
    fun `a screen rotated 270 degrees completes the cycle`() {
        assertEquals(
            HeadingFrame(horizontal = SensorAxis.MINUS_Y, forward = SensorAxis.X),
            SensorFrames.headingFrameFor(DeviceRotation.ROTATED_270),
        )
    }

    @Test
    fun `each quarter turn advances both axes one step round the cycle`() {
        // X -> Y -> -X -> -Y -> X. Asserting the *cycle* rather than only the four literal values
        // catches a plausible-looking edit that gets one rotation right and the next one wrong.
        val cycle = listOf(
            SensorAxis.X,
            SensorAxis.Y,
            SensorAxis.MINUS_X,
            SensorAxis.MINUS_Y,
        )

        DeviceRotation.entries.forEachIndexed { index, rotation ->
            val frame = SensorFrames.headingFrameFor(rotation)

            assertEquals(
                "horizontal axis for $rotation",
                cycle[index % cycle.size],
                frame.horizontal,
            )
            assertEquals(
                "forward axis for $rotation",
                cycle[(index + 1) % cycle.size],
                frame.forward,
            )
        }
    }

    @Test
    fun `no frame asks for the same axis twice`() {
        // remapCoordinateSystem returns false and leaves the output untouched when the two axes name
        // the same direction, which would silently keep the unrotated frame — a wrong heading with no
        // error anywhere.
        DeviceRotation.entries.forEach { rotation ->
            val frame = SensorFrames.headingFrameFor(rotation)
            assertNotEquals("$rotation repeats an axis", frame.horizontal, frame.forward)
        }
    }

    @Test
    fun `all four frames are distinct`() {
        val frames = DeviceRotation.entries.map { SensorFrames.headingFrameFor(it) }

        assertEquals(frames.size, frames.toSet().size)
    }

    @Test
    fun `every in-plane axis is used as the forward axis exactly once`() {
        // The forward axis is the one whose direction the heading describes, so the four rotations
        // must cover the four in-plane directions — one each, and none of the Z directions, which
        // point out of the screen and cannot describe a compass bearing.
        val forwardAxes = DeviceRotation.entries.map { SensorFrames.headingFrameFor(it).forward }

        assertEquals(
            setOf(SensorAxis.X, SensorAxis.Y, SensorAxis.MINUS_X, SensorAxis.MINUS_Y),
            forwardAxes.toSet(),
        )
    }
}
