package io.github.towkirahmedrony.salah.core.domain.prohibited

/**
 * The prohibited-window policy Salah ships with.
 *
 * ## What this is, and what it is not
 *
 * This is the **application's own documented policy**, not a quoted ruling. The descriptive rule
 * the three windows express — prayer is prohibited while the sun is rising, while it stands at its
 * zenith, and while it is setting — is not in dispute here; the *minute boundaries* that turn those
 * descriptions into clock times are the part that differs between scholarly conventions and
 * between the institutions that publish prayer timetables. Salah therefore does not claim that
 * these numbers are universally agreed, and it does not present them as one:
 *
 *  - the rule set is marked [RuleVerificationStatus.CONFIGURED], never
 *    [RuleVerificationStatus.REVIEWED], so nothing in the application describes it as confirmed;
 *  - [ProhibitedRuleSet.sources] is empty, because no reference for these minute values has been
 *    verified in this repository, and the screens say so instead of implying a citation;
 *  - the boundaries are **parameters**, not constants in the calculation path, so replacing them
 *    with reviewed values is a one-line data change that cannot move a prayer time;
 *  - every boundary is anchored to a true solar event calculated by the existing engine — sunrise,
 *    the solar transit and sunset — and never to Fajr, Dhuhr or Maghrib, which may carry method
 *    offsets of their own.
 *
 * It is documented in `docs/FORBIDDEN_PRAYER_TIMES.md`, together with what remains unresolved.
 */
object ConfiguredProhibitedRuleSet {

    /**
     * Identifier recorded on every window this set produces.
     *
     * Stable, because a window's origin has to stay identifiable after the numbers change.
     */
    const val ID: String = "salah-configured-default"

    /**
     * The policy in force.
     *
     * | Window | Opens | Closes |
     * |---|---|---|
     * | Sunrise ascent | sunrise | 15 minutes after sunrise |
     * | Zenith decline | 5 minutes before the solar transit | 10 minutes after the transit |
     * | Sunset setting | 15 minutes before sunset | sunset |
     *
     * The sunrise and sunset windows use the same 15-minute magnitude, which is a round
     * approximation of "until the sun has risen clear of the horizon" and "from the point the sun
     * begins to set" and is the figure most often used in practice. They are **not** derived from
     * one another: the two events are different and the rule set keeps them as separate parameters
     * precisely so one can be changed without moving the other.
     *
     * The zenith window is deliberately asymmetric. The prohibition describes the sun standing
     * still at its meridian and then declining, so the window opens at the transit and closes after
     * the decline; the five minutes *before* the transit are a precautionary margin around a
     * calculated instant, not a claim that the time before the meridian is itself prohibited. This
     * is stated again on the screen the user reads.
     */
    val DEFAULT: ProhibitedRuleSet = ProhibitedRuleSet(
        id = ID,
        label = "Salah default policy",
        sunriseAscentMinutes = 15,
        zenithBeforeMinutes = 5,
        zenithAfterMinutes = 10,
        sunsetSettingMinutes = 15,
        verification = RuleVerificationStatus.CONFIGURED,
        sources = emptyList(),
    )

    /** The rule book the application is wired with. */
    fun ruleBook(): ProhibitedRuleBook = ProhibitedRuleBook(listOf(DEFAULT))
}
