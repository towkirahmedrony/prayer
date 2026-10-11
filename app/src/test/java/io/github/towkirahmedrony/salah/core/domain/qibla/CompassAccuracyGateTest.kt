package io.github.towkirahmedrony.salah.core.domain.qibla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The warm-up gate in front of the calibration advice.
 *
 * The behaviour being pinned is restraint: Salah should say nothing about calibration until the
 * sensor has had a chance to report a real accuracy, because a magnetometer reports `UNRELIABLE`
 * while it is still settling and telling the user to wave the device in a figure-eight in that first
 * tenth of a second would be both wrong and irritating.
 */
class CompassAccuracyGateTest {

    @Test
    fun `nothing is reported while the sensor is still warming up`() {
        val gate = CompassAccuracyGate(warmUpSamples = 5)

        repeat(4) {
            assertNull("reported at sample ${it + 1}", gate.accept(MagneticAccuracy.UNRELIABLE))
        }
    }

    @Test
    fun `the value is passed through once the warm-up completes`() {
        val gate = CompassAccuracyGate(warmUpSamples = 5)

        repeat(4) { gate.accept(MagneticAccuracy.UNRELIABLE) }

        assertEquals(MagneticAccuracy.UNRELIABLE, gate.accept(MagneticAccuracy.UNRELIABLE))
        assertEquals(MagneticAccuracy.HIGH, gate.accept(MagneticAccuracy.HIGH))
    }

    @Test
    fun `the reported accuracy is never altered`() {
        val gate = CompassAccuracyGate(warmUpSamples = 1)

        MagneticAccuracy.entries.forEach { accuracy ->
            assertEquals(accuracy, gate.accept(accuracy))
        }
    }

    @Test
    fun `reset restarts the warm-up`() {
        val gate = CompassAccuracyGate(warmUpSamples = 3)
        repeat(3) { gate.accept(MagneticAccuracy.LOW) }
        assertEquals(MagneticAccuracy.LOW, gate.accept(MagneticAccuracy.LOW))

        gate.reset()

        assertNull(gate.accept(MagneticAccuracy.LOW))
        assertNull(gate.accept(MagneticAccuracy.LOW))
        assertEquals(MagneticAccuracy.LOW, gate.accept(MagneticAccuracy.LOW))
    }

    @Test
    fun `the sample count tracks what has been seen`() {
        val gate = CompassAccuracyGate(warmUpSamples = 3)

        assertEquals(0, gate.sampleCount)
        gate.accept(MagneticAccuracy.MEDIUM)
        gate.accept(MagneticAccuracy.MEDIUM)

        assertEquals(2, gate.sampleCount)

        gate.reset()
        assertEquals(0, gate.sampleCount)
    }

    @Test
    fun `the default warm-up is about a second of the compass sampling rate`() {
        // Ten samples at the 10 Hz the compass is sampled at. Long enough for the fusion to produce
        // a real value, short enough that a genuinely uncalibrated device is told promptly.
        assertEquals(10, CompassAccuracyGate.DEFAULT_WARM_UP_SAMPLES)
    }

    @Test
    fun `a gate with no warm-up reports immediately`() {
        val gate = CompassAccuracyGate(warmUpSamples = 0)

        assertEquals(MagneticAccuracy.LOW, gate.accept(MagneticAccuracy.LOW))
    }
}
