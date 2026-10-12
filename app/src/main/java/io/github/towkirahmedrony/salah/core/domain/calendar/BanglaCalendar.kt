package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.BanglaDate
import io.github.towkirahmedrony.salah.core.model.BanglaMonth
import java.time.LocalDate
import java.time.Year
import java.time.temporal.ChronoUnit

/**
 * Bangladesh (Bangla) civil calendar conversion.
 *
 * ## The rules implemented here
 *
 * This is the **Bangladesh** calendar as revised by the Bangla Academy in 2019, not the Bengali
 * calendar used in West Bengal, India. The two differ in both month lengths and the New Year date,
 * so describing one as the other would be wrong. The rules used are:
 *
 *  - **Poila Boishakh is fixed on 14 April.** In Bangladesh the civil New Year does not move between
 *    14 and 15 April, because the calendar is anchored to a fixed Gregorian date rather than to a
 *    solar computation.
 *  - **Boishakh through Ashwin (the first six months) have 31 days each.** The 2019 revision's
 *    substantive change was giving Ashwin 31 days where it previously had 30.
 *  - **Kartik, Agrahayan, Poush and Magh have 30 days each, and Chaitra has 30 days.**
 *  - **Falgun has 29 days, or 30 in a leap year.** Falgun begins on 14 February, so the extra day is
 *    29 February, and Chaitra still begins on 15 March — which is what keeps 1 Boishakh on 14 April
 *    in every year.
 *
 * That accounts for `6 * 31 + 4 * 30 + 29 + 30 = 365` days, or 366 when Falgun has 30.
 *
 * Verified reference dates (each asserted in `BanglaCalendarTest`, taken from the Bangladesh 1431
 * calendar published under the Bangla Academy rules):
 *
 * | Gregorian | Bangla |
 * |---|---|
 * | 14 April 2024 | 1 Boishakh 1431 |
 * | 15 May 2024 | 1 Jyaistha 1431 |
 * | 16 September 2024 | 1 Ashwin 1431 |
 * | 16 December 2024 | 1 Poush 1431 |
 * | 14 February 2025 | 1 Falgun 1431 |
 * | 15 March 2025 | 1 Chaitra 1431 |
 * | 21 February 2025 | 8 Falgun 1431 |
 * | 26 March 2025 | 12 Chaitra 1431 |
 * | 29 February 2024 | 16 Falgun 1430 (leap year: Falgun has 30 days) |
 *
 * ## Supported range and its one honest gap
 *
 * Conversion is offered for 14 April 2019 (the first Poila Boishakh under the revised rules) through
 * 31 December 2399. Two things bound it:
 *
 *  1. **Before the 2019 revision the rules were different** — Ashwin had 30 days — so applying the
 *     current rules to an earlier date would produce a date that was never the official one. Salah
 *     reports [isSupported] `false` and the UI says so rather than answering wrongly.
 *  2. **The leap rule for a Gregorian year divisible by 400 is not settled by the sources.**
 *     Bangladesh's published wording is "divisible by four and not by 100", which disagrees with the
 *     Gregorian calendar at years divisible by 400 (the year 2000 did have a 29 February; the year
 *     2400 will). Within the supported range the two rules agree, so nothing is guessed here — the
 *     range simply stops before the first year where they could diverge. See
 *     docs/CALENDAR_SYSTEM.md §4.
 *
 * The conversion is pure arithmetic over [LocalDate]: no zone, no locale, no clock, no network.
 */
class BanglaCalendar {

    /**
     * Converts [date] to the Bangladesh Bangla date, or returns `null` when [date] is outside the
     * verified range.
     *
     * Returning `null` rather than a best-effort answer is the point: the calendar screens render an
     * explicit "outside the verified range" state instead of a date Salah cannot stand behind.
     */
    fun convert(date: LocalDate): BanglaDate? {
        if (!isSupported(date)) return null

        val newYearGregorianYear = newYearGregorianYearOf(date)
        val year = banglaYearOf(newYearGregorianYear)
        // `startOfYear` takes a *Bangla* year, so it is given the converted one: passing the Gregorian
        // year here would look for a year 593 later and count a negative number of days.
        var remaining = ChronoUnit.DAYS.between(startOfYear(year), date).toInt()

        BanglaMonth.entries.forEach { month ->
            val length = lengthOfMonth(year, month)
            if (remaining < length) return BanglaDate(year = year, month = month, day = remaining + 1)
            remaining -= length
        }

        // Unreachable for a supported date: the month lengths always sum to the length of the year.
        return null
    }

