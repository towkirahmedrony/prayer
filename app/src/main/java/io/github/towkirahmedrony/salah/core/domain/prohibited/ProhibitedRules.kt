package io.github.towkirahmedrony.salah.core.domain.prohibited

import io.github.towkirahmedrony.salah.core.model.SolarEvent

/**
 * The kinds of window in which prayer is prohibited, as distinct rules rather than one generic
 * "minutes around sunrise and sunset".
 *
 * Kept separate on purpose: each kind is anchored to a different astronomical event, each is
 * described by a different boundary rule, each has its own duration parameter, and a future
 * reviewed rule set may accept some of them while rejecting others.
 */
enum class ProhibitedWindowKind {

    /** From sunrise until the sun has risen clear of the horizon. Anchored to sunrise. */
    SUNRISE_ASCENT,

    /** Around the sun's zenith, until it has begun to decline. Anchored to the solar transit. */
    ZENITH_DECLINE,

    /** From the point the sun begins to set until it has fully set. Anchored to sunset. */
    SUNSET_SETTING,
    ;

    /**
     * The astronomical event this window is anchored to.
     *
     * The anchor is a solar event and never a prayer: a method that offsets Fajr, Dhuhr or Maghrib
     * must not be able to move a prohibited window. A screen can therefore show the event time next
     * to the interval without the two being confusable.
     */
    val anchorEvent: SolarEvent
        get() = when (this) {
            SUNRISE_ASCENT -> SolarEvent.SUNRISE
            ZENITH_DECLINE -> SolarEvent.SOLAR_NOON
            SUNSET_SETTING -> SolarEvent.SUNSET
        }
}

/** How far a rule set has got towards being authoritative. */
enum class RuleVerificationStatus {
    /** Boundaries are proposed but not even configured. Never applied. */
    UNREVIEWED,

    /**
     * The application's own documented policy: every boundary is explicit, is published with the
     * application, and is shown to the user as the convention *this application* applies.
     *
     * Deliberately distinct from [REVIEWED]. A configured rule set is applied — a feature that shows
     * nothing at all is not the safer choice — but it is never presented as a confirmed religious
     * ruling, and the screens state which convention is in force and that disagreements exist.
     * See `docs/FORBIDDEN_PRAYER_TIMES.md`.
     */
    CONFIGURED,

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
 * @param verification how far this set has got towards being authoritative. A set that is
 *   [RuleVerificationStatus.UNREVIEWED] is never applied; a [RuleVerificationStatus.CONFIGURED] set
 *   is applied as the application's own documented policy and is disclosed as such; only a
 *   [RuleVerificationStatus.REVIEWED] set may be described as confirmed.
 * @param sources the references a reviewer approved, recorded verbatim. Empty while no reference
 *   has been verified, which is what the screens use to decide whether to say so.
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

    /** True when this set may be applied: configured by the application or reviewed. */
    val isApplicable: Boolean get() = verification != RuleVerificationStatus.UNREVIEWED

    /** True only when a qualified reviewer confirmed these boundaries. */
    val isReviewed: Boolean get() = verification == RuleVerificationStatus.REVIEWED
}

/**
 * The set of rule sets Salah is allowed to apply.
 *
 * A rule set is applied when it has been configured — either as the application's own documented
 * policy ([RuleVerificationStatus.CONFIGURED]) or after religious review
 * ([RuleVerificationStatus.REVIEWED]). Anything still [RuleVerificationStatus.UNREVIEWED] is
 * filtered out here and can never reach the calculator, the view model or the screen.
 *
 * Which set is in force is decided by data, not by the UI: adding a reviewed set ahead of the
 * configured one, or replacing the configured one, changes what the screens show without touching
 * the calculation path. See `docs/FORBIDDEN_PRAYER_TIMES.md` for the boundaries currently in force.
 */
class ProhibitedRuleBook(
    private val ruleSets: List<ProhibitedRuleSet> = emptyList(),
) {

    /** Every rule set Salah may apply. Empty only when nothing has been configured or reviewed. */
    val applicable: List<ProhibitedRuleSet>
        get() = ruleSets.filter { it.isApplicable }

    /** The rule set to apply, or `null` while nothing is applicable. */
    fun activeRuleSet(): ProhibitedRuleSet? = applicable.firstOrNull()

    /** True when Salah may show prohibited windows at all. */
    val isConfigured: Boolean get() = activeRuleSet() != null

    /** Shown to the user when [isConfigured] is false, so the gap is explained, not hidden. */
    val unconfiguredExplanation: String
        get() = "Salah has not been configured with a set of prohibited prayer-time rules, so no " +
            "intervals are shown. Window boundaries depend on juristic detail, and Salah will " +
            "not display intervals it has no documented convention for."
}
