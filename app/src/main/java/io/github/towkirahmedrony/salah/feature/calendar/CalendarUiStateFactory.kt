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

        val locale = formatting.localeFor(settings.dateLanguage, systemLocale)
        val today = month.today
        // The view model clamps the selection into the displayed month, so this fallback is unreachable
        // in the app; it exists so a mismatch degrades to showing a real day rather than crashing.
        val selectedDay = days.firstOrNull { it.date == selected } ?: days.first()
        val todayDay = days.firstOrNull { it.date == today } ?: selectedDay

        return CalendarUiState.Ready(
            system = settings.preferredSystem,
            month = month.yearMonth,
            monthLabel = formatting.gregorianMonth(month.yearMonth.atDay(1), locale),
            weekdayLabels = weekdayLabels(month, locale),
            weeks = month.weeks.map { week -> week.map { cell -> cellOf(cell, selectedDay.date, month) } },
            selected = selectedDay.toSelectedDay(locale),
            today = todayDay.toSelectedDay(locale),
            selectedIsToday = selectedDay.date == today,
            selectedOutOfRange = selectedDay.outOfRangeFor(settings.preferredSystem),
            ramadan = month.toRamadanSection(today, locale),
            hijriAdjustmentDays = settings.hijriAdjustmentDays,
            dateLanguage = settings.dateLanguage,
        )
    }

    /**
     * The column headers, taken from the first week's real dates so the order always matches the grid.
     *
     * Derived from the layout rather than from a fixed list, because the first day of the week depends
     * on the locale and a hardcoded Sunday-first list would silently mislabel every column.
     */
    private fun weekdayLabels(month: CalendarMonth, locale: Locale): List<String> {
        val reference = month.weeks
            .flatten()
            .firstOrNull { it != null }
            ?.date
            ?: month.yearMonth.atDay(1)
        val firstDayOfWeek = reference.dayOfWeek
        return (0 until GregorianCalendarGrid.DAYS_PER_WEEK).map { offset ->
            formatting.weekdayShortLabel(firstDayOfWeek.plus(offset.toLong()), locale)
        }
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

    private fun cellOf(cell: CalendarDay?, selected: LocalDate, month: CalendarMonth): CalendarCell {
        if (cell == null) {
            return CalendarCell(
                date = null,
                dayOfMonth = null,
                stage = CalendarCellStage.PADDING,
                isRamadanDay = false,
                occasion = null,
            )
        }
        val isToday = cell.date == month.today
        val isSelected = cell.date == selected
        return CalendarCell(
            date = cell.date,
            dayOfMonth = cell.date.dayOfMonth,
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
}
