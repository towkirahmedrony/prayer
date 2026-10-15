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

    /**
     * The end of Sehri, during Ramadan.
     *
     * Its time is the **calculated Fajr** of the same schedule, and it has no [prayer] of its own: Fajr
     * already has a target, and giving Sehri the Fajr prayer would make two reminders for one prayer
     * indistinguishable. It is scheduled as an advance reminder only, so it never lands on top of the
     * Fajr reminder; see [io.github.towkirahmedrony.salah.core.domain.calendar.RamadanCalendar].
     */
    SEHRI(null),

    /**
     * Iftar, during Ramadan.
     *
     * Its time is the **calculated Maghrib** of the same schedule, exposed as its own target so the
     * reminder can say "Iftar" rather than duplicating the Maghrib prayer reminder, and so the user can
     * switch Iftar reminders on or off independently of the Maghrib reminder.
     */
    IFTAR(null),

    /**
     * The optional **precautionary Imsak** time, during Ramadan.
     *
     * Imsak is a separate, earlier stopping time that some communities observe as a precaution. It is
     * **not** the end of Sehri — that is the calculated Fajr, which is [SEHRI]'s reference. Its time is
     * derived from the user's configured Imsak interval relative to the calculated Fajr, so it exists
     * only when an interval is configured; see
     * [io.github.towkirahmedrony.salah.core.model.CalendarSettings.imsakMinutesBeforeFajr]. Like the
     * other Ramadan targets it has no [prayer] of its own.
     */
    IMSAK(null),
    ;

    /** True when this target is one of the five obligatory prayers. */
    val isObligatoryPrayer: Boolean get() = prayer != null

    /** True for the Ramadan reminders, which are only ever scheduled inside Ramadan. */
    val isRamadanReminder: Boolean get() = this == SEHRI || this == IFTAR || this == IMSAK

    companion object {

        /** The five obligatory prayers, in declaration order. */
        val obligatoryPrayers: List<ReminderTarget> = entries.filter { it.isObligatoryPrayer }

        /** The Ramadan reminders (Sehri, Iftar and the optional Imsak), in declaration order. */
        val ramadanReminders: List<ReminderTarget> = entries.filter { it.isRamadanReminder }

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
