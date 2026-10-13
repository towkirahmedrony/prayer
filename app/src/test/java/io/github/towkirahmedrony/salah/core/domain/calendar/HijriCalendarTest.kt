package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.HijriDate
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.HijriMonth
import io.github.towkirahmedrony.salah.core.model.IslamicOccasion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The Hijri conversion, against reference dates that are independently checkable.
 *
 * The converter reads the platform's `HijrahChronology`, which is the **Umm al-Qura** table
 * (`islamic-umalqura`). Every Hijri→Gregorian expectation below was printed from the same table by the
 * JDK — AH 1445-09-01 → 2024-03-11, AH 1445-10-01 → 2024-04-10, AH 1445-12-10 → 2024-06-16, AH
 * 1446-09-01 → 2025-03-01, AH 1446-10-01 → 2025-03-30, AH 1446-12-10 → 2025-06-06, AH 1447-09-01 →
 * 2026-02-18 — and each of the first six also matches the date Saudi Arabia's Umm al-Qura-based
 * announcement put the observance on, which is why they are usable as fixtures at all.
 *
 * What is *not* claimed anywhere in these tests, or by the production code, is that these are the
 * announced dates for Bangladesh: local moon sighting may move them by a day, which is exactly why the
 * converter labels every result as an estimate.
 */
class HijriCalendarTest {

    private val calendar = HijriCalendar()

    private fun assertConverts(gregorian: String, year: Int, month: HijriMonth, day: Int) {
        assertEquals(
            "conversion of $gregorian",
            HijriDate(year = year, month = month, day = day, source = HijriDateSource.ESTIMATED_UMM_AL_QURA),
            calendar.dateOf(LocalDate.parse(gregorian)),
        )
    }

    @Test
    fun `converts the announced Ramadan and Eid dates of 1445 and 1446`() {
        assertConverts("2024-03-11", 1445, HijriMonth.RAMADAN, 1)
        assertConverts("2024-04-10", 1445, HijriMonth.SHAWWAL, 1)
        assertConverts("2024-06-16", 1445, HijriMonth.DHU_AL_HIJJAH, 10)
        assertConverts("2025-03-01", 1446, HijriMonth.RAMADAN, 1)
        assertConverts("2025-03-30", 1446, HijriMonth.SHAWWAL, 1)
        assertConverts("2025-06-06", 1446, HijriMonth.DHU_AL_HIJJAH, 10)
    }

    @Test
    fun `converts the Islamic new year, Ashura and Mawlid`() {
        assertConverts("2023-07-19", 1445, HijriMonth.MUHARRAM, 1)
        assertConverts("2023-07-28", 1445, HijriMonth.MUHARRAM, 10)
        assertConverts("2024-07-07", 1446, HijriMonth.MUHARRAM, 1)
        assertConverts("2024-09-15", 1446, HijriMonth.RABI_AL_AWWAL, 12)
    }

    @Test
    fun `converts a date on each side of a Hijri month boundary`() {
        // 29 Sha'ban is the day before 1 Ramadan, and 30 Ramadan is the day before 1 Shawwal. Both
        // boundaries are the ones an estimate can move, so both are asserted explicitly.
        assertConverts("2024-03-10", 1445, HijriMonth.SHABAN, 29)
        assertConverts("2024-03-11", 1445, HijriMonth.RAMADAN, 1)
        assertConverts("2024-04-08", 1445, HijriMonth.RAMADAN, 29)
        assertConverts("2024-04-09", 1445, HijriMonth.RAMADAN, 30)
        assertConverts("2024-04-10", 1445, HijriMonth.SHAWWAL, 1)
    }

    @Test
    fun `every result is labelled as an estimate, never as confirmed`() {
        val date = calendar.dateOf(LocalDate.parse("2024-03-11"))!!
        assertEquals(HijriDateSource.ESTIMATED_UMM_AL_QURA, date.source)
        // There is no constant for a confirmed date at all: Salah has no authority source integrated.
        assertEquals(
            listOf(HijriDateSource.ESTIMATED_UMM_AL_QURA, HijriDateSource.ESTIMATED_USER_ADJUSTED),
            HijriDateSource.entries.toList(),
        )
    }

