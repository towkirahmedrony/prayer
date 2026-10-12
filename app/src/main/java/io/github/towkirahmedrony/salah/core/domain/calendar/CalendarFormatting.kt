package io.github.towkirahmedrony.salah.core.domain.calendar

import io.github.towkirahmedrony.salah.core.model.BanglaDate
import io.github.towkirahmedrony.salah.core.model.DateLanguagePreference
import io.github.towkirahmedrony.salah.core.model.HijriDate
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
 * ## Which locale is used, and why it is not simply the device's
 *
 * The locale is derived from the stored [DateLanguagePreference], not read from the platform at each
 * call site. That is what makes "render dates in Bangla" a real setting rather than a suggestion the
 * device locale could silently override, and it means a date is formatted by exactly one rule
 * wherever it appears.
 *
 * ## Numerals
 *
 * Bangla and Hijri dates are composed here from the month identifier plus [numerals], rather than
 * handed to a platform formatter. Two reasons: the month name is part of the conversion *result*
 * (see `BanglaMonth`), and composing the string guarantees the numerals always match the month names.
 * A half-localised date — Bangla month name with Western digits — is impossible by construction. The
 * Gregorian date does go through the platform's formatter, with the same chosen locale, so it is
 * localised as a unit by the platform and never mixes scripts either.
 */
class CalendarFormatting {

    /** The locale dates are rendered in for [preference]. */
    fun localeFor(preference: DateLanguagePreference, systemDefault: Locale = Locale.getDefault()): Locale =
        when (preference) {
            DateLanguagePreference.SYSTEM -> systemDefault
            DateLanguagePreference.BANGLA -> Locale(BANGLA_LANGUAGE)
            DateLanguagePreference.ENGLISH -> Locale.ENGLISH
        }

    /** True when [locale] renders dates in Bangla script and numerals. */
    fun isBangla(locale: Locale): Boolean = locale.language == BANGLA_LANGUAGE

    /** [value] in the numerals of [locale]'s script, with an explicit minus sign preserved. */
    fun numerals(value: Int, locale: Locale): String =
        if (isBangla(locale)) BanglaNumerals.format(value) else value.toString()

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

    /** Localised Hijri date, for example `1 Ramadan 1447` or `১ রমজান ১৪৪৭`. */
    fun hijri(date: HijriDate, locale: Locale): String {
        val month = if (isBangla(locale)) date.month.bengaliName else date.month.englishName
        return "${numerals(date.day, locale)} $month ${numerals(date.year, locale)}"
    }

    /** `1 Ramadan 1447 AH` — the era marker used where the calendar system must be unmistakable. */
    fun hijriWithEra(date: HijriDate, locale: Locale): String = "${hijri(date, locale)} $HIJRI_ERA"

    companion object {
        /** Language subtag of Bangla. */
        const val BANGLA_LANGUAGE = "bn"

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
    fun format(value: Int): String {
        val sign = if (value < 0) "-" else ""
        val digits = value.toString().removePrefix("-")
        return sign + digits.map { digit ->
            val index = digit - '0'
            if (index in DIGITS.indices) DIGITS[index] else digit
        }.joinToString(separator = "")
    }
}
