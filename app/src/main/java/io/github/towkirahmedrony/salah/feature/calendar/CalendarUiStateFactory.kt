package io.github.towkirahmedrony.salah.feature.calendar

import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.calendar.GregorianCalendarGrid
import io.github.towkirahmedrony.salah.core.model.CalendarDay
import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import java.time.LocalDate
import java.util.Locale

/**
 * Pure mapping from the repository's calendar month to [CalendarUiState].
 *
 * Android-free and clock-free, which is what lets the interesting rules be unit-tested directly:
 * which cell is today, which is selected, which are both, how the three date representations appear
 * side by side, and — the rule that matters most — that a date a converter could not produce appears
 * as a stated unavailability rather than as a plausible-looking value.
 *
 * **The grid follows the selected calendar system.** The repository hands in a [CalendarMonth] whose
 * days are the selected system's month (for example Ramadan 1445 when Hijri is selected), and this
 * class renders it as that system: the heading, the day-cell numbers and their numerals all come from
 * [CalendarSystem]. That is the fix for the mode selector having no visible effect — the month, the
 * day numbers and the year now change with the mode, not only which chip is highlighted.
 *
 * **Two locales, deliberately.** The grid and its heading use [CalendarFormatting.gridLocale], which
 * gives each calendar mode its own script (Latin/Bengali/Arabic). The selected-day date *list* shows
 * all three calendars at once, so it uses one script chosen by the stored date language
 * ([CalendarFormatting.localeFor]). See docs/CALENDAR_IMPLEMENTATION.md.
 *
 * [systemLocale] is a parameter rather than a `Locale.getDefault()` call inside the method so a test
 * can pin it, and so the one place that decides between the stored date-language preference and the
 * device's own locale is [CalendarFormatting.localeFor].
 */
