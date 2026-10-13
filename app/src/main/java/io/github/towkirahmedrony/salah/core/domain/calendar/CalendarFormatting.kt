package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.BanglaDate
import io.github.towkirahmedrony.salah.core.model.CalendarSystem
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.core.model.HijriDate
import io.github.towkirahmedrony.salah.core.model.HijriMonth
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Renders calendar dates as text, outside the UI.
 *
 * Kept out of the composables for the same reason `HomeUiStateFactory` formats times: a date string
 * is a decision (which script, which numerals, which name), and a decision that a test can assert is
 * worth more than one spread through a layout.
 *
 * ## Two questions, answered separately
 *
 * There are two independent choices in Salah, and this class keeps them apart:
 *
 *  - **Which calendar system** the calendar grid shows — Gregorian, Bangladesh Bangla or Hijri. That
 *    is the *data*: it decides which month, which days and which year the grid contains. It also
 *    decides the script the grid is written in, because a Bangla calendar written in Latin script and
 *    a Hijri calendar written in Latin script are not what those modes are for. See [gridLocale].
 *  - **Which date language** the side-by-side date list is written in — the Today card and the
 *    selected-day card show all three calendars at once, so there one script has to be chosen for all
 *    of them: [localeFor], driven by [DateLanguagePreference].
 *
 * ## Numerals
 *
 * Bangla and Hijri dates are composed here from the month identifier plus [numerals], rather than
 * handed to a platform formatter. Two reasons: the month name is part of the conversion *result*
 * (see `BanglaMonth` and `HijriMonth`), and composing the string guarantees the numerals always match
 * the month names. A half-localised date — Bangla month name with Western digits — is impossible by
 * construction. The Gregorian date does go through the platform's formatter, with the same chosen
 * locale, so it is localised as a unit by the platform and never mixes scripts either.
 */
class CalendarFormatting {

    /** The locale dates are rendered in for [preference] in the side-by-side date list. */
    fun localeFor(preference: DateLanguagePreference, systemDefault: Locale = Locale.getDefault()): Locale =
        when (preference) {
            DateLanguagePreference.SYSTEM -> systemDefault
            DateLanguagePreference.BANGLA -> Locale(BANGLA_LANGUAGE)
            DateLanguagePreference.ENGLISH -> Locale.ENGLISH
        }

    /**
     * The locale the calendar **grid and its heading** are rendered in for [system].
     *
     * The calendar system decides its own script: Gregorian follows the stored date language (so a
     * user who wants Bangla-script Gregorian dates still gets them), while the Bangla and Hijri modes
     * render in Bengali and Arabic respectively. That is what makes "select Bangla" produce a Bangla
     * calendar rather than an anglicised one, and it keeps the grid's script a function of the one
     * setting the user changed — the calendar mode.
     */
    fun gridLocale(
        system: CalendarSystem,
        dateLanguage: DateLanguagePreference,
        systemDefault: Locale = Locale.getDefault(),
    ): Locale = when (system) {
        CalendarSystem.GREGORIAN -> localeFor(dateLanguage, systemDefault)
        CalendarSystem.BANGLA -> Locale(BANGLA_LANGUAGE)
        CalendarSystem.HIJRI -> Locale(ARABIC_LANGUAGE)
    }

    /** True when [locale] renders dates in Bangla script and numerals. */
    fun isBangla(locale: Locale): Boolean = locale.language == BANGLA_LANGUAGE

    /** True when [locale] renders dates in Arabic script and Arabic-Indic numerals. */
    fun isArabic(locale: Locale): Boolean = locale.language == ARABIC_LANGUAGE

    /**
     * [value] in the numerals of [locale]'s script, with an explicit minus sign preserved.
     *
     * Three numeral systems are supported because the app renders in three scripts: Bangla (০–৯),
     * Arabic-Indic (٠–٩) and Western (0–9). The tables are explicit rather than delegated to a platform
     * formatter, but explicit tables, so the result is identical on every device regardless of locale
     * data completeness.
     */
    fun numerals(value: Int, locale: Locale): String = when (locale.language) {
        BANGLA_LANGUAGE -> BanglaNumerals.format(value)
        ARABIC_LANGUAGE -> ArabicNumerals.format(value)
        else -> value.toString()
    }

