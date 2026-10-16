package io.github.towkirahmedrony.salah.core.domain.prohibited

import io.github.towkirahmedrony.salah.core.model.ProhibitedPeriod
import java.time.Duration
import java.time.ZonedDateTime

/** Where a prohibited window stands relative to a moment in time. */
enum class ProhibitedPeriodPhase {
    /** The moment is inside the window. */
    ACTIVE,

    /** The window has not opened yet on this local date. */
    UPCOMING,

    /** The window has closed for this local date. */
    PASSED,
}

/**
 * A prohibited window together with its status at a specific moment.
 *
 * @param startsIn time until the window opens; only present while [phase] is
 *   [ProhibitedPeriodPhase.UPCOMING].
 * @param endsIn time until the window closes; only present while [phase] is
 *   [ProhibitedPeriodPhase.ACTIVE].
 */
data class ProhibitedPeriodStatus(
    val period: ProhibitedPeriod,
    val phase: ProhibitedPeriodPhase,
    val startsIn: Duration? = null,
    val endsIn: Duration? = null,
)

/**
 * Classifies prohibited windows against a moment in time.
 *
 * Pure arithmetic over the windows the calculator produced: it reads no clock, performs no
 * astronomy and never moves a boundary. A window it is handed is the window it classifies, so a
 * wrong status can only come from a wrong window.
 *
 * **Boundary convention.** A window is half-open, `[startsAt, endsAt)`: the exact start instant is
 * [ProhibitedPeriodPhase.ACTIVE] and the exact end instant is [ProhibitedPeriodPhase.PASSED]. The
 * same half-open rule is used for prayer periods, so a boundary in one feature means the same thing
 * in the other.
 *
 * **Windows that cross midnight.** The three windows in use are anchored to sunrise, the solar
 * transit and sunset, all of which fall inside one local day, so none of them crosses midnight.
 * The arithmetic below is nevertheless written against absolute instants, so a rule set that did
 * produce a window spanning midnight would still be classified correctly.
 *
 * A moment outside every window is simply [ProhibitedPeriodPhase.PASSED] for the windows it is
 * after and [ProhibitedPeriodPhase.UPCOMING] for those it is before — there is no "no window" state,
 * because the windows are a fixed set of events rather than a single interval of the day.
 */
class ProhibitedPeriodStatusResolver {

    fun classify(periods: List<ProhibitedPeriod>, now: ZonedDateTime): List<ProhibitedPeriodStatus> =
        periods.map { period -> classify(period, now) }

    /**
     * The window that is open at [now], or `null` when none is.
     *
     * At most one window can be active at a moment because the configured policy's three windows do
     * not overlap, but this returns the first active one regardless, so a caller that supplied
     * overlapping windows still gets a definite answer rather than a silently dropped interval.
     */
    fun active(periods: List<ProhibitedPeriod>, now: ZonedDateTime): ProhibitedPeriodStatus? =
        classify(periods, now).firstOrNull { it.phase == ProhibitedPeriodPhase.ACTIVE }

    /**
     * The next window to open after [now], or `null` when every window has already passed today.
     *
     * "Next" is the upcoming window with the smallest opening time, so the caller does not depend on
     * the order the windows were supplied in.
     */
    fun next(periods: List<ProhibitedPeriod>, now: ZonedDateTime): ProhibitedPeriodStatus? =
        classify(periods, now)
            .filter { it.phase == ProhibitedPeriodPhase.UPCOMING }
            .minByOrNull { it.startsIn?.seconds ?: Long.MAX_VALUE }

    fun classify(period: ProhibitedPeriod, now: ZonedDateTime): ProhibitedPeriodStatus {
        val moment = now.withZoneSameInstant(period.startsAt.zone)
        val startsAt = period.startsAt
        val endsAt = period.endsAt

        return when {
            moment.isBefore(startsAt) -> ProhibitedPeriodStatus(
                period = period,
                phase = ProhibitedPeriodPhase.UPCOMING,
                startsIn = Duration.between(moment, startsAt),
            )

            moment.isBefore(endsAt) -> ProhibitedPeriodStatus(
                period = period,
                phase = ProhibitedPeriodPhase.ACTIVE,
                endsIn = Duration.between(moment, endsAt),
            )

            else -> ProhibitedPeriodStatus(period = period, phase = ProhibitedPeriodPhase.PASSED)
        }
    }
}
