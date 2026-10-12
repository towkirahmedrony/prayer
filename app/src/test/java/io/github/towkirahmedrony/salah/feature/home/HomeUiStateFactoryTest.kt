package io.github.towkirahmedrony.salah.feature.home

import io.github.towkirahmedrony.salah.core.domain.NextPrayerResolver
import io.github.towkirahmedrony.salah.core.domain.prayer.PrayerScheduleState
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.CalendarDateState
import io.github.towkirahmedrony.salah.core.model.CalendarSnapshot
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DataSourceInfo
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
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
import org.junit.Assert.assertNotEquals
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
 * invented Bangla or Hijri date, no sunrise listed as a prayer, provider failures shown, and the
 * calculation settings that produced a timetable always disclosed.
 *
 * The schedule fixture uses the values the real engine produces for Dhaka on this date (see
 * `AdhanPrayerTimesProviderTest`), so the mapping is exercised against realistic input.
 */
class HomeUiStateFactoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-11T09:00:00Z"), zone)

    /** 09:00 UTC == 15:00 in Dhaka (UTC+6). */
    private val now: ZonedDateTime = ZonedDateTime.now(clock)

    private val date: LocalDate = now.toLocalDate()

    private val factory = HomeUiStateFactory(nextPrayerResolver = NextPrayerResolver())

    private val location: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    private val origin = DataSourceInfo(
        source = PrayerTimeSource.LOCAL_CALCULATION,
        providerId = "local-adhan-kotlin",
        observedAt = clock.instant(),
        isAvailableOffline = true,
    )

    private fun completeSchedule(
        targetDate: LocalDate = date,
        adjustments: Map<PrayerName, Int> = emptyMap(),
    ): PrayerSchedule = PrayerSchedule(
        date = targetDate,
        location = location,
        profile = PrayerSettings.DEFAULT.toProfile(),
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, targetDate.atTime(4, 39).atZone(zone), adjustments[PrayerName.FAJR] ?: 0),
            PrayerTimeEntry(PrayerName.DHUHR, targetDate.atTime(11, 46).atZone(zone), adjustments[PrayerName.DHUHR] ?: 0),
            PrayerTimeEntry(PrayerName.ASR, targetDate.atTime(15, 58).atZone(zone), adjustments[PrayerName.ASR] ?: 0),
            PrayerTimeEntry(PrayerName.MAGHRIB, targetDate.atTime(17, 36).atZone(zone), adjustments[PrayerName.MAGHRIB] ?: 0),
            PrayerTimeEntry(PrayerName.ISHA, targetDate.atTime(18, 51).atZone(zone), adjustments[PrayerName.ISHA] ?: 0),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, targetDate.atTime(5, 54).atZone(zone)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, targetDate.atTime(11, 45).atZone(zone)),
            SolarEventEntry(SolarEvent.SUNSET, targetDate.atTime(17, 36).atZone(zone)),
        ),
        generatedAt = clock.instant(),
    )

    /**
     * The calendar snapshot the app now produces for a supported date: all three calendars resolved.
     *
     * The strings are fixtures — this class tests the *mapping* — but they are the real values for
     * 11 October 2026 (Boishakh 1 = 14 April, so it is the 26th of Ashwin; 30 Rabi' al-Thani 1448 in
     * the platform's Umm al-Qura table), so they could not be mistaken for invented dates.
     */
    private val calendar = CalendarSnapshot(
        gregorian = CalendarDateState.Available(
            system = CalendarSystem.GREGORIAN,
            formattedDate = "11 October 2026",
            isVerifiedSource = true,
        ),
        bangla = CalendarDateState.Available(
            system = CalendarSystem.BANGLA,
            formattedDate = "26 Ashwin 1433",
            isVerifiedSource = true,
        ),
        hijri = CalendarDateState.Available(
            system = CalendarSystem.HIJRI,
            formattedDate = "30 Rabi' al-Thani 1448 AH",
            isVerifiedSource = false,
        ),
        weekdayLabel = "Sunday",
    )

    /** The shape a converter reports when the requested date is outside the range it is verified for. */
    private val calendarOutsideSupportedRange = calendar.copy(
        bangla = CalendarDateState.NotAvailable(
            system = CalendarSystem.BANGLA,
            reason = UnavailabilityReason.OUT_OF_SUPPORTED_RANGE,
        ),
        hijri = CalendarDateState.NotAvailable(
            system = CalendarSystem.HIJRI,
            reason = UnavailabilityReason.OUT_OF_SUPPORTED_RANGE,
        ),
    )

    private val prohibitedNotAvailable = ProhibitedPeriodsState.NotAvailable(
        reason = UnavailabilityReason.RULES_NOT_VERIFIED,
    )

    private fun ready(
        location: LocationInfo?,
        scheduleState: PrayerScheduleState,
        settings: PrayerSettings = PrayerSettings.DEFAULT,
        calendar: CalendarSnapshot = this.calendar,
    ): HomeUiState.Ready {
        val state = factory.create(
            calendar = calendar,
            location = location,
            settings = settings,
            scheduleState = scheduleState,
            prohibitedState = prohibitedNotAvailable,
            now = now,
        )
        assertTrue("expected Ready but was $state", state is HomeUiState.Ready)
        return state as HomeUiState.Ready
    }

    @Test
    fun `explains every missing section instead of inventing a value`() {
        val state = ready(
            location = null,
            scheduleState = PrayerScheduleState.Unavailable(
                reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                origin = origin,
            ),
        )

        assertEquals("11 October 2026", state.gregorianDate)
        assertEquals(UnavailabilityReason.NO_LOCATION_CONFIGURED, state.location.unavailable)
        assertNull(state.location.label)

        assertEquals(UnavailabilityReason.NO_LOCATION_CONFIGURED, state.timetableNote)
        assertTrue(state.prayerRows.all { it.timeLabel == null })
        assertTrue(state.solarRows.all { it.timeLabel == null })
        assertNull(state.nextPrayer.prayer)
        assertNull(state.nextPrayer.countdown)

        assertTrue(state.prohibited.windows.isEmpty())
        assertEquals(UnavailabilityReason.RULES_NOT_VERIFIED, state.prohibited.unavailable)
        assertNull(state.failureDetail)
    }

    @Test
    fun `shows the Bangla and Hijri dates the device can actually produce`() {
        val state = ready(
            location = null,
            scheduleState = PrayerScheduleState.Unavailable(
                reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                origin = origin,
            ),
        )

        // The Home screen now carries a real conversion for both, rather than an explanation that
        // conversion is missing.
        assertEquals("26 Ashwin 1433", state.banglaSection.value)
        assertNull(state.banglaSection.unavailable)
        assertEquals("30 Rabi' al-Thani 1448 AH", state.hijriSection.value)
        assertNull(state.hijriSection.unavailable)
    }

    @Test
    fun `explains a date outside the verified range instead of showing one`() {
        val state = ready(
            location = null,
            scheduleState = PrayerScheduleState.Unavailable(
                reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                origin = origin,
            ),
            calendar = calendarOutsideSupportedRange,
        )

        assertEquals(UnavailabilityReason.OUT_OF_SUPPORTED_RANGE, state.banglaSection.unavailable)
        assertEquals(UnavailabilityReason.OUT_OF_SUPPORTED_RANGE, state.hijriSection.unavailable)
        assertNull(state.banglaSection.value)
        assertNull(state.hijriSection.value)
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
        assertEquals("58:00", state.nextPrayer.countdown)
        assertEquals(PrayerName.ASR, state.prayerRows.firstOrNull { it.isNext }?.prayer)
        assertTrue(state.prayerRows.all { it.timeLabel != null })
    }

    @Test
    fun `lists sunrise and sunset as solar events, never as prayers`() {
        val state = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(completeSchedule(), origin),
        )

        assertEquals(PrayerName.entries.toList(), state.prayerRows.map { it.prayer })
        assertEquals(SolarEvent.entries.toList(), state.solarRows.map { it.event })
    }

    @Test
    fun `does not count down when the schedule belongs to another day`() {
        val state = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(completeSchedule(date.plusDays(1)), origin),
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

    @Test
    fun `reports the method that produced the timetable`() {
        val settings = PrayerSettings(
            method = PrayerCalculationMethod.EGYPTIAN,
            asrMethod = AsrCalculationMethod.STANDARD,
            highLatitudeRule = HighLatitudeRule.SEVENTH_OF_THE_NIGHT,
        )
        val state = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(completeSchedule(), origin),
            settings = settings,
        )

        assertEquals(PrayerCalculationMethod.EGYPTIAN, state.calculation.method)
        assertEquals(AsrCalculationMethod.STANDARD, state.calculation.asrMethod)
        assertEquals(HighLatitudeRule.SEVENTH_OF_THE_NIGHT, state.calculation.highLatitudeRule)
        assertTrue(!state.calculation.hasAdjustments)
        assertEquals(TimeFormatPreference.SYSTEM, state.calculation.timeFormat)
    }

    @Test
    fun `discloses a manual adjustment next to the prayer it moved`() {
        val adjustments = mapOf(PrayerName.FAJR to 3, PrayerName.ISHA to -2)
        val state = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(
                completeSchedule(adjustments = adjustments),
                origin,
            ),
            settings = PrayerSettings.DEFAULT.copy(adjustmentsMinutes = adjustments),
        )

        assertTrue(state.calculation.hasAdjustments)
        assertEquals(3, state.prayerRows.first { it.prayer == PrayerName.FAJR }.adjustmentMinutes)
        assertEquals(-2, state.prayerRows.first { it.prayer == PrayerName.ISHA }.adjustmentMinutes)
        assertEquals(0, state.prayerRows.first { it.prayer == PrayerName.DHUHR }.adjustmentMinutes)
    }

    @Test
    fun `respects the user's time format preference`() {
        val schedule = completeSchedule()
        val twentyFour = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(schedule, origin),
            settings = PrayerSettings.DEFAULT.copy(timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR),
        )
        val twelve = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(schedule, origin),
            settings = PrayerSettings.DEFAULT.copy(timeFormat = TimeFormatPreference.TWELVE_HOUR),
        )

        assertEquals("15:58", twentyFour.nextPrayer.time)
        assertNotEquals(twentyFour.nextPrayer.time, twelve.nextPrayer.time)
        assertTrue(
            "expected an hour and minute reading, got ${twelve.nextPrayer.time}",
            twelve.nextPrayer.time!!.contains("58"),
        )
        // The countdown itself is a duration, so the format preference must not change it.
        assertEquals(twentyFour.nextPrayer.countdown, twelve.nextPrayer.countdown)
    }

    @Test
    fun `renders times in the prayer location's zone`() {
        // A fixed, locale-independent format is used deliberately: this test is about the zone,
        // not about how a particular locale renders a clock.
        val state = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(completeSchedule(), origin),
            settings = PrayerSettings.DEFAULT.copy(timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR),
        )

        // 04:39 in Dhaka is 22:39 the previous day in UTC; the label must be the local one.
        assertEquals("04:39", state.prayerRows.first { it.prayer == PrayerName.FAJR }.timeLabel)
        assertEquals("05:54", state.solarRows.first { it.event == SolarEvent.SUNRISE }.timeLabel)
    }

    @Test
    fun `reports provenance and offline readiness of calculated data`() {
        val state = ready(
            location = location,
            scheduleState = PrayerScheduleState.Available(completeSchedule(), origin),
        )

        assertEquals(PrayerTimeSource.LOCAL_CALCULATION, state.provenance.source)
        assertEquals("local-adhan-kotlin", state.provenance.providerId)
        assertTrue(state.provenance.offlineReady)
        assertNotNull(state.provenance.observedAtLabel)
    }
}
