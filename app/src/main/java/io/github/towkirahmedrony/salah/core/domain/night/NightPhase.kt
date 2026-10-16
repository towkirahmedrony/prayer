package io.github.towkirahmedrony.salah.core.domain.night

/**
 * Where a moment sits inside the night interval.
 *
 * The three phases are consecutive and cover the whole interval, so a moment inside it always has
 * one and only one phase:
 *
 * | Phase | Opens | Closes |
 * |---|---|---|
 * | [EARLY_NIGHT] | Maghrib | Islamic midnight (the midpoint) |
 * | [LATE_NIGHT] | Islamic midnight | the start of the last third |
 * | [LAST_THIRD] | the start of the last third | the following Fajr |
 *
 * A moment outside the interval — between one Fajr and the following Maghrib — has **no** phase, and
 * is reported as `null` rather than being forced into one of these. Nothing here is a ruling: it is
 * the arithmetic position of a moment inside the interval the tabulated prayer times define.
 */
enum class NightPhase {
    /** From the effective Maghrib to the midpoint of the interval. */
    EARLY_NIGHT,

    /** From the midpoint to the start of the final third. */
    LATE_NIGHT,

    /** From the start of the final third to the following effective Fajr. */
    LAST_THIRD,
}

/**
 * A boundary inside the night interval that a countdown can target.
 *
 * The boundaries are distinct instants and are never interchangeable: [ISLAMIC_MIDNIGHT] is the
 * midpoint of the Maghrib-to-Fajr interval, [LAST_THIRD_STARTS] is two thirds of the way through it,
 * and [FAJR] closes it. None of them is civil midnight, and none is the astronomical solar midnight
 * (`core/domain/solar/SolarDayResolver`), even when their clock times happen to be close.
 */
enum class NightBoundary {
    ISLAMIC_MIDNIGHT,
    LAST_THIRD_STARTS,
    FAJR,
}
