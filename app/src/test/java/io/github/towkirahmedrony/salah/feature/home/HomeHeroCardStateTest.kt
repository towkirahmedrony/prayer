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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Home hero card's two newer readings, both resolved in [HomeUiStateFactory]:
 *
 *  - the **next** Sehri end / Iftar pair (today's, then tomorrow's after Iftar) with its next event and
 *    countdown;
 *  - the open prayer period's progress and the `<1m` countdown wording.
 *
 * Times are never calculated here: the fixtures are explicit schedules, and every label is asserted
 * against the time the schedule carries.
 */
class HomeHeroCardStateTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)
    private val tomorrow: LocalDate = date.plusDays(1)
    private val generatedAt: Instant = Instant.parse("2026-10-11T00:00:00Z")

    private val factory = HomeUiStateFactory(
        nextPrayerResolver = NextPrayerResolver(),
        currentPrayerResolver = CurrentPrayerPeriodResolver(),
        prohibitedStatusResolver = ProhibitedPeriodStatusResolver(),
    )

    private val location: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    private val elsewhere: LocationInfo = checkNotNull(
        LocationInfo.create("Chattogram", 22.3569, 91.7832, "Asia/Dhaka"),
    )

    private val origin = DataSourceInfo(
        source = PrayerTimeSource.LOCAL_CALCULATION,
        providerId = "local-adhan-kotlin",
        observedAt = generatedAt,
        isAvailableOffline = true,
    )

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        date.atTime(hour, minute, second).atZone(zone)

    private fun tomorrowAt(hour: Int, minute: Int): ZonedDateTime =
        tomorrow.atTime(hour, minute).atZone(zone)

    /** Today's timetable: Fajr 04:39, Dhuhr 11:46, Asr 15:58, Maghrib 17:36, Isha 18:51. */
    private val today = PrayerSchedule(
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

    /** Tomorrow's timetable, one minute apart from today's where it matters: Fajr 04:40, Maghrib 17:35. */
    private fun tomorrowSchedule(
        targetDate: LocalDate = tomorrow,
        place: LocationInfo = location,
    ): PrayerSchedule = PrayerSchedule(
        date = targetDate,
        location = place,
        profile = PrayerSettings.DEFAULT.toProfile(),
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, targetDate.atTime(4, 40).atZone(zone)),
            PrayerTimeEntry(PrayerName.DHUHR, targetDate.atTime(11, 46).atZone(zone)),
            PrayerTimeEntry(PrayerName.ASR, targetDate.atTime(15, 57).atZone(zone)),
            PrayerTimeEntry(PrayerName.MAGHRIB, targetDate.atTime(17, 35).atZone(zone)),
            PrayerTimeEntry(PrayerName.ISHA, targetDate.atTime(18, 50).atZone(zone)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, targetDate.atTime(5, 55).atZone(zone)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, targetDate.atTime(11, 45).atZone(zone)),
            SolarEventEntry(SolarEvent.SUNSET, targetDate.atTime(17, 35).atZone(zone)),
        ),
        generatedAt = generatedAt,
    )

    private fun ready(
        now: ZonedDateTime,
        nextDay: PrayerSchedule? = null,
        schedule: PrayerSchedule = today,
    ): HomeUiState.Ready {
        val state = factory.create(
            calendar = null,
            location = location,
            settings = PrayerSettings.DEFAULT.copy(timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR),
            scheduleState = PrayerScheduleState.Available(schedule, origin),
            prohibitedState = ProhibitedPeriodsState.NotAvailable(reason = UnavailabilityReason.RULES_NOT_VERIFIED),
            now = now,
            nextDaySchedule = nextDay,
        )
        assertTrue("expected Ready but was $state", state is HomeUiState.Ready)
        return state as HomeUiState.Ready
    }

    // ---- next fasting event ---------------------------------------------------------------------

    @Test
    fun `at 03_30 today's Sehri end is the next event`() {
        val fasting = ready(at(3, 30), nextDay = tomorrowSchedule()).fasting

        assertFalse(fasting.isTomorrow)
        assertEquals(FastingEvent.SEHRI_END, fasting.nextEvent)
        assertEquals("04:39", fasting.sehriEndLabel)
        assertEquals("17:36", fasting.iftarLabel)
        assertFalse(fasting.sehriPassed)
        assertFalse(fasting.iftarPassed)
        assertEquals("1h 9m", fasting.countdown)
    }

    @Test
    fun `at 15_00 Sehri is dimmed and Iftar is the next event`() {
        val fasting = ready(at(15, 0), nextDay = tomorrowSchedule()).fasting

        assertFalse(fasting.isTomorrow)
        assertEquals(FastingEvent.IFTAR, fasting.nextEvent)
        assertEquals("04:39", fasting.sehriEndLabel)
        assertEquals("17:36", fasting.iftarLabel)
        assertTrue(fasting.sehriPassed)
        assertFalse(fasting.iftarPassed)
        assertEquals("2h 36m", fasting.countdown)
    }

    @Test
    fun `at 19_30 the card rolls over to tomorrow's Sehri and Iftar`() {
        val fasting = ready(at(19, 30), nextDay = tomorrowSchedule()).fasting

        assertTrue(fasting.isTomorrow)
        assertEquals(FastingEvent.SEHRI_END, fasting.nextEvent)
        assertEquals("04:40", fasting.sehriEndLabel)
        assertEquals("17:35", fasting.iftarLabel)
        assertFalse(fasting.sehriPassed)
        assertFalse(fasting.iftarPassed)
        assertEquals("9h 10m", fasting.countdown)
    }

    @Test
    fun `after Iftar without tomorrow's schedule the card falls back to today's, dimmed`() {
        val fasting = ready(at(19, 30), nextDay = null).fasting

        assertFalse(fasting.isTomorrow)
        assertEquals("04:39", fasting.sehriEndLabel)
        assertEquals("17:36", fasting.iftarLabel)
        assertTrue(fasting.sehriPassed)
        assertTrue(fasting.iftarPassed)
        assertNull(fasting.nextEvent)
        assertNull(fasting.countdown)
    }

    @Test
    fun `a next-day schedule for the wrong date is not used as tomorrow`() {
        val fasting = ready(at(19, 30), nextDay = tomorrowSchedule(targetDate = date.plusDays(2))).fasting

        assertFalse(fasting.isTomorrow)
        assertEquals("17:36", fasting.iftarLabel)
        assertNull(fasting.nextEvent)
    }

    @Test
    fun `a next-day schedule for another place is not used as tomorrow`() {
        val fasting = ready(at(19, 30), nextDay = tomorrowSchedule(place = elsewhere)).fasting

        assertFalse(fasting.isTomorrow)
        assertEquals("17:36", fasting.iftarLabel)
        assertNull(fasting.nextEvent)
    }

    @Test
    fun `a next-day schedule without Maghrib is not used as tomorrow`() {
        val partial = tomorrowSchedule().let { schedule ->
            schedule.copy(prayers = schedule.prayers.filterNot { it.prayer == PrayerName.MAGHRIB })
        }

        val fasting = ready(at(19, 30), nextDay = partial).fasting

        assertFalse(fasting.isTomorrow)
        assertEquals("17:36", fasting.iftarLabel)
    }

    @Test
    fun `exactly at Sehri end it has passed and Iftar is next`() {
        val fasting = ready(at(4, 39), nextDay = tomorrowSchedule()).fasting

        assertFalse(fasting.isTomorrow)
        assertTrue(fasting.sehriPassed)
        assertEquals(FastingEvent.IFTAR, fasting.nextEvent)
        assertEquals("12h 57m", fasting.countdown)
    }

    @Test
    fun `exactly at Iftar the card is already on tomorrow`() {
        val fasting = ready(at(17, 36), nextDay = tomorrowSchedule()).fasting

        assertTrue(fasting.isTomorrow)
        assertEquals(FastingEvent.SEHRI_END, fasting.nextEvent)
        assertEquals("04:40", fasting.sehriEndLabel)
        assertEquals("17:35", fasting.iftarLabel)
        assertEquals("11h 4m", fasting.countdown)
    }

    @Test
    fun `one second before Sehri end the countdown reads under a minute`() {
        val fasting = ready(at(4, 38, 59)).fasting

        assertEquals(FastingEvent.SEHRI_END, fasting.nextEvent)
        assertEquals("<1m", fasting.countdown)
    }

    @Test
    fun `there is no fasting reading without a schedule`() {
        val state = factory.create(
            calendar = null,
            location = location,
            settings = PrayerSettings.DEFAULT,
            scheduleState = PrayerScheduleState.Unavailable(
                reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                origin = origin,
            ),
            prohibitedState = ProhibitedPeriodsState.NotAvailable(reason = UnavailabilityReason.RULES_NOT_VERIFIED),
            now = at(19, 30),
            nextDaySchedule = tomorrowSchedule(),
        ) as HomeUiState.Ready

        assertEquals(FastingTimesSection.Unavailable, state.fasting)
    }

    // ---- period progress ------------------------------------------------------------------------

    @Test
    fun `progress is zero at the moment a period opens`() {
        val hero = ready(at(11, 46)).currentPrayer

        assertTrue(hero.isActive)
        assertEquals(PrayerName.DHUHR, hero.prayer)
        assertEquals(0f, hero.periodProgress!!, 0.0001f)
    }

    @Test
    fun `progress is one half in the middle of a period`() {
        // Maghrib runs 17:36 to 18:51, so 18:13:30 is exactly halfway.
        val hero = ready(at(18, 13, 30)).currentPrayer

        assertEquals(PrayerName.MAGHRIB, hero.prayer)
        assertEquals(0.5f, hero.periodProgress!!, 0.0001f)
    }

    @Test
    fun `progress approaches one just before a period closes`() {
        // The period is half-open, so at exactly 18:51 Isha is the open one; one second earlier Maghrib
        // is open with 4499 of 4500 seconds elapsed.
        val hero = ready(at(18, 50, 59)).currentPrayer

        assertEquals(PrayerName.MAGHRIB, hero.prayer)
        val progress = hero.periodProgress
        assertNotNull(progress)
        assertTrue("progress was $progress", progress!! > 0.99f && progress < 1f)
    }

    @Test
    fun `progress is always inside the zero to one range`() {
        listOf(at(4, 39), at(9, 0), at(11, 46), at(15, 0), at(17, 36), at(18, 50, 59)).forEach { now ->
            val progress = ready(now, nextDay = tomorrowSchedule()).currentPrayer.periodProgress
            if (progress != null) {
                assertTrue("progress $progress at $now", progress in 0f..1f)
            }
        }
    }

    @Test
    fun `there is no progress while no period is open`() {
        // Between sunrise (05:54) and Dhuhr (11:46) no period is open.
        val hero = ready(at(8, 0)).currentPrayer

        assertFalse(hero.isActive)
        assertNull(hero.periodProgress)
    }

    @Test
    fun `there is no progress for the Isha period that began before the schedule`() {
        val hero = ready(at(2, 0)).currentPrayer

        assertTrue(hero.isActive)
        assertEquals(PrayerName.ISHA, hero.prayer)
        assertNull(hero.startTime)
        assertNull(hero.periodProgress)
    }

    @Test
    fun `there is no progress without a schedule`() {
        val state = factory.create(
            calendar = null,
            location = location,
            settings = PrayerSettings.DEFAULT,
            scheduleState = PrayerScheduleState.Unavailable(
                reason = UnavailabilityReason.NO_LOCATION_CONFIGURED,
                origin = origin,
            ),
            prohibitedState = ProhibitedPeriodsState.NotAvailable(reason = UnavailabilityReason.RULES_NOT_VERIFIED),
            now = at(15, 0),
        ) as HomeUiState.Ready

        assertNull(state.currentPrayer.periodProgress)
    }

    // ---- "<1m" countdown ------------------------------------------------------------------------

    @Test
    fun `the hero countdown reads under a minute instead of 0m`() {
        // Asr opens at 15:58; at 15:57:30 the Dhuhr period has 30 seconds left.
        val state = ready(at(15, 57, 30))

        assertEquals(PrayerName.DHUHR, state.currentPrayer.prayer)
        assertEquals("<1m", state.currentPrayer.countdownCompact)
        assertEquals("<1m", state.nextPrayer.countdownCompact)
    }

    @Test
    fun `the full-precision countdown still ticks in seconds`() {
        val state = ready(at(15, 57, 30))

        assertEquals("00:30", state.currentPrayer.countdown)
        assertEquals("00:30", state.nextPrayer.countdown)
    }

    @Test
    fun `day and night lengths keep the plain compact wording`() {
        val state = ready(at(15, 0))

        // Sunrise 05:54 to sunset 17:36 is 11h 42m, a measurement rather than a countdown.
        assertEquals("11h 42m", state.daylight)
    }
}
