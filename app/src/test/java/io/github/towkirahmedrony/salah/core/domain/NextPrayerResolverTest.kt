package io.github.towkirahmedrony.salah.core.domain

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NextPrayerResolverTest {

    private val resolver = NextPrayerResolver()
    private val zone = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val location = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    /** Real engine values for Dhaka on this date (Karachi, Hanafi Asr). */
    private fun time(hour: Int, minute: Int): ZonedDateTime = date.atTime(hour, minute).atZone(zone)

    private fun completeSchedule(): PrayerSchedule = PrayerSchedule(
        date = date,
        location = location,
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, time(4, 39)),
            PrayerTimeEntry(PrayerName.DHUHR, time(11, 46)),
            PrayerTimeEntry(PrayerName.ASR, time(15, 58)),
            PrayerTimeEntry(PrayerName.MAGHRIB, time(17, 36)),
            PrayerTimeEntry(PrayerName.ISHA, time(18, 51)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, time(5, 54)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, time(11, 45)),
            SolarEventEntry(SolarEvent.SUNSET, time(17, 36)),
        ),
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    @Test
    fun `returns null without a schedule`() {
        assertNull(resolver.resolve(null, time(12, 0)))
    }

    @Test
    fun `returns null when the schedule is incomplete`() {
        val incomplete = completeSchedule().copy(
            prayers = completeSchedule().prayers.filterNot { it.prayer == PrayerName.ISHA },
        )
        assertNull(resolver.resolve(incomplete, time(17, 0)))
    }

    @Test
    fun `returns the next prayer when a prayer time is reached exactly`() {
        assertEquals(PrayerName.ASR, resolver.resolve(completeSchedule(), time(11, 46))?.prayer)
        assertEquals(time(15, 58), resolver.resolve(completeSchedule(), time(11, 46))?.time)
    }

    @Test
    fun `never treats sunrise or sunset as a prayer`() {
        // Sunrise (05:54) falls between Fajr (04:39) and Dhuhr (11:46). The resolver reads the
        // `prayers` list only, so once Fajr has begun the next prayer is Dhuhr: the sunrise and
        // sunset solar events are never offered as a prayer.
        val beforeSunrise = resolver.resolve(completeSchedule(), time(5, 30))
        val justAfterSunrise = resolver.resolve(completeSchedule(), time(5, 55))
        val justAfterSunset = resolver.resolve(completeSchedule(), time(17, 37))

        assertEquals(PrayerName.DHUHR, beforeSunrise?.prayer)
        assertEquals(PrayerName.DHUHR, justAfterSunrise?.prayer)
        assertEquals(PrayerName.ISHA, justAfterSunset?.prayer)
    }

    @Test
    fun `returns null after the last prayer instead of inventing tomorrow's times`() {
        assertNull(resolver.resolve(completeSchedule(), time(19, 0)))
        assertNull(resolver.resolve(completeSchedule(), time(23, 59)))
    }

    @Test
    fun `returns null from a schedule for another date`() {
        val tomorrow = resolver.resolve(completeSchedule(), time(12, 0).plusDays(1))
        assertNull(tomorrow)
    }

    @Test
    fun `compares instants so another time zone still resolves`() {
        // 00:00 UTC is 06:00 in Dhaka (UTC+6), so Fajr at 04:39 has already begun and the
        // next prayer is Dhuhr at 11:46: five hours and forty-six minutes away.
        val utcNow = ZonedDateTime.of(date, java.time.LocalTime.of(0, 0), ZoneId.of("UTC"))
        val next = resolver.resolve(completeSchedule(), utcNow)

        assertEquals(PrayerName.DHUHR, next?.prayer)
        assertEquals(Duration.ofHours(5).plusMinutes(46), next?.remaining)
    }

    @Test
    fun `computes the exact remaining duration`() {
        val next = resolver.resolve(completeSchedule(), time(15, 0))
        assertNotNull(next)
        assertEquals(Duration.ofMinutes(58), next!!.remaining)
    }
}