class CalendarUiStateFactory(
    private val formatting: CalendarFormatting = CalendarFormatting(),
) {

    fun create(
        month: CalendarMonth,
        settings: CalendarSettings,
        selected: LocalDate,
        systemLocale: Locale = Locale.getDefault(),
    ): CalendarUiState {
        val days = month.days
        if (days.isEmpty()) {
            return CalendarUiState.Failed(detail = "This month has no days to lay out.")
        }

        val system = settings.preferredSystem
        val locale = formatting.localeFor(settings.dateLanguage, systemLocale)
        val gridLocale = formatting.gridLocale(system, settings.dateLanguage, systemLocale)
        val today = month.today
        // The view model clamps the selection into the displayed month, so this fallback is unreachable
        // in the app; it exists so a mismatch degrades to showing a real day rather than crashing.
        val selectedDay = days.firstOrNull { it.date == selected } ?: days.first()
        val todayDay = days.firstOrNull { it.date == today } ?: selectedDay

        return CalendarUiState.Ready(
            system = system,
            month = month.yearMonth,
            monthLabel = monthLabel(month, system, gridLocale, locale),
            weekdayLabels = weekdayLabels(month, gridLocale),
            weeks = month.weeks.map { week ->
                week.map { cell -> cellOf(cell, selectedDay.date, month, system, gridLocale) }
            },
            selected = selectedDay.toSelectedDay(locale),
            today = todayDay.toSelectedDay(locale),
            selectedIsToday = selectedDay.date == today,
            selectedOutOfRange = selectedDay.outOfRangeFor(system),
            ramadan = month.toRamadanSection(today, locale),
            hijriAdjustmentDays = settings.hijriAdjustmentDays,
            dateLanguage = settings.dateLanguage,
            monthStart = days.first().date,
            monthEnd = days.last().date,
        )
    }

    /**
     * The month heading in the selected calendar system — `October 2026`, `আশ্বিন ১৪৩১` or
     * `رَمَضان ١٤٤٥`.
     *
     * Derived from the month's own first convertible day, so the heading is the system's month and
     * year, not the Gregorian month the days happen to fall in. When the system's month is not
     * available (a date outside the verified range) it falls back to the Gregorian month label; the
     * selected-day card then carries the out-of-range note.
     */
    private fun monthLabel(
        month: CalendarMonth,
        system: CalendarSystem,
        gridLocale: Locale,
        fallbackLocale: Locale,
    ): String {
        val gregorian = formatting.gregorianMonth(month.yearMonth.atDay(1), fallbackLocale)
        return when (system) {
            CalendarSystem.GREGORIAN -> formatting.gregorianMonth(month.yearMonth.atDay(1), gridLocale)
            CalendarSystem.BANGLA -> month.days.firstNotNullOfOrNull { it.bangla }
                ?.let { formatting.banglaMonth(it, gridLocale) }
                ?: gregorian

            CalendarSystem.HIJRI -> month.days.firstNotNullOfOrNull { it.hijri }
                ?.let { formatting.hijriMonth(it, gridLocale) }
                ?: gregorian
        }
    }

    /**
     * The column headers, in the order the grid lays its columns out.
     *
     * Built from [CalendarMonth.firstDayOfWeek] — the same value the grid used for its leading
     * padding — so a header can never sit above the wrong column. Deriving the header from the first
     * *real* day instead would shift every label by however many blank cells precede it.
     */
    private fun weekdayLabels(month: CalendarMonth, locale: Locale): List<String> =
        (0 until GregorianCalendarGrid.DAYS_PER_WEEK).map { offset ->
            formatting.weekdayShortLabel(month.firstDayOfWeek.plus(offset.toLong()), locale)
        }

    /**
     * Builds the state's single Ramadan summary.
     *
     * The range is shown with its source wording, so an estimated range can never be read as an
     * announced one; and when no range could be derived the label is `null` and the screen shows
     * nothing, rather than a guessed date.
     */
    private fun CalendarMonth.toRamadanSection(today: LocalDate, locale: Locale): RamadanSection {
        val window = ramadanWindow
        if (window == null) {
            return RamadanSection(
                phase = RamadanPhase.FAR_AWAY,
                todayDayNumber = null,
                rangeLabel = null,
                hijriYear = null,
                source = null,
            )
        }
        return RamadanSection(
            phase = window.phase(today),
            todayDayNumber = window.dayNumber(today),
            rangeLabel = "${formatting.gregorian(window.start, locale)} – " +
                formatting.gregorian(window.end, locale),
            hijriYear = window.hijriYear,
            source = window.source,
        )
    }

    private fun CalendarDay.toSelectedDay(locale: Locale): SelectedDay = SelectedDay(
        date = date,
        weekday = formatting.weekday(date, locale),
        lines = listOf(
            CalendarDateLine(
                system = CalendarSystem.GREGORIAN,
                value = formatting.gregorian(date, locale),
                unavailable = null,
                source = null,
            ),
            CalendarDateLine(
                system = CalendarSystem.BANGLA,
                value = bangla?.let { formatting.bangla(it, locale) },
                unavailable = if (bangla == null) UnavailabilityReason.OUT_OF_SUPPORTED_RANGE else null,
                source = null,
            ),
            CalendarDateLine(
                system = CalendarSystem.HIJRI,
                value = hijri?.let { formatting.hijriWithEra(it, locale) },
                unavailable = if (hijri == null) UnavailabilityReason.OUT_OF_SUPPORTED_RANGE else null,
                source = hijri?.source,
            ),
        ),
        occasions = occasions,
        ramadanDayNumber = ramadanDayNumber,
    )

    /** The reason the selected day cannot be shown in [system], or `null` when it can. */
    private fun CalendarDay.outOfRangeFor(system: CalendarSystem): UnavailabilityReason? = when (system) {
        CalendarSystem.GREGORIAN -> null
        CalendarSystem.BANGLA -> if (bangla == null) UnavailabilityReason.OUT_OF_SUPPORTED_RANGE else null
        CalendarSystem.HIJRI -> if (hijri == null) UnavailabilityReason.OUT_OF_SUPPORTED_RANGE else null
    }

    private fun cellOf(
        cell: CalendarDay?,
        selected: LocalDate,
        month: CalendarMonth,
        system: CalendarSystem,
        gridLocale: Locale,
    ): CalendarCell {
        if (cell == null) {
            return CalendarCell(
                date = null,
                dayOfMonth = null,
                dayLabel = null,
                stage = CalendarCellStage.PADDING,
                isRamadanDay = false,
                occasion = null,
            )
        }
        val isToday = cell.date == month.today
        val isSelected = cell.date == selected
        // The day number is the one the *selected system* uses, so the same cell reads `14`, `৩০` or
        // `٣٠` depending on the mode. A day the system cannot convert falls back to its Gregorian day
        // number, which keeps the grid complete while the card states why the conversion is missing.
        val dayNumber = dayNumberIn(cell, system) ?: cell.date.dayOfMonth
        return CalendarCell(
            date = cell.date,
            dayOfMonth = dayNumber,
            dayLabel = formatting.numerals(dayNumber, gridLocale),
            stage = when {
                isToday && isSelected -> CalendarCellStage.TODAY_SELECTED
                isSelected -> CalendarCellStage.SELECTED
                isToday -> CalendarCellStage.TODAY
                else -> CalendarCellStage.REGULAR
            },
            isRamadanDay = cell.ramadanDayNumber != null,
            occasion = cell.occasions.firstOrNull(),
        )
    }

    /** The day's number in [system], or `null` when that system could not convert the date. */
    private fun dayNumberIn(cell: CalendarDay, system: CalendarSystem): Int? = when (system) {
        CalendarSystem.GREGORIAN -> cell.date.dayOfMonth
        CalendarSystem.BANGLA -> cell.bangla?.day
        CalendarSystem.HIJRI -> cell.hijri?.day
    }
}
