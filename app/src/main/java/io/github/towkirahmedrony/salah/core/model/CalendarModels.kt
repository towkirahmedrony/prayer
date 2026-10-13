package io.github.towkirahmedrony.salah.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * The twelve months of the Bangladesh (Bangla) civil calendar.
 *
 * Both names are carried on the constant rather than looked up from resources, and that is
 * deliberate: a month name is part of the *conversion result*, not decoration on top of it. Salah
 * can render a Bangla date in Bangla or in Latin script regardless of the device's locale, which is
 * what the calendar display-language setting needs. Every other string in the calendar feature is a
 * string resource; see docs/CALENDAR_SYSTEM.md §7.
 *
 * The declaration order is the civil year order and is used to walk month lengths, so it must not be
 * reordered.
 */
enum class BanglaMonth(val englishName: String, val bengaliName: String) {
    BOISHAKH("Boishakh", "বৈশাখ"),
    JYAISTHA("Jyaistha", "জ্যৈষ্ঠ"),
    ASHADH("Ashadh", "আষাঢ়"),
    SHRABAN("Shraban", "শ্রাবণ"),
    BHADRA("Bhadra", "ভাদ্র"),
    ASHWIN("Ashwin", "আশ্বিন"),
    KARTIK("Kartik", "কার্তিক"),
    AGRAHAYAN("Agrahayan", "অগ্রহায়ণ"),
    POUSH("Poush", "পৌষ"),
    MAGH("Magh", "মাঘ"),
    FALGUN("Falgun", "ফাল্গুন"),
    CHAITRA("Chaitra", "চৈত্র"),
}

/**
 * A date in the Bangladesh (Bangla) civil calendar as defined by the 2019 Bangla Academy revision.
 *
 * This type carries no zone: the Bangla calendar is a civil calendar tied to a Gregorian day, and
 * the Gregorian day is decided (in the location's or device's zone) before the conversion happens.
 */
data class BanglaDate(
    val year: Int,
    val month: BanglaMonth,
    val day: Int,
) {
    /** 1-based month number inside the Bangla year. */
    val monthNumber: Int get() = month.ordinal + 1
}

/**
 * The twelve months of the Hijri (Islamic) lunar year. Declaration order is the year order.
 *
 * Each month carries its name in three scripts for the same reason `BanglaMonth` carries two: a month
 * name is part of the *conversion result* the Hijri calendar mode renders, so it cannot depend on the
 * device's locale data. `arabicName` is what the Hijri calendar mode shows, since that mode presents
 * the Islamic calendar in its own script; see docs/CALENDAR_IMPLEMENTATION.md.
 */
enum class HijriMonth(val englishName: String, val bengaliName: String, val arabicName: String) {
    MUHARRAM("Muharram", "মুহাররম", "مُحَرَّم"),
    SAFAR("Safar", "সফর", "صَفَر"),
    RABI_AL_AWWAL("Rabi' al-Awwal", "রবিউল আউয়াল", "رَبيع الأوّل"),
    RABI_AL_THANI("Rabi' al-Thani", "রবিউস সানি", "رَبيع الآخر"),
    JUMADA_AL_AWWAL("Jumada al-Awwal", "জমাদিউল আউয়াল", "جُمادى الأولى"),
    JUMADA_AL_THANI("Jumada al-Thani", "জমাদিউস সানি", "جُمادى الآخِرة"),
    RAJAB("Rajab", "রজব", "رَجَب"),
    SHABAN("Sha'ban", "শাবান", "شَعْبان"),
    RAMADAN("Ramadan", "রমজান", "رَمَضان"),
    SHAWWAL("Shawwal", "শাওয়াল", "شَوّال"),
    DHU_AL_QADAH("Dhu al-Qa'dah", "জিলকদ", "ذو القَعْدة"),
    DHU_AL_HIJJAH("Dhu al-Hijjah", "জিলহজ", "ذو الحِجّة"),
    ;

    /** 1-based month number inside the Hijri year. */
    val monthNumber: Int get() = ordinal + 1

    companion object {
        /** The month with 1-based [number], or `null` when no such month exists. */
        fun ofNumber(number: Int): HijriMonth? = entries.getOrNull(number - 1)
    }
}

