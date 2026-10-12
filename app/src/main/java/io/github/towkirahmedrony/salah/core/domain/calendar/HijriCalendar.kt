package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.HijriDate
import io.github.towkirahmedrony.salah.core.model.HijriDateSource
import io.github.towkirahmedrony.salah.core.model.HijriMonth
import io.github.towkirahmedrony.salah.core.model.IslamicOccasion
import io.github.towkirahmedrony.salah.core.model.RamadanWindow
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

/**
 * Hijri (Islamic) calendar conversion, using the platform's Umm al-Qura table.
 *
 * ## Why `java.time` and not a new dependency
 *
 * `java.time.chrono.HijrahChronology` — `HijrahDate` — is part of `java.base`, so this project gains
 * no dependency, no licence question, no maintenance risk and no JVM-target risk for using it (the
 * JVM-target issue that pins the prayer engine would be a real hazard for an arbitrary library). The
 * JDK's singleton chronology is the **Umm al-Qura** calendar of Saudi Arabia, which is a *tabular*
 * astronomy-based calendar: it is a fixed, published table, so the same input always produces the
 * same output on every device.
 *
 * ## What the result means, and what it does not
 *
 * A Hijri date here is an **estimate**. Umm al-Qura is not the calendar Bangladesh's religious
 * authorities use to announce Ramadan, Eid al-Fitr or Eid al-Adha; they depend on local moon
 * sighting. So:
 *
 *  - a date returned by this class is labelled [HijriDateSource.ESTIMATED_UMM_AL_QURA];
 *  - when the user has configured a day offset, it is labelled
 *    [HijriDateSource.ESTIMATED_USER_ADJUSTED], because it is the user's alignment, not Salah's;
 *  - Salah never produces a "confirmed" date, because it has no authority source integrated. See
 *    docs/CALENDAR_SYSTEM.md §5.
 *
 * It is commonly within a day of the announced date, and the reference dates in `HijriCalendarTest`
 * show it agreeing exactly with the announced Saudi dates for 1444–1447 AH — which is evidence for
 * the Umm al-Qura mapping, not a claim about Bangladesh.
 *
 * ## Supported range
 *
 * The table shipped with the platform covers roughly AH 1300 to AH 1600 (November 1882 to November
 * 2174). Rather than hard-code a guessed boundary, conversion is attempted and any date the
 * platform's table does not cover yields `null`, which the UI reports as outside the supported range.
 */
class HijriCalendar {

    /**
     * The Hijri date shown for [gregorian], or `null` when the platform's table does not cover it.
     *
     * @param adjustmentDays days added to the calculated date — the user's own offset. Applied
     *   *before* the value is read, so a shifted date is a genuinely different Hijri day and the
     *   month and year transitions move with it.
     */
    fun dateOf(gregorian: LocalDate, adjustmentDays: Int = 0): HijriDate? {
        val base = runCatching { HijrahDate.from(gregorian) }.getOrNull() ?: return null
        val value = if (adjustmentDays == 0) {
            base
        } else {
            runCatching { base.plus(adjustmentDays.toLong(), ChronoUnit.DAYS) }.getOrNull() ?: return null
        }

        val month = HijriMonth.ofNumber(value.get(ChronoField.MONTH_OF_YEAR)) ?: return null
        return HijriDate(
            year = value.get(ChronoField.YEAR),
            month = month,
            day = value.get(ChronoField.DAY_OF_MONTH),
            source = if (adjustmentDays == 0) {
                HijriDateSource.ESTIMATED_UMM_AL_QURA
            } else {
                HijriDateSource.ESTIMATED_USER_ADJUSTED
            },
        )
    }

