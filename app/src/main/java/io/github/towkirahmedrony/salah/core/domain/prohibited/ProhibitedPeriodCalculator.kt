package io.github.towkirahmedrony.salah.core.domain.prohibited

import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriod
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import java.time.ZonedDateTime

/**
 * Derives prohibited-prayer windows from an already calculated [PrayerSchedule].
 *
 * The calculator is deliberately dumb about religion and exact about arithmetic: every boundary
 * it applies comes from the [ProhibitedRuleSet] it is given, and it refuses to run at all on a
 * rule set that has not been reviewed. There is therefore no duration, angle or tolerance
 * hardcoded here, and no default rule set is baked in — a wrong answer is only possible if
 * someone hands it a wrong rule set on purpose.
 *
 * It is kept separate from ordinary prayer-time calculation: it consumes a schedule and never
 * influences one, so prohibited windows can never move a prayer time.
 *
 * Anchors are read from the schedule's solar events — sunrise, the solar transit and sunset —
 * which the engine calculates free of every prayer and method offset. That is why a window is
 * anchored to the true astronomical event rather than to Fajr or Maghrib, which may carry
 * offsets of their own.
 */
class ProhibitedPeriodCalculator {

    /**
     * Returns the windows [ruleSet] defines, in chronological order.
     *
     * @throws IllegalArgumentException when [ruleSet] has not been reviewed, or when the
     *   schedule is missing an anchor the rule set needs. Both are programming errors: the
     *   repository must check [ProhibitedRuleBook.isConfigured] first and the schedule must be
     *   complete. Returning a shorter list would silently under-report a prohibition.
     */
    fun calculate(schedule: PrayerSchedule, ruleSet: ProhibitedRuleSet): List<ProhibitedPeriod> {
        require(ruleSet.isReviewed) {
            "Rule set '${ruleSet.id}' has not been reviewed and must not be applied."
        }
        if (!schedule.isComplete) {
            throw IllegalArgumentException("A schedule without all five prayers cannot anchor prohibited windows.")
        }

        val zone = schedule.zoneId
        return ruleSet.windowKinds.map { kind ->
            val (startsAt, endsAt) = when (kind) {
                ProhibitedWindowKind.SUNRISE_ASCENT -> {
                    val sunrise = schedule.requireSolarEvent(SolarEvent.SUNRISE)
                    sunrise to sunrise.plusMinutes(ruleSet.sunriseAscentMinutes.toLong())
                }

                ProhibitedWindowKind.ZENITH_DECLINE -> {
                    val transit = schedule.requireSolarEvent(SolarEvent.SOLAR_NOON)
                    transit.minusMinutes(ruleSet.zenithBeforeMinutes.toLong()) to
                        transit.plusMinutes(ruleSet.zenithAfterMinutes.toLong())
                }

                ProhibitedWindowKind.SUNSET_SETTING -> {
                    val sunset = schedule.requireSolarEvent(SolarEvent.SUNSET)
                    sunset.minusMinutes(ruleSet.sunsetSettingMinutes.toLong()) to sunset
                }
            }
            ProhibitedPeriod(
                startsAt = startsAt,
                endsAt = endsAt,
                ruleSetId = ruleSet.id,
                kind = kind,
                explanation = ruleSet.explanationFor(kind),
            )
        }.sortedBy { it.startsAt.toInstant() }
            .also { windows ->
                // Written against the local zone explicitly, so the assertion holds even if the
                // device zone differs from the prayer location's zone.
                check(windows.all { it.startsAt.zone == zone && it.endsAt.zone == zone }) {
                    "Prohibited windows must be expressed in the schedule's zone."
                }
            }
    }

    private fun PrayerSchedule.requireSolarEvent(event: SolarEvent): ZonedDateTime =
        timeOf(event) ?: throw IllegalArgumentException(
            "The rule set needs the ${event.name} anchor, which this schedule does not contain.",
        )

    private fun ProhibitedRuleSet.explanationFor(kind: ProhibitedWindowKind): String = when (kind) {
        ProhibitedWindowKind.SUNRISE_ASCENT ->
            "From sunrise until the sun has risen clear of the horizon ($sunriseAscentMinutes min)."

        ProhibitedWindowKind.ZENITH_DECLINE ->
            "Around the sun's zenith, from $zenithBeforeMinutes min before the transit until " +
                "$zenithAfterMinutes min after it."

        ProhibitedWindowKind.SUNSET_SETTING ->
            "From $sunsetSettingMinutes min before sunset until sunset."
    }
}
