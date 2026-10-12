package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * The Sehri and Iftar surface of Ramadan, kept deliberately thin.
 *
 * This class does **not** calculate prayer times. Sehri and Iftar are read out of the very same
 * [PrayerSchedule] the Home screen shows — Sehri ends at the calculated **Fajr**, Iftar is the
 * calculated **Maghrib** — so there is one calculation path in Salah and no second engine that could
 * disagree with the timetable the user sees. The rule is stated once, here, and asserted in
 * `RamadanCalendarTest`.
 *
 * **No invented buffer.** Salah applies no "safe minutes" offset to Sehri: an arbitrary reduction is a
 * religious claim Salah cannot make, and the honest answer is that the calculated Fajr is the
 * reference and the user should follow the timetable their community trusts. Salah's existing
 * per-prayer manual adjustment is the supported way to align with a published timetable, and it moves
 * both Fajr and the Sehri reference together because they are the same value.
 *
 * **No source invented either.** Whether a displayed time is a local calculation or an imported
 * verified timetable is decided by the schedule's own provenance
 * ([io.github.towkirahmedrony.salah.core.model.DataSourceInfo]), which is carried to the UI. Nothing
 * here claims to match the Islamic Foundation timetable.
 */
class RamadanCalendar(
    private val hijriCalendar: HijriCalendar = HijriCalendar(),
) {

    /**
     * The Ramadan window containing [date], or the next one after it, or `null` when the platform's
     * Hijri table does not cover either.
     */
    fun windowFor(date: LocalDate, adjustmentDays: Int = 0): RamadanWindow? =
        hijriCalendar.ramadanWindowFor(date, adjustmentDays)

    /** True when [date] falls inside Ramadan under the selected Hijri settings. */
    fun isRamadan(date: LocalDate, adjustmentDays: Int = 0): Boolean =
        hijriCalendar.isRamadan(date, adjustmentDays)

    /** 1-based Ramadan day for [date], or `null` when it is outside [window]. */
    fun dayNumber(window: RamadanWindow, date: LocalDate): Int? = window.dayNumber(date)

    /**
     * How [today] relates to [window]. Delegates to [RamadanWindow.phase] so the boundaries are
     * decided in exactly one place.
     */
    fun phase(window: RamadanWindow, today: LocalDate): RamadanPhase = window.phase(today)

    companion object {
        /**
         * End of Sehri for [schedule]: the calculated Fajr time.
         *
         * `null` when the schedule has no Fajr — which happens at high latitudes where twilight never
         * reaches the required angle. Salah then shows no Sehri time for that day rather than a guess.
         */
        fun sehriEndOf(schedule: PrayerSchedule): ZonedDateTime? = schedule.timeOf(PrayerName.FAJR)

        /** Iftar for [schedule]: the calculated Maghrib time. `null` when Maghrib is missing. */
        fun iftarOf(schedule: PrayerSchedule): ZonedDateTime? = schedule.timeOf(PrayerName.MAGHRIB)
    }
}
