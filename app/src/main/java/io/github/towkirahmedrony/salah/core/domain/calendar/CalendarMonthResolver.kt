package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.BanglaDate
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Gregorian days covered by one month of a calendar system.
 *
 * Every calendar Salah presents is a run of consecutive civil days: the Gregorian month, the
 * Bangladesh Bangla month and the Hijri month all consist of consecutive Gregorian dates. This value
 * is that run, expressed in the Gregorian identity the rest of the app uses for date-dependent
 * features (prayer times, Ramadan, Sehri and Iftar), so nothing downstream has to know which system
 * produced the layout.
 *
 * [start] and [end] are inclusive, which is what makes [length] exact for a 29-day Hijri month and a
 * 30-day Bangladesh Falgun in a leap year — neither of which is 30 or 31 days respectively.
 */
data class CalendarMonthSpan(
    val system: CalendarSystem,
    /** First Gregorian day of the month, inclusive. */
    val start: LocalDate,
    /** Last Gregorian day of the month, inclusive. */
    val end: LocalDate,
) {
    /** Number of days in the month. */
    val length: Int get() = (end.toEpochDay() - start.toEpochDay()).toInt() + 1

    /** Every Gregorian day of the month, in order. */
    val dates: List<LocalDate> get() = (0 until length).map { offset -> start.plusDays(offset.toLong()) }
}

/**
 * Finds the month of a selected calendar system that contains a given Gregorian day.
 *
 * This is the piece that makes the calendar-mode selection real: the Calendar screen asks, for the
 * chosen system and a Gregorian anchor, which Gregorian days the *system's* month covers, and lays
 * those days out. Conversion is delegated to the same pure converters the rest of Salah uses —
 * [BanglaCalendar] under the 2019 Bangladesh rules and [HijriCalendar]'s Umm al-Qura table — so a
 * month boundary here is the same boundary those converters place everywhere else.
 *
 * Returns `null` when the system's month cannot be established for [anchor]: a Gregorian day before
 * the verified Bangladesh range, or outside the platform's Hijri table. The caller then falls back to
 * the Gregorian month and the screen states that the conversion is out of range rather than showing a
 * fabricated month.
 *
 * No zone, no locale, no clock and no I/O: [anchor] is the Gregorian day the caller already decided.
 */
class CalendarMonthResolver(
    private val banglaCalendar: BanglaCalendar = BanglaCalendar(),
    private val hijriCalendar: HijriCalendar = HijriCalendar(),
) {

    /**
     * The month of [system] that contains [date], or `null` when it cannot be determined.
     *
     * @param hijriAdjustmentDays the user's Hijri offset. Applied to the Hijri month boundary exactly
     *   as [HijriCalendar.dateOf] applies it to a day, so the displayed Hijri day numbers inside the
     *   month always run `1..length`.
     */
    fun monthContaining(
        system: CalendarSystem,
        date: LocalDate,
        hijriAdjustmentDays: Int = 0,
    ): CalendarMonthSpan? = when (system) {
        CalendarSystem.GREGORIAN -> gregorianMonth(date)
        CalendarSystem.BANGLA -> banglaMonth(date)
        CalendarSystem.HIJRI -> hijriMonth(date, hijriAdjustmentDays)
    }

    private fun gregorianMonth(date: LocalDate): CalendarMonthSpan {
        val month = YearMonth.from(date)
        return CalendarMonthSpan(
            system = CalendarSystem.GREGORIAN,
            start = month.atDay(1),
            end = month.atEndOfMonth(),
        )
    }

    private fun banglaMonth(date: LocalDate): CalendarMonthSpan? {
        val bangla = banglaCalendar.convert(date) ?: return null
        val start = banglaCalendar.toGregorian(BanglaDate(bangla.year, bangla.month, 1)) ?: return null
        val length = banglaCalendar.lengthOfMonth(bangla.year, bangla.month)
        val end = start.plusDays(length - 1L)
        // Refuse a month that runs past the verified range rather than converting part of it.
        if (!banglaCalendar.isSupported(end)) return null
        return CalendarMonthSpan(system = CalendarSystem.BANGLA, start = start, end = end)
    }

    private fun hijriMonth(date: LocalDate, adjustmentDays: Int): CalendarMonthSpan? {
        val hijri = hijriCalendar.dateOf(date, adjustmentDays) ?: return null
        val start = hijriCalendar.monthStart(hijri.year, hijri.month, adjustmentDays) ?: return null
        val length = hijriCalendar.monthLength(hijri.year, hijri.month) ?: return null
        return CalendarMonthSpan(system = CalendarSystem.HIJRI, start = start, end = start.plusDays(length - 1L))
    }
}
