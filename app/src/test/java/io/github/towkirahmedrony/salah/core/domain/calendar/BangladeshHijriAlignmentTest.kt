package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.HijriMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The Bangladesh-aligned default, asserted against the platform's own Hijri table.
 *
 * Salah's underlying calendar is Umm al-Qura, whose Ramadan starts one day earlier than Bangladesh's
 * National Moon Sighting Committee announced in both 1446 AH and 1447 AH. A fresh install therefore
 * starts at [CalendarSettings.DEFAULT_HIJRI_ADJUSTMENT_DAYS] (`-1`), and
 * [CalendarSettings.UMM_AL_QURA] keeps the raw `0` baseline explicit.
 *
 * Every expectation below was printed from `java.time.chrono.HijrahDate` (JDK 17): AH 1446-09-01 =
 * 2025-03-01 and AH 1447-09-01 = 2026-02-18, so the `-1` alignment starts Ramadan on 2 March 2025 and
 * 19 February 2026. This is a heuristic, not a verified announcement: it matched those two years and
 * cannot guarantee every month, Eid or occasion.
 */
class BangladeshHijriAlignmentTest {

    private val calendar = HijriCalendar()

    @Test
    fun `a fresh install defaults to the Bangladesh alignment`() {
        assertEquals(-1, CalendarSettings.DEFAULT_HIJRI_ADJUSTMENT_DAYS)
        assertEquals(-1, CalendarSettings.DEFAULT.hijriAdjustmentDays)
        assertEquals(-1, CalendarSettings().hijriAdjustmentDays)
        // The raw table remains an explicit, representable setting.
        assertEquals(0, CalendarSettings.UMM_AL_QURA.hijriAdjustmentDays)
        // And the default is inside the range the stepper can represent.
        assertTrue(
            CalendarSettings.DEFAULT.hijriAdjustmentDays in
                -CalendarSettings.MAX_HIJRI_ADJUSTMENT_DAYS..CalendarSettings.MAX_HIJRI_ADJUSTMENT_DAYS,
        )
    }

    @Test
    fun `Ramadan 1447 starts on 19 February 2026 by default and 18 February for the raw table`() {
        val aligned = calendar.ramadanWindow(1447, CalendarSettings.DEFAULT.hijriAdjustmentDays)!!
        assertEquals(LocalDate.of(2026, 2, 19), aligned.start)
        assertEquals(30, aligned.dayCount)
        assertEquals(HijriDateSource.ESTIMATED_USER_ADJUSTED, aligned.source)

        val raw = calendar.ramadanWindow(1447, CalendarSettings.UMM_AL_QURA.hijriAdjustmentDays)!!
        assertEquals(LocalDate.of(2026, 2, 18), raw.start)
        assertEquals(30, raw.dayCount)
        assertEquals(HijriDateSource.ESTIMATED_UMM_AL_QURA, raw.source)
    }

    @Test
    fun `Ramadan 1446 starts on 2 March 2025 by default and 1 March for the raw table`() {
        val aligned = calendar.ramadanWindow(1446, CalendarSettings.DEFAULT.hijriAdjustmentDays)!!
        assertEquals(LocalDate.of(2025, 3, 2), aligned.start)
        assertEquals(LocalDate.of(2025, 3, 30), aligned.end)
        assertEquals(29, aligned.dayCount)

        val raw = calendar.ramadanWindow(1446, CalendarSettings.UMM_AL_QURA.hijriAdjustmentDays)!!
        assertEquals(LocalDate.of(2025, 3, 1), raw.start)
        assertEquals(LocalDate.of(2025, 3, 29), raw.end)
        assertEquals(29, raw.dayCount)
    }

    @Test
    fun `the aligned date is the raw date shifted by the default, and is still an estimate`() {
        // 18 February 2026 is 1 Ramadan in the table; 19 February with the default -1 is the same day.
        val raw = calendar.dateOf(LocalDate.of(2026, 2, 18))!!
        assertEquals(HijriMonth.RAMADAN, raw.month)
        assertEquals(1, raw.day)
        assertEquals(HijriDateSource.ESTIMATED_UMM_AL_QURA, raw.source)

        val aligned = calendar.dateOf(
            LocalDate.of(2026, 2, 19),
            CalendarSettings.DEFAULT_HIJRI_ADJUSTMENT_DAYS,
        )!!
        assertEquals(HijriMonth.RAMADAN, aligned.month)
        assertEquals(1, aligned.day)
        assertEquals(1447, aligned.year)
        // The default is an adjustment like any other, so the source is never a confirmed one.
        assertEquals(HijriDateSource.ESTIMATED_USER_ADJUSTED, aligned.source)
    }

    @Test
    fun `clamping from the default reaches the lower bound and stays there`() {
        var settings = CalendarSettings.DEFAULT

        settings = settings.shiftHijriAdjustment(-1)
        assertEquals(-2, settings.hijriAdjustmentDays)

        settings = settings.shiftHijriAdjustment(-1)
        assertEquals(-2, settings.hijriAdjustmentDays)

        assertEquals(-2, CalendarSettings.clampHijriAdjustment(-3))
        assertEquals(2, CalendarSettings.clampHijriAdjustment(3))
    }
}
