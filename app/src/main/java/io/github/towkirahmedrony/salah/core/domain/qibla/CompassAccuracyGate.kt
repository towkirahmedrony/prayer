package io.github.towkirahmedrony.salah.core.domain.qibla

/**
 * Screen in front of the calibration advice, so Salah does not nag on the strength of a warm-up
 * value.
 *
 * **The problem.** A magnetometer is not calibrated the instant the screen opens. Android reports
 * `SENSOR_STATUS_UNRELIABLE` while the sensor and its fusion are still settling — typically for the
 * first handful of samples — and telling the user to wave the device in a figure-eight at that
 * moment would be both wrong and irritating. Worse, a minority of devices report `UNRELIABLE`
 * permanently because their sensor simply does not publish a calibratable accuracy, and a note that
 * appears within the first tenth of a second would look like a claim about the hardware rather than
 * the sensor's own report.
 *
 * **What it does.** Discards the accuracy value until a fixed number of samples have arrived, then
 * passes it through unchanged. It never invents a value, never upgrades an accuracy, and never
 * reports "good" — it only declines to speak too early.
 *
 * Pure and Android-free, so the timing rule is unit-tested rather than observed by hand.
 */
class CompassAccuracyGate(
    private val warmUpSamples: Int = DEFAULT_WARM_UP_SAMPLES,
) {

    private var observed = 0

    /** Forgets the warm-up, so a restarted compass is silent again while it settles. */
    fun reset() {
        observed = 0
    }

    /** Number of samples seen since the last [reset]. */
    val sampleCount: Int get() = observed

    /**
     * Records one accuracy report.
     *
     * @return the accuracy to act on, or `null` while the sensor is still warming up — in which case
     *   no calibration guidance should be shown at all.
     */
    fun accept(accuracy: MagneticAccuracy): MagneticAccuracy? {
        observed++
        return if (observed >= warmUpSamples) accuracy else null
    }

    companion object {
        /**
         * Samples to ignore before trusting the reported accuracy.
         *
         * Ten samples at the roughly 10 Hz the compass is sampled at is about one second — long
         * enough for the fusion to produce a real accuracy value, short enough that a genuinely
         * uncalibrated device still gets told so promptly.
         */
        const val DEFAULT_WARM_UP_SAMPLES: Int = 10
    }
}
