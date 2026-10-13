package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Which Gregorian days each calendar system's month covers.
 *
 * This is the bridge the calendar-mode fix depends on: the screen asks for "the month of the selected
 * system that contains this Gregorian day" and lays out exactly these dates. The expectations are not
 * this implementation's own output — the Bangla anchors (1 Ashwin = 16 September 2024, 1 Falgun =
 * 14 February, 30 Falgun 1430 = 14 March 2024) are the verified reference dates in `BanglaCalendarTest`,
 * and the Hijri anchors (1 Ramadan 1445 = 11 March 2024, Ramadan 1445 is 30 days; 1 Ramadan 1446 =
 * 1 March 2025 and is 29 days) are the ones `HijriCalendarTest` asserts against the Umm al-Qura table.
 *
 * The structural assertions — that a month's first day converts to day 1, its last to its length, and
 * every day in between is consecutive — are what catch an off-by-one that an absolute-date fixture
 * could miss.
 */
class CalendarMonthResolverTest {

    private val resolver = CalendarMonthResolver()
    private val bangla = BanglaCalendar()
    private val hijri = HijriCalendar()

    @Test
    fun `a Gregorian month is the Gregorian month, leap day included`() {
        val february = resolver.monthContaining(CalendarSystem.GREGORIAN, LocalDate.of(2024, 2, 10))!!
        assertEquals(CalendarSystem.GREGORIAN, february.system)
        assertEquals(LocalDate.of(2024, 2, 1), february.start)
        assertEquals(LocalDate.of(2024, 2, 29), february.end)
        assertEquals(29, february.length)
        assertEquals(29, february.dates.size)

        assertEquals(28, resolver.monthContaining(CalendarSystem.GREGORIAN, LocalDate.of(2023, 2, 10))!!.length)
        assertEquals(31, resolver.monthContaining(CalendarSystem.GREGORIAN, LocalDate.of(2024, 3, 10))!!.length)
    }

    @Test
    fun `the Bangla month is the Bangladesh civil month, not the Gregorian one`() {
        // 20 September 2024 is inside Ashwin 1431, which runs 16 September to 16 October 2024.
        val ashwin = resolver.monthContaining(CalendarSystem.BANGLA, LocalDate.of(2024, 9, 20))!!
        assertEquals(CalendarSystem.BANGLA, ashwin.system)
        assertEquals(LocalDate.of(2024, 9, 16), ashwin.start)
        assertEquals(LocalDate.of(2024, 10, 16), ashwin.end)
        assertEquals(31, ashwin.length)

        // And the boundary is where the source says it is: 15 September is still Bhadra.
        val bhadra = resolver.monthContaining(CalendarSystem.BANGLA, LocalDate.of(2024, 9, 15))!!
        assertEquals(LocalDate.of(2024, 8, 16), bhadra.start)
        assertEquals(LocalDate.of(2024, 9, 15), bhadra.end)
    }

    @Test
    fun `a Bangla month's days are consecutive and numbered one to its length`() {
        // Falgun 1430 falls in the leap year 2024, so it has 30 days and absorbs 29 February.
        val falgun = resolver.monthContaining(CalendarSystem.BANGLA, LocalDate.of(2024, 3, 1))!!
        assertEquals(LocalDate.of(2024, 2, 14), falgun.start)
        assertEquals(LocalDate.of(2024, 3, 14), falgun.end)
        assertEquals(30, falgun.length)

        assertBanglaDaysRunOneToLength(falgun)
        assertEquals(LocalDate.of(2024, 2, 29), falgun.start.plusDays(15))
        assertEquals(16, bangla.convert(LocalDate.of(2024, 2, 29))!!.day)
    }

    @Test
    fun `a Bangla month before the verified range is refused rather than approximated`() {
        assertNull(resolver.monthContaining(CalendarSystem.BANGLA, LocalDate.of(2019, 1, 1)))
        assertNull(resolver.monthContaining(CalendarSystem.BANGLA, LocalDate.of(2018, 6, 15)))
        // The first day the 2019 rules apply to is inside Boishakh 1426.
        val first = resolver.monthContaining(CalendarSystem.BANGLA, LocalDate.of(2019, 4, 14))!!
        assertEquals(LocalDate.of(2019, 4, 14), first.start)
    }

    @Test
    fun `the Hijri month is the lunar month with the table's own length`() {
        // 20 March 2024 is inside Ramadan 1445, which runs 11 March to 9 April 2024.
        val ramadan = resolver.monthContaining(CalendarSystem.HIJRI, LocalDate.of(2024, 3, 20))!!
        assertEquals(CalendarSystem.HIJRI, ramadan.system)
        assertEquals(LocalDate.of(2024, 3, 11), ramadan.start)
        assertEquals(LocalDate.of(2024, 4, 9), ramadan.end)
        assertEquals(30, ramadan.length)
        assertHijriDaysRunOneToLength(ramadan, adjustmentDays = 0)

        // Ramadan 1446 has 29 days, so a lunar month is not assumed to be 30.
        val next = resolver.monthContaining(CalendarSystem.HIJRI, LocalDate.of(2025, 3, 5))!!
        assertEquals(LocalDate.of(2025, 3, 1), next.start)
        assertEquals(LocalDate.of(2025, 3, 29), next.end)
        assertEquals(29, next.length)
    }

    @Test
    fun `the Hijri month moves with the user's adjustment exactly as the date does`() {
        // A positive offset displays a later Hijri date, so the month starts a day earlier.
        val shifted = resolver.monthContaining(CalendarSystem.HIJRI, LocalDate.of(2024, 3, 11), 1)!!
        assertEquals(LocalDate.of(2024, 3, 10), shifted.start)
        assertEquals(30, shifted.length)
        assertHijriDaysRunOneToLength(shifted, adjustmentDays = 1)
    }

    @Test
    fun `a Hijri month outside the platform's table is refused`() {
        assertNull(resolver.monthContaining(CalendarSystem.HIJRI, LocalDate.of(2175, 1, 1)))
        assertNull(resolver.monthContaining(CalendarSystem.HIJRI, LocalDate.of(1882, 11, 11)))
    }

    private fun assertBanglaDaysRunOneToLength(span: CalendarMonthSpan) {
        span.dates.forEachIndexed { index, date ->
            val converted = bangla.convert(date)
            assertTrue("no Bangla date for $date", converted != null)
            assertEquals("day of $date", index + 1, converted!!.day)
        }
        val first = bangla.convert(span.start)!!
        val last = bangla.convert(span.end)!!
        assertEquals(1, first.day)
        assertEquals(span.length, last.day)
        assertEquals(first.month, last.month)
        assertEquals(first.year, last.year)
    }

    private fun assertHijriDaysRunOneToLength(span: CalendarMonthSpan, adjustmentDays: Int) {
        span.dates.forEachIndexed { index, date ->
            val converted = hijri.dateOf(date, adjustmentDays)
            assertTrue("no Hijri date for $date", converted != null)
            assertEquals("day of $date", index + 1, converted!!.day)
        }
        val first = hijri.dateOf(span.start, adjustmentDays)!!
        val last = hijri.dateOf(span.end, adjustmentDays)!!
        assertEquals(1, first.day)
        assertEquals(span.length, last.day)
        assertEquals(first.month, last.month)
        assertEquals(first.year, last.year)
    }
}
