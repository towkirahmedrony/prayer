package io.github.towkirahmedrony.salah.feature.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.IslamicOccasion
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

        /** The month being displayed, so the navigation actions can move from it. */
        val month: YearMonth,

        /** Localised month and year, for example `October 2026`. */
        val monthLabel: String,

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

/** One cell of the month grid. [dayOfMonth] is `null` exactly when the stage is [CalendarCellStage.PADDING]. */
data class CalendarCell(
    val date: LocalDate?,
    val dayOfMonth: Int?,
    val stage: CalendarCellStage,
    val isRamadanDay: Boolean,
    val occasion: IslamicOccasion?,
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
