package io.github.towkirahmedrony.salah.core.domain

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
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
 * Locks in the period convention the Home screen reads: which prayer period contains a moment, what
 * closes it, and how much of it is left.
 *
 * Every instant here is fixed, so nothing in this file depends on when it is run. The timetable is
 * the one the real engine produces for Dhaka on this date (see `AdhanPrayerTimesProviderTest`):
 * Fajr 04:39, sunrise 05:54, Dhuhr 11:46, Asr 15:58, Maghrib 17:36, Isha 18:51.
 *
 * The remaining time asserted throughout is the time until the next boundary in the timetable. It is
 * deliberately not described as a deadline for performing the prayer: that is a different question,
 * with a different answer, and Salah does not answer it here.
 */
class CurrentPrayerPeriodResolverTest {

    private val resolver = CurrentPrayerPeriodResolver()
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val dhaka: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    /** Chattogram, a second valid Bangladesh coordinate, for the same-moment-different-place checks. */
    private val chattogram: LocationInfo = checkNotNull(
        LocationInfo.create("Chattogram", 22.3569, 91.7832, "Asia/Dhaka"),
    )

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        date.atTime(hour, minute, second).atZone(zone)

    private fun schedule(
        targetDate: LocalDate = date,
        location: LocationInfo = dhaka,
        prayers: List<PrayerTimeEntry> = listOf(
            PrayerTimeEntry(PrayerName.FAJR, at(4, 39)),
            PrayerTimeEntry(PrayerName.DHUHR, at(11, 46)),
            PrayerTimeEntry(PrayerName.ASR, at(15, 58)),
            PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 36)),
            PrayerTimeEntry(PrayerName.ISHA, at(18, 51)),
        ),
        solarEvents: List<SolarEventEntry> = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, at(5, 54)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, at(11, 45)),
            SolarEventEntry(SolarEvent.SUNSET, at(17, 36)),
        ),
    ): PrayerSchedule = PrayerSchedule(
        date = targetDate,
        location = location,
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers = prayers,
        solarEvents = solarEvents,
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    private fun active(status: CurrentPrayerStatus): CurrentPrayerPeriod {
        assertTrue("expected an active period but was $status", status is CurrentPrayerStatus.Active)
        return (status as CurrentPrayerStatus.Active).period
    }

    // ---------------------------------------------------------------- before Fajr and after Isha

    @Test
    fun `before Fajr the previous day's Isha period is still open`() {
        val period = active(resolver.resolve(schedule(), at(2, 0)))

        assertEquals(PrayerName.ISHA, period.prayer)
        // The start is genuinely not on today's schedule; the resolver reports the absence instead
        // of borrowing the previous day's timetable.
        assertNull(period.startsAt)
        assertEquals(at(4, 39), period.endsAt)
        assertEquals(Duration.ofMinutes(159), period.remaining)
    }

    @Test
    fun `the pre-dawn period is closed by today's Fajr, not by an invented boundary`() {
        val period = active(resolver.resolve(schedule(), at(0, 30)))

        val boundary = period.endsWith as PeriodBoundary.Prayer
        assertEquals(PrayerName.FAJR, boundary.prayer)
        // A same-day boundary: the next day flag belongs to Isha's evening period only.
        assertTrue(!boundary.isNextDay)
        assertEquals(at(4, 39), boundary.time)
    }

    @Test
    fun `a moment exactly at Fajr belongs to the Fajr period`() {
        val period = active(resolver.resolve(schedule(), at(4, 39)))

        assertEquals(PrayerName.FAJR, period.prayer)
        assertEquals(at(4, 39), period.startsAt)
    }

    // ------------------------------------------------------------------------------- Fajr window

    @Test
    fun `during Fajr the period ends at sunrise, which is a solar event`() {
        val period = active(resolver.resolve(schedule(), at(5, 0)))

        assertEquals(PrayerName.FAJR, period.prayer)
        assertEquals(at(4, 39), period.startsAt)
        assertEquals(at(5, 54), period.endsAt)
        assertEquals(Duration.ofMinutes(54), period.remaining)

        val boundary = period.endsWith
        assertTrue("sunrise must not be modelled as a prayer but was $boundary", boundary is PeriodBoundary.Solar)
        assertEquals(SolarEvent.SUNRISE, (boundary as PeriodBoundary.Solar).event)
    }

    @Test
    fun `immediately before sunrise the Fajr period still has one second left`() {
        val period = active(resolver.resolve(schedule(), at(5, 53, 59)))

        assertEquals(PrayerName.FAJR, period.prayer)
        assertEquals(Duration.ofSeconds(1), period.remaining)
    }

    @Test
    fun `a moment exactly at sunrise closes the Fajr period`() {
        val status = resolver.resolve(schedule(), at(5, 54))

        assertTrue(status is CurrentPrayerStatus.Between)
        assertEquals(
            PrayerName.DHUHR,
            (status as CurrentPrayerStatus.Between).nextPrayer,
        )
    }

    // --------------------------------------------------------------------- between the two periods

    @Test
    fun `after sunrise and before Dhuhr no prayer period is open`() {
        val status = resolver.resolve(schedule(), at(9, 30))

        assertTrue("expected a between-periods reading but was $status", status is CurrentPrayerStatus.Between)
        status as CurrentPrayerStatus.Between
        assertEquals(PrayerName.DHUHR, status.nextPrayer)
        assertEquals(at(11, 46), status.nextPrayerTime)
        assertEquals(Duration.ofMinutes(136), status.remaining)
    }

    @Test
    fun `a moment exactly at Dhuhr opens the Dhuhr period`() {
        val period = active(resolver.resolve(schedule(), at(11, 46)))

        assertEquals(PrayerName.DHUHR, period.prayer)
        assertEquals(at(11, 46), period.startsAt)
    }

    // --------------------------------------------------------------- Dhuhr, the requested example

    @Test
    fun `between Dhuhr and Asr the current prayer is Dhuhr and Asr closes it`() {
        // 15:00 in Dhaka: the example the feature is specified around.
        val period = active(resolver.resolve(schedule(), at(15, 0)))

        assertEquals(PrayerName.DHUHR, period.prayer)
        assertEquals(at(11, 46), period.startsAt)
        assertEquals(at(15, 58), period.endsAt)
        assertEquals(Duration.ofMinutes(58), period.remaining)

        val boundary = period.endsWith
        assertTrue(boundary is PeriodBoundary.Prayer)
        assertEquals(PrayerName.ASR, (boundary as PeriodBoundary.Prayer).prayer)
        assertTrue(!boundary.isNextDay)
    }

    @Test
    fun `immediately before Asr the Dhuhr period still has one second left`() {
        val period = active(resolver.resolve(schedule(), at(15, 57, 59)))

        assertEquals(PrayerName.DHUHR, period.prayer)
        assertEquals(Duration.ofSeconds(1), period.remaining)
    }

    @Test
    fun `a moment exactly at Asr opens the Asr period`() {
        val period = active(resolver.resolve(schedule(), at(15, 58)))

        assertEquals(PrayerName.ASR, period.prayer)
        assertEquals(at(15, 58), period.startsAt)
    }

    // ------------------------------------------------------------------------- Asr and Maghrib

    @Test
    fun `during Asr the period ends at Maghrib`() {
        val period = active(resolver.resolve(schedule(), at(16, 30)))

        assertEquals(PrayerName.ASR, period.prayer)
        assertEquals(at(15, 58), period.startsAt)
        assertEquals(at(17, 36), period.endsAt)
        assertEquals(Duration.ofMinutes(66), period.remaining)
        assertEquals(
            PrayerName.MAGHRIB,
            (period.endsWith as PeriodBoundary.Prayer).prayer,
        )
    }

    @Test
    fun `during Maghrib the period ends at Isha`() {
        val period = active(resolver.resolve(schedule(), at(18, 0)))

        assertEquals(PrayerName.MAGHRIB, period.prayer)
        assertEquals(at(17, 36), period.startsAt)
        assertEquals(at(18, 51), period.endsAt)
        assertEquals(Duration.ofMinutes(51), period.remaining)
        assertEquals(
            PrayerName.ISHA,
            (period.endsWith as PeriodBoundary.Prayer).prayer,
        )
    }

    // ---------------------------------------------------------------------------------- Isha

    @Test
    fun `during Isha the period is closed by the next day's Fajr`() {
        val nextDayFajr = date.plusDays(1).atTime(4, 40).atZone(zone)

        val period = active(resolver.resolve(schedule(), at(19, 30), nextDayFajr))

        assertEquals(PrayerName.ISHA, period.prayer)
        assertEquals(at(18, 51), period.startsAt)
        assertEquals(nextDayFajr, period.endsAt)
        assertEquals(Duration.ofMinutes(550), period.remaining)

        val boundary = period.endsWith as PeriodBoundary.Prayer
        assertEquals(PrayerName.FAJR, boundary.prayer)
        assertTrue("the closing Fajr is on the following local date", boundary.isNextDay)
    }

    @Test
    fun `after Isha and before the next Fajr the Isha period is still the current one`() {
        val nextDayFajr = date.plusDays(1).atTime(4, 40).atZone(zone)

        val period = active(resolver.resolve(schedule(), at(23, 59, 59), nextDayFajr))

        assertEquals(PrayerName.ISHA, period.prayer)
        assertEquals(Duration.ofSeconds(1) + Duration.ofMinutes(280), period.remaining)
    }

    @Test
    fun `Isha is reported as unresolved when the next day's Fajr is not available`() {
        // Without tomorrow's Fajr the closing boundary cannot be named, so the period is not ended
        // at a guessed instant.
        val status = resolver.resolve(schedule(), at(19, 30))

        assertTrue(status is CurrentPrayerStatus.Unavailable)
        assertEquals(
            UnavailabilityReason.UNKNOWN,
            (status as CurrentPrayerStatus.Unavailable).reason,
        )
    }

    @Test
    fun `a next day Fajr that does not follow Isha is refused`() {
        // Provider data of the wrong shape must not be used to close a period.
        val beforeIsha = date.atTime(17, 0).atZone(zone)

        val status = resolver.resolve(schedule(), at(19, 30), beforeIsha)

        assertTrue(status is CurrentPrayerStatus.Unavailable)
    }

    // ------------------------------------------------------------------ midnight and date rollover

    @Test
    fun `after midnight the reading comes from the new local date's schedule`() {
        // 00:30 on the following local date: Home's request is for that date, and the pre-dawn
        // period it contains is that day's carried-over Isha.
        val nextDate = date.plusDays(1)
        val nextSchedule = schedule(
            targetDate = nextDate,
            prayers = listOf(
                PrayerTimeEntry(PrayerName.FAJR, nextDate.atTime(4, 40).atZone(zone)),
                PrayerTimeEntry(PrayerName.DHUHR, nextDate.atTime(11, 45).atZone(zone)),
                PrayerTimeEntry(PrayerName.ASR, nextDate.atTime(15, 57).atZone(zone)),
                PrayerTimeEntry(PrayerName.MAGHRIB, nextDate.atTime(17, 35).atZone(zone)),
                PrayerTimeEntry(PrayerName.ISHA, nextDate.atTime(18, 50).atZone(zone)),
            ),
            solarEvents = listOf(
                SolarEventEntry(SolarEvent.SUNRISE, nextDate.atTime(5, 55).atZone(zone)),
                SolarEventEntry(SolarEvent.SOLAR_NOON, nextDate.atTime(11, 44).atZone(zone)),
                SolarEventEntry(SolarEvent.SUNSET, nextDate.atTime(17, 35).atZone(zone)),
            ),
        )

        val period = active(resolver.resolve(nextSchedule, nextDate.atTime(0, 30).atZone(zone)))

        assertEquals(PrayerName.ISHA, period.prayer)
        assertEquals(nextDate.atTime(4, 40).atZone(zone), period.endsAt)
        assertEquals(Duration.ofMinutes(250), period.remaining)
    }

    @Test
    fun `a schedule for another local date is refused rather than applied`() {
        val status = resolver.resolve(schedule(), date.plusDays(1).atTime(15, 0).atZone(zone))

        assertTrue(status is CurrentPrayerStatus.Unavailable)
        assertEquals(
            UnavailabilityReason.UNKNOWN,
            (status as CurrentPrayerStatus.Unavailable).reason,
        )
    }

    @Test
    fun `the moment is read in the prayer location's zone, not the device's`() {
        // 15:00 in Dhaka is 09:00 UTC. Read in any other zone the answer would differ, so the
        // conversion is what makes this the same reading on every device.
        val asUtc = date.atTime(15, 0).atZone(zone).withZoneSameInstant(ZoneId.of("UTC"))

        val period = active(resolver.resolve(schedule(), asUtc))

        assertEquals(PrayerName.DHUHR, period.prayer)
        assertEquals(at(15, 58), period.endsAt)
    }

    // ------------------------------------------------------------------------ missing and invalid

    @Test
    fun `a missing schedule is unavailable, never a guess`() {
        val status = resolver.resolve(null, at(15, 0))

        assertTrue(status is CurrentPrayerStatus.Unavailable)
    }

    @Test
    fun `an incomplete schedule is unavailable rather than partially interpreted`() {
        val partial = schedule(
            prayers = listOf(PrayerTimeEntry(PrayerName.FAJR, at(4, 39))),
        )

        val status = resolver.resolve(partial, at(15, 0))

        assertTrue(status is CurrentPrayerStatus.Unavailable)
    }

    @Test
    fun `duplicated prayer times are treated as invalid data`() {
        val duplicated = schedule(
            prayers = listOf(
                PrayerTimeEntry(PrayerName.FAJR, at(4, 39)),
                PrayerTimeEntry(PrayerName.DHUHR, at(11, 46)),
                PrayerTimeEntry(PrayerName.ASR, at(11, 46)),
                PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 36)),
                PrayerTimeEntry(PrayerName.ISHA, at(18, 51)),
            ),
        )

        val status = resolver.resolve(duplicated, at(13, 0))

        assertTrue(status is CurrentPrayerStatus.Unavailable)
    }

    @Test
    fun `prayer times out of chronological order are treated as invalid data`() {
        val outOfOrder = schedule(
            prayers = listOf(
                PrayerTimeEntry(PrayerName.FAJR, at(4, 39)),
                PrayerTimeEntry(PrayerName.DHUHR, at(11, 46)),
                PrayerTimeEntry(PrayerName.ASR, at(10, 58)),
                PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 36)),
                PrayerTimeEntry(PrayerName.ISHA, at(18, 51)),
            ),
        )

        val status = resolver.resolve(outOfOrder, at(13, 0))

        assertTrue(status is CurrentPrayerStatus.Unavailable)
    }

    @Test
    fun `a morning without sunrise is unresolved rather than closed at a wrong boundary`() {
        val withoutSunrise = schedule(
            solarEvents = listOf(
                SolarEventEntry(SolarEvent.SOLAR_NOON, at(11, 45)),
                SolarEventEntry(SolarEvent.SUNSET, at(17, 36)),
            ),
        )

        val status = resolver.resolve(withoutSunrise, at(5, 30))

        assertTrue(status is CurrentPrayerStatus.Unavailable)
    }

    // ----------------------------------------------------------------- different Bangladesh places

    @Test
    fun `a second Bangladesh location produces its own boundaries`() {
        val chattogramSchedule = schedule(
            location = chattogram,
            prayers = listOf(
                PrayerTimeEntry(PrayerName.FAJR, at(4, 34)),
                PrayerTimeEntry(PrayerName.DHUHR, at(11, 45)),
                PrayerTimeEntry(PrayerName.ASR, at(15, 55)),
                PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 30)),
                PrayerTimeEntry(PrayerName.ISHA, at(18, 45)),
            ),
        )

        val period = active(resolver.resolve(chattogramSchedule, at(16, 15)))

        assertEquals(PrayerName.ASR, period.prayer)
        assertEquals(at(17, 30), period.endsAt)
        assertEquals(Duration.ofMinutes(75), period.remaining)
    }

    @Test
    fun `a location in another zone is read in its own zone, not the board's`() {
        // The Bangladesh policy keeps every stored location in Asia/Dhaka, but the resolver must not
        // assume that: the schedule's own zone is what the times mean.
        val elsewhere = checkNotNull(
            LocationInfo.create("London", 51.5074, -0.1278, "Europe/London"),
        )
        val londonSchedule = PrayerSchedule(
            date = date,
            location = elsewhere,
            profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
            prayers = listOf(
                PrayerTimeEntry(PrayerName.FAJR, date.atTime(5, 30).atZone(ZoneId.of("Europe/London"))),
                PrayerTimeEntry(PrayerName.DHUHR, date.atTime(12, 45).atZone(ZoneId.of("Europe/London"))),
                PrayerTimeEntry(PrayerName.ASR, date.atTime(15, 30).atZone(ZoneId.of("Europe/London"))),
                PrayerTimeEntry(PrayerName.MAGHRIB, date.atTime(18, 5).atZone(ZoneId.of("Europe/London"))),
                PrayerTimeEntry(PrayerName.ISHA, date.atTime(19, 30).atZone(ZoneId.of("Europe/London"))),
            ),
            solarEvents = listOf(
                SolarEventEntry(SolarEvent.SUNRISE, date.atTime(7, 15).atZone(ZoneId.of("Europe/London"))),
                SolarEventEntry(SolarEvent.SOLAR_NOON, date.atTime(12, 44).atZone(ZoneId.of("Europe/London"))),
                SolarEventEntry(SolarEvent.SUNSET, date.atTime(18, 12).atZone(ZoneId.of("Europe/London"))),
            ),
            generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
        )

        // 11 October is inside British Summer Time (UTC+1), so 15:00 UTC is 16:00 in London: inside
        // the Asr period, which runs 15:30 to 18:05 local. Read as UTC the same instant would land
        // in Dhuhr, which is exactly the mistake this test guards against.
        val period = active(
            resolver.resolve(londonSchedule, date.atTime(15, 0).atZone(ZoneId.of("UTC"))),
        )

        assertEquals(PrayerName.ASR, period.prayer)
        assertEquals(ZoneId.of("Europe/London"), period.endsAt.zone)
        assertEquals(date.atTime(18, 5).atZone(ZoneId.of("Europe/London")), period.endsAt)
    }

    // --------------------------------------------------------------------------- period inventory

    @Test
    fun `every hour of the day resolves to exactly one reading`() {
        val nextDayFajr = date.plusDays(1).atTime(4, 40).atZone(zone)
        val readings = (0 until 24).map { hour ->
            resolver.resolve(schedule(), at(hour, 0), nextDayFajr)
        }

        // No hour may fall through to an unresolved state: the whole day is covered by periods and by
        // the gap between sunrise and Dhuhr.
        assertTrue(
            "unexpected unresolved hours: ${readings.withIndex().filter { it.value is CurrentPrayerStatus.Unavailable }.map { it.index }}",
            readings.none { it is CurrentPrayerStatus.Unavailable },
        )
        // And the reading is never a period that has already closed.
        readings.filterIsInstance<CurrentPrayerStatus.Active>().forEach { reading ->
            val period = reading.period
            assertTrue(period.prayer.name, period.remaining >= Duration.ZERO)
            assertTrue(period.endsAt.isAfter(period.startsAt ?: period.endsAt.minusDays(1)))
        }
    }

    @Test
    fun `the remaining time is measured to the boundary that closes the period`() {
        val period = active(resolver.resolve(schedule(), at(12, 0)))

        assertEquals(PrayerName.DHUHR, period.prayer)
        assertEquals(Duration.between(at(12, 0), period.endsAt), period.remaining)
        assertEquals(at(15, 58), period.endsAt)
    }
}