    /** The Gregorian date on which [year]'s Boishakh starts. */
    fun startOfYear(banglaYear: Int): LocalDate =
        LocalDate.of(banglaYear + ERA_OFFSET, NEW_YEAR_MONTH, NEW_YEAR_DAY)

    /**
     * The inverse of [convert], for a Bangla date inside the supported range.
     *
     * Used by tests and by any caller that needs to place a Bangla month boundary, and implemented by
     * walking the same month lengths so the two directions cannot drift apart.
     */
    fun toGregorian(bangla: BanglaDate): LocalDate? {
        val year = bangla.year
        val start = startOfYear(year)
        if (!isSupported(start)) return null
        if (bangla.day < 1) return null

        val offset = BanglaMonth.entries
            .takeWhile { it.ordinal < bangla.month.ordinal }
            .sumOf { month -> lengthOfMonth(year, month) }
            .toLong()
        val length = lengthOfMonth(year, bangla.month)
        if (bangla.day > length) return null

        val result = start.plusDays(offset + bangla.day - 1L)
        return if (isSupported(result)) result else null
    }

    /**
     * Number of days in [month] of [banglaYear].
     *
     * Falgun is the only month whose length varies, and it varies with the *Gregorian* year it falls
     * in: Falgun of Bangla year `B` lies in Gregorian year `B + [GREGORIAN_OFFSET_OF_FALGUN_YEAR]`.
     */
    fun lengthOfMonth(banglaYear: Int, month: BanglaMonth): Int {
        if (month != BanglaMonth.FALGUN) return MONTH_LENGTHS[month.ordinal]
        return if (Year.isLeap((banglaYear + GREGORIAN_OFFSET_OF_FALGUN_YEAR).toLong())) 30 else 29
    }

    /** True when [date] is inside the range this implementation is verified for. */
    fun isSupported(date: LocalDate): Boolean =
        !date.isBefore(SUPPORTED_FROM) && !date.isAfter(SUPPORTED_UNTIL)

    /**
     * The Gregorian year whose April starts [date]'s Bangla year.
     *
     * Dates from 1 January to 13 April still belong to the Bangla year that began the previous April.
     */
    private fun newYearGregorianYearOf(date: LocalDate): Int {
        val newYearThisYear = LocalDate.of(date.year, NEW_YEAR_MONTH, NEW_YEAR_DAY)
        return if (date.isBefore(newYearThisYear)) date.year - 1 else date.year
    }

    /** Bangla year for a Gregorian year whose Poila Boishakh has already happened. */
    private fun banglaYearOf(newYearGregorianYear: Int): Int = newYearGregorianYear - ERA_OFFSET

    companion object {
        /** First Gregorian day the 2019 Bangla Academy rules apply to. */
        val SUPPORTED_FROM: LocalDate = LocalDate.of(2019, 4, 14)

        /**
         * Last Gregorian day Salah converts.
         *
         * Stops before 2400, the first year in which the Bangladesh leap-year wording and the
         * Gregorian rule could disagree. See the class documentation.
         */
        val SUPPORTED_UNTIL: LocalDate = LocalDate.of(2399, 12, 31)

        /** Poila Boishakh, in the Gregorian calendar. Fixed in Bangladesh. */
        const val NEW_YEAR_MONTH: Int = 4
        const val NEW_YEAR_DAY: Int = 14

        /** `banglaYear = gregorianYear - 593` for dates on or after 14 April. */
        const val ERA_OFFSET: Int = 593

        /** `gregorianYearOfFalgun = banglaYear + 594`, so Falgun 1430 falls in 2024. */
        const val GREGORIAN_OFFSET_OF_FALGUN_YEAR: Int = 594

        /** Month lengths under the current Bangladesh rules, except Falgun. */
        private val MONTH_LENGTHS: List<Int> = listOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 29, 30)
    }
}
