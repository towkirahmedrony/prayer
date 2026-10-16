package io.github.towkirahmedrony.salah.core.domain.night

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.NightPeriods
import io.github.towkirahmedrony.salah.core.model.NightPeriodsState
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Locks in the night interval and its boundaries.
 *
 * The reference timetable is the engine's own output for Dhaka on 11 October 2026: Maghrib 17:36 on
 * the day and Fajr 04:39 the following day. The night interval is therefore 17:36 -> 04:39 next day,
 * a duration of 11h03m; Islamic midnight is its exact midpoint (23:07:30) and the last third begins
 * at 00:58. These pin the arithmetic; the definitions themselves are documented in
 * `docs/SOLAR_AND_NIGHT_PERIODS.md`, and the last-third anchoring of Tahajjud is a stated convention,
 * not a ruling.
 */
class NightPeriodCalculatorTest {

    private val calculator = NightPeriodCalculator()
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val dhaka: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    private fun at(day: LocalDate, hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        day.atTime(hour, minute, second).atZone(zone)

    private fun schedule(
        targetDate: LocalDate,
        fajr: ZonedDateTime = at(targetDate, 4, 39),
        maghrib: ZonedDateTime = at(targetDate, 17, 36),
    ): PrayerSchedule = PrayerSchedule(
        date = targetDate,
        location = dhaka,
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, fajr),
            PrayerTimeEntry(PrayerName.DHUHR, at(targetDate, 11, 46)),
            PrayerTimeEntry(PrayerName.ASR, at(targetDate, 15, 58)),
            PrayerTimeEntry(PrayerName.MAGHRIB, maghrib),
            PrayerTimeEntry(PrayerName.ISHA, at(targetDate, 18, 51)),
        ),
        solarEvents = listOf(
            SolarEventEntry(SolarEvent.SUNRISE, at(targetDate, 5, 54)),
            SolarEventEntry(SolarEvent.SOLAR_NOON, at(targetDate, 11, 45)),
            SolarEventEntry(SolarEvent.SUNSET, at(targetDate, 17, 36)),
        ),
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    private val today = schedule(date)
    private val tomorrow = schedule(date.plusDays(1))

    private fun available(
        from: PrayerSchedule? = today,
        next: PrayerSchedule? = tomorrow,
    ): NightPeriods {
        val state = calculator.calculate(from, next)
        assertTrue("expected Available but was $state", state is NightPeriodsState.Available)
        return (state as NightPeriodsState.Available).periods
    }

    // ------------------------------------------------------------------------------ the interval

    @Test
    fun `the interval runs from effective Maghrib to the following effective Fajr`() {
        val periods = available()

        assertEquals(at(date, 17, 36), periods.startsAt)
        assertEquals(at(date.plusDays(1), 4, 39), periods.endsAt)
        assertEquals(Duration.ofHours(11).plusMinutes(3), periods.duration)
    }

    @Test
    fun `the interval crosses civil midnight and its ends lie on different local dates`() {
        val periods = available()

        assertEquals(date, periods.startsAt.toLocalDate())
        assertEquals(date.plusDays(1), periods.endsAt.toLocalDate())
        // Islamic midnight here is 23:07, before civil midnight, so it is still on the opening date;
        // the last third begins at 00:58, on the following date. The interval's two ends therefore lie
        // on different dates, which is what makes the crossing of civil midnight relevant.
        assertEquals(date, periods.islamicMidnight.toLocalDate())
        assertEquals(date.plusDays(1), periods.lastThirdStartsAt.toLocalDate())
        assertTrue(periods.startsAt.toLocalDate() != periods.endsAt.toLocalDate())
    }

    @Test
    fun `islamic midnight is the exact midpoint of the interval`() {
        val periods = available()

        // 17:36 + (11h03m / 2) = 23:07:30.
        assertEquals(at(date, 23, 7, 30), periods.islamicMidnight)
        assertEquals(
            Duration.between(periods.startsAt, periods.islamicMidnight),
            Duration.between(periods.islamicMidnight, periods.endsAt),
        )
    }

    @Test
    fun `the last third begins two thirds of the way through the interval`() {
        val periods = available()

        // 04:39 - (11h03m / 3) = 00:58.
        assertEquals(at(date.plusDays(1), 0, 58), periods.lastThirdStartsAt)
        val lastThird = Duration.between(periods.lastThirdStartsAt, periods.endsAt)
        assertEquals(periods.duration.dividedBy(3L), lastThird)
    }

    @Test
    fun `Tahajjud begins at the last third under the shipped convention`() {
        val state = calculator.calculate(today, tomorrow) as NightPeriodsState.Available

        assertEquals(ConfiguredNightConvention.ID, state.convention.id)
        assertTrue(state.convention.tahajjudAnchoredToLastThird)
        assertEquals(state.periods.lastThirdStartsAt, state.periods.tahajjudStartsAt)
    }

    @Test
    fun `the alternative convention anchors Tahajjud to the midpoint instead`() {
        val alternative = NightPeriodCalculator(
            convention = ConfiguredNightConvention.DEFAULT.copy(tahajjudAnchoredToLastThird = false),
        )
        val state = alternative.calculate(today, tomorrow) as NightPeriodsState.Available

        assertEquals(state.periods.islamicMidnight, state.periods.tahajjudStartsAt)
        assertTrue(state.periods.tahajjudStartsAt.isBefore(state.periods.lastThirdStartsAt))
    }

    // ---------------------------------------------------------------------------------- the phases

