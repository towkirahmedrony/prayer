package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings

/**
 * The calculation profile Salah uses when no remote configuration applies and the user owns nothing.
 *
 * It is the engine's Karachi 18°/18° + Hanafi Asr output plus two small offsets that bring Dhuhr and
 * Maghrib in line with the Islamic Foundation Bangladesh timetable for Dhaka.
 *
 * ## Where the offsets come from
 *
 * The app's astronomical output was compared with two independent republications of the Islamic
 * Foundation Bangladesh Dhaka timetable (27 days across March, April, August and October 2026). Both
 * agree, and the gap is stable across seasons:
 *
 *  - Dhuhr is about 4 minutes after the solar transit (3.3-4.7). The Karachi method already adds 1
 *    minute inside the engine, so the manual offset is **3**.
 *  - Maghrib is about 4 minutes after astronomical sunset (3.7-5.0). The method adds nothing, so the
 *    manual offset is **4**.
 *  - Fajr, Asr and Isha were not consistent enough to justify an offset and are left alone.
 *
 * Both offsets move the prayer *later*, which is the cautious direction for Dhuhr and for Maghrib
 * (and therefore Iftar).
 *
 * This is still a configured default, not a reviewed ruling: the republications were read, the
 * Foundation's own site was not. A published remote configuration replaces it, and a user's own
 * adjustments always win. See `docs/PRAYER_CALCULATION.md`.
 *
 * [PrayerSettings.DEFAULT] is deliberately left without adjustments: it is the value
 * [io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides.deriving] compares a stored
 * settings object against, and giving it adjustments would make a fresh install look as if the user
 * had chosen their own.
 */
object BundledPrayerDefaults {

    /** Manual Dhuhr offset in minutes, added to the method's own +1. */
    const val DHUHR_OFFSET_MINUTES: Int = 3

    /** Manual Maghrib offset in minutes. */
    const val MAGHRIB_OFFSET_MINUTES: Int = 4

    /** The bundled profile. */
    val PROFILE: PrayerCalculationProfile = PrayerSettings.DEFAULT.toProfile().copy(
        adjustmentsMinutes = mapOf(
            PrayerName.DHUHR to DHUHR_OFFSET_MINUTES,
            PrayerName.MAGHRIB to MAGHRIB_OFFSET_MINUTES,
        ),
    )
}