    @Test
    fun `a user adjustment moves the Hijri day, and says that it was the user who moved it`() {
        val unadjusted = calendar.dateOf(LocalDate.parse("2024-03-11"))!!
        val forward = calendar.dateOf(LocalDate.parse("2024-03-11"), adjustmentDays = 1)!!
        val backward = calendar.dateOf(LocalDate.parse("2024-03-11"), adjustmentDays = -1)!!

        assertEquals(2, forward.day)
        assertEquals(HijriMonth.RAMADAN, forward.month)
        assertEquals(HijriDateSource.ESTIMATED_USER_ADJUSTED, forward.source)
        // Stepping back from 1 Ramadan lands on the last day of Sha'ban, so the month moves with it.
        assertEquals(HijriMonth.SHABAN, backward.month)
        assertEquals(29, backward.day)
        assertEquals(1445, backward.year)
        assertEquals(HijriDateSource.ESTIMATED_USER_ADJUSTED, backward.source)
    }

    @Test
    fun `the Ramadan window comes from the platform's own month lengths`() {
        // 30 days in 1445, 29 days in 1446: both lengths really occur, and the window uses the
        // table's own value rather than assuming 30.
        val first = calendar.ramadanWindow(1445)!!
        assertEquals(LocalDate.parse("2024-03-11"), first.start)
        assertEquals(LocalDate.parse("2024-04-09"), first.end)
        assertEquals(30, first.dayCount)
        assertEquals(HijriDateSource.ESTIMATED_UMM_AL_QURA, first.source)

        val second = calendar.ramadanWindow(1446)!!
        assertEquals(LocalDate.parse("2025-03-01"), second.start)
        assertEquals(LocalDate.parse("2025-03-29"), second.end)
        assertEquals(29, second.dayCount)
    }

    @Test
    fun `a month's start and length come from the platform's table`() {
        // 1 Ramadan 1445 is 11 March 2024 and the month has 30 days; 1 Ramadan 1446 is 1 March 2025
        // and has 29 — the same values the Ramadan window assertions use.
        assertEquals(LocalDate.parse("2024-03-11"), calendar.monthStart(1445, HijriMonth.RAMADAN))
        assertEquals(30, calendar.monthLength(1445, HijriMonth.RAMADAN))
        assertEquals(LocalDate.parse("2025-03-01"), calendar.monthStart(1446, HijriMonth.RAMADAN))
        assertEquals(29, calendar.monthLength(1446, HijriMonth.RAMADAN))
        // Sha'ban 1445 ends the day before Ramadan, so it has 29 days.
        assertEquals(29, calendar.monthLength(1445, HijriMonth.SHABAN))
        assertEquals(LocalDate.parse("2024-02-11"), calendar.monthStart(1445, HijriMonth.SHABAN))
    }

    @Test
    fun `a month's start moves with the user's adjustment, exactly as a date does`() {
        // A positive offset displays a later Hijri date, so the Gregorian start of the month is a day
        // earlier — the same relationship `dateOf` and `ramadanWindow` implement.
        assertEquals(LocalDate.parse("2024-03-10"), calendar.monthStart(1445, HijriMonth.RAMADAN, 1))
        assertEquals(LocalDate.parse("2024-03-12"), calendar.monthStart(1445, HijriMonth.RAMADAN, -1))
        // The length is a property of the month, not of where it starts, so it does not move.
        assertEquals(30, calendar.monthLength(1445, HijriMonth.RAMADAN))
    }

    @Test
    fun `a month outside the platform's table has no start or length`() {
        assertNull(calendar.monthStart(1299, HijriMonth.RAMADAN))
        assertNull(calendar.monthLength(1299, HijriMonth.RAMADAN))
        assertNull(calendar.monthStart(1601, HijriMonth.MUHARRAM))
        assertNull(calendar.monthLength(1601, HijriMonth.MUHARRAM))
    }

    @Test
    fun `the Ramadan window moves with the user's adjustment`() {
        // A positive offset means the displayed Hijri date is later, so Ramadan starts a day earlier
        // in the Gregorian calendar — the same relationship `dateOf` implements.
        assertEquals(LocalDate.parse("2024-03-10"), calendar.ramadanWindow(1445, 1)!!.start)
        assertEquals(LocalDate.parse("2024-04-08"), calendar.ramadanWindow(1445, 1)!!.end)
        assertEquals(LocalDate.parse("2024-03-12"), calendar.ramadanWindow(1445, -1)!!.start)
        assertEquals(HijriDateSource.ESTIMATED_USER_ADJUSTED, calendar.ramadanWindow(1445, 1)!!.source)
    }

