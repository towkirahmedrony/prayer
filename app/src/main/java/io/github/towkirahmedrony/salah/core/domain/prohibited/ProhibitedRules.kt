package io.github.towkirahmedrony.salah.core.domain.prohibited

/**
 * The kinds of window in which prayer is prohibited, as distinct rules rather than one generic
 * "minutes around sunrise and sunset".
 *
 * Kept separate on purpose: each kind is anchored to a different astronomical event, each is
 * described differently in the sources, and a future reviewed rule set may accept some of them
 * while rejecting others.
 */
enum class ProhibitedWindowKind {
    /** From sunrise until the sun has risen clear of the horizon. Anchored to sunrise. */
    SUNRISE_ASCENT,

    /** Around the sun's zenith, until it has begun to decline. Anchored to the solar transit. */
    ZENITH_DECLINE,

    /** From the point the sun begins to set until it has fully set. Anchored to sunset. */
    SUNSET_SETTING,
}

/** Whether a rule set has been through religious review. */
enum class RuleVerificationStatus {
    /** Boundaries are proposed but no qualified reviewer has confirmed them. Never displayed. */
    UNREVIEWED,

    /** Boundaries were reviewed and approved, including the sources used to justify them. */
    REVIEWED,
}

/**
 * A fully specified, named set of prohibited-window boundaries.
 *
 * Every boundary here is an explicit parameter, not a constant hidden inside the calculator, so
 * that a reviewer can read and approve the exact numbers Salah would use — and so that no
 * duration is ever hardcoded into the calculation path.
 *
 * @param id stable identifier, recorded on every window this rule set produces.
 * @param label human readable name shown to a user when the set becomes selectable.
 * @param sunriseAscentMinutes minutes after sunrise during which prayer is prohibited.
 * @param zenithBeforeMinutes minutes before the solar transit at which the window opens.
 * @param zenithAfterMinutes minutes after the solar transit at which the window closes.
 * @param sunsetSettingMinutes minutes before sunset at which the window opens.
 * @param verification review state. A rule set that is not [RuleVerificationStatus.REVIEWED]
 *   must never be presented to a user as religious guidance.
 * @param sources the references a reviewer approved, recorded verbatim.
 */
data class ProhibitedRuleSet(
    val id: String,
    val label: String,
    val sunriseAscentMinutes: Int,
    val zenithBeforeMinutes: Int,
    val zenithAfterMinutes: Int,
    val sunsetSettingMinutes: Int,
    val verification: RuleVerificationStatus,
    val sources: List<String> = emptyList(),
) {
    /** The window kinds this set actually specifies. */
    val windowKinds: List<ProhibitedWindowKind> = buildList {
        if (sunriseAscentMinutes > 0) add(ProhibitedWindowKind.SUNRISE_ASCENT)
        if (zenithAfterMinutes > 0) add(ProhibitedWindowKind.ZENITH_DECLINE)
        if (sunsetSettingMinutes > 0) add(ProhibitedWindowKind.SUNSET_SETTING)
    }

    val isReviewed: Boolean get() = verification == RuleVerificationStatus.REVIEWED
}

/**
 * The set of rule sets Salah is allowed to apply.
 *
 * It ships **empty**, and that is the point: no prohibited-window boundary in this project has
 * been through religious review, so there is nothing to apply and Salah reports the honest
 * unavailability state instead of displaying intervals it cannot justify.
 *
 * This type exists so that enabling the feature later is a data change — add one reviewed
 * [ProhibitedRuleSet] — rather than a code change in the UI or the calculation path. The
 * calculator that consumes a rule set is already implemented and unit-tested; see
 * [ProhibitedPeriodCalculator].
 */
class ProhibitedRuleBook(
    private val reviewedRuleSets: List<ProhibitedRuleSet> = emptyList(),
) {

    /** Every rule set available to the application. Empty until review happens. */
    val reviewed: List<ProhibitedRuleSet>
        get() = reviewedRuleSets.filter { it.isReviewed }

    /** The rule set to apply, or `null` while nothing has been reviewed. */
    fun activeRuleSet(): ProhibitedRuleSet? = reviewed.firstOrNull()

    /** True when Salah may show prohibited windows at all. */
    val isConfigured: Boolean get() = activeRuleSet() != null

    /** Shown to the user when [isConfigured] is false, so the gap is explained, not hidden. */
    val unconfiguredExplanation: String
        get() = "Salah has not been configured with a reviewed set of prohibited prayer-time " +
            "rules, so no intervals are shown. Window boundaries depend on juristic detail " +
            "that must be confirmed by a qualified reviewer before they are displayed as " +
            "religious guidance."
}
