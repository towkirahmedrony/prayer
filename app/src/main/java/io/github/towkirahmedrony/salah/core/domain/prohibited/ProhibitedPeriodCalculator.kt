package io.github.towkirahmedrony.salah.core.domain.prohibited

import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriod
import io.github.towkirahmedrony.salah.core.model.SolarEvent
import java.time.ZonedDateTime

/**
 * Derives prohibited-prayer windows from an already calculated [PrayerSchedule].
 *
 * Every boundary comes from the [ProhibitedRuleSet] it is given, and it refuses to run on a rule
 * set that is not applicable. No duration is hardcoded here. It consumes a schedule and never
 * influences one, so prohibited windows can never move a prayer time.
 *
 * Anchors are read from the schedule's solar events — sunrise, the solar transit and sunset —
 * which the engine calculates free of every prayer and method offset.
 *
 * **One deliberate exception.** The zenith window is closed no later than the displayed Dhuhr
 * time. Dhuhr begins when the sun has passed the meridian (zawal), and zawal itself is not a
 * forbidden time, so the app must never show Dhuhr as started while also showing the zenith
 * prohibition still running. The window's opening is never moved.
 */
class ProhibitedPeriodCalculator {

    /**
     * Returns the windows [ruleSet] defines, in chronological order.
     *
     * @throws IllegalArgumentException when [ruleSet] is not applicable, or when the schedule is
     *   missing an anchor the rule set needs.
     */
    fun calculate(schedule: PrayerSchedule, ruleSet: ProhibitedRuleSet): List<ProhibitedPeriod> {
        require(ruleSet.isApplicable) {
            "Rule set '${ruleSet.id}' has not been configured or reviewed and must not be applied."
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
                    val opens = transit.minusMinutes(ruleSet.zenithBeforeMinutes.toLong())
                    val closesByRule = transit.plusMinutes(ruleSet.zenithAfterMinutes.toLong())
                    val dhuhr = schedule.timeOf(PrayerName.DHUHR)
                    // Close at Dhuhr when it falls inside the window; otherwise keep the rule's end.
                    val closes = if (dhuhr != null && dhuhr.isAfter(opens) && dhuhr.isBefore(closesByRule)) {
                        dhuhr.withZoneSameInstant(zone)
                    } else {
                        closesByRule
                    }
                    opens to closes
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
                "$zenithAfterMinutes min after it, ending earlier if Dhuhr begins first."

        ProhibitedWindowKind.SUNSET_SETTING ->
            "From $sunsetSettingMinutes min before sunset until sunset."
    }
}
