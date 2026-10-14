package io.github.towkirahmedrony.salah.core.domain.calendar

/**
 * The three traditional stages Ramadan is commonly divided into.
 *
 * This is a **classification of the days of Ramadan, not a second date system**. The dates, the Hijri
 * conversion and the prayer times are all untouched by it: a day belongs to exactly one stage because
 * of its 1-based Ramadan day number, and nothing here recalculates a date. Splitting the month up is a
 * reading aid for a thirty-row table, so it is deliberately a pure function of the day number.
 *
 * The boundaries are the widely used ones — the first ten days, the middle ten, and everything from the
 * twenty-first day to the end of the month. The last one is **open-ended on purpose**: Ramadan is 29 or
 * 30 days depending on the sighting, and a stage that ended at a hardcoded day 29 would be wrong in a
 * thirty-day year and would silently hide the last day. [ofDayNumber] therefore only knows where a
 * stage *begins*; how long the last one runs is decided by the window itself.
 *
 * The names are the stage's identity and are deliberately not localised here — the screen renders them
 * through string resources, exactly as it does every other label, so a Bengali interface shows Bengali
 * names.
 */
enum class RamadanStage(
    /** 1-based Ramadan day this stage begins on. */
    val firstDayNumber: Int,
) {
    /** Days 1–10. */
    RAHMAH(firstDayNumber = 1),

    /** Days 11–20. */
    MAGHFIRAH(firstDayNumber = 11),

    /** Day 21 to the last day of the month, so 29 or 30 days in total. */
    NAJAT(firstDayNumber = 21),
    ;

    companion object {
        /**
         * The stage that contains the 1-based Ramadan [dayNumber].
         *
         * Expressed as "the last stage that has already begun" rather than as three upper bounds, so the
         * final stage has no upper bound to get wrong. A day number below the first day of Ramadan cannot
         * be produced by a Ramadan window — the window numbers its own days from one — and would fall in
         * [RAHMAH].
         */
        fun ofDayNumber(dayNumber: Int): RamadanStage = when {
            dayNumber >= NAJAT.firstDayNumber -> NAJAT
            dayNumber >= MAGHFIRAH.firstDayNumber -> MAGHFIRAH
            else -> RAHMAH
        }
    }
}
