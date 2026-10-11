package io.github.towkirahmedrony.salah.data.prohibited

import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedPeriodCalculator
import io.github.towkirahmedrony.salah.core.domain.prohibited.ProhibitedRuleBook
import io.github.towkirahmedrony.salah.core.domain.repository.ProhibitedPeriodsRepository
import io.github.towkirahmedrony.salah.core.model.PrayerSchedule
import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriodsState
import io.github.towkirahmedrony.salah.core.model.UnavailabilityReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Prohibited-time provider.
 *
 * It applies whatever [ProhibitedRuleBook] has reviewed. Because that book ships empty, this
 * repository reports [UnavailabilityReason.RULES_NOT_VERIFIED] and the reason is explained to
 * the user rather than filled in with plausible-looking numbers.
 *
 * The wiring is real, not a stub: as soon as a reviewed rule set is added to the rule book, the
 * calculator starts producing windows for the schedule the Home screen already has, with no
 * change to the UI, the view model or this class.
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

                else -> ProhibitedPeriodsState.Available(
                    periods = calculator.calculate(schedule, ruleSet),
                )
            }
        },
    )
}
