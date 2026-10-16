package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerPeriodResolver
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.night.NightBoundary
import io.github.towkirahmedrony.salah.core.domain.night.NightPhase
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodStatusResolver
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Locks in the Home mapping of the night interval and the solar geometry: the values are the points
 * the real factory derives from the reference Dhaka timetable, the missing-input cases are reported
 * rather than invented, and the sections are a pure function of the schedule and the shown moment.
 */
class HomeNightSectionTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-11T14:00:00Z"), zone)
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val factory = HomeUiStateFactory(
        nextPrayerResolver = NextPrayerResolver(),
        currentPrayerResolver = CurrentPrayerPeriodResolver(),
        prohibitedStatusResolver = ProhibitedPeriodStatusResolver(),
    )

    private val location: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    private val origin = DataSourceInfo(
        source = PrayerTimeSource.LOCAL_CALCULATION,
        providerId = "local-adhan-kotlin",
        observedAt = clock.instant(),
        isAvailableOffline = true,
    )

    private fun schedule(targetDate: LocalDate): PrayerSchedule = PrayerSchedule(
        date = targetDate,
        location = location,
        profile = PrayerSettings.DEFAULT.toProfile(),
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, targetDate.atTime(4, 39).atZone(zone)),
            PrayerTimeEntry(PrayerName.DHUHR, targetDate.atTime(11, 46).atZone(zone)),
            PrayerTimeEntry(PrayerName.ASR, targetDate.atTime(15, 58).atZone(zone)),
            PrayerTimeEntry(PrayerName.MAGHRIB, targetDate.atTime(17, 36).atZone(zone)),
            PrayerTimeEntry(PrayerName.ISHA, targetDate.atTime(18, 51).atZone(zone)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, targetDate.atTime(5, 54).atZone(zone)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, targetDate.atTime(11, 45).atZone(zone)),
            SolarEventEntry(SolarEvent.SUNSET, targetDate.atTime(17, 36).atZone(zone)),
        ),
        generatedAt = clock.instant(),
    )

    private fun ready(
        scheduleState: PrayerScheduleState = PrayerScheduleState.Available(schedule(date), origin),
        now: ZonedDateTime = date.atTime(20, 0).atZone(zone),
        nextDaySchedule: PrayerSchedule? = schedule(date.plusDays(1)),
    ): HomeUiState.Ready {
        val state = factory.create(
            calendar = null,
            location = location,
            settings = PrayerSettings.DEFAULT.copy(timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR),
            scheduleState = scheduleState,
            prohibitedState = ProhibitedPeriodsState.NotAvailable(UnavailabilityReason.RULES_NOT_VERIFIED),
            now = now,
            nextDaySchedule = nextDaySchedule,
        )
        assertTrue("expected Ready but was $state", state is HomeUiState.Ready)
        return state as HomeUiState.Ready
    }

    @Test
    fun `maps the night boundaries and the phase for the displayed moment`() {
        val state = ready()

        assertNull(state.night.unavailable)
        assertEquals(NightPhase.EARLY_NIGHT, state.night.phase)
        assertEquals("23:07", state.night.islamicMidnightLabel)
        assertEquals("00:58", state.night.lastThirdStartsLabel)
        assertEquals("00:58", state.night.tahajjudStartsLabel)
        assertEquals("11h 3m", state.night.durationLabel)
        assertEquals(NightBoundary.ISLAMIC_MIDNIGHT, state.night.nextBoundary)
        assertEquals("3:07:30", state.night.countdown)
        assertNotNull(state.night.convention)
    }

    @Test
    fun `advances the phase and the countdown with the moment`() {
        val lastThird = ready(now = date.plusDays(1).atTime(1, 0).atZone(zone))

        assertEquals(NightPhase.LAST_THIRD, lastThird.night.phase)
        assertEquals(NightBoundary.FAJR, lastThird.night.nextBoundary)
        assertEquals("3:39:00", lastThird.night.countdown)
    }

    @Test
    fun `maps the solar geometry from the schedule's own events`() {
        val state = ready()

        assertNull(state.solarDay.unavailable)
        assertEquals("23:45", state.solarDay.solarMidnightLabel)
        assertEquals("11h 42m", state.solarDay.dayLengthLabel)
        assertEquals("12h 18m", state.solarDay.nightLengthLabel)
    }

    @Test
    fun `reports the night interval as unavailable without the following day's Fajr`() {
        val state = ready(nextDaySchedule = null)

        assertNotNull(state.night.unavailable)
        assertNull(state.night.islamicMidnightLabel)
        assertNull(state.night.lastThirdStartsLabel)
        assertNull(state.night.tahajjudStartsLabel)
        assertNull(state.night.phase)
        assertNull(state.night.countdown)
        assertNull(state.night.convention)
        // The solar geometry needs only today's events, so it is still shown.
        assertNull(state.solarDay.unavailable)
    }

    @Test
    fun `reports both sections as unavailable with no schedule`() {
        val state = ready(
            scheduleState = PrayerScheduleState.Unavailable(
                reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                origin = origin,
            ),
            nextDaySchedule = null,
        )

        assertNotNull(state.night.unavailable)
        assertNotNull(state.solarDay.unavailable)
        assertNull(state.night.durationLabel)
        assertNull(state.solarDay.solarMidnightLabel)
    }

    @Test
    fun `outside the night interval there is no phase but the boundaries are still stated`() {
        val state = ready(now = date.atTime(9, 0).atZone(zone))

        assertNull(state.night.phase)
        // The interval is known, so its boundaries are still shown; only the "where are we" reading
        // is absent, which is the correct reading for mid-morning.
        assertEquals("23:07", state.night.islamicMidnightLabel)
        assertEquals(NightBoundary.ISLAMIC_MIDNIGHT, state.night.nextBoundary)
    }
}
