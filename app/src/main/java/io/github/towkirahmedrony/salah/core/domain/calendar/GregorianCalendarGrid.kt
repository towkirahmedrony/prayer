package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarDay
import io.github.towkirahmedrony.salah.core.model.CalendarMonth
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Builds the week layout of one calendar month.
 *
 * Only the layout lives here: how many blank cells precede the first day, how the days chunk into
 * seven-day rows, and how the final row is padded. Each day's *content* is supplied by the caller
 * through [day], which is what keeps this testable without a clock, a locale or an Android device.
 *
 * The layout works over an explicit list of Gregorian dates rather than a `YearMonth`, so the same
 * code lays out the Gregorian month, the Bangladesh Bangla month and the Hijri month — all three of
 * which are presented as a run of consecutive Gregorian days. A Bangla month (for example 1–31
 * Ashwin) and a Hijri month (for example 1–30 Ramadan) do not coincide with a Gregorian month, so the
 * caller supplies the exact days the selected calendar system's month covers. See
 * [CalendarMonthResolver].
 *
 * [today] and [ramadanWindow] are inputs rather than lookups so the grid never reads a clock: a month
 * computed for a given "today" is reproducible, which is what its unit test relies on.
 *
 * The first day of the week is a parameter rather than a constant because it genuinely differs:
 * Bangladesh and most of the Islamic world conventionally start the week on Saturday, while the
 * device's own locale may say Sunday or Monday. The caller passes
 * `WeekFields.of(locale).firstDayOfWeek`, so the grid matches the user's own calendar, and the same
 * value is carried into the result so the header row is built from it.
 *
 * Nothing here can throw on a month, year or leap-year boundary: [YearMonth] and [LocalDate] do the
 * arithmetic, and `atDay(1)`/`lengthOfMonth()` are total for every supported year.
 */
class GregorianCalendarGrid {

    /** Layout of the Gregorian month [yearMonth], with each day produced by [day]. */
    fun layout(
        yearMonth: YearMonth,
        firstDayOfWeek: DayOfWeek,
        today: LocalDate,
        ramadanWindow: RamadanWindow?,
        day: (LocalDate) -> CalendarDay,
    ): CalendarMonth = layout(
        dates = (1..yearMonth.lengthOfMonth()).map { yearMonth.atDay(it) },
        firstDayOfWeek = firstDayOfWeek,
        today = today,
        ramadanWindow = ramadanWindow,
        day = day,
    )

    /**
     * Layout of an explicit run of Gregorian [dates], with each day produced by [day].
     *
     * The caller is expected to pass a whole month of one calendar system, in order. The first date
     * decides the leading padding, and the returned [CalendarMonth.yearMonth] is the Gregorian
     * year-month of that first date (the anchor the screen's navigation moves from).
     */
    fun layout(
        dates: List<LocalDate>,
        firstDayOfWeek: DayOfWeek,
        today: LocalDate,
        ramadanWindow: RamadanWindow?,
        day: (LocalDate) -> CalendarDay,
    ): CalendarMonth {
        if (dates.isEmpty()) {
            return CalendarMonth(
                yearMonth = YearMonth.from(today),
                weeks = emptyList(),
                today = today,
                ramadanWindow = ramadanWindow,
                firstDayOfWeek = firstDayOfWeek,
            )
        }

        val firstDate = dates.first()
        val leadingBlanks = ((firstDate.dayOfWeek.value - firstDayOfWeek.value) % DAYS_PER_WEEK + DAYS_PER_WEEK) %
            DAYS_PER_WEEK

        val cells = buildList<CalendarDay?> {
            repeat(leadingBlanks) { add(null) }
            dates.forEach { date -> add(day(date)) }
            // Pad the last row so every row has exactly seven cells; the UI can then draw a uniform
            // grid without special-casing the final week.
            while (size % DAYS_PER_WEEK != 0) add(null)
        }

        return CalendarMonth(
            yearMonth = YearMonth.from(firstDate),
            weeks = cells.chunked(DAYS_PER_WEEK),
            today = today,
            ramadanWindow = ramadanWindow,
            firstDayOfWeek = firstDayOfWeek,
        )
    }

    companion object {
        /** Cells per week row. */
        const val DAYS_PER_WEEK: Int = 7
    }
}
