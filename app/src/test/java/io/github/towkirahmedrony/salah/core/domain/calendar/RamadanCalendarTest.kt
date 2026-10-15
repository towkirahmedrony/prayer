package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.RamadanPhase
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import io.github.towkirahmedrony.salah.testing.testSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Sehri and Iftar are **not calculated here**. They are read out of the same prayer schedule the Home
 * timetable shows, which is why these tests assert identity with that schedule's Fajr and Maghrib
 * rather than a value computed by a second engine.
 *
 * The rule Salah implements, and the only one it may state, is: Sehri ends at the calculated Fajr and
 * Iftar is the calculated Maghrib. No buffer is added, so a user who follows a published timetable with
 * a different Sehri rule aligns it through the manual per-prayer adjustment — and that adjustment moves
 * Fajr and the Sehri reference together because they are the same value. That is asserted below too.
 */
class RamadanCalendarTest {

    private val calendar = RamadanCalendar()
    private val zone = ZoneId.of("Asia/Dhaka")

    @Test
    fun `Sehri ends at the calculated Fajr of the same schedule`() {
        val schedule = testSchedule(LocalDate.parse("2024-03-11"), zone = zone, fajr = LocalTime.of(4, 47))

        assertEquals(schedule.timeOf(PrayerName.FAJR), RamadanCalendar.sehriEndOf(schedule))
        assertEquals(
            ZonedDateTime.of(LocalDate.parse("2024-03-11"), LocalTime.of(4, 47), zone),
            RamadanCalendar.sehriEndOf(schedule),
        )
    }

    @Test
    fun `Iftar is the calculated Maghrib of the same schedule`() {
        val schedule = testSchedule(LocalDate.parse("2024-03-11"), zone = zone, maghrib = LocalTime.of(18, 12))

        assertEquals(schedule.timeOf(PrayerName.MAGHRIB), RamadanCalendar.iftarOf(schedule))
        assertEquals(
            ZonedDateTime.of(LocalDate.parse("2024-03-11"), LocalTime.of(18, 12), zone),
            RamadanCalendar.iftarOf(schedule),
        )
    }

    @Test
    fun `a manual Fajr adjustment moves the Sehri reference with it`() {
        // The app's own per-prayer adjustment is the supported way to align with a published
        // timetable, and because Sehri *is* Fajr it cannot drift away from the timetable shown.
        val base = testSchedule(LocalDate.parse("2024-03-11"), zone = zone, fajr = LocalTime.of(4, 47))
        val adjusted = testSchedule(
            LocalDate.parse("2024-03-11"),
            zone = zone,
            fajr = LocalTime.of(4, 52),
        )

        assertEquals(LocalTime.of(4, 47), RamadanCalendar.sehriEndOf(base)!!.toLocalTime())
        assertEquals(LocalTime.of(4, 52), RamadanCalendar.sehriEndOf(adjusted)!!.toLocalTime())
    }

    @Test
    fun `a day whose Fajr or Maghrib cannot be calculated has no Sehri or Iftar time`() {
        // At a high latitude the engine can fail to produce a prayer; Salah then shows no time for
        // that day rather than a plausible-looking one.
        val noFajr = testSchedule(LocalDate.parse("2024-06-21"), zone = zone)
            .let { schedule -> schedule.copy(prayers = schedule.prayers.filterNot { it.prayer == PrayerName.FAJR }) }
        val noMaghrib = testSchedule(LocalDate.parse("2024-06-21"), zone = zone)
            .let { schedule -> schedule.copy(prayers = schedule.prayers.filterNot { it.prayer == PrayerName.MAGHRIB }) }

        assertNull(RamadanCalendar.sehriEndOf(noFajr))
        assertNull(RamadanCalendar.iftarOf(noMaghrib))
        // The other one is unaffected: a missing Fajr does not remove Iftar.
        assertEquals(LocalTime.of(18, 0), RamadanCalendar.iftarOf(noFajr)!!.toLocalTime())
    }

    @Test
    fun `a configured Imsak interval is exactly that many minutes before the calculated Fajr`() {
        val schedule = testSchedule(LocalDate.parse("2024-03-11"), zone = zone, fajr = LocalTime.of(4, 47))

        // The documented rule: Imsak is the calculated Fajr minus the user's interval.
        assertEquals(
            ZonedDateTime.of(LocalDate.parse("2024-03-11"), LocalTime.of(4, 37), zone),
            RamadanCalendar.imsakOf(schedule, minutesBeforeFajr = 10),
        )
        assertEquals(
            ZonedDateTime.of(LocalDate.parse("2024-03-11"), LocalTime.of(4, 17), zone),
            RamadanCalendar.imsakOf(schedule, minutesBeforeFajr = 30),
        )
    }

    @Test
    fun `Imsak is a separate value from the Sehri deadline`() {
        val schedule = testSchedule(LocalDate.parse("2024-03-11"), zone = zone, fajr = LocalTime.of(4, 47))

        // Sehri ends at Fajr; Imsak is an earlier, distinct time and never replaces the deadline.
        assertEquals(LocalTime.of(4, 47), RamadanCalendar.sehriEndOf(schedule)!!.toLocalTime())
        assertEquals(LocalTime.of(4, 32), RamadanCalendar.imsakOf(schedule, minutesBeforeFajr = 15)!!.toLocalTime())
        assertFalse(RamadanCalendar.imsakOf(schedule, 15)!!.toLocalTime() == RamadanCalendar.sehriEndOf(schedule)!!.toLocalTime())
    }

