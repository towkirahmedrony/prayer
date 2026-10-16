package io.github.towkirahmedrony.salah.core.domain.solar

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.SolarDayState
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Locks in the day's solar geometry and, in particular, that the derived values are measured from
 * the schedule's own solar events rather than from a clock or a prayer time.
 *
 * The reference values are the real engine's output for Dhaka on 11 October 2026 (see
 * `AdhanPrayerTimesProviderTest`): sunrise 05:54, solar transit 11:45, sunset 17:36. They are used
 * here only to pin the arithmetic, not as an authority comparison.
 */
class SolarDayResolverTest {

    private val resolver = SolarDayResolver()
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val dhaka: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    private fun at(day: LocalDate, hour: Int, minute: Int): ZonedDateTime =
        day.atTime(hour, minute).atZone(zone)

    private fun schedule(
        targetDate: LocalDate = date,
        sunrise: ZonedDateTime? = at(targetDate, 5, 54),
        solarNoon: ZonedDateTime? = at(targetDate, 11, 45),
        sunset: ZonedDateTime? = at(targetDate, 17, 36),
    ): PrayerSchedule = PrayerSchedule(
        date = targetDate,
        location = dhaka,
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, at(targetDate, 4, 39)),
            PrayerTimeEntry(PrayerName.DHUHR, at(targetDate, 11, 46)),
            PrayerTimeEntry(PrayerName.ASR, at(targetDate, 15, 58)),
            PrayerTimeEntry(PrayerName.MAGHRIB, at(targetDate, 17, 36)),
            PrayerTimeEntry(PrayerName.ISHA, at(targetDate, 18, 51)),
        ),
        solarEvents = buildList {
            sunrise?.let { add(SolarEventEntry(SolarEvent.SUNRISE, it)) }
            solarNoon?.let { add(SolarEventEntry(SolarEvent.SOLAR_NOON, it)) }
            sunset?.let { add(SolarEventEntry(SolarEvent.SUNSET, it)) }
        },
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    private fun available(from: PrayerSchedule = schedule()): SolarDayState.Available {
        val state = resolver.resolve(from)
        assertTrue("expected Available but was $state", state is SolarDayState.Available)
        return state as SolarDayState.Available
    }

    @Test
    fun `measures the day and night lengths from sunrise and sunset`() {
        val day = available().day

        // 05:54 to 17:36 is 11h42m.
        assertEquals(Duration.ofHours(11).plusMinutes(42), day.dayLength)
        // The astronomical night is the complement within a 24-hour day: 12h18m.
        assertEquals(Duration.ofHours(12).plusMinutes(18), day.nightLength)
        assertEquals(
            Duration.ofDays(1),
            day.dayLength!!.plus(day.nightLength!!),
        )
    }

    @Test
    fun `solar midday is the transit, not clock noon and not the Dhuhr prayer time`() {
        val day = available().day

        assertEquals(at(date, 11, 45), day.solarNoon)
        // The displayed Dhuhr carries the method's one-minute offset; the transit does not.
        assertEquals(at(date, 11, 46), schedule().timeOf(PrayerName.DHUHR))
        assertTrue(day.solarNoon != at(date, 12, 0))
    }

    @Test
    fun `solar midnight is the anti-transit, twelve hours from the transit`() {
        val day = available().day

        assertEquals(at(date, 23, 45), day.solarMidnight)
        assertEquals(Duration.ofHours(12), Duration.between(day.solarNoon, day.solarMidnight))
        // It is not civil midnight, and the zone is the schedule's own.
        assertEquals(zone, day.solarMidnight!!.zone)
    }

    @Test
    fun `a solar midnight after a late transit falls on the next local date`() {
        // At a location whose civil day is offset, the anti-transit can land past local midnight.
        val late = schedule(solarNoon = at(date, 12, 30))
        val day = available(late).day

        assertEquals(at(date, 12, 30), day.solarNoon)
        assertEquals(date.plusDays(1), day.solarMidnight!!.toLocalDate())
        // The schedule's own date is untouched; only the derived instant moves.
        assertEquals(date, day.date)
    }

    @Test
    fun `a leap day resolves and keeps its own date`() {
        val leapDay = LocalDate.of(2028, 2, 29)
        val day = available(schedule(targetDate = leapDay)).day

        assertEquals(leapDay, day.date)
        assertNotNull(day.dayLength)
        assertNotNull(day.solarMidnight)
    }

    @Test
    fun `a missing sunset leaves the lengths unstated but keeps solar midnight`() {
        val day = available(schedule(sunset = null)).day

        assertNull(day.dayLength)
        assertNull(day.nightLength)
        assertNotNull(day.solarNoon)
        assertNotNull(day.solarMidnight)
    }

    @Test
    fun `a missing transit leaves solar midnight unstated but keeps the lengths`() {
        val day = available(schedule(solarNoon = null)).day

        assertNull(day.solarMidnight)
        assertEquals(Duration.ofHours(11).plusMinutes(42), day.dayLength)
        assertEquals(Duration.ofHours(12).plusMinutes(18), day.nightLength)
    }

    @Test
    fun `each derived field is null when its event is absent, never approximated`() {
        // Sunrise and sunset absent (a polar day): no length can be measured, so none is.
        val day = available(schedule(sunrise = null, sunset = null)).day

        assertNull(day.sunrise)
        assertNull(day.sunset)
        assertNull(day.dayLength)
        assertNull(day.nightLength)
        // The transit is still defined, so solar midnight still is.
        assertNotNull(day.solarNoon)
        assertNotNull(day.solarMidnight)
    }

    @Test
    fun `an absent schedule is reported as unavailable`() {
        val state = resolver.resolve(null)

        assertTrue(state is SolarDayState.NotAvailable)
        assertEquals(UnavailabilityReason.UNKNOWN, (state as SolarDayState.NotAvailable).reason)
    }

    @Test
    fun `events keep the prayer location's zone`() {
        val day = available().day

        assertEquals(zone, day.zoneId)
        assertEquals(zone, day.sunrise!!.zone)
        assertEquals(zone, day.sunset!!.zone)
        assertEquals(zone, day.solarNoon!!.zone)
    }

    @Test
    fun `a schedule for another day reports that day, not today`() {
        val other = date.plusDays(1)
        val day = available(schedule(targetDate = other)).day

        assertEquals(other, day.date)
        assertEquals(other, day.solarMidnight!!.toLocalDate())
    }
}
