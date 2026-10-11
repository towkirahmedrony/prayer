package io.github.towkirahmedrony.salah.data.prayer

import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleRequest
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerTimesProviderResult
import io.github.towkirahmedrony.salah.core.domain.prayer.ProviderAvailability
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Integration tests for the real local engine (Adhan Kotlin, `com.batoulapps.adhan:adhan2:0.0.5`).
 *
 * ## Where the expected values come from
 *
 * The exact times asserted here are **regression values taken from this engine version itself**
 * for a documented location, date, time zone and profile. They pin the engine version and the
 * Salah-to-engine mapping so a silent drift in either is caught.
 *
 * They are *not* an independent religious verification. Nothing in this file establishes that a
 * calculation matches any authority's published timetable; that requires comparing against an
 * identified timetable for the same location and date, which has not been done. See
 * `docs/PRAYER_CALCULATION.md` §6.
 *
 * ## Tolerance
 *
 * All comparisons are exact, at the engine's own one-minute rounding. The engine truncates each
 * event to a whole minute, so there is no sub-minute tolerance to apply; an assertion is either
 * the same minute or it is a real difference.
 */
class AdhanPrayerTimesProviderTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneId.of("UTC"))
    private val provider = AdhanPrayerTimesProvider(clock = clock)

    private val dhaka: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )
    private val date: LocalDate = LocalDate.of(2026, 10, 11)
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    private fun profile(
        method: PrayerCalculationMethod = PrayerCalculationMethod.KARACHI,
        asrMethod: AsrCalculationMethod = AsrCalculationMethod.HANAFI,
        highLatitudeRule: HighLatitudeRule = HighLatitudeRule.AUTOMATIC,
        adjustments: Map<PrayerName, Int> = emptyMap(),
    ) = PrayerCalculationProfile(method, asrMethod, highLatitudeRule, adjustments)

    private suspend fun schedule(
        location: LocationInfo = dhaka,
        on: LocalDate = date,
        withProfile: PrayerCalculationProfile = profile(),
    ): PrayerSchedule {
        val result = provider.schedule(PrayerScheduleRequest(on, location, withProfile))
        assertTrue("expected a schedule but was $result", result is PrayerTimesProviderResult.Success)
        return (result as PrayerTimesProviderResult.Success).schedule
    }

    /** `HH:mm` in the schedule's own zone, which is what every expectation below is written in. */
    private fun PrayerSchedule.at(prayer: PrayerName): String =
        timeOf(prayer)!!.toLocalTime().toString().substring(0, 5)

    private fun PrayerSchedule.at(event: SolarEvent): String =
        timeOf(event)!!.toLocalTime().toString().substring(0, 5)

    private val PrayerSchedule.ordered: List<Instant>
        get() = (prayers.sortedBy { it.time.toInstant() }.map { it.time.toInstant() } +
            solarEvents.map { it.time.toInstant() }).sorted()

    @Test
    fun `is a configured, offline provider`() {
        assertEquals(PrayerTimeSource.LOCAL_CALCULATION, provider.source)
        assertTrue(provider.isAvailableOffline)
        assertEquals(ProviderAvailability.CONFIGURED, provider.availability())
    }

    @Test
    fun `produces a complete daily schedule for Dhaka with the expected values`() = runTest {
        val schedule = schedule()

        assertTrue(schedule.isComplete)
        assertEquals(date, schedule.date)
        assertEquals(zone, schedule.zoneId)

        assertEquals("04:39", schedule.at(PrayerName.FAJR))
        assertEquals("11:46", schedule.at(PrayerName.DHUHR))
        assertEquals("15:58", schedule.at(PrayerName.ASR))
        assertEquals("17:36", schedule.at(PrayerName.MAGHRIB))
        assertEquals("18:51", schedule.at(PrayerName.ISHA))

        assertEquals("05:54", schedule.at(SolarEvent.SUNRISE))
        assertEquals("11:45", schedule.at(SolarEvent.SOLAR_NOON))
        assertEquals("17:36", schedule.at(SolarEvent.SUNSET))
    }

    @Test
    fun `orders the day from Fajr to Isha`() = runTest {
        val schedule = schedule()
        val ordered = schedule.ordered

        assertEquals(ordered, ordered.sorted())
        assertTrue(schedule.timeOf(PrayerName.FAJR)!!.isBefore(schedule.timeOf(SolarEvent.SUNRISE)!!))
        assertTrue(schedule.timeOf(SolarEvent.SOLAR_NOON)!!.isBefore(schedule.timeOf(PrayerName.DHUHR)!!))
        assertTrue(schedule.timeOf(PrayerName.DHUHR)!!.isBefore(schedule.timeOf(PrayerName.ASR)!!))
        assertTrue(schedule.timeOf(PrayerName.ASR)!!.isBefore(schedule.timeOf(SolarEvent.SUNSET)!!))
        assertTrue(schedule.timeOf(PrayerName.ISHA)!!.isAfter(schedule.timeOf(PrayerName.MAGHRIB)!!))
    }

    @Test
    fun `keeps the solar events independent of the calculation method`() = runTest {
        // Dubai's method offsets sunrise by -3 and Maghrib by +3 minutes. Solar events are read
        // from the unadjusted engine call, so they must come out identical for both methods,
        // while Maghrib itself must move. This is the check that sunrise and sunset are not
        // aliases of a prayer time.
        val karachi = schedule()
        val dubai = schedule(withProfile = profile(method = PrayerCalculationMethod.DUBAI))

        assertEquals(karachi.at(SolarEvent.SUNRISE), dubai.at(SolarEvent.SUNRISE))
        assertEquals(karachi.at(SolarEvent.SUNSET), dubai.at(SolarEvent.SUNSET))
        assertNotEquals(karachi.at(PrayerName.MAGHRIB), dubai.at(PrayerName.MAGHRIB))
        assertTrue(dubai.timeOf(PrayerName.MAGHRIB)!!.isAfter(dubai.timeOf(SolarEvent.SUNSET)!!))
        assertNotEquals(dubai.at(SolarEvent.SUNSET), dubai.at(PrayerName.MAGHRIB))
    }

    @Test
    fun `changing the Asr method changes Asr and nothing else`() = runTest {
        val hanafi = schedule()
        val standard = schedule(withProfile = profile(asrMethod = AsrCalculationMethod.STANDARD))

        assertEquals("15:58", hanafi.at(PrayerName.ASR))
        assertEquals("15:06", standard.at(PrayerName.ASR))
        assertTrue(hanafi.timeOf(PrayerName.ASR)!!.isAfter(standard.timeOf(PrayerName.ASR)!!))

        PrayerName.entries.filter { it != PrayerName.ASR }.forEach { prayer ->
            assertEquals(
                "the Asr method must not move $prayer",
                hanafi.at(prayer),
                standard.at(prayer),
            )
        }
        SolarEvent.entries.forEach { event ->
            assertEquals(hanafi.at(event), standard.at(event))
        }
    }

    @Test
    fun `changing the calculation method changes Fajr and Isha`() = runTest {
        val karachi = schedule()
        val northAmerica = schedule(withProfile = profile(method = PrayerCalculationMethod.NORTH_AMERICA))

        // Karachi uses 18/18 angles, ISNA 15/15, so both twilight bounds must move. The two
        // methods are intentionally not asserted to be equal anywhere.
        assertNotEquals(karachi.at(PrayerName.FAJR), northAmerica.at(PrayerName.FAJR))
        assertNotEquals(karachi.at(PrayerName.ISHA), northAmerica.at(PrayerName.ISHA))
        assertTrue(northAmerica.timeOf(PrayerName.FAJR)!!.isAfter(karachi.timeOf(PrayerName.FAJR)!!))
    }

    @Test
    fun `applies manual adjustments to prayers only and records them`() = runTest {
        val plain = schedule()
        val adjusted = schedule(
            withProfile = profile(adjustments = mapOf(PrayerName.FAJR to 5, PrayerName.ISHA to -3)),
        )

        assertEquals(
            plain.timeOf(PrayerName.FAJR)!!.plusMinutes(5),
            adjusted.timeOf(PrayerName.FAJR),
        )
        assertEquals(
            plain.timeOf(PrayerName.ISHA)!!.minusMinutes(3),
            adjusted.timeOf(PrayerName.ISHA),
        )
        assertEquals(
            5,
            adjusted.prayers.first { it.prayer == PrayerName.FAJR }.adjustmentMinutes,
        )
        assertEquals(
            -3,
            adjusted.prayers.first { it.prayer == PrayerName.ISHA }.adjustmentMinutes,
        )
        assertEquals(0, adjusted.prayers.first { it.prayer == PrayerName.DHUHR }.adjustmentMinutes)

        // Sunrise and sunset are not prayers, so no manual prayer correction may touch them.
        SolarEvent.entries.forEach { event ->
            assertEquals(plain.at(event), adjusted.at(event))
        }
    }

    @Test
    fun `returns an explicit unavailable result inside the polar day and night`() = runTest {
        // Tromso on both solstices: the sun never reaches the Fajr or Isha angle, so the engine
        // cannot produce a schedule. Salah must report that, never throw and never guess.
        val tromso = checkNotNull(LocationInfo.create("Tromsø", 69.6492, 18.9553, "Europe/Oslo"))

        listOf(LocalDate.of(2026, 6, 21), LocalDate.of(2026, 12, 21)).forEach { solstice ->
            val result = provider.schedule(
                PrayerScheduleRequest(solstice, tromso, profile(method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE)),
            )

            assertTrue("expected Unavailable but was $result", result is PrayerTimesProviderResult.Unavailable)
            assertEquals(
                UnavailabilityReason.CALCULATION_NOT_POSSIBLE,
                (result as PrayerTimesProviderResult.Unavailable).reason,
            )
            assertFalse(result.detail.isNullOrBlank())
        }
    }

    @Test
    fun `renders every time in the prayer location's zone across a daylight-saving transition`() = runTest {
        // 29 March 2026 is the day British Summer Time begins, so the whole local day runs on
        // UTC+1. Everything must come out in the location's zone and offset, whatever the
        // device's own zone is: the test clock is UTC, which is neither.
        val london = checkNotNull(LocationInfo.create("London", 51.5074, -0.1278, "Europe/London"))
        val schedule = schedule(
            location = london,
            on = LocalDate.of(2026, 3, 29),
            withProfile = profile(method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE),
        )

        assertEquals(ZoneId.of("Europe/London"), schedule.zoneId)
        assertEquals("05:07", schedule.at(PrayerName.FAJR))
        assertEquals("13:06", schedule.at(PrayerName.DHUHR))
        assertEquals("21:05", schedule.at(PrayerName.ISHA))
        assertEquals("13:05", schedule.at(SolarEvent.SOLAR_NOON))

        (schedule.prayers.map { it.time } + schedule.solarEvents.map { it.time }).forEach { time ->
            assertEquals("a time escaped into another zone: $time", ZoneId.of("Europe/London"), time.zone)
            assertEquals("expected BST (+01:00) for every entry, got $time", 1, time.offset.totalSeconds / 3_600)
        }
    }

    @Test
    fun `is deterministic and does not depend on the device clock`() = runTest {
        val later = AdhanPrayerTimesProvider(Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"), ZoneId.of("UTC")))
            .schedule(PrayerScheduleRequest(date, dhaka, profile())) as PrayerTimesProviderResult.Success

        val first = schedule()

        // Same inputs, same instants; only the reported generation timestamp may differ.
        assertEquals(
            first.prayers.map { it.time.toInstant() },
            later.schedule.prayers.map { it.time.toInstant() },
        )
        assertEquals(
            first.solarEvents.map { it.time.toInstant() },
            later.schedule.solarEvents.map { it.time.toInstant() },
        )
        assertEquals(clock.instant(), first.generatedAt)
    }

    @Test
    fun `records the request it was calculated from`() = runTest {
        val used = profile(method = PrayerCalculationMethod.KUWAIT, asrMethod = AsrCalculationMethod.STANDARD)
        val schedule = schedule(withProfile = used)

        // The schedule carries the profile so a cached entry can never be shown for different
        // settings than the ones it was produced with.
        assertEquals(used, schedule.profile)
        assertEquals(dhaka, schedule.location)
    }

    @Test
    fun `produces a schedule for a southern-hemisphere location`() = runTest {
        // Guards against a northern-hemisphere assumption in the mapping and in the ordering.
        val sydney = checkNotNull(LocationInfo.create("Sydney", -33.8688, 151.2093, "Australia/Sydney"))
        val schedule = schedule(
            location = sydney,
            on = LocalDate.of(2026, 10, 11),
            withProfile = profile(method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE),
        )

        assertTrue(schedule.isComplete)
        assertEquals(ZoneId.of("Australia/Sydney"), schedule.zoneId)
        assertTrue(schedule.timeOf(PrayerName.FAJR)!!.isBefore(schedule.timeOf(SolarEvent.SUNRISE)!!))
        assertTrue(schedule.timeOf(PrayerName.ISHA)!!.isAfter(schedule.timeOf(PrayerName.MAGHRIB)!!))
    }
}
