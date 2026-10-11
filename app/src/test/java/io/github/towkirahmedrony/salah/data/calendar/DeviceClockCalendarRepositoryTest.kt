package io.github.towkirahmedrony.salah.data.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * The date header must stop showing yesterday once the device date rolls over, and it must do so
 * without polling: the repository computes the wait to the next *local* midnight and re-emits
 * exactly then.
 *
 * The repository deliberately reads [ZoneId.systemDefault] on every pass, so a device time-zone
 * change is picked up without a restart. That makes the day boundary under test depend on the
 * JVM default zone, so this class pins it: UTC for the ordinary cases, and Europe/London for the
 * daylight-saving case. Without pinning, the assertions would be at the mercy of the runner's
 * zone and would not actually test the boundary arithmetic.
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

    private fun gregorianDate(snapshot: CalendarSnapshot): String =
        (snapshot.gregorian as CalendarDateState.Available).formattedDate

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
    private fun TestScope.observe(clock: Clock): MutableList<CalendarSnapshot> {
        val snapshots = mutableListOf<CalendarSnapshot>()
        backgroundScope.launch {
            DeviceClockCalendarRepository(clock).observeToday().collect { snapshots += it }
        }
        return snapshots
    }

    @Test
    fun `emits the device Gregorian date and explains the missing calendars`() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-11T09:00:00Z"), ZoneId.of("UTC"))
        val snapshots = observe(clock)
        runCurrent()

        assertEquals(1, snapshots.size)
        val snapshot = snapshots.single()
        assertEquals("11 October 2026", gregorianDate(snapshot))
        assertEquals("Sunday", snapshot.weekdayLabel)
        assertEquals(
            UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED,
            (snapshot.bangla as CalendarDateState.NotAvailable).reason,
        )
        assertEquals(
            UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED,
            (snapshot.hijri as CalendarDateState.NotAvailable).reason,
        )
        assertEquals(
            CalendarSystem.GREGORIAN,
            (snapshot.gregorian as CalendarDateState.Available).system,
        )
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
    }

    @Test
    fun `does not re-emit while the date is unchanged`() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-11T00:00:00Z"), ZoneId.of("UTC"))
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
}