    /**
     * The estimated Gregorian range of Ramadan [hijriYear].
     *
     * Derived from the Hijri calendar rather than stored, so changing the Hijri offset moves the
     * range by exactly that many days and the two can never disagree. The length comes from the
     * platform's own table for that month, so a 29-day Ramadan stays 29 days.
     *
     * @param adjustmentDays the user's Hijri offset, in days. A *positive* offset means the displayed
     *   Hijri date is `adjustmentDays` later than the calculation, which moves the Gregorian start of
     *   Ramadan correspondingly *earlier* — the same relationship [dateOf] implements.
     */
    fun ramadanWindow(hijriYear: Int, adjustmentDays: Int = 0): RamadanWindow? {
        val firstOfRamadan = runCatching { HijrahDate.of(hijriYear, RAMADAN_MONTH_NUMBER, 1) }
            .getOrNull() ?: return null
        val length = firstOfRamadan.lengthOfMonth()
        if (length <= 0) return null

        val startEpochDay = firstOfRamadan.toEpochDay() - adjustmentDays
        val start = runCatching { LocalDate.ofEpochDay(startEpochDay) }.getOrNull() ?: return null

        return RamadanWindow(
            hijriYear = hijriYear,
            start = start,
            end = start.plusDays(length - 1L),
            source = if (adjustmentDays == 0) {
                HijriDateSource.ESTIMATED_UMM_AL_QURA
            } else {
                HijriDateSource.ESTIMATED_USER_ADJUSTED
            },
        )
    }

    /**
     * The Ramadan window that contains [gregorian], or the next one that starts after it.
     *
     * Returns `null` only when neither this Hijri year's nor the next one's Ramadan is inside the
     * platform's table — the honest "no reliable data" case the Ramadan screen reports instead of
     * inventing dates.
     */
    fun ramadanWindowFor(gregorian: LocalDate, adjustmentDays: Int = 0): RamadanWindow? {
        val hijri = dateOf(gregorian, adjustmentDays) ?: return null
        val candidates = (hijri.year..hijri.year + 1).mapNotNull { year ->
            ramadanWindow(year, adjustmentDays)
        }
        return candidates.firstOrNull { it.contains(gregorian) }
            ?: candidates.firstOrNull { it.start.isAfter(gregorian) }
    }

    /** True when the selected Hijri settings place [gregorian] inside Ramadan. */
    fun isRamadan(gregorian: LocalDate, adjustmentDays: Int = 0): Boolean =
        dateOf(gregorian, adjustmentDays)?.isRamadan == true

    /**
     * Observances that fall on [hijri].
     *
     * Each is a lunar date, so the Gregorian date moves every year and is never stored as a fixed
     * date anywhere in Salah. The list is intentionally short: it contains only observances whose
     * date is a calendar datum rather than a matter of interpretation.
     */
    fun occasionsOn(hijri: HijriDate): List<IslamicOccasion> = when {
        hijri.month == HijriMonth.MUHARRAM && hijri.day == 1 -> listOf(IslamicOccasion.ISLAMIC_NEW_YEAR)
        hijri.month == HijriMonth.MUHARRAM && hijri.day == 10 -> listOf(IslamicOccasion.ASHURA)
        hijri.month == HijriMonth.RABI_AL_AWWAL && hijri.day == 12 -> listOf(IslamicOccasion.MAWLID)
        hijri.month == HijriMonth.RAMADAN && hijri.day == 1 -> listOf(IslamicOccasion.RAMADAN_BEGINS)
        hijri.month == HijriMonth.DHU_AL_HIJJAH && hijri.day == 9 -> listOf(IslamicOccasion.ARAFAH)
        hijri.month == HijriMonth.DHU_AL_HIJJAH && hijri.day == 10 -> listOf(IslamicOccasion.EID_AL_ADHA)
        hijri.month == HijriMonth.SHAWWAL && hijri.day == 1 -> listOf(IslamicOccasion.EID_AL_FITR)
        else -> emptyList()
    }

    private companion object {
        /** Ramadan is the ninth Hijri month. */
        const val RAMADAN_MONTH_NUMBER = 9
    }
}
