package io.github.towkirahmedrony.salah.data.prohibited

import io.github.towkirahmedrony.salah.core.domain.prohibited.ConfiguredProhibitedRuleSet
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodCalculator
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedRuleBook
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedRuleSet
import io.github.towkirahmedrony.salah.core.domain.prohibited.RuleVerificationStatus
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Verifies what Salah actually ships: an empty rule book, and therefore an honest "not
 * configured" state rather than invented intervals.
 *
 * The second half shows the wiring is real by supplying a reviewed rule set and asserting that
 * windows then appear — which is the whole change a later phase has to make.
 */
class RuleBookProhibitedPeriodsRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private fun at(hour: Int, minute: Int): ZonedDateTime = date.atTime(hour, minute).atZone(zone)

    private val schedule = PrayerSchedule(
        date = date,
        location = checkNotNull(LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka")),
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
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
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    private fun reviewedRuleSet() = ProhibitedRuleSet(
        id = "reviewed-for-test",
        label = "Reviewed for test",
        sunriseAscentMinutes = 15,
        zenithBeforeMinutes = 5,
        zenithAfterMinutes = 5,
        sunsetSettingMinutes = 15,
        verification = RuleVerificationStatus.REVIEWED,
    )

    private fun repository(ruleBook: ProhibitedRuleBook) =
        RuleBookProhibitedPeriodsRepository(
            ruleBook = ruleBook,
            calculator = ProhibitedPeriodCalculator(),
        )

    @Test
    fun `the shipped rule book is empty and reports the unverified state`() = runTest {
        val book = ProhibitedRuleBook()

        assertFalse(book.isConfigured)
        assertTrue(book.applicable.isEmpty())

        val state = repository(book).observePeriods(schedule).first()

        assertTrue(state is ProhibitedPeriodsState.NotAvailable)
        state as ProhibitedPeriodsState.NotAvailable
        assertEquals(UnavailabilityReason.RULES_NOT_VERIFIED, state.reason)
        assertEquals(book.unconfiguredExplanation, state.detail)
    }

    @Test
    fun `an unreviewed rule set is not applied even when it is present`() = runTest {
        val book = ProhibitedRuleBook(
            listOf(reviewedRuleSet().copy(verification = RuleVerificationStatus.UNREVIEWED)),
        )

        assertFalse(book.isConfigured)

        val state = repository(book).observePeriods(schedule).first()

        assertEquals(
            UnavailabilityReason.RULES_NOT_VERIFIED,
            (state as ProhibitedPeriodsState.NotAvailable).reason,
        )
    }

    @Test
    fun `a reviewed rule set produces windows from the schedule`() = runTest {
        val book = ProhibitedRuleBook(listOf(reviewedRuleSet()))
        assertTrue(book.isConfigured)

        val state = repository(book).observePeriods(schedule).first()

        assertTrue(state is ProhibitedPeriodsState.Available)
        assertEquals(3, (state as ProhibitedPeriodsState.Available).periods.size)
    }

    @Test
    fun `the rule set the application ships with produces windows and discloses itself`() = runTest {
        val book = ConfiguredProhibitedRuleSet.ruleBook()
        assertTrue(book.isConfigured)

        val state = repository(book).observePeriods(schedule).first()

        assertTrue(state is ProhibitedPeriodsState.Available)
        state as ProhibitedPeriodsState.Available
        assertEquals(3, state.periods.size)
        // The convention travels with the windows so the screen can name the policy in force rather
        // than implying the boundaries are confirmed.
        assertEquals(ConfiguredProhibitedRuleSet.ID, state.convention.ruleSetId)
        assertFalse(state.convention.isReviewed)
        assertFalse(
            "no reference for these minute values has been verified in this repository",
            state.convention.hasVerifiedSources,
        )
        assertTrue(state.periods.all { it.ruleSetId == ConfiguredProhibitedRuleSet.ID })
        assertTrue(state.periods.all { it.explanation.isNotBlank() })
    }

    @Test
    fun `the shipped rule set is applied as a configured policy rather than a reviewed one`() {
        val shipped = ConfiguredProhibitedRuleSet.DEFAULT

        assertEquals(RuleVerificationStatus.CONFIGURED, shipped.verification)
        assertTrue(shipped.isApplicable)
        assertFalse(shipped.isReviewed)
    }

    @Test
    fun `a reviewed rule set takes precedence over the configured one`() = runTest {
        val book = ProhibitedRuleBook(
            listOf(reviewedRuleSet(), ConfiguredProhibitedRuleSet.DEFAULT),
        )

        val state = repository(book).observePeriods(schedule).first()
            as ProhibitedPeriodsState.Available

        assertEquals("reviewed-for-test", state.convention.ruleSetId)
        assertTrue(state.convention.isReviewed)
    }

    @Test
    fun `a schedule missing an anchor is unavailable rather than a shorter list of windows`() = runTest {
        // The calculator refuses when it cannot anchor a window; returning the two it could
        // calculate would silently under-report a prohibition.
        val withoutTransit = schedule.copy(
            solarEvents = schedule.solarEvents.filterNot {
                it.event == SolarEvent.SOLAR_NOON
            },
        )

        val state = repository(ConfiguredProhibitedRuleSet.ruleBook())
            .observePeriods(withoutTransit)
            .first()

        assertTrue(state is ProhibitedPeriodsState.NotAvailable)
        assertEquals(
            UnavailabilityReason.UNKNOWN,
            (state as ProhibitedPeriodsState.NotAvailable).reason,
        )
    }

    @Test
    fun `an incomplete schedule is unavailable`() = runTest {
        val partial = schedule.copy(prayers = schedule.prayers.take(2))

        val state = repository(ConfiguredProhibitedRuleSet.ruleBook())
            .observePeriods(partial)
            .first()

        assertTrue(state is ProhibitedPeriodsState.NotAvailable)
    }

    @Test
    fun `a reviewed rule set still needs a schedule to anchor to`() = runTest {
        val state = repository(ProhibitedRuleBook(listOf(reviewedRuleSet())))
            .observePeriods(null)
            .first()

        assertTrue(state is ProhibitedPeriodsState.NotAvailable)
        assertEquals(
            UnavailabilityReason.UNKNOWN,
            (state as ProhibitedPeriodsState.NotAvailable).reason,
        )
    }
}
