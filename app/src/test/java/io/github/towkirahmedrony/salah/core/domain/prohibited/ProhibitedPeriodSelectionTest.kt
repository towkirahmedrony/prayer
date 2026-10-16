package io.github.towkirahmedrony.salah.core.domain.prohibited

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriod
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Locks in the two selections a caller asks for when it wants "what is happening now" rather than
 * "the whole day": [ProhibitedPeriodStatusResolver.active] and
 * [ProhibitedPeriodStatusResolver.next].
 *
 * The windows are the real ones the shipped rule set produces for the Dhaka reference timetable:
 * sunrise [05:54, 06:09], zenith [11:40, 11:55], sunset [17:21, 17:36].
 */
class ProhibitedPeriodSelectionTest {

    private val calculator = ProhibitedPeriodCalculator()
    private val resolver = ProhibitedPeriodStatusResolver()
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val dhaka: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        date.atTime(hour, minute, second).atZone(zone)

    private val schedule: PrayerSchedule = PrayerSchedule(
        date = date,
        location = dhaka,
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, at(4, 39)),
            PrayerTimeEntry(PrayerName.DHUHR, at(11, 46)),
            PrayerTimeEntry(PrayerName.ASR, at(15, 58)),
            PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 36)),
            PrayerTimeEntry(PrayerName.ISHA, at(18, 51)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, at(5, 54)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, at(11, 45)),
            SolarEventEntry(SolarEvent.SUNSET, at(17, 36)),
        ),
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    private val windows: List<ProhibitedPeriod> =
        calculator.calculate(schedule, ConfiguredProhibitedRuleSet.DEFAULT)

    @Test
    fun `the active window is the one that is open at the moment`() {
        val active = resolver.active(windows, at(5, 56))

        assertEquals(ProhibitedWindowKind.SUNRISE_ASCENT, active?.period?.kind)
        assertEquals(Duration.ofMinutes(13), active?.endsIn)
        assertNull(active?.startsIn)
    }

    @Test
    fun `no window is active in between the morning and the afternoon`() {
        assertNull(resolver.active(windows, at(9, 0)))
        assertNull(resolver.active(windows, at(15, 0)))
    }

    @Test
    fun `an exact start is active and an exact end is not`() {
        assertEquals(
            ProhibitedWindowKind.SUNRISE_ASCENT,
            resolver.active(windows, at(5, 54))?.period?.kind,
        )
        assertNull("the interval is half-open, so 06:09 has closed it", resolver.active(windows, at(6, 9)))
        assertEquals(
            ProhibitedWindowKind.ZENITH_DECLINE,
            resolver.active(windows, at(11, 40))?.period?.kind,
        )
        assertNull(resolver.active(windows, at(11, 55)))
    }

    @Test
    fun `the next window is the nearest one still ahead`() {
        assertEquals(
            ProhibitedWindowKind.SUNRISE_ASCENT,
            resolver.next(windows, at(5, 0))?.period?.kind,
        )
        assertEquals(
            ProhibitedWindowKind.SUNSET_SETTING,
            resolver.next(windows, at(15, 0))?.period?.kind,
        )
    }

    @Test
    fun `the next window's countdown is to its own opening`() {
        val next = resolver.next(windows, at(15, 0))!!

        assertEquals(Duration.ofHours(2).plusMinutes(21), next.startsIn)
        assertNull(next.endsIn)
    }

    @Test
    fun `once the last window has closed there is no next window today`() {
        assertNull(resolver.next(windows, at(17, 36)))
        assertNull(resolver.next(windows, at(23, 0)))
    }

    @Test
    fun `at an exact end the next selection moves to the following window`() {
        assertEquals(
            ProhibitedWindowKind.ZENITH_DECLINE,
            resolver.next(windows, at(6, 9))?.period?.kind,
        )
        assertEquals(
            ProhibitedWindowKind.SUNSET_SETTING,
            resolver.next(windows, at(11, 55))?.period?.kind,
        )
    }

    @Test
    fun `an empty day has neither an active nor a next window`() {
        assertNull(resolver.active(emptyList(), at(9, 0)))
        assertNull(resolver.next(emptyList(), at(9, 0)))
    }
}
