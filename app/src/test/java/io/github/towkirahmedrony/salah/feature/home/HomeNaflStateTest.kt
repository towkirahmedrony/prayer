package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.CurrentPrayerPeriodResolver
import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.nafl.NaflKind
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.domain.prohibited.ConfiguredProhibitedRuleSet
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodStatusResolver
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedWindowKind
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.PrayerTimeSource
import io.github.towkirahmedrony.salah.core.model.ProhibitedConvention
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriod
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What the Home state says about the voluntary prayers and the hero ring's non-fard reading, on the
 * Dhaka day of 8 November 2026 (windows: sunrise 06:08-06:28, zenith 11:37-11:46, sunset 16:56-17:16).
 */
class HomeNaflStateTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 11, 8)
    private val generatedAt: Instant = Instant.parse("2026-11-08T00:00:00Z")

    private val factory = HomeUiStateFactory(
        nextPrayerResolver = NextPrayerResolver(),
        currentPrayerResolver = CurrentPrayerPeriodResolver(),
        prohibitedStatusResolver = ProhibitedPeriodStatusResolver(),
    )

    private val location: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.76, 90.40, "Asia/Dhaka"),
    )

    private val origin = DataSourceInfo(
        source = PrayerTimeSource.LOCAL_CALCULATION,
        providerId = "local-adhan-kotlin",
        observedAt = generatedAt,
        isAvailableOffline = true,
    )

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        date.atTime(hour, minute, second).atZone(zone)

    private fun scheduleFor(target: LocalDate, fajrMinute: Int): PrayerSchedule = PrayerSchedule(
        date = target,
        location = location,
        profile = PrayerSettings.DEFAULT.toProfile(),
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, target.atTime(4, fajrMinute).atZone(zone)),
            PrayerTimeEntry(PrayerName.DHUHR, target.atTime(11, 46).atZone(zone)),
            PrayerTimeEntry(PrayerName.ASR, target.atTime(15, 40).atZone(zone)),
            PrayerTimeEntry(PrayerName.MAGHRIB, target.atTime(17, 20).atZone(zone)),
            PrayerTimeEntry(PrayerName.ISHA, target.atTime(18, 33).atZone(zone)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, target.atTime(6, 8).atZone(zone)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, target.atTime(11, 42).atZone(zone)),
            SolarEventEntry(SolarEvent.SUNSET, target.atTime(17, 16).atZone(zone)),
        ),
        generatedAt = generatedAt,
    )

    private fun window(kind: ProhibitedWindowKind, start: ZonedDateTime, end: ZonedDateTime) =
        ProhibitedPeriod(
            startsAt = start,
            endsAt = end,
            ruleSetId = ConfiguredProhibitedRuleSet.ID,
            kind = kind,
            explanation = "test window",
        )

    private val available = ProhibitedPeriodsState.Available(
        periods = listOf(
            window(ProhibitedWindowKind.SUNRISE_ASCENT, at(6, 8), at(6, 28)),
            window(ProhibitedWindowKind.ZENITH_DECLINE, at(11, 37), at(11, 46)),
            window(ProhibitedWindowKind.SUNSET_SETTING, at(16, 56), at(17, 16)),
        ),
        convention = ProhibitedConvention(
            ruleSetId = ConfiguredProhibitedRuleSet.ID,
            label = ConfiguredProhibitedRuleSet.DEFAULT.label,
            isReviewed = false,
            hasVerifiedSources = false,
        ),
    )

    private val unavailable = ProhibitedPeriodsState.NotAvailable(
        reason = UnavailabilityReason.RULES_NOT_VERIFIED,
    )

    private fun ready(
        now: ZonedDateTime,
        prohibited: ProhibitedPeriodsState = available,
        withTomorrow: Boolean = true,
    ): HomeUiState.Ready {
        val state = factory.create(
            calendar = null,
            location = location,
            settings = PrayerSettings.DEFAULT.copy(timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR),
            scheduleState = PrayerScheduleState.Available(scheduleFor(date, 51), origin),
            prohibitedState = prohibited,
            now = now,
            nextDaySchedule = if (withTomorrow) scheduleFor(date.plusDays(1), 52) else null,
        )
        assertTrue("expected Ready but was $state", state is HomeUiState.Ready)
        return state as HomeUiState.Ready
    }

    @Test
    fun `inside the sunrise window the ring is told it is prohibited, with the time left`() {
        val nafl = checkNotNull(ready(at(6, 10)).heroNafl)

        assertEquals(HeroNaflKind.PROHIBITED, nafl.kind)
        assertEquals(ProhibitedWindowKind.SUNRISE_ASCENT, nafl.windowKind)
        assertEquals("06:28", nafl.endLabel)
        assertEquals("00:18:00", nafl.countdownClock)
    }

    @Test
    fun `when the sunrise window closes Duha takes over the ring`() {
        val state = ready(at(6, 28))
        val nafl = checkNotNull(state.heroNafl)

        assertFalse("no fard period is open between sunrise and Dhuhr", state.currentPrayer.isActive)
        assertEquals(HeroNaflKind.DUHA, nafl.kind)
        assertNull(nafl.windowKind)
        assertEquals("11:37", nafl.endLabel)
        assertEquals("05:09:00", nafl.countdownClock)
        assertEquals(0f, nafl.progress!!, 0.0001f)
    }

    @Test
    fun `the zenith window is prohibited until Dhuhr begins`() {
        val state = ready(at(11, 40))
        val nafl = checkNotNull(state.heroNafl)

        assertFalse(state.currentPrayer.isActive)
        assertEquals(ProhibitedWindowKind.ZENITH_DECLINE, nafl.windowKind)
        assertEquals("11:46", nafl.endLabel)
        assertEquals("00:06:00", nafl.countdownClock)
    }

    @Test
    fun `Asr keeps running through the sunset window and the prohibition rides along`() {
        val state = ready(at(16, 59))
        val nafl = checkNotNull(state.heroNafl)

        assertTrue(state.currentPrayer.isActive)
        assertEquals(PrayerName.ASR, state.currentPrayer.prayer)
        assertEquals(HeroNaflKind.PROHIBITED, nafl.kind)
        assertEquals(ProhibitedWindowKind.SUNSET_SETTING, nafl.windowKind)
        assertEquals("17:16", nafl.endLabel)
        assertEquals("00:17:00", nafl.countdownClock)
        assertNotNull(nafl.countdownCompact)
    }

    @Test
    fun `there is no non-fard reading in the afternoon`() {
        assertNull(ready(at(14, 0)).heroNafl)
    }

    @Test
    fun `without prohibited windows there is no Duha and no prohibited reading`() {
        val state = ready(at(6, 10), prohibited = unavailable)

        assertNull(state.heroNafl)
        assertTrue(state.nafl.rows.none { it.kind == NaflKind.DUHA })
        assertTrue(state.nafl.rows.any { it.kind == NaflKind.AWWABIN })
    }

    @Test
    fun `the rows state each period with its own labels`() {
        val nafl = ready(at(9, 0)).nafl

        val duha = nafl.rows.first { it.kind == NaflKind.DUHA }
        assertEquals("06:28", duha.startLabel)
        assertEquals("11:37", duha.endLabel)
        assertEquals("08:55", duha.preferredFromLabel)
        assertTrue(duha.isOpen)
        assertFalse(duha.isPassed)

        val awwabin = nafl.rows.first { it.kind == NaflKind.AWWABIN }
        assertEquals("17:20", awwabin.startLabel)
        assertEquals("18:33", awwabin.endLabel)
        assertFalse(awwabin.isOpen)
        assertFalse(awwabin.isPassed)

        val tahajjud = nafl.rows.first { it.kind == NaflKind.TAHAJJUD }
        // Valid window is after Isha (18:33) to Fajr; the last third (01:01) is the recommended time.
        assertEquals("18:33", tahajjud.startLabel)
        assertEquals("04:52", tahajjud.endLabel)
        assertFalse(tahajjud.startsNextDay)
        assertTrue(tahajjud.endsNextDay)
        assertEquals("01:01", nafl.lastThirdStartsLabel)
    }

    @Test
    fun `Tahajjud is open after Isha across midnight and passed after Fajr`() {
        val evening = ready(at(20, 0)).nafl.rows.first { it.kind == NaflKind.TAHAJJUD }
        assertTrue(evening.isOpen)
        assertFalse(evening.isPassed)

        val afterMidnight = ready(date.plusDays(1).atTime(1, 30).atZone(zone)).nafl.rows
            .first { it.kind == NaflKind.TAHAJJUD }
        assertTrue(afterMidnight.isOpen)

        val afterFajr = ready(date.plusDays(1).atTime(5, 0).atZone(zone)).nafl.rows
            .first { it.kind == NaflKind.TAHAJJUD }
        assertTrue(afterFajr.isPassed)
        assertFalse(afterFajr.isOpen)
    }

    @Test
    fun `Duha is passed once it has closed`() {
        val duha = ready(at(14, 0)).nafl.rows.first { it.kind == NaflKind.DUHA }

        assertTrue(duha.isPassed)
        assertFalse(duha.isOpen)
    }

    @Test
    fun `without tomorrow's schedule there is no Tahajjud row`() {
        val nafl = ready(at(9, 0), withTomorrow = false).nafl

        assertTrue(nafl.rows.none { it.kind == NaflKind.TAHAJJUD })
        assertNull(nafl.lastThirdStartsLabel)
    }
}