    @Test
    fun `each phase opens and closes at the documented boundary`() {
        val periods = available()

        assertEquals(NightPhase.EARLY_NIGHT, calculator.phaseAt(periods, at(date, 20, 0)))
        // The exact midpoint already belongs to the second half ([start, end)).
        assertEquals(NightPhase.LATE_NIGHT, calculator.phaseAt(periods, at(date, 23, 7, 30)))
        assertEquals(NightPhase.LATE_NIGHT, calculator.phaseAt(periods, at(date.plusDays(1), 0, 30)))
        // The exact start of the last third already belongs to it.
        assertEquals(NightPhase.LAST_THIRD, calculator.phaseAt(periods, at(date.plusDays(1), 0, 58)))
        assertEquals(NightPhase.LAST_THIRD, calculator.phaseAt(periods, at(date.plusDays(1), 4, 0)))
    }

    @Test
    fun `a moment outside the interval has no phase`() {
        val periods = available()

        assertNull("before Maghrib is not the night", calculator.phaseAt(periods, at(date, 17, 35)))
        assertNull("at Fajr the interval has closed", calculator.phaseAt(periods, at(date.plusDays(1), 4, 39)))
        assertNull("mid-morning is not the night", calculator.phaseAt(periods, at(date.plusDays(1), 10, 0)))
    }

    @Test
    fun `phases are classified by absolute instant across time zones`() {
        val periods = available()

        // 23:00 in Dhaka is 17:00 UTC: the same absolute instant, so the same phase.
        val utcMoment = date.atTime(17, 0).atZone(ZoneId.of("UTC"))
        assertEquals(NightPhase.EARLY_NIGHT, calculator.phaseAt(periods, utcMoment))
    }

    // ------------------------------------------------------------------------------ the countdown

    @Test
    fun `the next boundary is the nearest one still ahead`() {
        val periods = available()

        val early = calculator.upcomingBoundary(periods, at(date, 20, 0))!!
        assertEquals(NightBoundary.ISLAMIC_MIDNIGHT, early.boundary)
        assertEquals(Duration.ofHours(3).plusMinutes(7).plusSeconds(30), early.remaining)

        val afterMidnight = calculator.upcomingBoundary(periods, at(date, 23, 10))!!
        assertEquals(NightBoundary.LAST_THIRD_STARTS, afterMidnight.boundary)
        assertEquals(Duration.ofHours(1).plusMinutes(48), afterMidnight.remaining)

        val lastThird = calculator.upcomingBoundary(periods, at(date.plusDays(1), 1, 0))!!
        assertEquals(NightBoundary.FAJR, lastThird.boundary)
        assertEquals(Duration.ofHours(3).plusMinutes(39), lastThird.remaining)
    }

    @Test
    fun `a moment exactly at a boundary targets the following one`() {
        val periods = available()

        assertEquals(
            NightBoundary.LAST_THIRD_STARTS,
            calculator.upcomingBoundary(periods, at(date, 23, 7, 30))!!.boundary,
        )
        // At the last third, the next boundary is Fajr.
        assertEquals(
            NightBoundary.FAJR,
            calculator.upcomingBoundary(periods, at(date.plusDays(1), 0, 58))!!.boundary,
        )
    }

    @Test
    fun `at or after Fajr there is no upcoming boundary`() {
        val periods = available()

        assertNull(calculator.upcomingBoundary(periods, at(date.plusDays(1), 4, 39)))
        assertNull(calculator.upcomingBoundary(periods, at(date.plusDays(1), 12, 0)))
    }

    // ---------------------------------------------------------------------- missing and invalid input

    @Test
    fun `a missing following-day Fajr makes the interval unavailable`() {
        val state = calculator.calculate(today, null)

        assertTrue(state is NightPeriodsState.NotAvailable)
        assertEquals(UnavailabilityReason.UNKNOWN, (state as NightPeriodsState.NotAvailable).reason)
    }

    @Test
    fun `a missing schedule makes the interval unavailable`() {
        val state = calculator.calculate(null, tomorrow)

        assertTrue(state is NightPeriodsState.NotAvailable)
        assertEquals(UnavailabilityReason.UNKNOWN, (state as NightPeriodsState.NotAvailable).reason)
    }

    @Test
    fun `a schedule without Maghrib makes the interval unavailable`() {
        val maghribLess = PrayerSchedule(
            date = date,
            location = dhaka,
            profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
            prayers = listOf(PrayerTimeEntry(PrayerName.FAJR, at(date, 4, 39))),
            solarEvents = emptyList(),
            generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
        )

        val state = calculator.calculate(maghribLess, tomorrow)

        assertTrue(state is NightPeriodsState.NotAvailable)
    }

    @Test
    fun `a following Fajr that is not after Maghrib is refused, not silently reordered`() {
        // The schedule handed in as "the following day" is really today's: its Fajr (04:39) is before
        // this day's Maghrib (17:36), so the interval would be negative and is refused rather than
        // reordered into a valid-looking one.
        val state = calculator.calculate(today, today)

        assertTrue(state is NightPeriodsState.NotAvailable)
    }

    @Test
    fun `the instant-based entry point rejects a non-positive interval`() {
        assertThrows(IllegalArgumentException::class.java) {
            calculator.periods(at(date, 17, 36), at(date, 17, 36))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculator.periods(at(date, 17, 36), at(date, 10, 0))
        }
    }

    @Test
    fun `a different day produces a different interval, never a reused one`() {
        val first = available()
        val second = available(from = tomorrow, next = schedule(date.plusDays(2)))

        assertEquals(date, first.startsAt.toLocalDate())
        assertEquals(date.plusDays(1), second.startsAt.toLocalDate())
        assertTrue(second.startsAt.isAfter(first.startsAt))
    }
}