    @Test
    fun `a day with no Fajr has no Imsak time either`() {
        val noFajr = testSchedule(LocalDate.parse("2024-06-21"), zone = zone)
            .let { schedule -> schedule.copy(prayers = schedule.prayers.filterNot { it.prayer == PrayerName.FAJR }) }

        assertNull(RamadanCalendar.imsakOf(noFajr, minutesBeforeFajr = 10))
    }

    @Test
    fun `the window for a date is the one containing it, or the next one`() {
        assertEquals(1445, calendar.windowFor(LocalDate.parse("2024-03-20"))!!.hijriYear)
        assertEquals(1446, calendar.windowFor(LocalDate.parse("2024-05-01"))!!.hijriYear)
        assertEquals(
            LocalDate.parse("2027-02-08"),
            calendar.windowFor(LocalDate.parse("2026-10-12"))!!.start,
        )
    }

    @Test
    fun `Ramadan membership follows the user's Hijri adjustment`() {
        assertTrue(calendar.isRamadan(LocalDate.parse("2024-03-11")))
        // Shifting the estimate forward by a day makes 10 March the first day of Ramadan, and keeps
        // 11 March inside it.
        assertTrue(calendar.isRamadan(LocalDate.parse("2024-03-10"), adjustmentDays = 1))
        assertTrue(calendar.isRamadan(LocalDate.parse("2024-03-11"), adjustmentDays = 1))
        assertFalse(calendar.isRamadan(LocalDate.parse("2024-03-09"), adjustmentDays = 1))
        // And 30 Ramadan becomes the first of Shawwal, so it is no longer a day of fasting.
        assertTrue(calendar.isRamadan(LocalDate.parse("2024-04-09")))
        assertFalse(calendar.isRamadan(LocalDate.parse("2024-04-09"), adjustmentDays = 1))
    }

    @Test
    fun `the day number is 1-based from the start of the window and null outside it`() {
        val window = calendar.windowFor(LocalDate.parse("2024-03-20"))!!

        assertEquals(1, calendar.dayNumber(window, window.start))
        assertEquals(2, calendar.dayNumber(window, window.start.plusDays(1)))
        assertEquals(window.dayCount, calendar.dayNumber(window, window.end))
        assertNull(calendar.dayNumber(window, window.start.minusDays(1)))
        assertNull(calendar.dayNumber(window, window.end.plusDays(1)))
    }

    @Test
    fun `the window's day count matches its dates`() {
        val window = calendar.windowFor(LocalDate.parse("2024-03-20"))!!

        assertEquals(30, window.dayCount)
        assertEquals(window.start, window.dates.first())
        assertEquals(window.end, window.dates.last())
        assertEquals(window.dayCount, window.dates.size)
        assertEquals(HijriDateSource.ESTIMATED_UMM_AL_QURA, window.source)
    }

    @Test
    fun `both ends of Ramadan are inside it and the day after is not nearby`() {
        val window = RamadanWindow(
            hijriYear = 1446,
            start = LocalDate.parse("2025-03-01"),
            end = LocalDate.parse("2025-03-29"),
            source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
        )

        assertEquals(RamadanPhase.IN_RAMADAN, window.phase(LocalDate.parse("2025-03-01")))
        assertEquals(RamadanPhase.IN_RAMADAN, window.phase(LocalDate.parse("2025-03-15")))
        assertEquals(RamadanPhase.IN_RAMADAN, window.phase(LocalDate.parse("2025-03-29")))
        // The day after the end is *not* "upcoming": the next Ramadan is a year away, and saying
        // otherwise would be a countdown to nothing.
        assertEquals(RamadanPhase.FAR_AWAY, window.phase(LocalDate.parse("2025-03-30")))
    }

    @Test
    fun `upcoming is bounded to the published window, on both sides of the boundary`() {
        val window = RamadanWindow(
            hijriYear = 1446,
            start = LocalDate.parse("2025-03-01"),
            end = LocalDate.parse("2025-03-29"),
            source = HijriDateSource.ESTIMATED_UMM_AL_QURA,
        )

        assertEquals(RamadanPhase.UPCOMING, window.phase(window.start.minusDays(1)))
        assertEquals(RamadanPhase.UPCOMING, window.phase(window.start.minusDays(RamadanWindow.UPCOMING_WINDOW_DAYS)))
        assertEquals(
            RamadanPhase.FAR_AWAY,
            window.phase(window.start.minusDays(RamadanWindow.UPCOMING_WINDOW_DAYS + 1)),
        )
    }

    @Test
    fun `a date with no window in the platform's table produces no Ramadan claim`() {
        assertNull(calendar.windowFor(LocalDate.parse("2175-06-01")))
        assertFalse(calendar.isRamadan(LocalDate.parse("2175-06-01")))
    }
}
