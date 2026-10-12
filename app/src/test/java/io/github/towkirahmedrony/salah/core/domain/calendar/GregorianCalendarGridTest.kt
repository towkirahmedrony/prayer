package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarDay
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The month layout, including the boundaries the prompt calls out: month and year transitions and leap
 * years. Every assertion is about a layout that a caller supplies the *content* for, so no clock, locale
 * or device is involved.
 */
class GregorianCalendarGridTest {

    private val grid = GregorianCalendarGrid()

    private val today = LocalDate.of(2024, 3, 11)

    /** A day whose content is deliberately empty: the layout must not need any of it. */
    private fun emptyDay(date: LocalDate): CalendarDay = CalendarDay(
        date = date,
        isToday = date == today,
        bangla = null,
        hijri = null,
        occasions = emptyList(),
        ramadanDayNumber = null,
    )

    private fun layout(
        month: YearMonth,
        firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
        window: RamadanWindow? = null,
    ) = grid.layout(month, firstDayOfWeek, today, window, ::emptyDay)

    @Test
    fun `every week row has exactly seven cells`() {
        listOf(
            YearMonth.of(2024, 2),
            YearMonth.of(2024, 3),
            YearMonth.of(2025, 2),
            YearMonth.of(2026, 10),
            YearMonth.of(2000, 1),
        ).forEach { month ->
            val weeks = layout(month).weeks
            assertTrue("$month had no weeks", weeks.isNotEmpty())
            weeks.forEach { week -> assertEquals("$month row size", 7, week.size) }
        }
    }

    @Test
    fun `the real days of the month appear once, in order, with padding around them`() {
        val month = layout(YearMonth.of(2024, 3))
        val days = month.days

        assertEquals(31, days.size)
        assertEquals(LocalDate.of(2024, 3, 1), days.first().date)
        assertEquals(LocalDate.of(2024, 3, 31), days.last().date)
        assertEquals((1..31).toList(), days.map { it.date.dayOfMonth })
        // Padding is always null, never a placeholder day.
        month.weeks.flatten().filter { it == null }.forEach { assertNull(it) }
    }

    @Test
    fun `leading padding matches the weekday the month starts on`() {
        // 1 March 2024 is a Friday. With a Sunday-first week that is five blank cells.
        val sundayFirst = layout(YearMonth.of(2024, 3), DayOfWeek.SUNDAY)
        assertEquals(5, sundayFirst.weeks.first().takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2024, 3, 1), sundayFirst.weeks.first().first { it != null }!!.date)

        // With a Monday-first week it is four.
        val mondayFirst = layout(YearMonth.of(2024, 3), DayOfWeek.MONDAY)
        assertEquals(4, mondayFirst.weeks.first().takeWhile { it == null }.size)

        // And a month starting on the week's first day has none at all.
        // 1 September 2024 is a Sunday.
        val september = layout(YearMonth.of(2024, 9), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2024, 9, 1), september.weeks.first().first()!!.date)

        // 1 March 2024 is a Friday, so a Friday-first week also starts with the day itself.
        val fridayFirst = layout(YearMonth.of(2024, 3), DayOfWeek.FRIDAY)
        assertEquals(0, fridayFirst.weeks.first().takeWhile { it == null }.size)
    }

    @Test
    fun `February has 29 days in a leap year and 28 otherwise`() {
        assertEquals(29, layout(YearMonth.of(2024, 2)).days.size)
        assertEquals(28, layout(YearMonth.of(2023, 2)).days.size)
        // Century years follow the Gregorian rule: 2000 is a leap year, 1900 and 2100 are not.
        assertEquals(29, layout(YearMonth.of(2000, 2)).days.size)
        assertEquals(28, layout(YearMonth.of(2100, 2)).days.size)
    }

    @Test
    fun `navigating across a year boundary keeps every day of both months`() {
        val december = layout(YearMonth.of(2024, 12))
        val january = layout(YearMonth.of(2025, 1))

        assertEquals(31, december.days.size)
        assertEquals(LocalDate.of(2024, 12, 31), december.days.last().date)
        assertEquals(31, january.days.size)
        assertEquals(LocalDate.of(2025, 1, 1), january.days.first().date)
    }

    @Test
    fun `extreme but representable months do not throw`() {
        // The grid is asked for whatever the user navigates to; anything java.time accepts must lay out.
        listOf(
            YearMonth.of(1, 1),
            YearMonth.of(1, 12),
            YearMonth.of(4, 2),
            YearMonth.of(9999, 12),
            YearMonth.of(2399, 12),
        ).forEach { month ->
            val weeks = layout(month).weeks
            assertEquals(0, weeks.sumOf { it.size } % 7)
            assertEquals(month.lengthOfMonth(), weeks.flatten().count { it != null })
        }
    }

    @Test
    fun `today is carried into the month so the grid and the header agree`() {
        val month = layout(YearMonth.of(2024, 3))
        assertEquals(today, month.today)
        // In another month, today is still today: the header must keep showing it.
        val other = layout(YearMonth.of(2026, 10))
        assertEquals(today, other.today)
        assertEquals(0, other.days.count { it.isToday })
    }

    @Test
    fun `the Ramadan window is carried into the month`() {
        val window = RamadanWindow(
            hijriYear = 1446,
            start = LocalDate.parse("2025-02-28"),
            end = LocalDate.parse("2025-03-29"),
            source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
        )
        val month = layout(YearMonth.of(2025, 3), window = window)

        assertNotNull(month.ramadanWindow)
        assertEquals(window, month.ramadanWindow)
        assertNull(layout(YearMonth.of(2025, 3)).ramadanWindow)
    }
}
