package io.github.towkirahmedrony.salah.core.domain

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Locks in the display rule for prayer periods: consecutive periods never share a displayed minute,
 * while the engine's own prayer times and closing boundaries are left exactly as they are.
 *
 * The fixture is Dhaka on 11 October 2026 with Asr at 15:40, the reported boundary: Dhuhr's period
 * closes at 15:40, so Asr's period must be displayed from 15:41. Every instant is fixed, so nothing
 * here depends on when the suite runs.
 */
class PrayerPeriodDisplayBoundsTest {

    private val bounds = PrayerPeriodDisplayBounds()
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val dhaka: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        date.atTime(hour, minute, second).atZone(zone)

    private fun schedule(
        prayers: List<PrayerTimeEntry> = listOf(
            PrayerTimeEntry(PrayerName.FAJR, at(4, 39)),
            PrayerTimeEntry(PrayerName.DHUHR, at(11, 46)),
            PrayerTimeEntry(PrayerName.ASR, at(15, 40)),
            PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 36)),
            PrayerTimeEntry(PrayerName.ISHA, at(18, 51)),
        ),
        solarEvents: List<SolarEventEntry> = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, at(5, 54)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, at(11, 45)),
            SolarEventEntry(SolarEvent.SUNSET, at(17, 36)),
        ),
    ): PrayerSchedule = PrayerSchedule(
        date = date,
        location = dhaka,
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers = prayers,
        solarEvents = solarEvents,
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    @Test
    fun `a prayer that opens when the previous period closes is displayed a minute later`() {
        val timetable = schedule()

        // The engine's Asr time is 15:40 and stays 15:40.
        assertEquals(at(15, 40), timetable.timeOf(PrayerName.ASR))
        // Dhuhr's period closes on that same instant...
        assertEquals(at(15, 40), bounds.endOf(timetable, PrayerName.DHUHR)!!.time)
        // ...so Asr's period is displayed from 15:41, leaving the closing minute to Dhuhr alone.
        assertEquals(at(15, 41), bounds.displayedStartOf(timetable, PrayerName.ASR))
    }

    @Test
    fun `the displayed start is the minute after the closing minute whatever the seconds`() {
        // Real engine output is not on a whole minute; truncation must still see the same minute.
        val timetable = schedule(
            prayers = listOf(
                PrayerTimeEntry(PrayerName.FAJR, at(4, 39)),
                PrayerTimeEntry(PrayerName.DHUHR, at(11, 46)),
                PrayerTimeEntry(PrayerName.ASR, at(15, 40, 37)),
                PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 36)),
                PrayerTimeEntry(PrayerName.ISHA, at(18, 51)),
            ),
        )

        assertEquals(at(15, 41), bounds.displayedStartOf(timetable, PrayerName.ASR))
    }

    @Test
    fun `no consecutive pair of periods shares a displayed minute`() {
        val timetable = schedule()
        val nextDayFajr = date.plusDays(1).atTime(4, 40).atZone(zone)

        val intervals = PrayerName.entries.map { prayer ->
            val start = bounds.displayedStartOf(timetable, prayer, nextDayFajr)!!
            val end = bounds.endOf(timetable, prayer, nextDayFajr)!!.time
            start to end
        }

        intervals.zipWithNext().forEach { (earlier, later) ->
            val gap = Duration.between(earlier.second, later.first)
            assertTrue(
                "expected at least a one-minute display gap between ${earlier.second} and ${later.first}, got $gap",
                gap >= Duration.ofMinutes(1),
            )
        }
        // The closing boundary itself is untouched: Dhuhr still ends at Asr's Adhan instant.
        assertEquals(timetable.timeOf(PrayerName.ASR), intervals[1].second)
        // And every period keeps its own closing boundary, in order.
        assertEquals(at(5, 54), intervals[0].second)
        assertEquals(at(18, 51), intervals[3].second)
        assertEquals(nextDayFajr, intervals[4].second)
    }

    @Test
    fun `the first period and the period after the sunrise gap keep their own minute`() {
        val timetable = schedule()

        assertEquals(at(4, 39), bounds.displayedStartOf(timetable, PrayerName.FAJR))
        assertEquals(at(11, 46), bounds.displayedStartOf(timetable, PrayerName.DHUHR))
        // The sunrise gap is real and is not closed by the display rule.
        assertEquals(at(5, 54), bounds.endOf(timetable, PrayerName.FAJR)!!.time)
    }

    @Test
    fun `the shift is measured from the previous closing minute and never accumulates`() {
        val timetable = schedule()

        // Maghrib is displayed from the minute after Asr's period closes (17:36).
        assertEquals(at(17, 37), bounds.displayedStartOf(timetable, PrayerName.MAGHRIB))
        // Isha follows Maghrib's closing minute (18:51), not Maghrib's shifted label, so it moves by
        // exactly one minute and not two.
        assertEquals(at(18, 52), bounds.displayedStartOf(timetable, PrayerName.ISHA))
        assertEquals(Duration.ofMinutes(1), Duration.between(at(18, 51), at(18, 52)))
    }

    @Test
    fun `Isha is closed by the next day's Fajr and keeps that closing minute`() {
        val timetable = schedule()
        val nextDayFajr = date.plusDays(1).atTime(4, 40).atZone(zone)

        val end = bounds.endOf(timetable, PrayerName.ISHA, nextDayFajr)!!

        assertEquals(nextDayFajr, end.time)
        assertTrue("the closing Fajr is on the following local date", end.isNextDay)
        assertEquals(at(18, 52), bounds.displayedStartOf(timetable, PrayerName.ISHA, nextDayFajr))
    }

    @Test
    fun `without tomorrow's Fajr the Isha boundary is absent rather than invented`() {
        val timetable = schedule()

        assertNull(bounds.endOf(timetable, PrayerName.ISHA))
        // The opening label only needs Maghrib's closing boundary, so it is still resolved.
        assertEquals(at(18, 52), bounds.displayedStartOf(timetable, PrayerName.ISHA))
    }

    @Test
    fun `a next day Fajr that does not follow Isha is refused`() {
        val timetable = schedule()
        val beforeIsha = date.atTime(17, 0).atZone(zone)

        assertNull(bounds.endOf(timetable, PrayerName.ISHA, beforeIsha))
    }

    @Test
    fun `missing and incomplete schedules have no displayed bounds`() {
        assertNull(bounds.displayedStartOf(null, PrayerName.ASR))
        assertNull(bounds.endOf(null, PrayerName.ASR))

        val incomplete = schedule(prayers = listOf(PrayerTimeEntry(PrayerName.FAJR, at(4, 39))))

        assertNull(bounds.displayedStartOf(incomplete, PrayerName.ASR))
        assertNull(bounds.endOf(incomplete, PrayerName.ASR))
    }
}