/**
 * Where a displayed Hijri date came from.
 *
 * There are exactly two states today, and neither of them is a claim that Bangladesh's religious
 * authorities have confirmed the date. Salah has **no** integrated authority source, so it cannot
 * produce — and therefore cannot display — an authority-confirmed Hijri date. If one is ever
 * integrated, this enum gains a third constant and only that source may set it.
 */
enum class HijriDateSource {
    /** Estimated from the Umm al-Qura table published with the platform (java.time). */
    ESTIMATED_UMM_AL_QURA,

    /** The estimate above, shifted by the day offset the user configured. */
    ESTIMATED_USER_ADJUSTED,
}

/**
 * A date in the Hijri (Islamic) calendar.
 *
 * [source] travels with the value so a date can never reach the UI without the label that says
 * whether it is the raw estimate or the user's own shifted estimate.
 */
data class HijriDate(
    val year: Int,
    val month: HijriMonth,
    val day: Int,
    val source: HijriDateSource,
) {
    val isRamadan: Boolean get() = month == HijriMonth.RAMADAN
}

/**
 * Islamic observances Salah can derive from the Hijri calendar.
 *
 * Every one of these is *derived from a lunar date*, never stored as a fixed Gregorian date, and the
 * UI labels each as an estimate because local moon sighting may move it by a day. Nothing here is
 * presented as a confirmed public holiday.
 *
 * Deliberately absent: Laylat al-Qadr. Its date is a matter of religious interpretation rather than
 * a calendar datum, so Salah does not pin it to a day.
 */
enum class IslamicOccasion {
    ISLAMIC_NEW_YEAR,
    ASHURA,
    MAWLID,
    RAMADAN_BEGINS,
    ARAFAH,
    EID_AL_ADHA,
    EID_AL_FITR,
}

/** Which script and numerals calendar dates are rendered in. */
enum class DateLanguagePreference {
    /** Follow the device locale, Salah's default everywhere else. */
    SYSTEM,

    /** Bangla month names and Bangla numerals. */
    BANGLA,

    /** Latin-script month names and Western Arabic numerals. */
    ENGLISH,
}

/**
 * The calendar preferences the user can change, persisted in the shared DataStore.
 *
 * A flat, immutable object for the same reason [PrayerSettings] is: it is stored as a unit and the
 * repository can always produce one, so nothing downstream has to handle a missing value.
 */
data class CalendarSettings(
    /** The calendar Salah's Calendar screen opens on. */
    val preferredSystem: CalendarSystem = CalendarSystem.GREGORIAN,

    /**
     * Days added to the calculated Hijri date.
     *
     * Salah's Hijri date is an estimate from the Umm al-Qura table, and local moon sighting can move
     * an observance by a day. This offset lets the user align Salah with whatever their community
     * follows, without Salah pretending to know the announcement. Clamped to
     * `-[MAX_HIJRI_ADJUSTMENT_DAYS]..[MAX_HIJRI_ADJUSTMENT_DAYS]`.
     */
    val hijriAdjustmentDays: Int = 0,

    /** Which language and numeral system calendar dates are rendered in. */
    val dateLanguage: DateLanguagePreference = DateLanguagePreference.SYSTEM,
) {

    /** A copy with the Hijri offset moved by [days], clamped to the supported range. */
    fun shiftHijriAdjustment(days: Int): CalendarSettings =
        copy(hijriAdjustmentDays = clampHijriAdjustment(hijriAdjustmentDays + days))

    companion object {
        /** Largest Hijri day offset Salah accepts, in either direction. */
        const val MAX_HIJRI_ADJUSTMENT_DAYS: Int = 2

        /** The preferences a fresh install starts from: estimated dates, no offset. */
        val DEFAULT: CalendarSettings = CalendarSettings()

        /** Fixes an out-of-range stored offset so the UI can always represent it. */
        fun clampHijriAdjustment(days: Int): Int =
            days.coerceIn(-MAX_HIJRI_ADJUSTMENT_DAYS, MAX_HIJRI_ADJUSTMENT_DAYS)
    }
}

/**
 * One day of the calendar grid, with every representation already resolved.
 *
 * Conversion is done by the repository, not by the composable: the UI receives an immutable value
 * and only decides how to lay it out.
 */
