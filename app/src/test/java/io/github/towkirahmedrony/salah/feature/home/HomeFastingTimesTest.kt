package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerPeriodResolver
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Sehri end and Iftar the Home hero card shows: they are the calculated Fajr and Maghrib read from
 * today's schedule, never a second calculation, and they are absent when there is no schedule.
 */
class HomeFastingTimesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)
    private val generatedAt: Instant = Instant.parse("2026-10-11T00:00:00Z")

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
        observedAt = generatedAt,
        isAvailableOffline = true,
    )

    private fun at(hour: Int, minute: Int): ZonedDateTime = date.atTime(hour, minute).atZone(zone)

    private val schedule = PrayerSchedule(
        date = date,
        location = location,
        profile = PrayerSettings.DEFAULT.toProfile(),
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
        generatedAt = generatedAt,
    )

    private fun ready(scheduleState: PrayerScheduleState, now: ZonedDateTime): HomeUiState.Ready {
        val state = factory.create(
            calendar = null,
            location = location,
            settings = PrayerSettings.DEFAULT.copy(timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR),
            scheduleState = scheduleState,
            prohibitedState = ProhibitedPeriodsState.NotAvailable(reason = UnavailabilityReason.RULES_NOT_VERIFIED),
            now = now,
        )
        assertTrue("expected Ready but was $state", state is HomeUiState.Ready)
        return state as HomeUiState.Ready
    }

    private fun available(now: ZonedDateTime) =
        ready(PrayerScheduleState.Available(schedule, origin), now)

    @Test
    fun `Sehri ends at the Fajr the timetable shows and Iftar is its Maghrib`() {
        val state = available(at(15, 0))

        val fajr = state.prayerRows.first { it.prayer == PrayerName.FAJR }.timeLabel
        val maghrib = state.prayerRows.first { it.prayer == PrayerName.MAGHRIB }.timeLabel

        assertNotNull(fajr)
        assertNotNull(maghrib)
        assertEquals(fajr, state.fasting.sehriEndLabel)
        assertEquals(maghrib, state.fasting.iftarLabel)
    }

    @Test
    fun `before Sehri ends neither time has passed`() {
        val fasting = available(at(3, 30)).fasting

        assertFalse(fasting.sehriPassed)
        assertFalse(fasting.iftarPassed)
    }

    @Test
    fun `between Sehri and Iftar only Sehri has passed`() {
        val fasting = available(at(15, 0)).fasting

        assertTrue(fasting.sehriPassed)
        assertFalse(fasting.iftarPassed)
    }

    @Test
    fun `after Iftar both have passed`() {
        val fasting = available(at(19, 30)).fasting

        assertTrue(fasting.sehriPassed)
        assertTrue(fasting.iftarPassed)
    }

    @Test
    fun `a time that begins exactly now counts as passed`() {
        val fasting = available(at(17, 36)).fasting

        assertTrue(fasting.iftarPassed)
    }

    @Test
    fun `there are no times without a schedule`() {
        val state = ready(
            PrayerScheduleState.Unavailable(reason = UnavailabilityReason.NO_LOCATION_CONFIGURED, origin = origin),
            at(15, 0),
        )

        assertEquals(FastingTimesSection.Unavailable, state.fasting)
    }
}
