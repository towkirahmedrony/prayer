package io.github.towkirahmedrony.salah.core.domain.prohibited

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.PrayerTimeEntry
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import io.github.towkirahmedrony.salah.core.model.SolarEventEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests the *structure* of the prohibited-window calculation: anchors, ordering, zone, the rule
 * that the zenith window never outlasts Dhuhr, and the refusal to run on an unreviewed rule set.
 *
 * The rule set used here is synthetic and exists only to exercise arithmetic. Its durations are
 * **not** a religious source, nothing in this file establishes that any window boundary is
 * correct, and the rule set deliberately does not ship in the application. See
 * `docs/PRAYER_CALCULATION.md` §7 for what reviewing a real rule set requires.
 */
class ProhibitedPeriodCalculatorTest {

    private val calculator = ProhibitedPeriodCalculator()
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val date: LocalDate = LocalDate.of(2026, 10, 11)

    private val location: LocationInfo = checkNotNull(
        LocationInfo.create("Dhaka", 23.8103, 90.4125, "Asia/Dhaka"),
    )

    private fun at(hour: Int, minute: Int): ZonedDateTime =
        date.atTime(hour, minute).atZone(zone)

    private fun schedule(
        sunNoon: ZonedDateTime? = at(11, 45),
        complete: Boolean = true,
        dhuhr: ZonedDateTime = at(11, 46),
    ): PrayerSchedule = PrayerSchedule(
        date = date,
        location = location,
        profile = PrayerCalculationProfile.DEFAULT_KARACHI_HANAFI,
        prayers = if (complete) {
            listOf(
                PrayerTimeEntry(PrayerName.FAJR, at(4, 39)),
                PrayerTimeEntry(PrayerName.DHUHR, dhuhr),
                PrayerTimeEntry(PrayerName.ASR, at(15, 58)),
                PrayerTimeEntry(PrayerName.MAGHRIB, at(17, 36)),
                PrayerTimeEntry(PrayerName.ISHA, at(18, 51)),
            )
        } else {
            listOf(PrayerTimeEntry(PrayerName.FAJR, at(4, 39)))
        },
        solarEvents = buildList {
            add(SolarEventEntry(SolarEvent.SUNRISE, at(5, 54)))
            sunNoon?.let { add(SolarEventEntry(SolarEvent.SOLAR_NOON, it)) }
            add(SolarEventEntry(SolarEvent.SUNSET, at(17, 36)))
        },
        generatedAt = Instant.parse("2026-10-11T00:00:00Z"),
    )

    private fun ruleSet(
        sunriseAscentMinutes: Int = 15,
        zenithBeforeMinutes: Int = 5,
        zenithAfterMinutes: Int = 5,
        sunsetSettingMinutes: Int = 15,
        verification: RuleVerificationStatus = RuleVerificationStatus.REVIEWED,
    ) = ProhibitedRuleSet(
        id = "test-rule-set",
        label = "Test rule set (not a religious source)",
        sunriseAscentMinutes = sunriseAscentMinutes,
        zenithBeforeMinutes = zenithBeforeMinutes,
        zenithAfterMinutes = zenithAfterMinutes,
        sunsetSettingMinutes = sunsetSettingMinutes,
        verification = verification,
    )

    private fun zenithOf(windows: List<io.github.towkirahmedrony.salah.core.model.ProhibitedPeriod>) =
        windows.first { it.kind == ProhibitedWindowKind.ZENITH_DECLINE }

    @Test
    fun `refuses to run on an unreviewed rule set`() {
        val unreviewed = ruleSet(verification = RuleVerificationStatus.UNREVIEWED)

        assertThrows(IllegalArgumentException::class.java) {
            calculator.calculate(schedule(), unreviewed)
        }
    }

    @Test
    fun `anchors each window to the solar event the rule names`() {
        val windows = calculator.calculate(schedule(), ruleSet())

        assertEquals(
            listOf(
                ProhibitedWindowKind.SUNRISE_ASCENT,
                ProhibitedWindowKind.ZENITH_DECLINE,
                ProhibitedWindowKind.SUNSET_SETTING,
            ),
            windows.map { it.kind },
        )

        // Sunrise until sunrise + 15.
        assertEquals(at(5, 54), windows[0].startsAt)
        assertEquals(at(6, 9), windows[0].endsAt)
        // Opens five minutes before the transit. The rule would close it at 11:50, but Dhuhr
        // begins at 11:46, so it closes there.
        assertEquals(at(11, 40), windows[1].startsAt)
        assertEquals(at(11, 46), windows[1].endsAt)
        // Fifteen minutes before sunset, ending at sunset.
        assertEquals(at(17, 21), windows[2].startsAt)
        assertEquals(at(17, 36), windows[2].endsAt)
    }

