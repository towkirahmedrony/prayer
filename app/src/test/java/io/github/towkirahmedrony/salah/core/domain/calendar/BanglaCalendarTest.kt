package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.BanglaDate
import io.github.towkirahmedrony.salah.core.model.BanglaMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The Bangladesh Bangla calendar, against reference dates taken from published sources rather than
 * from this implementation's own output.
 *
 * The rules under test are the 2019 revision: the first six months (Boishakh through Ashwin) have 31
 * days, Kartik through Magh have 30, Chaitra has 30 and Falgun has 29 — 30 when the Gregorian year it
 * falls in is a leap year. Poila Boishakh is fixed on 14 April. The month lengths and the leap rule
 * are stated by The Daily Star ("Since 2019, the first six months ... of the year have had 31 days.
 * The rest of the six months of the year now had 30 days, other than Falgun, which had 29 days. On
 * years divisible by four and not by 100, however, Falgun would have 30 days") and by Dhaka Tribune
 * ("the first six months of Bangla year will have 31 days"), and the fixed 14 April New Year and the
 * 14 February start of Falgun by the same Daily Star article.
 *
 * The individual day-number fixtures below follow from those published lengths plus the published
 * starting dates, and are the arithmetic an independently published Bangladesh calendar produces:
 * 1 Poush is 16 December, 1 Falgun is 14 February, 1 Chaitra is 15 March, 1 Boishakh is 14 April.
 */
class BanglaCalendarTest {

    private val calendar = BanglaCalendar()

    private fun assertConverts(gregorian: String, year: Int, month: BanglaMonth, day: Int) {
        assertEquals(
            "conversion of $gregorian",
            BanglaDate(year = year, month = month, day = day),
            calendar.convert(LocalDate.parse(gregorian)),
        )
    }

    @Test
    fun `Poila Boishakh is 14 April and the Bangla year is the Gregorian year minus 593`() {
        // The anchor the whole calendar hangs on: fixed in Bangladesh under the 2019 rules.
        assertConverts("2019-04-14", 1426, BanglaMonth.BOISHAKH, 1)
        assertConverts("2024-04-14", 1431, BanglaMonth.BOISHAKH, 1)
        assertConverts("2025-04-14", 1432, BanglaMonth.BOISHAKH, 1)
    }

    @Test
    fun `the first six months each have 31 days`() {
        // Boishakh 1431 starts 14 April 2024, so each subsequent month starts 31 days later.
        assertConverts("2024-05-15", 1431, BanglaMonth.JYAISTHA, 1)
        assertConverts("2024-06-15", 1431, BanglaMonth.ASHADH, 1)
        assertConverts("2024-07-16", 1431, BanglaMonth.SHRABAN, 1)
        assertConverts("2024-08-16", 1431, BanglaMonth.BHADRA, 1)
        assertConverts("2024-09-16", 1431, BanglaMonth.ASHWIN, 1)
    }

    @Test
    fun `Ashwin's extra day is the 2019 change, so 1 Kartik moves with it`() {
        // Daily Star: "October 16, 2019 became the first instance of Ashwin 31" — i.e. 31 Ashwin
        // 1426 fell on 16 October 2019, which is only possible if Ashwin 1 was 16 September 2019.
        assertConverts("2019-09-16", 1426, BanglaMonth.ASHWIN, 1)
        assertConverts("2019-10-16", 1426, BanglaMonth.ASHWIN, 31)
        assertConverts("2019-10-17", 1426, BanglaMonth.KARTIK, 1)
        assertConverts("2024-10-17", 1431, BanglaMonth.KARTIK, 1)
    }

    @Test
    fun `Kartik through Magh have 30 days`() {
        assertConverts("2024-11-16", 1431, BanglaMonth.AGRAHAYAN, 1)
        // Widely published Bangladesh dates: 1 Poush is 16 December and 1 Magh is 15 January.
        assertConverts("2024-12-16", 1431, BanglaMonth.POUSH, 1)
        assertConverts("2025-01-15", 1431, BanglaMonth.MAGH, 1)
    }

    @Test
    fun `Falgun starts on 14 February and Chaitra on 15 March`() {
        assertConverts("2025-02-14", 1431, BanglaMonth.FALGUN, 1)
        assertConverts("2025-03-15", 1431, BanglaMonth.CHAITRA, 1)
        // And the day before each, so the boundary is where the source says it is.
        assertConverts("2025-02-13", 1431, BanglaMonth.MAGH, 30)
        assertConverts("2025-03-14", 1431, BanglaMonth.FALGUN, 29)
    }

    @Test
    fun `Falgun takes the leap day, so 29 February is 16 Falgun`() {
        // 2024 is a leap year, so Falgun 1430 has 30 days and 29 February is its 16th day.
        assertConverts("2024-02-29", 1430, BanglaMonth.FALGUN, 16)
        assertConverts("2024-03-14", 1430, BanglaMonth.FALGUN, 30)
        // Still 1 Chaitra on 15 March, and still 1 Boishakh on 14 April: the leap day is absorbed
        // inside Falgun rather than pushing the New Year.
        assertConverts("2024-03-15", 1430, BanglaMonth.CHAITRA, 1)
        assertConverts("2024-04-14", 1431, BanglaMonth.BOISHAKH, 1)
    }

    @Test
    fun `the Bangla year rolls over on 14 April, not 1 January`() {
        assertConverts("2025-04-13", 1431, BanglaMonth.CHAITRA, 30)
        assertConverts("2025-04-14", 1432, BanglaMonth.BOISHAKH, 1)
        // A Gregorian New Year's Day is an ordinary Chaitra day in the middle of the Bangla year.
        assertConverts("2025-01-01", 1431, BanglaMonth.POUSH, 17)
        assertConverts("2024-01-01", 1430, BanglaMonth.POUSH, 17)
    }

    @Test
    fun `month lengths sum to the length of the year`() {
        val months = BanglaMonth.entries
        assertEquals(365, months.sumOf { calendar.lengthOfMonth(1431, it) })
        // 2024 is a leap year, so Falgun 1430 is one day longer.
        assertEquals(30, calendar.lengthOfMonth(1430, BanglaMonth.FALGUN))
        assertEquals(366, months.sumOf { calendar.lengthOfMonth(1430, it) })
        assertEquals(31, calendar.lengthOfMonth(1430, BanglaMonth.ASHWIN))
        assertEquals(30, calendar.lengthOfMonth(1430, BanglaMonth.CHAITRA))
    }

    @Test
    fun `every day of a supported year converts and converts back`() {
        // Round-tripping a whole year catches any off-by-one in the cumulative month offsets: the
        // forward and inverse directions walk the same table, so a mismatch means one of them is wrong.
        var date = LocalDate.of(2024, 4, 14)
        while (date.isBefore(LocalDate.of(2025, 4, 14))) {
            val bangla = calendar.convert(date)
            assertEquals("no Bangla date for $date", true, bangla != null)
            assertEquals("round trip of $date", date, calendar.toGregorian(bangla!!))
            date = date.plusDays(1)
        }
    }

    @Test
    fun `consecutive Gregorian days give consecutive Bangla days`() {
        val first = calendar.convert(LocalDate.of(2024, 4, 14))!!
        val second = calendar.convert(LocalDate.of(2024, 4, 15))!!
        assertEquals(first.month, second.month)
        assertEquals(first.day + 1, second.day)

        // And across a month boundary the month advances instead of the day overflowing.
        val lastOfAshadh = calendar.convert(LocalDate.of(2024, 7, 15))!!
        val firstOfShraban = calendar.convert(LocalDate.of(2024, 7, 16))!!
        assertEquals(BanglaMonth.ASHADH, lastOfAshadh.month)
        assertEquals(31, lastOfAshadh.day)
        assertEquals(BanglaMonth.SHRABAN, firstOfShraban.month)
        assertEquals(1, firstOfShraban.day)
    }

    @Test
    fun `conversion is refused outside the verified range instead of approximated`() {
        // The 2019 rules do not describe any earlier year: Ashwin had 30 days before them.
        assertFalse(calendar.isSupported(LocalDate.of(2019, 4, 13)))
        assertNull(calendar.convert(LocalDate.of(1947, 8, 14)))
        assertNull(calendar.convert(LocalDate.of(2019, 1, 1)))

        // 2400 is the first year where the published leap wording ("divisible by four and not by
        // 100") and the Gregorian calendar disagree, so the range stops before it.
        assertTrue(calendar.isSupported(LocalDate.of(2399, 12, 31)))
        assertNull(calendar.convert(LocalDate.of(2400, 1, 1)))
        assertNull(calendar.convert(LocalDate.of(2500, 6, 1)))
    }

    @Test
    fun `the inverse conversion refuses dates it cannot represent`() {
        assertNull(calendar.toGregorian(BanglaDate(1431, BanglaMonth.BOISHAKH, 0)))
        assertNull(calendar.toGregorian(BanglaDate(1431, BanglaMonth.CHAITRA, 31)))
        assertNull(calendar.toGregorian(BanglaDate(1431, BanglaMonth.FALGUN, 30)))
        // Falgun 1430 does have 30 days, because 2024 is a leap year.
        assertEquals(
            LocalDate.of(2024, 3, 14),
            calendar.toGregorian(BanglaDate(1430, BanglaMonth.FALGUN, 30)),
        )
        assertNull(calendar.toGregorian(BanglaDate(1300, BanglaMonth.BOISHAKH, 1)))
    }

    @Test
    fun `the New Year is a fixed Gregorian date in every supported year`() {
        // Bangladesh anchors the calendar to 14 April; unlike West Bengal's, it never moves to the
        // 15th. Checked across a leap year and a century-year boundary inside the range.
        for (year in 2019..2399 step 7) {
            assertEquals(
                LocalDate.of(year, 4, 14),
                calendar.startOfYear(year - BanglaCalendar.ERA_OFFSET),
            )
        }
    }

    @Test
    fun `a Gregorian year's day numbers in Bangla are never the same for two different days`() {
        val seen = mutableSetOf<BanglaDate>()
        var date = LocalDate.of(2024, 1, 1)
        while (date.isBefore(LocalDate.of(2025, 1, 1))) {
            val bangla = calendar.convert(date)!!
            assertTrue("duplicate Bangla date $bangla", seen.add(bangla))
            date = date.plusDays(1)
        }
        assertEquals(366, seen.size)
        assertNotEquals(BanglaMonth.BOISHAKH, calendar.convert(LocalDate.of(2024, 1, 1))!!.month)
    }
}
