package io.github.towkirahmedrony.salah.data.calendar

import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The date header must stop showing yesterday once the device date rolls over, and it must do so
 * without polling: the wait is computed to the next local midnight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceClockCalendarRepositoryTest {

    /** A clock the test can move forward, standing in for the device clock. */
    private class MutableClock(val current: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun instant(): Instant = current

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        /** A test-fake so the midnight waiter can re-emit once the virtual instant passes midnight. */
        fun instant(): Instant? = current
    }

    private fun gregorianDate(snapshot: CalendarSnapshot): String =
        (snapshot.gregorian as CalendarDateState.Available).formattedDate

    @Test
    fun `emits the device Gregorian date and explains the missing calendars`() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-11T09:00:00Z"), ZoneId.of("UTC"))
        val snapshots = mutableListOf<CalendarSnapshot>()

        val job = kotlinx.coroutines.launch {
            DeviceClockCalendarRepository(clock).observeToday().collect { snapshots += it }
        }
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
        assertEquals(CalendarSystem.GREGORIAN, (snapshot.gregorian as CalendarDateState.Available).system)
        job.cancel()
    }

    @Test
    fun `re-emits once the device date rolls over`() = runTest {
        // 18:00 UTC, so six hours remain until local midnight.
        val clock = MutableClock(Instant.parse("2026-10-11T18:00:00Z"), ZoneId.of("UTC"))
        val snapshots = mutableListOf<CalendarSnapshot>()

        val job = kotlinx.coroutines.launch {
            DeviceClockCalendarRepository(clock).observeToday().collect { snapshots += it }
        }
        runCurrent()
        assertEquals(1, snapshots.size)
        assertEquals("11 October 2026", gregorianDate(snapshots[0]))

        // Advance past local midnight; the scheduled midnight waiter fires once we cross the
        // boundary, so the repo emits the next day's snapshot.
        clock.current = Instant.parse("2026-10-12T00:01:00Z")
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(2, snapshots.size)
        assertEquals("12 October 2026", gregorianDate(snapshots[1]))
        job.cancel()
    }

    @Test
    fun `does not re-emit while the date is unchanged`() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-11T00:00:00Z"), ZoneId.of("UTC"))
        val snapshots = mutableListOf<CalendarSnapshot>()

        val job = kotlinx.coroutines.launch {
            DeviceClockCalendarRepository(clock).observeToday().collect { snapshots += it }
        }
        runCurrent()

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, snapshots.size)
        job.cancel()
    }

    @Test
    fun `uses the local day boundary, not a fixed 24 hours, across a daylight-saving change`() = runTest {
        val zone = ZoneId.of("Europe/London")

        // On 29 March 2026 the clocks go forward at 01:00 GMT, so the local day that began at
        // 00:00 GMT finished 23 hours later — the midnight waiter must complete at that earlier
        // instant, not after a flat 24 hours.
        val zonesInADay = Duration.between(
            Instant.parse("2026-03-29T00:00:00Z").atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(),
            Instant.parse("2026-03-30T00:00:00Z").atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(),
        )

        val clock = MutableClock(Instant.parse("2026-03-29T00:30:00Z"), zone)
        val snapshots = mutableListOf<CalendarSnapshot>()

        val job = kotlinx.coroutines.launch {
            DeviceClockCalendarRepository(clock).observeToday().collect { snapshots += it }
        }
        runCurrent()
        assertEquals(1, snapshots.size)

        // The moment the clock is after local midnight, the pending waiter completes.
        clock.current = Instant.parse("2026-03-30T00:01:00Z")
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(2, snapshots.size)
        assertEquals("30 March 2026", gregorianDate(snapshots[1]))
        job.cancel()
    }
}
