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
import java.time.ZonedDateTime

class NextPrayerResolverTest {

    private val resolver = NextPrayerResolver()

    private val location = checkNotNull(
        LocationInfo.create(
            label = "Dhaka",
            latitude = 23.8103,
            longitude = 90.4125,
            timeZoneId = "Asia/Dhaka",
        ),
    )

    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private fun time(hour: Int, minute: Int): ZonedDateTime =
        date.atTime(hour, minute).atZone(location.zoneId)

    private fun completeSchedule(): PrayerSchedule = PrayerSchedule(
        date = date,
        location = location,
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, time(4, 55)),
            PrayerTimeEntry(PrayerName.DHUHR, time(11, 52)),
            PrayerTimeEntry(PrayerName.ASR, time(15, 18)),
            PrayerTimeEntry(PrayerName.MAGHRIB, time(17, 36)),
            PrayerTimeEntry(PrayerName.ISHA, time(18, 56)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, time(6, 10)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, time(11, 54)),
            SolarEventEntry(SolarEvent.SUNSET, time(17, 34)),
        ),
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    @Test
    fun `resolves the prayer that follows the current time`() {
        val next = resolver.resolve(completeSchedule(), time(12, 0))

        assertNotNull(next)
        assertEquals(PrayerName.ASR, next!!.prayer)
        assertEquals(time(15, 18), next.time)
    }

    @Test
    fun `resolves fajr before sunrise`() {
        val next = resolver.resolve(completeSchedule(), time(3, 0))

        assertEquals(PrayerName.FAJR, next?.prayer)
    }

    @Test
    fun `never treats sunrise as a prayer`() {
        // Sunrise (06:10) falls between Fajr (04:55) and Dhuhr (11:52). The resolver reads the
        // `prayers` list only, so once Fajr has begun the next prayer is Dhuhr - the sunrise
        // solar event is never offered as a prayer.
        val betweenFajrAndSunrise = resolver.resolve(completeSchedule(), time(5, 30))
        val justAfterSunrise = resolver.resolve(completeSchedule(), time(6, 11))

        assertEquals(PrayerName.DHUHR, betweenFajrAndSunrise?.prayer)
        assertEquals(time(11, 52), betweenFajrAndSunrise?.time)
        assertEquals(PrayerName.DHUHR, justAfterSunrise?.prayer)
        assertEquals(time(11, 52), justAfterSunrise?.time)
    }

    @Test
    fun `returns the next prayer when a prayer time is reached exactly`() {
        // At 11:52 Dhuhr begins, so the next prayer is Asr.
        val next = resolver.resolve(completeSchedule(), time(11, 52))

        assertEquals(PrayerName.ASR, next?.prayer)
    }

    @Test
    fun `returns null after the last prayer instead of inventing tomorrow's times`() {
        assertNull(resolver.resolve(completeSchedule(), time(19, 0)))
        assertNull(resolver.resolve(completeSchedule(), time(23, 59)))
    }

    @Test
    fun `returns null when the schedule is for another date`() {
        val tomorrow = resolver.resolve(completeSchedule(), time(12, 0).plusDays(1))

        assertNull(tomorrow)
    }

    @Test
    fun `returns null when the schedule is incomplete`() {
        val incomplete = completeSchedule().copy(
            prayers = completeSchedule().prayers.filterNot { it.prayer == PrayerName.ISHA },
        )

        assertNull(resolver.resolve(incomplete, time(17, 0)))
    }

    @Test
    fun `returns null without a schedule`() {
        assertNull(resolver.resolve(null, time(12, 0)))
    }

    @Test
    fun `computes the exact remaining duration`() {
        val next = resolver.resolve(completeSchedule(), time(15, 0))

        assertNotNull(next)
        assertEquals(Duration.ofMinutes(18), next!!.remaining)
    }

    @Test
    fun `compares instants so another time zone still resolves`() {
        // 09:00 UTC is 15:00 in Dhaka (UTC+6), so Asr at 15:18 is 18 minutes away.
        val utcNow = ZonedDateTime.of(date, java.time.LocalTime.of(9, 0), java.time.ZoneOffset.UTC)

        val next = resolver.resolve(completeSchedule(), utcNow)

        assertEquals(PrayerName.ASR, next?.prayer)
        assertEquals(Duration.ofMinutes(18), next?.remaining)
    }
}
