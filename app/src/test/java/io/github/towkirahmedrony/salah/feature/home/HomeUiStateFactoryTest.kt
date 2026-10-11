package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
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
 * Locks in the honesty rules of the Home screen: no countdown without a real schedule, no
 * invented Bangla or Hijri date, no sunrise listed as a prayer, and provider failures shown.
 */
class HomeUiStateFactoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), zone)

    /** 09:00 UTC == 15:00 in Dhaka (UTC+6). */
    private val now: ZonedDateTime = ZonedDateTime.now(clock)

    private val date: LocalDate = now.toLocalDate()

    private val factory = HomeUiStateFactory(
        nextPrayerResolver = NextPrayerResolver(),
        clock = clock,
    )

    private val location: LocationInfo = checkNotNull(
        LocationInfo.create(
            label = "Dhaka",
            latitude = 23.8103,
            longitude = 90.4125,
            timeZoneId = "Asia/Dhaka",
        ),
    )

    private val origin = DataSourceInfo(
        source = PrayerTimeSource.LOCAL_CALCULATION,
        providerId = "test-provider",
        observedAt = clock.instant(),
        isAvailableOffline = true,
    )

    private fun completeSchedule(targetDate: LocalDate = date): PrayerSchedule = PrayerSchedule(
        date = targetDate,
        location = location,
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, targetDate.atTime(4, 55).atZone(zone)),
            PrayerTimeEntry(PrayerName.DHUHR, targetDate.atTime(11, 52).atZone(zone)),
            PrayerTimeEntry(PrayerName.ASR, targetDate.atTime(15, 18).atZone(zone)),
            PrayerTimeEntry(PrayerName.MAGHRIB, targetDate.atTime(17, 36).atZone(zone)),
            PrayerTimeEntry(PrayerName.ISHA, targetDate.atTime(18, 56).atZone(zone)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, targetDate.atTime(6, 10).atZone(zone)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, targetDate.atTime(11, 54).atZone(zone)),
            SolarEventEntry(SolarEvent.SUNSET, targetDate.atTime(17, 34).atZone(zone)),
        ),
        generatedAt = clock.instant(),
    )

    private val calendar = CalendarSnapshot(
        gregorian = CalendarDateState.Available(
            system = CalendarSystem.GREGORIAN,
            formattedDate = "11 October 2026",
            isVerifiedSource = true,
        ),
        bangla = CalendarDateState.NotAvailable(
            system = CalendarSystem.BANGLA,
            reason = UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED,
        ),
        hijri = CalendarDateState.NotAvailable(
            system = CalendarSystem.HIJRI,
            reason = UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED,
        ),
        weekdayLabel = "Sunday",
    )

    private val prohibitedNotAvailable = ProhibitedPeriodsState.NotAvailable(
        reason = UnavailabilityReason.RULES_NOT_VERIFIED,
    )

    private fun notConfiguredState(): PrayerScheduleState = PrayerScheduleState.Unavailable(
        reason = UnavailabilityReason.CALCULATION_NOT_CONFIGURED,
        detail = "No engine integrated.",
        origin = DataSourceInfo(
            source = PrayerTimeSource.UNCONFIGURED,
            providerId = "local-unconfigured",
            observedAt = clock.instant(),
            isAvailableOffline = true,
        ),
    )

    private fun ready(
        location: LocationInfo?,
        scheduleState: PrayerScheduleState,
    ): HomeUiState.Ready {
        val state = factory.create(
            calendar = calendar,
            location = location,
            scheduleState = scheduleState,
            prohibitedState = prohibitedNotAvailable,
            now = now,
        )
        assertTrue(
            "expected Ready but was $state",
            state is HomeUiState.Ready,
        )
        return state as HomeUiState.Ready
    }

    @Test
    fun `explains every missing section instead of inventing a value`() {
        val state = ready(location = null, scheduleState = notConfiguredState())

        // Dates
        assertEquals("11 October 2026", state.gregorianDate)
        assertEquals(UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED, state.banglaSection.unavailable)
        assertEquals(UnavailabilityReason.CALENDAR_NOT_IMPLEMENTED, state.hijriSection.unavailable)
        assertNull(state.banglaSection.value)
        assertNull(state.hijriSection.value)

        // Location
        assertEquals(UnavailabilityReason.NO_LOCATION_CONFIGURED, state.location.unavailable)
        assertNull(state.location.label)

        // Prayer schedule: no times and no countdown
        assertEquals(UnavailabilityReason.CALCULATION_NOT_CONFIGURED, state.timetableNote)
        assertTrue(state.prayerRows.all { it.timeLabel == null })
        assertTrue(state.solarRows.all { it.timeLabel == null })
        assertNull(state.nextPrayer.prayer)
        assertNull(state.nextPrayer.countdown)

        // Prohibited times
        assertTrue(state.prohibited.windows.isEmpty())
        assertEquals(UnavailabilityReason.RULES_NOT_VERIFIED, state.prohibited.unavailable)

        // Provenance
        assertEquals(PrayerTimeSource.UNCONFIGURED, state.provenance.source)
        assertNull(state.failureDetail)
    }

    @Test
    fun `shows a countdown only for a real schedule`() {
        val state = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(completeSchedule(), origin),
        )

        assertNull(state.timetableNote)
        assertEquals(PrayerName.ASR, state.nextPrayer.prayer)
        assertNotNull(state.nextPrayer.time)
        assertEquals("18:00", state.nextPrayer.countdown)
        assertEquals(PrayerName.ASR, state.prayerRows.firstOrNull { it.isNext }?.prayer)
        assertTrue(state.prayerRows.all { it.timeLabel != null })
    }

    @Test
    fun `lists sunrise and sunset as solar events, never as prayers`() {
        val state = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(completeSchedule(), origin),
        )

        assertEquals(
            PrayerName.entries.toList(),
            state.prayerRows.map { it.prayer },
        )
        assertEquals(
            SolarEvent.entries.toList(),
            state.solarRows.map { it.event },
        )
    }

    @Test
    fun `does not count down when the schedule belongs to another day`() {
        val tomorrow = completeSchedule(date.plusDays(1))
        val state = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(tomorrow, origin),
        )

        assertNull(state.nextPrayer.prayer)
        assertNull(state.nextPrayer.countdown)
    }

    @Test
    fun `surfaces a provider failure on the ready screen`() {
        val state = ready(
            location = location,
            scheduleState = PrayerScheduleState.Failed(
                detail = "provider exploded",
                origin = origin,
            ),
        )

        assertEquals("provider exploded", state.failureDetail)
        assertNull(state.nextPrayer.prayer)
        assertNull(state.nextPrayer.countdown)
        assertTrue(state.prayerRows.all { it.timeLabel == null })
    }
}