    /**
     * Localised Gregorian date, for example `14 April 2024` or `১৪ এপ্রিল ২০২৪`.
     *
     * Delegated to the platform's formatter with the explicitly chosen locale: the Gregorian calendar
     * is the platform's own, so its month names and numerals are already correct for every locale the
     * device supports.
     */
    fun gregorian(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern(GREGORIAN_PATTERN, locale).withLocale(locale))

    /** Localised weekday name, for example `Sunday` or `রবিবার`. */
    fun weekday(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern(WEEKDAY_PATTERN, locale).withLocale(locale))

    /** Month and year of a Gregorian month, for the calendar header. */
    fun gregorianMonth(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern(MONTH_PATTERN, locale).withLocale(locale))

    /** Short weekday label for the calendar header row. */
    fun weekdayShortLabel(dayOfWeek: DayOfWeek, locale: Locale): String =
        dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

    /** Localised Bangladesh Bangla date, for example `14 Boishakh 1431` or `১৪ বৈশাখ ১৪৩১`. */
    fun bangla(date: BanglaDate, locale: Locale): String {
        val month = if (isBangla(locale)) date.month.bengaliName else date.month.englishName
        return "${numerals(date.day, locale)} $month ${numerals(date.year, locale)}"
    }

    /** Month and year of a Bangla month, for the calendar heading, for example `আশ্বিন ১৪৩১`. */
    fun banglaMonth(date: BanglaDate, locale: Locale): String {
        val month = if (isBangla(locale)) date.month.bengaliName else date.month.englishName
        return "$month ${numerals(date.year, locale)}"
    }

    /** Localised Hijri date, for example `1 Ramadan 1447`, `১ রমজান ১৪৪৭` or `١ رمضان ١٤٤٧`. */
    fun hijri(date: HijriDate, locale: Locale): String =
        "${numerals(date.day, locale)} ${hijriMonthName(date.month, locale)} ${numerals(date.year, locale)}"

    /** Month and year of a Hijri month, for the calendar heading, for example `رَمَضان ١٤٤٥`. */
    fun hijriMonth(date: HijriDate, locale: Locale): String =
        "${hijriMonthName(date.month, locale)} ${numerals(date.year, locale)}"

    /** `1 Ramadan 1447 AH` — the era marker used where the calendar system must be unmistakable. */
    fun hijriWithEra(date: HijriDate, locale: Locale): String = "${hijri(date, locale)} $HIJRI_ERA"

    /** The month name in the script [locale] uses. */
    private fun hijriMonthName(month: HijriMonth, locale: Locale): String = when (locale.language) {
        BANGLA_LANGUAGE -> month.bengaliName
        ARABIC_LANGUAGE -> month.arabicName
        else -> month.englishName
    }

    companion object {
        /** Language subtag of Bangla. */
        const val BANGLA_LANGUAGE = "bn"

        /** Language subtag of Arabic. */
        const val ARABIC_LANGUAGE = "ar"

        /** Year suffix for Hijri dates. Latin script in both languages, as it is an era marker. */
        const val HIJRI_ERA = "AH"

        private const val GREGORIAN_PATTERN = "d MMMM yyyy"
        private const val WEEKDAY_PATTERN = "EEEE"
        private const val MONTH_PATTERN = "MMMM yyyy"
    }
}

/**
 * Bangla numerals (০–৯).
 *
 * A pure table rather than a platform call, so a Bangla number renders identically on every device
 * whether or not its locale data is complete, and so the conversion is unit-testable without a
 * locale.
 */
object BanglaNumerals {

    private val DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    /** [value] with each decimal digit replaced by its Bangla digit. A leading `-` is preserved. */
    fun format(value: Int): String = formatWithDigits(value, DIGITS)
}

/**
 * Arabic-Indic numerals (٠–٩).
 *
 * The same shape as [BanglaNumerals] and for the same reasons: the Hijri calendar mode is written in
 * Arabic script, and composing its numerals from a table keeps the digits and the month name in the
 * same script on every device.
 */
object ArabicNumerals {

    private val DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

    /** [value] with each decimal digit replaced by its Arabic-Indic digit. A leading `-` is preserved. */
    fun format(value: Int): String = formatWithDigits(value, DIGITS)
}

/** Shared digit-table substitution used by both numeral tables. */
private fun formatWithDigits(value: Int, digits: CharArray): String {
    val sign = if (value < 0) "-" else ""
    val decimal = value.toString().removePrefix("-")
    return sign + decimal.map { character ->
        val index = character - '0'
        if (index in digits.indices) digits[index] else character
    }.joinToString(separator = "")
}
