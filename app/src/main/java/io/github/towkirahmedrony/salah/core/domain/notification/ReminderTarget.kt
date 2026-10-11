package io.github.towkirahmedrony.salah.core.domain.notification

import io.github.towkirahmedrony.salah.core.model.PrayerName

/**
 * The flat, schedulable identity of something Salah can remind the user about.
 *
 * The domain keeps prayers ([PrayerName]) and solar events
 * ([io.github.towkirahmedrony.salah.core.model.SolarEvent]) as separate types, and that separation
 * is preserved here: [SUNRISE] is a distinct constant whose [prayer] is `null`, so a solar event
 * can never be handled by code that assumes an obligatory prayer. What this enum adds is a
 * *stable ordinal* that alarm identities are built from, which an enum with a sealed hierarchy
 * could not provide without an explicit mapping table.
 *
 * The declaration order is part of that identity scheme and must not be reordered once alarms
 * exist: see [PrayerNotificationIds].
 */
enum class ReminderTarget(
    /** The obligatory prayer this target reminds of, or `null` for a solar event. */
    val prayer: PrayerName?,
) {
    FAJR(PrayerName.FAJR),
    DHUHR(PrayerName.DHUHR),
    ASR(PrayerName.ASR),
    MAGHRIB(PrayerName.MAGHRIB),
    ISHA(PrayerName.ISHA),

    /**
     * Sunrise — an astronomical event, never one of the five obligatory prayers.
     *
     * It has no [prayer], it is configured by its own switch, and every string Salah renders for
     * it says "sunrise", never "prayer".
     */
    SUNRISE(null),
    ;

    /** True when this target is one of the five obligatory prayers. */
    val isObligatoryPrayer: Boolean get() = prayer != null

    companion object {

        /** The five obligatory prayers, in declaration order. */
        val obligatoryPrayers: List<ReminderTarget> = entries.filter { it.isObligatoryPrayer }

        /** The single solar target. */
        val SOLAR: ReminderTarget = SUNRISE

        /** The target that reminds of [prayer]. */
        fun of(prayer: PrayerName): ReminderTarget = when (prayer) {
            PrayerName.FAJR -> FAJR
            PrayerName.DHUHR -> DHUHR
            PrayerName.ASR -> ASR
            PrayerName.MAGHRIB -> MAGHRIB
            PrayerName.ISHA -> ISHA
        }
    }
}
