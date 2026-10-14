package io.github.towkirahmedrony.salah.feature.ramadan

import io.github.towkirahmedrony.salah.core.domain.calendar.RamadanStage
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import java.time.LocalDate

/**
 * Immutable state of the Ramadan screen.
 *
 * The screen's whole promise is that it shows either a real schedule or a reason there is none, so the
 * state carries [daysWithoutSchedule] and [rows] rather than a partially filled table with blanks that
 * could read as times.
 */
sealed interface RamadanUiState {

    data object Loading : RamadanUiState

    data class Failed(val detail: String) : RamadanUiState

    data class Ready(
        /** How today relates to the estimated window. */
        val phase: RamadanPhase,

        /** Hijri year of the window, or `null` when no window could be derived. */
        val hijriYear: Int?,

        /**
         * The screen's heading, for example `Ramadan 1448 AH` or `রমজান ১৪৪৮ AH`, already localised,
         * or `null` when no window could be derived — the screen then falls back to the plain screen
         * name. Composed here rather than in the layout because the year's numerals have to follow
         * the interface language exactly as the table's dates do.
         */
        val titleLabel: String?,

        /** Estimated Gregorian range, already localised, or `null` when it is unavailable. */
        val rangeLabel: String?,

        /** How many days the estimated window covers, or `null` when it is unavailable. */
        val totalDays: Int?,

        /** Which Hijri source produced the range. Always an estimate. */
        val source: HijriDateSource?,

        /** Ramadan day number for today, or `null` when today is not in Ramadan. */
        val todayDayNumber: Int?,

        /**
         * The window's days, grouped into the three traditional stages of Ramadan, in day order.
         *
         * The grouping is derived from the window's own length, so a 29-day and a 30-day Ramadan both
         * work and the last stage simply holds whatever remains after day 20. Empty when no window could
         * be derived — the screen then shows no dates at all rather than an empty table.
         */
        val stageGroups: List<RamadanStageGroup>,

        /** How many of [rows] have no schedule, so the screen can say so once instead of per row. */
        val daysWithoutSchedule: Int,

        /** Provenance of the times, from the schedule itself. `null` when there is no schedule. */
        val timeSource: PrayerTimeSource?,
        val timeSourceProviderId: String?,

        val locationConfigured: Boolean,

        val masterEnabled: Boolean,
        val sehriReminderEnabled: Boolean,
        val sehriLeadMinutes: Int,
        val iftarReminderEnabled: Boolean,
        val iftarLeadMinutes: Int,

        val hijriAdjustmentDays: Int,
        val message: RamadanMessage?,
    ) : RamadanUiState {
        /**
         * Every day of [stageGroups] in day order.
         *
         * A view of the grouping rather than a second copy of the days: the rows are built once and the
         * grouping is a lens on them, so a screen that does not need the stages (and the tests that
         * assert the table's own facts) cannot drift from the screen that draws them.
         */
        val rows: List<RamadanDayRow> get() = stageGroups.flatMap(RamadanStageGroup::rows)
    }
}

/**
 * One traditional stage of Ramadan and the days that fall in it.
 *
 * [firstDayNumber] and [lastDayNumber] are read off the rows themselves, so the header's day range is
 * the range the table actually shows. That is what makes the last stage 21–29 or 21–30 without the
 * header ever being told which: the window decides how many days Ramadan has, and the header follows.
 */
data class RamadanStageGroup(
    val stage: RamadanStage,
    val firstDayNumber: Int,
    val lastDayNumber: Int,
    val rows: List<RamadanDayRow>,
)

/** Transient feedback after a reminder action, set only from a real reconciliation result. */
sealed interface RamadanMessage {
    /** A reconciliation completed and produced at least one day of schedule. */
    data object ScheduleUpdated : RamadanMessage

    /** A reconciliation ran but no day of the window could be calculated. */
    data object NoSchedule : RamadanMessage
}

/**
 * One day of the Ramadan table.
 *
 * Sehri and Iftar carry the prayer they come from — [fajrLabel] and [maghribLabel] — so the rule is
 * available to the screen and to any later reader of the state, even though the redesigned table
 * states it once in prose instead of repeating the same clock time under every row. A `null` label
 * means the schedule for that day has no such time — never that it was zero.
 */
data class RamadanDayRow(
    val date: LocalDate,
    val ramadanDayNumber: Int,
    /**
     * Compact Gregorian date of the day, for example `19 Feb`.
     *
     * This is the row's label — the Gregorian date is what identifies a day in a calendar. The year is
     * not repeated here because the heading already states the window's own years, and the row's Ramadan
     * day is carried once, by [hijriLabel], rather than spelled out again as a second number.
     */
    val gregorianLabel: String,
    /**
     * The Hijri date of the day, for example `1 Ramadan 1445 AH`, or `null` when the platform's Hijri
     * table does not cover this date.
     *
     * The Ramadan day number is read out of this date, which is why the row carries no separate
     * `Ramadan day 3`-style label. It is the same number in the calendar the observance is counted in.
     */
    val hijriLabel: String?,
    /** Sehri end: the calculated Fajr. */
    val sehriEndLabel: String?,
    val fajrLabel: String?,
    /** Iftar: the calculated Maghrib. */
    val iftarLabel: String?,
    val maghribLabel: String?,
    val isToday: Boolean,
    val hasSchedule: Boolean,
    /** Why there is no schedule for this day, when there is none. */
    val unavailable: UnavailabilityReason?,
)

/**
 * Everything [RamadanUiStateFactory] needs, gathered by the view model.
 *
 * A single input object rather than seven parameters, so the factory stays a pure function of one value
 * and its tests construct exactly what the view model would.
 */
data class RamadanUiStateInputs(
    val today: LocalDate,
    val calendarSettings: CalendarSettings,
    val prayerSettings: PrayerSettings,
    val notificationSettings: PrayerNotificationSettings,
    /** `null` when no Ramadan window could be derived — shown as an honest empty state. */
    val window: RamadanWindow?,
    /** One state per date of [window]; a missing date is a day with no schedule. */
    val schedules: Map<LocalDate, PrayerScheduleState>,
    val locationConfigured: Boolean,
)
