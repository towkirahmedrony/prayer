package io.github.towkirahmedrony.salah.core.domain.night

import io.github.towkirahmedrony.salah.core.model.NightPeriodConvention

/**
 * The night-period convention Salah ships with.
 *
 * ## What is fixed and what is a convention
 *
 * The interval itself is fixed by the timetable: effective Maghrib to the following effective Fajr.
 * The *midpoint* (Islamic midnight) and the *start of the final third* are then pure arithmetic over
 * that interval and need no convention at all. The only conventional choice is which of those two a
 * reader calls the start of **Tahajjud**, and Salah adopts the widely used one:
 *
 *  - **Tahajjud begins at the start of the last third of the night** and runs to Fajr
 *    ([tahajjudAnchoredToLastThird] = `true`, the default).
 *  - The alternative — Tahajjud as the whole second half, from the Islamic midnight — is
 *    representable by constructing the convention with the flag set to `false`; nothing else changes.
 *
 * Salah does not claim the last-third anchoring is the only position; it is the convention *this
 * application* applies, it is documented in `docs/SOLAR_AND_NIGHT_PERIODS.md`, and a screen states
 * that optional night prayer is voluntary and that conventions differ. No ruling is encoded here.
 */
object ConfiguredNightConvention {

    /** Stable identifier recorded on every night interval this convention produces. */
    const val ID: String = "salah-night-default"

    /** The convention in force. */
    val DEFAULT: NightPeriodConvention = NightPeriodConvention(
        id = ID,
        label = "Salah default night convention (Tahajjud from the last third)",
        tahajjudAnchoredToLastThird = true,
    )
}