data class CalendarDay(
    val date: LocalDate,
    val isToday: Boolean,
    /** `null` when the date falls outside the verified Bangladesh-calendar range. */
    val bangla: BanglaDate?,
    /** `null` when the platform's Hijri table does not cover this date. */
    val hijri: HijriDate?,
    val occasions: List<IslamicOccasion>,
    /** 1-based day of Ramadan, or `null` when the date is not in Ramadan. */
    val ramadanDayNumber: Int?,
)

/**
 * One month of the calendar grid.
 *
 * [weeks] is the layout: seven entries per row, `null` where the row is padded. Building the layout
 * here rather than in the UI keeps "which weekday does this month start on" testable without a
 * device.
 */
data class CalendarMonth(
    val yearMonth: YearMonth,
    val weeks: List<List<CalendarDay?>>,
    /**
     * Today, in the same zone the grid was built in.
     *
     * Carried here rather than read again by the consumer so the whole month — including which cell is
     * "today" and what today's other calendars say — is one consistent snapshot. It is meaningful even
     * when the user has navigated to another month, which is exactly when the header still needs it.
     */
    val today: LocalDate,

    /**
     * The Ramadan window containing today, or the next one after it, or `null` when the platform's
     * Hijri table does not cover either.
     *
     * Part of the month snapshot rather than looked up by the screen so that "which days are in
     * Ramadan" and "what is the Ramadan range" are answered from one value, and a day's Ramadan number
     * is always consistent with the range shown beside it.
     */
    val ramadanWindow: RamadanWindow?,

    /**
     * The weekday the first *column* of the grid represents.
     *
     * Carried here rather than inferred from the first real day, because a month rarely starts on the
     * week's first day: deriving column headers from the first day of the month would shift every
     * header by that many columns. The grid lays the cells out with this value and the header row is
     * built from the same value, so the two cannot disagree.
     */
    val firstDayOfWeek: DayOfWeek,
) {
    /** Every real day of the month, in order. */
    val days: List<CalendarDay> get() = weeks.flatten().mapNotNull { it }
}

/**
 * A Ramadan period, with the Gregorian range the selected Hijri settings put it in.
 *
 * [source] is always an estimate today: the range comes from the Umm al-Qura table (optionally
 * shifted by the user's offset), not from an announcement by Bangladesh's religious authorities.
 * Salah has no authority source integrated, so it never claims `confirmed`.
 */
data class RamadanWindow(
    /** The Hijri year whose Ramadan this is. */
    val hijriYear: Int,
    /** First Gregorian day of Ramadan, inclusive. */
    val start: LocalDate,
    /** Last Gregorian day of Ramadan, inclusive. */
    val end: LocalDate,
    val source: HijriDateSource,
) {
    /** Every Gregorian date in the window, in order. */
    val dates: List<LocalDate>
        get() = generateSequence(start) { current ->
            if (current < end) current.plusDays(1) else null
        }.toList()

    val dayCount: Int get() = dates.size

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    /** 1-based Ramadan day for [date], or `null` when the date is outside the window. */
    fun dayNumber(date: LocalDate): Int? =
        if (!contains(date)) null else (date.toEpochDay() - start.toEpochDay()).toInt() + 1

    /**
     * How [today] relates to this window.
     *
     * Boundaries are inclusive: the first and last day of Ramadan are both
     * [RamadanPhase.IN_RAMADAN], and the day after the end is [RamadanPhase.FAR_AWAY] rather than
     * `UPCOMING`, because the next Ramadan is a year away.
     */
    fun phase(today: LocalDate): RamadanPhase = when {
        contains(today) -> RamadanPhase.IN_RAMADAN
        today.isBefore(start) &&
            ChronoUnit.DAYS.between(today, start) <= UPCOMING_WINDOW_DAYS -> RamadanPhase.UPCOMING
        else -> RamadanPhase.FAR_AWAY
    }

    companion object {
        /** How far ahead Salah calls the next Ramadan "upcoming". */
        const val UPCOMING_WINDOW_DAYS: Long = 60
    }
}

/** How close to Ramadan the Calendar and Ramadan screens consider "now". */
enum class RamadanPhase {
    /** Today is inside the estimated Ramadan range. */
    IN_RAMADAN,

    /** Today is before the estimated start, within the next 60 days. */
    UPCOMING,

    /** The next estimated Ramadan is more than 60 days away. */
    FAR_AWAY,
}