    @Test
    fun `closes the zenith window at Dhuhr when Dhuhr begins inside it`() {
        val zenith = zenithOf(calculator.calculate(schedule(dhuhr = at(11, 48)), ruleSet()))

        assertEquals(at(11, 40), zenith.startsAt)
        assertEquals(at(11, 48), zenith.endsAt)
    }

    @Test
    fun `never moves the opening of the zenith window, whatever Dhuhr is`() {
        val zenith = zenithOf(calculator.calculate(schedule(dhuhr = at(11, 42)), ruleSet()))

        assertEquals(at(11, 40), zenith.startsAt)
        assertEquals(at(11, 42), zenith.endsAt)
    }

    @Test
    fun `keeps the rule's own end when Dhuhr begins after the window`() {
        val zenith = zenithOf(calculator.calculate(schedule(dhuhr = at(11, 52)), ruleSet()))

        assertEquals(at(11, 40), zenith.startsAt)
        assertEquals(at(11, 50), zenith.endsAt)
    }

    @Test
    fun `keeps the rule's own end when Dhuhr is before the window opens`() {
        // A Dhuhr that early is a mis-configuration; the window must stay well-formed rather than
        // collapse or invert.
        val zenith = zenithOf(calculator.calculate(schedule(dhuhr = at(11, 30)), ruleSet()))

        assertEquals(at(11, 40), zenith.startsAt)
        assertEquals(at(11, 50), zenith.endsAt)
        assertTrue(zenith.startsAt.isBefore(zenith.endsAt))
    }

    @Test
    fun `returns windows in chronological order`() {
        val windows = calculator.calculate(schedule(), ruleSet())

        assertEquals(
            windows.map { it.startsAt.toInstant() }.sorted(),
            windows.map { it.startsAt.toInstant() },
        )
        assertTrue(windows.all { it.startsAt.isBefore(it.endsAt) })
    }

    @Test
    fun `expresses every window in the schedule's zone, not the device's`() {
        val windows = calculator.calculate(schedule(), ruleSet())

        windows.forEach { window ->
            assertEquals(zone, window.startsAt.zone)
            assertEquals(zone, window.endsAt.zone)
        }
    }

    @Test
    fun `records the rule set behind each window`() {
        val windows = calculator.calculate(schedule(), ruleSet())

        assertTrue(windows.all { it.ruleSetId == "test-rule-set" })
        assertTrue(windows.all { it.explanation.isNotBlank() })
    }

    @Test
    fun `only produces the kinds the rule set actually specifies`() {
        val windows = calculator.calculate(
            schedule(),
            ruleSet(zenithBeforeMinutes = 0, zenithAfterMinutes = 0),
        )

        assertEquals(
            listOf(ProhibitedWindowKind.SUNRISE_ASCENT, ProhibitedWindowKind.SUNSET_SETTING),
            windows.map { it.kind },
        )
    }

    @Test
    fun `reports a rule's own configured boundary instead of a default`() {
        val windows = calculator.calculate(
            schedule(),
            ruleSet(sunriseAscentMinutes = 3, sunsetSettingMinutes = 1),
        )

        assertEquals(at(5, 57), windows.first { it.kind == ProhibitedWindowKind.SUNRISE_ASCENT }.endsAt)
        assertEquals(at(17, 35), windows.first { it.kind == ProhibitedWindowKind.SUNSET_SETTING }.startsAt)
    }

    @Test
    fun `fails loudly when an anchor the rule set needs is missing`() {
        // A schedule without the solar transit cannot anchor a zenith window. Returning a
        // shorter list would silently under-report a prohibition.
        assertThrows(IllegalArgumentException::class.java) {
            calculator.calculate(schedule(sunNoon = null), ruleSet())
        }
    }

    @Test
    fun `fails loudly when the schedule is not complete`() {
        assertThrows(IllegalArgumentException::class.java) {
            calculator.calculate(schedule(complete = false), ruleSet())
        }
    }

    @Test
    fun `never modifies the schedule it reads`() {
        val original = schedule()
        val before = original.prayers.map { it.time } + original.solarEvents.map { it.time }

        calculator.calculate(original, ruleSet())

        assertEquals(before, original.prayers.map { it.time } + original.solarEvents.map { it.time })
    }
}