    @Test
    fun `finds the window containing a date, or the next one after it`() {
        assertEquals(1445, calendar.ramadanWindowFor(LocalDate.parse("2024-03-20"))!!.hijriYear)
        assertEquals(1446, calendar.ramadanWindowFor(LocalDate.parse("2024-04-10"))!!.hijriYear)
        // The first and last day of Ramadan are both inside it.
        assertEquals(1445, calendar.ramadanWindowFor(LocalDate.parse("2024-03-11"))!!.hijriYear)
        assertEquals(1445, calendar.ramadanWindowFor(LocalDate.parse("2024-04-09"))!!.hijriYear)
        // Well outside, it reports the next window rather than nothing.
        assertEquals(1448, calendar.ramadanWindowFor(LocalDate.parse("2026-10-12"))!!.hijriYear)
        assertEquals(LocalDate.parse("2027-02-08"), calendar.ramadanWindowFor(LocalDate.parse("2026-10-12"))!!.start)
    }

    @Test
    fun `reports whether a date is in Ramadan under the selected adjustment`() {
        assertTrue(calendar.isRamadan(LocalDate.parse("2024-03-11")))
        assertTrue(calendar.isRamadan(LocalDate.parse("2024-04-09")))
        assertFalse(calendar.isRamadan(LocalDate.parse("2024-04-10")))
        // A day before the estimated start becomes Ramadan once the user's offset is applied, and a
        // day after the estimated end stops being Ramadan.
        assertFalse(calendar.isRamadan(LocalDate.parse("2024-03-09")))
        assertTrue(calendar.isRamadan(LocalDate.parse("2024-03-10"), adjustmentDays = 1))
        // 1 Ramadan is still Ramadan once it is displayed as 2 Ramadan: the offset moves the whole
        // window, it does not remove days from the middle of it.
        assertTrue(calendar.isRamadan(LocalDate.parse("2024-03-11"), adjustmentDays = 1))
        // But the end moves too: 30 Ramadan is 1 Shawwal once shifted, and 10 April is 2 Shawwal.
        assertTrue(calendar.isRamadan(LocalDate.parse("2024-04-09")))
        assertFalse(calendar.isRamadan(LocalDate.parse("2024-04-09"), adjustmentDays = 1))
        assertFalse(calendar.isRamadan(LocalDate.parse("2024-04-10"), adjustmentDays = 1))
    }

    @Test
    fun `derives observances from the lunar date rather than a fixed Gregorian one`() {
        assertOccasion("2024-07-07", IslamicOccasion.ISLAMIC_NEW_YEAR)
        assertOccasion("2023-07-28", IslamicOccasion.ASHURA)
        assertOccasion("2024-09-15", IslamicOccasion.MAWLID)
        assertOccasion("2024-03-11", IslamicOccasion.RAMADAN_BEGINS)
        assertOccasion("2024-06-15", IslamicOccasion.ARAFAH)
        assertOccasion("2024-06-16", IslamicOccasion.EID_AL_ADHA)
        assertOccasion("2024-04-10", IslamicOccasion.EID_AL_FITR)
    }

    @Test
    fun `an ordinary day carries no occasion`() {
        assertEquals(emptyList<IslamicOccasion>(), calendar.occasionsOn(hijri(1446, 3, 13)))
        assertEquals(emptyList<IslamicOccasion>(), calendar.occasionsOn(hijri(1446, 1, 5)))
    }

    @Test
    fun `dates outside the platform's table are refused, not guessed`() {
        // The platform's table starts at AH 1300 (12 November 1882) and ends in AH 1600
        // (25 November 2174). Outside it there is no conversion to give.
        assertEquals(
            HijriDate(1300, HijriMonth.MUHARRAM, 1, HijriDateSource.ESTIMATED_UMM_AL_QURA),
            calendar.dateOf(LocalDate.parse("1882-11-12")),
        )
        assertNull(calendar.dateOf(LocalDate.parse("1882-11-11")))
        assertNull(calendar.dateOf(LocalDate.parse("2175-01-01")))
        assertNull(calendar.ramadanWindowFor(LocalDate.parse("2175-06-01")))
        // The screens then show an explicit out-of-range state; see DeviceClockCalendarRepositoryTest.
    }

    @Test
    fun `a window outside the table is reported as unavailable`() {
        assertNull(calendar.ramadanWindow(1299))
        assertNull(calendar.ramadanWindow(1601))
        assertNotNull(calendar.ramadanWindow(1450))
    }

    private fun hijri(year: Int, monthNumber: Int, day: Int): HijriDate =
        HijriDate(
            year = year,
            month = HijriMonth.ofNumber(monthNumber)!!,
            day = day,
            source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
        )

    private fun assertOccasion(gregorian: String, occasion: IslamicOccasion) {
        val date = calendar.dateOf(LocalDate.parse(gregorian))!!
        assertEquals("$gregorian carries exactly one occasion", listOf(occasion), calendar.occasionsOn(date))
    }
}
