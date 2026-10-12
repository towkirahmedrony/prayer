package io.github.towkirahmedrony.salah.core.domain.prohibited

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriod
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Locks in the boundaries and the statuses of the prohibited intervals Salah applies.
 *
 * The intervals are produced by the real calculator from the rule set the application ships, so
 * these tests exercise the production convention rather than a synthetic one. The timetable is the
 * one the engine produces for Dhaka on this date: Fajr 04:39, sunrise 05:54, Dhuhr 11:46 (solar
 * transit 11:45), Asr 15:58, Maghrib 17:36, Isha 18:51.
 *
 * Nothing here claims the minutes are universally agreed; `docs/FORBIDDEN_PRAYER_TIMES.md` records
 * that they are the application's policy, and the assertions below are what pins that policy down.
 */
class ProhibitedPeriodStatusResolverTest {

    private val calculator = ProhibitedPeriodCalculator()
    private val resolver = ProhibitedPeriodStatusResolver()
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val ruleSet: ProhibitedRuleSet = ConfiguredProhibitedRuleSet.DEFAULT

    private val dhaka: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        date.atTime(hour, minute, second).atZone(zone)

    private fun prayerSchedule(
        location: LocationInfo = dhaka,
        sunrise: ZonedDateTime? = at(5, 54),
        solarNoon: ZonedDateTime? = at(11, 45),
        sunset: ZonedDateTime? = at(17, 36),
        dhuhr: ZonedDateTime = at(11, 46),
    ): PrayerSchedule = PrayerSchedule(
        date = date,
        location = location,
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers = listOf(
            PrayerTimeEntry(PrayerName.FAJR, at(4, 39)),
            PrayerTimeEntry(PrayerName.DHUHR, dhuhr),
            PrayerTimeEntry(PrayerName.ASR, at(15, 58)),
            PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 36)),
            PrayerTimeEntry(PrayerName.ISHA, at(18, 51)),
        ),
        solarEvents = buildList {
            sunrise?.let { add(SolarEventEntry(SolarEvent.SUNRISE, it)) }
            solarNoon?.let { add(SolarEventEntry(SolarEvent.SOLAR_NOON, it)) }
            sunset?.let { add(SolarEventEntry(SolarEvent.SUNSET, it)) }
        },
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    private fun windows(from: PrayerSchedule = prayerSchedule()): List<ProhibitedPeriod> =
        calculator.calculate(from, ruleSet)

    private fun statusOf(
        kind: ProhibitedWindowKind,
        moment: ZonedDateTime,
        from: PrayerSchedule = prayerSchedule(),
    ): ProhibitedPeriodStatus =
        resolver.classify(windows(from), moment).first { it.period.kind == kind }

    private fun durationOf(period: ProhibitedPeriod): Duration =
        Duration.between(period.startsAt, period.endsAt)

    // ------------------------------------------------------------------- the configured convention

    @Test
    fun `the shipped rule set is applicable and produces the three intervals in order`() {
        assertTrue(ruleSet.isApplicable)
        assertFalse(
            "the shipped boundaries have not been confirmed by a reviewer",
            ruleSet.isReviewed,
        )

        assertEquals(
            listOf(
                ProhibitedWindowKind.SUNRISE_ASCENT,
                ProhibitedWindowKind.ZENITH_DECLINE,
                ProhibitedWindowKind.SUNSET_SETTING,
            ),
            windows().map { it.kind },
        )
    }

    @Test
    fun `each interval keeps its own configured boundaries`() {
        val sunriseWindow = windows()[0]
        val zenithWindow = windows()[1]
        val sunsetWindow = windows()[2]

        // Sunrise ascent: sunrise until sunrise + 15 minutes.
        assertEquals(at(5, 54), sunriseWindow.startsAt)
        assertEquals(at(6, 9), sunriseWindow.endsAt)
        assertEquals(Duration.ofMinutes(15), durationOf(sunriseWindow))

        // Zenith decline: five minutes before the transit until ten minutes after it.
        assertEquals(at(11, 40), zenithWindow.startsAt)
        assertEquals(at(11, 55), zenithWindow.endsAt)
        assertEquals(Duration.ofMinutes(15), durationOf(zenithWindow))

        // Sunset setting: fifteen minutes before sunset, ending at sunset.
        assertEquals(at(17, 21), sunsetWindow.startsAt)
        assertEquals(at(17, 36), sunsetWindow.endsAt)
        assertEquals(Duration.ofMinutes(15), durationOf(sunsetWindow))
    }

    @Test
    fun `each duration is its own parameter and moving one does not move the others`() {
        val changed = ruleSet.copy(sunriseAscentMinutes = 3, sunsetSettingMinutes = 7)
        val changedWindows = calculator.calculate(prayerSchedule(), changed)

        assertEquals(at(5, 57), changedWindows[0].endsAt)
        assertEquals(at(11, 55), changedWindows[1].endsAt)
        assertEquals(at(17, 29), changedWindows[2].startsAt)
    }

    @Test
    fun `the anchor of each interval is a solar event, never a prayer`() {
        assertEquals(SolarEvent.SUNRISE, ProhibitedWindowKind.SUNRISE_ASCENT.anchorEvent)
        assertEquals(SolarEvent.SOLAR_NOON, ProhibitedWindowKind.ZENITH_DECLINE.anchorEvent)
        assertEquals(SolarEvent.SUNSET, ProhibitedWindowKind.SUNSET_SETTING.anchorEvent)
    }

    @Test
    fun `solar noon is a different instant from the displayed Dhuhr prayer time`() {
        val today = prayerSchedule()
        val transit = today.timeOf(SolarEvent.SOLAR_NOON)
        val dhuhr = today.timeOf(PrayerName.DHUHR)

        assertNotNull(transit)
        assertNotNull(dhuhr)
        assertFalse("the fixture must keep the two distinct", transit == dhuhr)

        // The zenith interval is anchored to the transit, not to the prayer time: with a Dhuhr the
        // method has shifted, the interval must not move with it.
        val shifted = prayerSchedule(dhuhr = at(12, 10))
        val shiftedZenith = windows(shifted)[1]

        assertEquals(transit, shiftedZenith.startsAt.plusMinutes(5))
        assertEquals(at(11, 40), shiftedZenith.startsAt)
    }

    @Test
    fun `an interval is not the whole prayer period around its event`() {
        val today = prayerSchedule()
        val sunriseWindow = windows(today)[0]
        val fajr = checkNotNull(today.timeOf(PrayerName.FAJR))
        val dhuhr = checkNotNull(today.timeOf(PrayerName.DHUHR))

        // The Fajr period runs 04:39 to sunrise; the prohibited interval is the fifteen minutes
        // after sunrise. Reporting the whole period as prohibited would be wrong.
        assertTrue(Duration.between(fajr, dhuhr) > durationOf(sunriseWindow))
        assertEquals(at(5, 54), sunriseWindow.startsAt)
        assertTrue(sunriseWindow.startsAt.isAfter(fajr))

        // The same holds around sunset: the interval is the last fifteen minutes before sunset, not
        // the whole Maghrib to Isha period.
        val sunsetWindow = windows(today)[2]
        assertTrue(durationOf(sunsetWindow) < Duration.between(at(17, 36), at(18, 51)))
    }

    // ------------------------------------------------------------------------ exact-time semantics

    @Test
    fun `before an interval it is upcoming and the countdown is to its start`() {
        val status = statusOf(ProhibitedWindowKind.SUNRISE_ASCENT, at(5, 0))

        assertEquals(ProhibitedPeriodPhase.UPCOMING, status.phase)
        assertEquals(Duration.ofMinutes(54), status.startsIn)
        assertNull("an upcoming interval has no closing countdown", status.endsIn)
    }

    @Test
    fun `exactly at the start of an interval it is active`() {
        val status = statusOf(ProhibitedWindowKind.SUNRISE_ASCENT, at(5, 54))

        assertEquals(ProhibitedPeriodPhase.ACTIVE, status.phase)
        assertEquals(Duration.ofMinutes(15), status.endsIn)
        assertNull("an active interval has no opening countdown", status.startsIn)
    }

    @Test
    fun `during an interval the countdown is to its end`() {
        val status = statusOf(ProhibitedWindowKind.SUNRISE_ASCENT, at(6, 0))

        assertEquals(ProhibitedPeriodPhase.ACTIVE, status.phase)
        assertEquals(Duration.ofMinutes(9), status.endsIn)
    }

    @Test
    fun `one second before the end it is still active`() {
        val status = statusOf(ProhibitedWindowKind.SUNRISE_ASCENT, at(6, 8, 59))

        assertEquals(ProhibitedPeriodPhase.ACTIVE, status.phase)
        assertEquals(Duration.ofSeconds(1), status.endsIn)
    }

    @Test
    fun `exactly at the end of an interval it has passed`() {
        val status = statusOf(ProhibitedWindowKind.SUNRISE_ASCENT, at(6, 9))

        assertEquals(ProhibitedPeriodPhase.PASSED, status.phase)
        assertNull(status.startsIn)
        assertNull(status.endsIn)
    }

    @Test
    fun `after an interval it stays passed for the rest of the local date`() {
        val status = statusOf(ProhibitedWindowKind.SUNRISE_ASCENT, at(23, 0))

        assertEquals(ProhibitedPeriodPhase.PASSED, status.phase)
    }

    @Test
    fun `each interval is classified independently of the others`() {
        val statuses = resolver.classify(windows(), at(11, 42))

        assertEquals(
            ProhibitedPeriodPhase.PASSED,
            statuses.first { it.period.kind == ProhibitedWindowKind.SUNRISE_ASCENT }.phase,
        )
        assertEquals(
            ProhibitedPeriodPhase.ACTIVE,
            statuses.first { it.period.kind == ProhibitedWindowKind.ZENITH_DECLINE }.phase,
        )
        assertEquals(
            ProhibitedPeriodPhase.UPCOMING,
            statuses.first { it.period.kind == ProhibitedWindowKind.SUNSET_SETTING }.phase,
        )
    }

    @Test
    fun `the zenith interval covers the transit and the decline after it`() {
        assertEquals(
            ProhibitedPeriodPhase.ACTIVE,
            statusOf(ProhibitedWindowKind.ZENITH_DECLINE, at(11, 40)).phase,
        )
        assertEquals(
            ProhibitedPeriodPhase.ACTIVE,
            statusOf(ProhibitedWindowKind.ZENITH_DECLINE, at(11, 45)).phase,
        )
        assertEquals(
            ProhibitedPeriodPhase.ACTIVE,
            statusOf(ProhibitedWindowKind.ZENITH_DECLINE, at(11, 54, 59)).phase,
        )
        assertEquals(
            ProhibitedPeriodPhase.PASSED,
            statusOf(ProhibitedWindowKind.ZENITH_DECLINE, at(11, 55)).phase,
        )
    }

    @Test
    fun `the sunset interval ends at sunset, not after it`() {
        assertEquals(
            ProhibitedPeriodPhase.UPCOMING,
            statusOf(ProhibitedWindowKind.SUNSET_SETTING, at(17, 20)).phase,
        )
        assertEquals(
            ProhibitedPeriodPhase.ACTIVE,
            statusOf(ProhibitedWindowKind.SUNSET_SETTING, at(17, 30)).phase,
        )
        assertEquals(
            ProhibitedPeriodPhase.PASSED,
            statusOf(ProhibitedWindowKind.SUNSET_SETTING, at(17, 36)).phase,
        )
    }

    // ------------------------------------------------------------- classification over a whole day

    @Test
    fun `no two intervals are ever active at the same moment`() {
        // Every minute of the local day, so an overlapping policy would be caught rather than
        // asserted away.
        val overlapping = (0..1_439).map { minute ->
            val moment = date.atStartOfDay(zone).plusMinutes(minute.toLong())
            val active = resolver.classify(windows(), moment)
                .filter { it.phase == ProhibitedPeriodPhase.ACTIVE }
            moment to active
        }.filter { (_, active) -> active.size > 1 }

        assertTrue("overlapping intervals at $overlapping", overlapping.isEmpty())
    }

    @Test
    fun `every minute of the day is classified, and none is left unclassified`() {
        val wrongCount = (0..1_439).count { minute ->
            val moment = date.atStartOfDay(zone).plusMinutes(minute.toLong())
            resolver.classify(windows(), moment).size != 3
        }

        assertEquals(0, wrongCount)
    }

    @Test
    fun `intervals stay inside one local date and never cross midnight`() {
        windows().forEach { period ->
            assertEquals(date, period.startsAt.toLocalDate())
            assertEquals(date, period.endsAt.toLocalDate())
            assertTrue(period.startsAt.isBefore(period.endsAt))
        }
    }

    @Test
    fun `yesterday's windows do not classify as today's upcoming events`() {
        // A new local date must be recalculated, not reused: the previous day's windows are all in
        // the past when read against today's moments.
        val stale = windows().map { period ->
            period.copy(
                startsAt = period.startsAt.minusDays(1),
                endsAt = period.endsAt.minusDays(1),
            )
        }

        resolver.classify(stale, date.atStartOfDay(zone).plusHours(6)).forEach { status ->
            assertEquals(ProhibitedPeriodPhase.PASSED, status.phase)
        }
        assertEquals(date.minusDays(1), stale.first().startsAt.toLocalDate())
    }

    @Test
    fun `classifying in another zone still reads the interval's own zone`() {
        val status = statusOf(
            ProhibitedWindowKind.SUNRISE_ASCENT,
            date.atTime(0, 0).atZone(ZoneId.of("UTC")),
        )

        // 00:00 UTC is 06:00 in Dhaka: inside the sunrise interval.
        assertEquals(ProhibitedPeriodPhase.ACTIVE, status.phase)
        assertEquals(zone, status.period.startsAt.zone)
    }

    // ------------------------------------------------------------------- different Bangladesh places

    @Test
    fun `a second Bangladesh location produces its own intervals`() {
        val chattogram = checkNotNull(
            LocationInfo.create("Chattogram", 22.3569, 91.7832, "Asia/Dhaka"),
        )
        val chattogramSchedule = PrayerSchedule(
            date = date,
            location = chattogram,
            profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
            prayers = listOf(
                PrayerTimeEntry(PrayerName.FAJR, at(4, 34)),
                PrayerTimeEntry(PrayerName.DHUHR, at(11, 45)),
                PrayerTimeEntry(PrayerName.ASR, at(15, 55)),
                PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 30)),
                PrayerTimeEntry(PrayerName.ISHA, at(18, 45)),
            ),
            solarEvents = listOf(
                SolarEventEntry(SolarEvent.SUNRISE, at(5, 48)),
                SolarEventEntry(SolarEvent.SOLAR_NOON, at(11, 44)),
                SolarEventEntry(SolarEvent.SUNSET, at(17, 30)),
            ),
            generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
        )

        val chattogramWindows = calculator.calculate(chattogramSchedule, ruleSet)

        assertEquals(at(5, 48), chattogramWindows[0].startsAt)
        assertEquals(at(6, 3), chattogramWindows[0].endsAt)
        assertEquals(at(17, 15), chattogramWindows[2].startsAt)
        assertFalse(dhaka.latitude == chattogram.latitude)

        // The status is read against the same absolute instants, so a place with different times
        // gives a different answer for the same moment.
        assertEquals(
            ProhibitedPeriodPhase.ACTIVE,
            resolver.classify(chattogramWindows, at(5, 50))
                .first { it.period.kind == ProhibitedWindowKind.SUNRISE_ASCENT }
                .phase,
        )
    }

    // ------------------------------------------------------------------ missing and invalid input

    @Test
    fun `a schedule without the transit cannot produce the zenith interval`() {
        // The calculator refuses rather than returning a shorter list, which would silently
        // under-report a prohibition; the repository turns that refusal into an unavailable state.
        assertThrows(IllegalArgumentException::class.java) {
            calculator.calculate(prayerSchedule(solarNoon = null), ruleSet)
        }
    }

    @Test
    fun `a schedule without sunrise cannot produce the sunrise interval`() {
        assertThrows(IllegalArgumentException::class.java) {
            calculator.calculate(prayerSchedule(sunrise = null), ruleSet)
        }
    }

    @Test
    fun `an unreviewed rule set is refused by the calculator`() {
        assertThrows(IllegalArgumentException::class.java) {
            calculator.calculate(
                prayerSchedule(),
                ruleSet.copy(verification = RuleVerificationStatus.UNREVIEWED),
            )
        }
    }

    @Test
    fun `an incomplete schedule cannot anchor any interval`() {
        val partial = PrayerSchedule(
            date = date,
            location = dhaka,
            profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
            prayers = listOf(PrayerTimeEntry(PrayerName.FAJR, at(4, 39))),
            solarEvents = listOf(SolarEventEntry(SolarEvent.SUNRISE, at(5, 54))),
            generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            calculator.calculate(partial, ruleSet)
        }
    }
}
