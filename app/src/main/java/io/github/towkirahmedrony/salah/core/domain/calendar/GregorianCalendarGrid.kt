package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarDay
import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Builds the week layout of a Gregorian month.
 *
 * Only the layout lives here: how many blank cells precede the first day, how the days chunk into
 * seven-day rows, and how the final row is padded. Each day's *content* is supplied by the caller
 * through [day], which is what keeps this testable without a clock, a locale or an Android device —
 * and what lets the same layout serve the Gregorian, Bangla and Hijri views, since all three are
 * presented over the same Gregorian grid.
 *
 * [today] and [ramadanWindow] are inputs rather than lookups so the grid never reads a clock: a month
 * computed for a given "today" is reproducible, which is what its unit test relies on.
 *
 * The first day of the week is a parameter rather than a constant because it genuinely differs:
 * Bangladesh and most of the Islamic world conventionally start the week on Saturday, while the
 * device's own locale may say Sunday or Monday. The caller passes
 * `WeekFields.of(locale).firstDayOfWeek`, so the grid matches the user's own calendar.
 *
 * Nothing here can throw on a month, year or leap-year boundary: [YearMonth] and [LocalDate] do the
 * arithmetic, and `atDay(1)`/`lengthOfMonth()` are total for every supported year.
 */
class GregorianCalendarGrid {

    /** Layout of [yearMonth], with each day produced by [day]. */
    fun layout(
        yearMonth: YearMonth,
        firstDayOfWeek: DayOfWeek,
        today: LocalDate,
        ramadanWindow: RamadanWindow?,
        day: (LocalDate) -> CalendarDay,
    ): CalendarMonth {
        val firstOfMonth = yearMonth.atDay(1)
        val leadingBlanks = ((firstOfMonth.dayOfWeek.value - firstDayOfWeek.value) % DAYS_PER_WEEK + DAYS_PER_WEEK) %
            DAYS_PER_WEEK

        val cells = buildList<CalendarDay?> {
            repeat(leadingBlanks) { add(null) }
            for (dayOfMonth in 1..yearMonth.lengthOfMonth()) {
                add(day(yearMonth.atDay(dayOfMonth)))
            }
            // Pad the last row so every row has exactly seven cells; the UI can then draw a uniform
            // grid without special-casing the final week.
            while (size % DAYS_PER_WEEK != 0) add(null)
        }

        return CalendarMonth(
            yearMonth = yearMonth,
            weeks = cells.chunked(DAYS_PER_WEEK),
            today = today,
            ramadanWindow = ramadanWindow,
        )
    }

    companion object {
        /** Cells per week row. */
        const val DAYS_PER_WEEK: Int = 7
    }
}
