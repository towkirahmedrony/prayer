package io.github.towkirahmedrony.salah.core.domain.prohibited

/**
 * The prohibited-window policy Salah ships with.
 *
 * ## What this is, and what it is not
 *
 * This is the **application's own documented policy**, not a quoted ruling. The descriptive rule
 * the three windows express — prayer is prohibited while the sun is rising, while it stands at its
 * zenith, and while it is setting — is not in dispute here; the *minute boundaries* that turn those
 * descriptions into clock times are the part that differs between scholarly conventions.
 *
 *  - the rule set is marked [RuleVerificationStatus.CONFIGURED], never
 *    [RuleVerificationStatus.REVIEWED], so nothing in the application describes it as confirmed;
 *  - [ProhibitedRuleSet.sources] stays empty: the references below were read by the developer, not
 *    approved by a qualified reviewer, so the screens still say the values are unreviewed;
 *  - the boundaries are **parameters**, not constants in the calculation path;
 *  - every boundary is anchored to a true solar event calculated by the existing engine — sunrise,
 *    the solar transit and sunset — and never to Fajr, Dhuhr or Maghrib, except that the zenith
 *    window never extends past the displayed Dhuhr time (see [ProhibitedPeriodCalculator]).
 *
 * ## Where the numbers come from (Hanafi fatwa answers, read November 2026)
 *
 *  - Sunrise: until the sun is a spear's length high, "about twenty minutes" (SeekersGuidance,
 *    Sohail Hanif and Sufyan Qufi); another answer gives 15-30 minutes depending on place and season
 *    (SeekersGuidance, Tabraze Azam). 20 is used.
 *  - Sunset: from when the sun turns dull until it sets, "about twenty minutes to half an hour"
 *    (SeekersGuidance, Sohail Hanif); 15-30 per Tabraze Azam. 20 is used, the low end of both.
 *  - Zenith (istiwa): a momentary period, so jurists add a margin: 5 minutes either side
 *    (Darul Ifta Birmingham, citing Aapke Masail), 3-5 minutes either side (Qibla.com, Abdurrahman
 *    Mangera). 5 is used. The same answers state that *zawal* — the sun leaving the meridian — is
 *    when Dhuhr begins and is not itself a forbidden time, which is why the window is closed at
 *    Dhuhr by the calculator.
 *
 * It is documented in `docs/FORBIDDEN_PRAYER_TIMES.md`, together with what remains unresolved.
 */
object ConfiguredProhibitedRuleSet {

    /** Identifier recorded on every window this set produces. */
    const val ID: String = "salah-configured-default"

    /**
     * The policy in force.
     *
     * | Window | Opens | Closes |
     * |---|---|---|
     * | Sunrise ascent | sunrise | 20 minutes after sunrise |
     * | Zenith decline | 5 minutes before the solar transit | 5 minutes after it, or Dhuhr if earlier |
     * | Sunset setting | 20 minutes before sunset | sunset |
     */
    val DEFAULT: ProhibitedRuleSet = ProhibitedRuleSet(
        id = ID,
        label = "Salah default policy",
        sunriseAscentMinutes = 20,
        zenithBeforeMinutes = 5,
        zenithAfterMinutes = 5,
        sunsetSettingMinutes = 20,
        verification = RuleVerificationStatus.CONFIGURED,
        sources = emptyList(),
    )

    /** The rule book the application is wired with. */
    fun ruleBook(): ProhibitedRuleBook = ProhibitedRuleBook(listOf(DEFAULT))
}
