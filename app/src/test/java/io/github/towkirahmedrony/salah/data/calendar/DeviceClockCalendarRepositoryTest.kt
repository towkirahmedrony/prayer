package io.github.towkirahmedrony.salah.data.calendar

import io.github.towkirahmedrony.salah.core.domain.location.LocationDayZone
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSettings
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.core.model.IslamicOccasion
import io.github.towkirahmedrony.salah.core.model.LocationInfo
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
 *  1. **which Gregorian day it is** — the instant comes from the device clock and is placed on a day in
 *     the *configured location's* zone, so the date line above the prayer times and the times
 *     themselves cannot name two different civil days; with no location configured the device's own
 *     zone answers, and then a device time-zone change still moves it;
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
    private fun millisUntilNextLocalMidnight(from: Instant): Long =
        millisUntilNextMidnight(from, ZoneId.systemDefault())

    /** Whole milliseconds from [from] to the next midnight in [zone]. */
    private fun millisUntilNextMidnight(from: Instant, zone: ZoneId): Long {
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
        interfaceLanguage: MutableStateFlow<AppLanguage> = MutableStateFlow(AppLanguage.SYSTEM),
        /**
         * The stored location's zone is resolved from this, or `null` for a repository with no
         * location zone source at all — which is every test above the location-zone ones, and the
         * device-zone behaviour they pin.
         */
        locations: MutableStateFlow<LocationInfo?>? = null,
    ): MutableList<CalendarSnapshot> {
        val snapshots = mutableListOf<CalendarSnapshot>()
        val dayZone = locations?.let { source ->
            LocationDayZone(locationSource = source, scope = backgroundScope)
        }
        backgroundScope.launch {
            DeviceClockCalendarRepository(
                clock = clock,
                settings = settings,
                interfaceLanguage = interfaceLanguage,
                dayZone = dayZone,
            ).observeToday().collect { snapshots += it }
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

    // --- The day is the *location's* day ---------------------------------------

    private val dhaka = checkNotNull(LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"))

    /** A location on UTC-7 in March, so the same instant can be a different civil day from Dhaka's. */
    private val losAngeles =
        checkNotNull(LocationInfo.create("Los Angeles", 34.0522, -118.2437, "America/Los_Angeles"))

    @Test
    fun `takes the day from the configured location's zone, not the device's`() = runTest {
        // The device is on UTC (pinned above) and the location is on UTC+6, so 20:00 UTC is already the
        // next civil day in Dhaka. The header must name the day the prayer timetable underneath it was
        // calculated for: 12 March 2024 is 2 Ramadan 1445 and 28 Falgun 1430, not the device's 11 March.
        val clock = MutableClock(Instant.parse("2024-03-11T20:00:00Z"), ZoneId.of("UTC"))
        val snapshots = observe(clock, locations = MutableStateFlow<LocationInfo?>(dhaka))
        runCurrent()

        val snapshot = snapshots.single()
        assertEquals("12 March 2024", gregorianDate(snapshot))
        assertEquals("Tuesday", snapshot.weekdayLabel)
        assertEquals("2 Ramadan 1445 AH", hijriDate(snapshot))
        assertEquals("28 Falgun 1430", banglaDate(snapshot))
    }

    @Test
    fun `a location change re-emits the day in the new location's zone`() = runTest {
        // The device's zone never moves here, so only the stored location can explain a changed date:
        // 20:00 UTC is 12 March in Dhaka (UTC+6) and still 11 March in Los Angeles (UTC-7).
        val clock = MutableClock(Instant.parse("2024-03-11T20:00:00Z"), ZoneId.of("UTC"))
        val locations = MutableStateFlow<LocationInfo?>(dhaka)
        val snapshots = observe(clock, locations = locations)
        runCurrent()
        assertEquals("12 March 2024", gregorianDate(snapshots.last()))

        locations.value = losAngeles
        runCurrent()

        assertTrue("a location change must emit a new snapshot", snapshots.size >= 2)
        assertEquals("11 March 2024", gregorianDate(snapshots.last()))
    }

    @Test
    fun `rolls over at the location's local midnight, not the device's`() = runTest {
        // 17:59 UTC is 23:59 in Dhaka, so the location's day turns one minute later — while the device's
        // own (UTC) day still has six hours to run. The wait has to be measured in the location's zone.
        val start = Instant.parse("2026-10-11T17:59:00Z")
        val clock = MutableClock(start, ZoneId.of("UTC"))
        val snapshots = observe(clock, locations = MutableStateFlow<LocationInfo?>(dhaka))
        runCurrent()
        assertEquals("11 October 2026", gregorianDate(snapshots[0]))

        clock.current = Instant.parse("2026-10-11T18:00:00Z")
        advanceTimeBy(millisUntilNextMidnight(start, ZoneId.of("Asia/Dhaka")) + 1)
        runCurrent()

        assertEquals("12 October 2026", gregorianDate(snapshots.last()))
        // The device's own zone has *not* rolled over — it is still 11 October in UTC — which is what
        // makes the new snapshot the location's day rather than the device's.
        assertEquals(LocalDate.of(2026, 10, 11), clock.current.atZone(ZoneId.of("UTC")).toLocalDate())
    }

    @Test
    fun `today's date lines follow the interface language, not the device's`() = runTest {
        // 11 March 2024 is the same day as the device-locale test above; only the language it is
        // written in changes. The interface language is the `SYSTEM` branch of the date-language
        // preference — which is the whole reason a reader who picks Bengali on an English phone gets
        // Bengali month and weekday names instead of Latin ones.
        val clock = MutableClock(Instant.parse("2024-03-11T09:00:00Z"), ZoneId.of("UTC"))
        val snapshots = observe(clock, interfaceLanguage = MutableStateFlow(AppLanguage.BANGLA))
        runCurrent()

        val snapshot = snapshots.single()
        // The Bangla and Hijri dates are composed from explicit month tables, so they are exact — and
        // they are the *same dates* the device-locale run produced, which is what makes this a change
        // of script rather than of data.
        assertEquals("২৭ ফাল্গুন ১৪৩০", banglaDate(snapshot))
        assertEquals("১ রমজান ১৪৪৫ AH", hijriDate(snapshot))
        // The Gregorian date and the weekday name come from the platform's locale data, so they are
        // checked for the script and the numerals rather than spelled out here: Bengali script, and not
        // one Western digit left in the year.
        val gregorian = gregorianDate(snapshot)
        assertTrue("the Gregorian date is not in Bengali: $gregorian", gregorian.any { it.inBengaliBlock() })
        assertFalse("the Gregorian date kept Western digits: $gregorian", gregorian.any { it in '0'..'9' })
        val weekday = snapshot.weekdayLabel.orEmpty()
        assertTrue("the weekday is not in Bengali: $weekday", weekday.any { it.inBengaliBlock() })
    }

    @Test
    fun `an explicit date language still wins over the interface language`() = runTest {
        // The two settings answer different questions: the interface language is how the screen is
        // written, the date language is which script the dates themselves are in. A reader who chose
        // English words but Bengali dates must get Bengali dates.
        val settings = MutableStateFlow(CalendarSettings(dateLanguage = DateLanguagePreference.BANGLA))
        val clock = MutableClock(Instant.parse("2024-03-11T09:00:00Z"), ZoneId.of("UTC"))
        val snapshots = observe(
            clock,
            settings = settings,
            interfaceLanguage = MutableStateFlow(AppLanguage.ENGLISH),
        )
        runCurrent()

        assertEquals("২৭ ফাল্গুন ১৪৩০", banglaDate(snapshots.single()))
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

/** True when [this] is in the Bengali Unicode block (U+0980–U+09FF). */
private fun Char.inBengaliBlock(): Boolean = code in 0x0980..0x09FF
