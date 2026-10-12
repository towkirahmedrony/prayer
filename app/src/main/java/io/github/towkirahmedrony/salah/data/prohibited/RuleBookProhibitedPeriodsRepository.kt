package io.github.towkirahmedrony.salah.data.prohibited

import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodCalculator
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedRuleBook
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedRuleSet
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.ProhibitedConvention
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Prohibited-time provider.
 *
 * It applies whatever [ProhibitedRuleBook] has made applicable — the application's configured
 * policy by default, or a reviewed set if one is added — and anchors the windows to the solar
 * events of the schedule it is handed, so sunrise, the solar transit and sunset come from the same
 * engine that produced the prayer times.
 *
 * **Every outcome is a value.** A missing schedule, a schedule that cannot anchor the rule set's
 * windows, or a rule book with nothing applicable are all reported as
 * [ProhibitedPeriodsState.NotAvailable] with a reason. The calculator throws on incomplete input by
 * design so a window can never be silently dropped; this class is where that refusal is converted
 * into something a screen can show, instead of an exception reaching the UI as a crash.
 *
 * Nothing here invents an interval: when the data needed to calculate one is missing, the state
 * says so.
 */
class RuleBookProhibitedPeriodsRepository(
    private val ruleBook: ProhibitedRuleBook,
    private val calculator: ProhibitedPeriodCalculator,
) : ProhibitedPeriodsRepository {

    override fun observePeriods(schedule: PrayerSchedule?): Flow<ProhibitedPeriodsState> = flowOf(
        when (val ruleSet = ruleBook.activeRuleSet()) {
            null -> ProhibitedPeriodsState.NotAvailable(
                reason = UnavailabilityReason.RULES_NOT_VERIFIED,
                detail = ruleBook.unconfiguredExplanation,
            )

            else -> when {
                schedule == null -> ProhibitedPeriodsState.NotAvailable(
                    reason = UnavailabilityReason.UNKNOWN,
                    detail = "Prohibited windows are derived from the day's schedule, which is " +
                        "not available yet.",
                )

                else -> available(schedule, ruleSet)
            }
        },
    )

    /**
     * Calculates the windows, or explains why this schedule and rule set cannot produce them.
     *
     * The solar events a rule set anchors to can legitimately be absent — a schedule assembled by
     * another path, for instance — and the calculator refuses to guess at a boundary it does not
     * have. That refusal is reported as unavailability here, never as a shorter list of windows.
     */
    private fun available(
        schedule: PrayerSchedule,
        ruleSet: ProhibitedRuleSet,
    ): ProhibitedPeriodsState = try {
        ProhibitedPeriodsState.Available(
            periods = calculator.calculate(schedule, ruleSet),
            convention = ruleSet.toConvention(),
        )
    } catch (error: IllegalArgumentException) {
        ProhibitedPeriodsState.NotAvailable(
            reason = UnavailabilityReason.UNKNOWN,
            detail = "The prohibited-time windows could not be calculated from this schedule: " +
                (error.message ?: "the required solar events are missing."),
        )
    }

    private fun ProhibitedRuleSet.toConvention(): ProhibitedConvention = ProhibitedConvention(
        ruleSetId = id,
        label = label,
        isReviewed = isReviewed,
        hasVerifiedSources = sources.isNotEmpty(),
    )
}
