package io.github.towkirahmedrony.salah.core.domain.night

import io.github.towkirahmedrony.salah.core.model.NightPeriodConvention

/**
 * The night-period convention Salah ships with.
 *
 * ## What is fixed and what is not part of this interval
 *
 * The interval itself is fixed by the timetable: effective Maghrib to the following effective Fajr.
 * The *midpoint* (Islamic midnight) and the *start of the final third* are then pure arithmetic over
 * that interval and need no convention at all.
 *
 * Tahajjud is deliberately not anchored to this interval. Its valid window is after Isha to before
 * Fajr, and the last third is its recommended time; both are derived by `NaflPrayerCalculator`,
 * which reads Isha directly. The interval only supplies the last-third instant that Nafl uses as
 * the recommended start.
 *
 * Salah does not claim any ruling is encoded here. No ruling — what to pray, how many units, whether
 * it may be offered at a different time — is represented in this feature.
 */
object ConfiguredNightConvention {

    /** Stable identifier recorded on every night interval this convention produces. */
    const val ID: String = "salah-night-default"

    /** The convention in force. */
    val DEFAULT: NightPeriodConvention = NightPeriodConvention(
        id = ID,
        label = "Salah night interval (Maghrib to following Fajr)",
    )
}
