package io.github.towkirahmedrony.salah.data.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.core.model.IslamicOccasion
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone

/**
 * The calendar repository against real conversions.
 *
 * Three things are under test here and nowhere else, because they are the repository's own job rather
 * than a converter's:
 *
 *  1. **which Gregorian day it is** — taken from the device clock in the device's own zone, so a
 *     device date or time-zone change moves it;
 *  2. **that a rollover re-emits** — the header must stop showing yesterday at local midnight, without
 *     polling and correctly across a daylight-saving day;
 *  3. **that the stored settings are inputs** — moving the Hijri offset must move the displayed Hijri
 *     date and the Ramadan window on the next emission rather than at the next restart.
 *
 * The reference dates are the Umm al-Qura ones printed from the platform's own table (AH 1445-09-01 =
 * 11 March 2024) and the Bangladesh Bangla rules of 2019 (Falgun 1 = 14 February, Boishakh 1 = 14
 * April), so a failure here means a date the app shows is wrong, not that a fixture moved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceClockCalendarRepositoryTest {

    private lateinit var originalDefault: TimeZone

    @Before
    fun pinDefaultZoneToUtc() {
        originalDefault = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalDefault)
    }

    /** A clock whose instant the test can move forward, standing in for the device clock. */
    private class MutableClock(
        var current: Instant,
        private val zone: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun instant(): Instant = current
        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
    }

    private fun available(state: CalendarDateState.Available?): String = state!!.formattedDate

    private fun gregorianDate(snapshot: CalendarSnapshot): String =
        available(snapshot.gregorian as? CalendarDateState.Available)

    private fun banglaDate(snapshot: CalendarSnapshot): String =
        available(snapshot.bangla as? CalendarDateState.Available)

    private fun hijriDate(snapshot: CalendarSnapshot): String =
        available(snapshot.hijri as? CalendarDateState.Available)

    /** Whole milliseconds from [from] to the next local midnight in the current default zone. */
    private fun millisUntilNextLocalMidnight(from: Instant): Long {
        val zone = ZoneId.systemDefault()
        val midnight = from.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return Duration.between(from, midnight).toMillis()
    }

    /**
     * Starts collecting the repository on [TestScope.backgroundScope], so the collector is
     * cancelled when the test body finishes. The repository re-emits on a never-ending local
     * midnight loop, so a collector launched on the test scope itself would keep `runTest`
     * waiting forever.
     */
    private fun TestScope.observe(
        clock: Clock,
        settings: MutableStateFlow<CalendarSettings> = MutableStateFlow(CalendarSettings.DEFAULT),
    ): MutableList<CalendarSnapshot> {
        val snapshots = mutableListOf<CalendarSnapshot>()
        backgroundScope.launch {
            DeviceClockCalendarRepository(clock = clock, settings = settings).observeToday()
                .collect { snapshots += it }
        }
        return snapshots
    }

    @Test
    fun `shows all three calendars for the device's day`() = runTest {
        val clock = MutableClock(Instant.parse("2024-03-11T09:00:00Z"), ZoneId.of("UTC"))
        val snapshots = observe(clock)
        runCurrent()

        assertEquals(1, snapshots.size)
        val snapshot = snapshots.single()
        assertEquals("11 March 2024", gregorianDate(snapshot))
        assertEquals("Monday", snapshot.weekdayLabel)
        // 14 February is 1 Falgun 1430, so 11 March is its 27th day.
        assertEquals("27 Falgun 1430", banglaDate(snapshot))
        assertEquals("1 Ramadan 1445 AH", hijriDate(snapshot))
        assertEquals(
            CalendarSystem.GREGORIAN,
            (snapshot.gregorian as CalendarDateState.Available).system,
        )
    }

    @Test
    fun `the Gregorian and Bangla dates are verified sources and the Hijri one is not`() = runTest {
        val clock = MutableClock(Instant.parse("2024-03-11T09:00:00Z"), ZoneId.of("UTC"))
        val snapshots = observe(clock)
        runCurrent()

        val snapshot = snapshots.single()
        assertTrue((snapshot.gregorian as CalendarDateState.Available).isVerifiedSource)
        assertTrue((snapshot.bangla as CalendarDateState.Available).isVerifiedSource)
        // Never true: an Umm al-Qura estimate is not a moon-sighting announcement.
        assertFalse((snapshot.hijri as CalendarDateState.Available).isVerifiedSource)
    }

    @Test
    fun `reports the date language the user chose rather than following the device`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"))
        try {
            val settings = MutableStateFlow(
                CalendarSettings(dateLanguage = DateLanguagePreference.BANGLA),
            )
            val clock = MutableClock(Instant.parse("2024-04-14T06:00:00Z"), ZoneId.of("Asia/Dhaka"))
            val snapshots = observe(clock, settings)
            runCurrent()

            val snapshot = snapshots.single()
            // 14 April 2024 is 1 Boishakh 1431, rendered in Bangla numerals and script.
            assertEquals("১ বৈশাখ ১৪৩১", banglaDate(snapshot))
            assertEquals("৫ শাওয়াল ১৪৪৫ AH", hijriDate(snapshot))
            // The Gregorian date is localised by the platform with the same chosen locale, so it is
            // Bangla here even though the device locale is not.
            assertTrue(gregorianDate(snapshot).contains("এপ্রিল"))
        } finally {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }
    }

    @Test
    fun `re-emits once the device date rolls over`() = runTest {
        // 18:00 UTC, so six hours remain until local midnight.
        val start = Instant.parse("2026-10-11T18:00:00Z")
        val clock = MutableClock(start, ZoneId.of("UTC"))
        val snapshots = observe(clock)
        runCurrent()
        assertEquals(1, snapshots.size)
        assertEquals("11 October 2026", gregorianDate(snapshots[0]))

        // The waiter was scheduled for local midnight; move the clock past it and let virtual
        // time reach that scheduled instant.
        clock.current = Instant.parse("2026-10-12T00:01:00Z")
        advanceTimeBy(millisUntilNextLocalMidnight(start) + 1)
        runCurrent()

        assertEquals(2, snapshots.size)
        assertEquals("12 October 2026", gregorianDate(snapshots[1]))
        // And the other calendars move with the day rather than staying on yesterday's value.
        assertTrue(banglaDate(snapshots[1]) != banglaDate(snapshots[0]))
        assertTrue(hijriDate(snapshots[1]) != hijriDate(snapshots[0]))
    }

    @Test
    fun `the Hijri date crosses its own month boundary at local midnight`() = runTest {
        // 29 Sha'ban 1445, and 1 Ramadan begins at the next local midnight. This is the
        // time-zone-sensitive boundary the prompt calls out: the day is decided in the device's zone,
        // not in UTC and not at noon.
        val start = Instant.parse("2024-03-10T18:00:00Z")
        val clock = MutableClock(start, ZoneId.of("UTC"))
        val snapshots = observe(clock)
        runCurrent()

        assertEquals("29 Sha'ban 1445 AH", hijriDate(snapshots[0]))

        clock.current = Instant.parse("2024-03-11T00:01:00Z")
        advanceTimeBy(millisUntilNextLocalMidnight(start) + 1)
        runCurrent()

        assertEquals("1 Ramadan 1445 AH", hijriDate(snapshots[1]))
    }

    @Test
    fun `reports a date a converter cannot cover instead of approximating it`() = runTest {
        val clock = MutableClock(Instant.parse("2019-01-01T12:00:00Z"), ZoneId.of("UTC"))
        val snapshots = observe(clock)
        runCurrent()

        val before = snapshots.single()
        // The 2019 Bangla rules do not describe 2019-01-01, so no Bangla date is shown for it...
        assertEquals(
            UnavailabilityReason.OUT_OF_SUPPORTED_RANGE,
            (before.bangla as CalendarDateState.NotAvailable).reason,
        )
        assertEquals(CalendarSystem.BANGLA, (before.bangla as CalendarDateState.NotAvailable).system)
        // ...while the Hijri table does cover it, because it is always an estimate with a stated range.
        assertEquals(CalendarSystem.HIJRI, (before.hijri as CalendarDateState.Available).system)
    }

    @Test
    fun `reports a Hijri date outside the platform's table as unavailable`() = runTest {
        val clock = MutableClock(Instant.parse("2175-01-01T12:00:00Z"), ZoneId.of("UTC"))
        val snapshots = observe(clock)
        runCurrent()

        val snapshot = snapshots.single()
        assertEquals(
            UnavailabilityReason.OUT_OF_SUPPORTED_RANGE,
            (snapshot.hijri as CalendarDateState.NotAvailable).reason,
        )
        // 2175 is still inside the Bangla range, and the Gregorian date is always available.
        assertEquals(CalendarSystem.BANGLA, (snapshot.bangla as CalendarDateState.Available).system)
    }

    @Test
    fun `moving the Hijri adjustment re-emits rather than waiting for a restart`() = runTest {
        val settings = MutableStateFlow(CalendarSettings.DEFAULT)
        val clock = MutableClock(Instant.parse("2024-03-11T09:00:00Z"), ZoneId.of("UTC"))
        val snapshots = observe(clock, settings)
        runCurrent()

        assertEquals("1 Ramadan 1445 AH", hijriDate(snapshots.last()))

        settings.value = CalendarSettings(hijriAdjustmentDays = 1)
        runCurrent()

        assertEquals(2, snapshots.size)
        assertEquals("2 Ramadan 1445 AH", hijriDate(snapshots.last()))
        // The Hijri date is still reported as an estimate rather than as a confirmed one, because the
        // user's offset changes which estimate is shown, not how reliable it is.
        assertFalse((snapshots.last().hijri as CalendarDateState.Available).isVerifiedSource)
    }

    @Test
    fun `does not re-emit while the date and settings are unchanged`() = runTest {
        val clock = MutableClock(Instant.parse("2024-03-11T00:00:00Z"), ZoneId.of("UTC"))
        val snapshots = observe(clock)
        runCurrent()

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, snapshots.size)
    }

    @Test
    fun `waits to the local midnight across a daylight-saving change, not a flat 24 hours`() = runTest {
        // This test needs a DST region, so it overrides the default zone for its own duration.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        try {
            // 00:30 GMT on the day British Summer Time begins (29 March 2026). Clocks jump
            // forward at 01:00 GMT, so that local day ends at 23:00 UTC — 22 h 30 m away, which a
            // flat 24-hour wait would miss by ninety minutes.
            val start = Instant.parse("2026-03-29T00:30:00Z")
            val untilLocalMidnight = millisUntilNextLocalMidnight(start)
            assertEquals(Duration.ofMinutes(22 * 60 + 30).toMillis(), untilLocalMidnight)

            val clock = MutableClock(start, ZoneId.of("Europe/London"))
            val snapshots = observe(clock)
            runCurrent()
            assertEquals(1, snapshots.size)
            assertEquals("29 March 2026", gregorianDate(snapshots[0]))

            clock.current = Instant.parse("2026-03-29T23:01:00Z")
            advanceTimeBy(untilLocalMidnight + 1)
            runCurrent()

            assertEquals(2, snapshots.size)
            assertEquals("30 March 2026", gregorianDate(snapshots[1]))
        } finally {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }
    }

    @Test
    fun `lays out a month with every day converted, including leap day`() = runTest {
        val clock = MutableClock(Instant.parse("2024-03-11T09:00:00Z"), ZoneId.of("UTC"))
        val month = DeviceClockCalendarRepository(clock = clock)
            .observeMonth(CalendarSystem.GREGORIAN, LocalDate.of(2024, 3, 1))
            .first()

        assertEquals(YearMonth.of(2024, 3), month.yearMonth)
        assertEquals(31, month.days.size)
        assertTrue(month.weeks.all { it.size == 7 })
        assertEquals(LocalDate.of(2024, 3, 11), month.today)

        // Every day in the month carries a real Bangla date and a real Hijri date.
        assertTrue(month.days.all { it.bangla != null })
        assertTrue(month.days.all { it.hijri != null })
        // 11 March 2024 is the first day of Ramadan, so day 1 is flagged and every later day follows.
        assertEquals(1, month.days.first { it.date == LocalDate.of(2024, 3, 11) }.ramadanDayNumber)
        assertEquals(21, month.days.first { it.date == LocalDate.of(2024, 3, 31) }.ramadanDayNumber)
        assertEquals(
            listOf(IslamicOccasion.RAMADAN_BEGINS),
            month.days.first { it.date == LocalDate.of(2024, 3, 11) }.occasions,
        )
        assertNull(month.days.first { it.date == LocalDate.of(2024, 3, 10) }.ramadanDayNumber)
    }

    @Test
    fun `lays out a February with the leap day when the year has one`() = runTest {
        val clock = MutableClock(Instant.parse("2024-02-29T09:00:00Z"), ZoneId.of("UTC"))
        val month = DeviceClockCalendarRepository(clock = clock)
            .observeMonth(CalendarSystem.GREGORIAN, LocalDate.of(2024, 2, 1))
            .first()

        assertEquals(29, month.days.size)
        assertEquals(LocalDate.of(2024, 2, 29), month.days.last().date)
        assertTrue(month.days.last().isToday)
        // 29 February 2024 is 16 Falgun 1430: Falgun took the leap day.
        assertEquals(16, month.days.last().bangla!!.day)
    }

    @Test
    fun `the repository reports the Ramadan window containing today`() = runTest {
        val inRamadan = MutableClock(Instant.parse("2024-03-20T09:00:00Z"), ZoneId.of("UTC"))
        val window = DeviceClockCalendarRepository(clock = inRamadan).observeRamadanWindow().first()

        assertEquals(1445, window!!.hijriYear)
        assertEquals(LocalDate.of(2024, 3, 11), window.start)
        assertEquals(LocalDate.of(2024, 4, 9), window.end)
    }

    @Test
    fun `the Ramadan window moves when the user's adjustment moves`() = runTest {
        val settings = MutableStateFlow(CalendarSettings(hijriAdjustmentDays = 1))
        val clock = MutableClock(Instant.parse("2024-03-20T09:00:00Z"), ZoneId.of("UTC"))
        val window = DeviceClockCalendarRepository(clock = clock, settings = settings)
            .observeRamadanWindow()
            .first()

        assertEquals(LocalDate.of(2024, 3, 10), window!!.start)
        assertEquals(LocalDate.of(2024, 4, 8), window.end)
    }
}
