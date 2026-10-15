package io.github.towkirahmedrony.salah.feature.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.IslamicOccasion
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import java.time.LocalDate
import java.time.YearMonth

/**
 * Immutable state of the Calendar screen.
 *
 * Every date here is already converted and formatted; the composables lay it out and nothing else. The
 * same rules the Home screen follows apply: a date that could not be produced carries a reason instead
 * of a value, and a Hijri date always carries the source that says it is an estimate.
 */
sealed interface CalendarUiState {

    /** The first month has not arrived yet. */
    data object Loading : CalendarUiState

    /** The month could not be produced at all. Shown with a retry action, never as an empty grid. */
    data class Failed(val detail: String) : CalendarUiState

    data class Ready(
        /** The calendar the user is currently reading. */
        val system: CalendarSystem,

        /** The Gregorian month of the grid's first day, kept as a stable anchor for the screen. */
        val month: YearMonth,

        /**
         * First Gregorian day of the displayed month, inclusive. The month is the *selected calendar
         * system's* month, so this is not necessarily the first of a Gregorian month.
         */
        val monthStart: LocalDate,

        /** Last Gregorian day of the displayed month, inclusive. */
        val monthEnd: LocalDate,

        /** Localised month and year in the selected calendar, for example `October 2026`. */
        val monthLabel: String,

        /**
         * The Hijri span the displayed month covers, for example `Rabi' al-Thani – Jumada al-Awwal
         * 1448`, or `null` when the platform's Hijri table does not cover the month.
         *
         * The header's subtitle names the months a reader would otherwise have to infer from the grid's
         * day numbers. It is a *span* because a Gregorian or Bangla month rarely lines up with a Hijri
         * one: it can begin in one Hijri month and end in the next.
         */
        val hijriSpanLabel: String?,

        /**
         * The Bangla span the displayed month covers, for example `আশ্বিন – কার্তিক ১৪৩৩`, or `null`
         * when the date is outside the verified Bangladesh-calendar range. Always written in Bengali
         * script and Bengali numerals, which is how the Bangla calendar is named.
         */
        val banglaSpanLabel: String?,

        /** Column headers, in the locale's week order. */
        val weekdayLabels: List<String>,

        val weeks: List<List<CalendarCell>>,

        /** The selected day, resolved in every representation. */
        val selected: SelectedDay,

        /** Today's date, resolved the same way, so the header can show it beside the selection. */
        val today: SelectedDay,

        /** True when the selected day *is* today, so the screen can say so rather than imply it. */
        val selectedIsToday: Boolean,

        /** Set when the selected day lies outside a converter's supported range. */
        val selectedOutOfRange: UnavailabilityReason?,

        val ramadan: RamadanSection,

        /** The five prayers for the selected day at the saved location, or the reason they are absent. */
        val prayerTimes: PrayerTimesSection,

        val hijriAdjustmentDays: Int,

        val dateLanguage: DateLanguagePreference,
    ) : CalendarUiState
}

/** How a grid cell should be drawn. Kept as a value so the layout has no `if` chain. */
enum class CalendarCellStage {
    /** Padding cell at the start or end of a week. */
    PADDING,

    /** An ordinary day of the month. */
    REGULAR,

    /** Today, when it is not the selected day. */
    TODAY,

    /** The selected day, when it is not today. */
    SELECTED,

    /** Today and the selected day at once, which is the initial state of the screen. */
    TODAY_SELECTED,
}

/**
 * One cell of the month grid.
 *
 * [date] is the cell's Gregorian identity — the date every other feature is keyed by — while
 * [dayOfMonth] and [dayLabel] are its number *in the selected calendar system*.
 * [dayOfMonth] is `null` exactly when the stage is [CalendarCellStage.PADDING]; [dayLabel] is the same
 * number rendered in the mode's numerals (`14`, `১৪`, `١٤`) and is `null` for a padding cell.
 */
data class CalendarCell(
    val date: LocalDate?,
    val dayOfMonth: Int?,
    val dayLabel: String?,
    val stage: CalendarCellStage,
    val isRamadanDay: Boolean,
    val occasion: IslamicOccasion?,
    /**
     * True when this is the first Gregorian day of a Hijri month.
     *
     * Drawn as the grid's crescent mark: it is where the Hijri month turns over, which is the one
     * boundary a reader can otherwise only see by switching to the Hijri view. It is a fact about the
     * calculated table, never a claim that the month was sighted — the legend says so.
     */
    val isHijriMonthStart: Boolean = false,
)

/** One calendar representation of a single day. */
data class CalendarDateLine(
    val system: CalendarSystem,
    /** Already localised, or `null` when [unavailable] explains why it could not be produced. */
    val value: String?,
    val unavailable: UnavailabilityReason?,
    /** Only set for Hijri dates, and only ever an estimated source. */
    val source: HijriDateSource?,
)

/** A fully resolved day: its weekday, its three date lines, and what falls on it. */
data class SelectedDay(
    val date: LocalDate,
    val weekday: String,
    val lines: List<CalendarDateLine>,
    val occasions: List<IslamicOccasion>,
    /** 1-based Ramadan day, or `null` when the date is not in Ramadan. */
    val ramadanDayNumber: Int?,
    /**
     * True when the day is a Friday.
     *
     * The screen labels a Friday as Jumu'ah; that is the week's own name for the day rather than a
     * calendar observance, so it is derived from the weekday and claims nothing about a date.
     */
    val isJumuah: Boolean = false,
)

/**
 * The five obligatory prayers for the selected day, at the saved location.
 *
 * Shown beside the selected date so a reader looking at a day also sees when its prayers fall — the
 * same schedule the Home screen and the reminders are built from, formatted by the same rule. The rows
 * carry no times when no schedule could be produced, and [unavailable] then explains the gap rather
 * than leaving five dashes unexplained.
 */
data class PrayerTimesSection(
    /** The location the times belong to, already composed for display, or `null` when unset. */
    val locationLabel: String?,
    val rows: List<PrayerTimeRow>,
    val unavailable: UnavailabilityReason?,
)

/** One prayer and its time, or `null` when the schedule does not carry that prayer. */
data class PrayerTimeRow(
    val prayer: PrayerName,
    val timeLabel: String?,
)

/**
 * The Ramadan summary shown at the top of the Calendar screen.
 *
 * [rangeLabel] is an *estimated* range and always says so; [source] is the reason it says so. When the
 * range cannot be derived at all, [rangeLabel] is `null` and the screen shows nothing rather than a
 * guessed date.
 */
data class RamadanSection(
    val phase: RamadanPhase,
    /** Ramadan day number for today, or `null` when today is not in Ramadan. */
    val todayDayNumber: Int?,
    /** Estimated Gregorian range, already formatted, or `null` when it is unavailable. */
    val rangeLabel: String?,
    /** The Hijri year the range belongs to, or `null` when unavailable. */
    val hijriYear: Int?,
    val source: HijriDateSource?,
)
