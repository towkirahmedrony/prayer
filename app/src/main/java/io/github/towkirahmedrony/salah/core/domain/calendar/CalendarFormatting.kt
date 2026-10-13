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
 *    is the *data*: it decides which month, which days and which year the grid contains. It does *not*
 *    pin the grid's script — the Gregorian and Hijri grids are written in the interface language (or an
 *    explicit date-language preference), so a Bengali interface shows `রমজান ১৪৪৫` and an English one
 *    shows `Ramadan 1445`. Only the Bangla mode pins Bengali, because its month names are the
 *    calendar's own rather than a rendering of a Gregorian month. See [gridLocale].
 *  - **Which date language** the side-by-side date list is written in — the Today card and the
 *    selected-day card show all three calendars at once, so there one script has to be chosen for all
 *    of them: [localeFor], driven by [DateLanguagePreference].
 *
 * ## `systemDefault` is the interface language
 *
 * [DateLanguagePreference.SYSTEM]'s branch of [localeFor], and the Gregorian and Hijri branches of
 * [gridLocale], do not read the device locale from inside this class: the caller passes the locale the
 * interface is currently written in as [systemDefault]. The calendar view model passes `AppLanguage`'s locale, so
 * "follow the interface" really follows the app's language selector instead of the phone's setting —
 * which is the difference between a Bengali interface showing Bengali month names and a Bengali
 * interface showing Latin ones. A caller that passes nothing gets `Locale.getDefault()`, which is the
 * pre-selector behaviour and is what the domain-level tests pin.
 *
 * ## Numerals
 *
 * Bangla and Hijri dates are composed here from the month identifier plus [numerals], rather than
 * handed to a platform formatter. Two reasons: the month name is part of the conversion *result*
 * (see `BanglaMonth` and `HijriMonth`), and composing the string guarantees the numerals always match
 * the month names. A half-localised date — Bangla month name with Western digits — is impossible by
 * construction.
 *
 * The Gregorian date does go through the platform's formatter, which is what gives it the locale's
 * own month names, word order and era suffix, but the platform is not trusted with the *digits*: the
 * formatted text is passed through [localizeDigits] so the numbers are written in the same script as
 * the names beside them. That is not belt-and-braces. `java.util` on the JVM renders `bn` with
 * Western digits (`মার্চ 2024`) even though the month name is Bengali, and Android's ICU renders the
 * same locale with Bengali ones, so without this step a Bengali calendar would show a Bengali month
 * heading over Latin year digits above a grid of Bengali day numbers. Substituting is idempotent —
 * text that is already in the right numerals is left alone — so both platforms end up identical.
 */
class CalendarFormatting {

    /**
     * The locale dates are rendered in for [preference] in the side-by-side date list.
     *
     * [systemDefault] is the locale the caller's interface is written in — the calendar screen passes
     * the selected [io.github.towkirahmedrony.salah.core.model.AppLanguage]'s locale — so that
     * "follow the device" follows the *app's* language, not the phone's. The parameter is still named
     * for the device because that is what [systemDefault] defaults to when a caller has no interface
     * language to offer: the domain tests and the Home snapshot path.
     */
    fun localeFor(preference: DateLanguagePreference, systemDefault: Locale = Locale.getDefault()): Locale =
        when (preference) {
            DateLanguagePreference.SYSTEM -> systemDefault
            DateLanguagePreference.BANGLA -> Locale(BANGLA_LANGUAGE)
            DateLanguagePreference.ENGLISH -> Locale.ENGLISH
        }

    /**
     * The locale the calendar **grid and its heading** are rendered in for [system].
     *
     * The calendar system decides *which* month is shown; the interface language decides *the words*.
     * The Gregorian and Hijri grids both defer to the stored date language — whose
     * [DateLanguagePreference.SYSTEM] branch follows [systemDefault], the interface language — so a
     * Bengali interface shows a Bengali Hijri month (`রমজান ১৪৪৫`) and an English one shows an English
     * Hijri month (`Ramadan 1445`). This is what makes the Hijri mode follow the language selector
     * instead of always rendering in Arabic script.
     *
     * The Bangla branch is the one exception, and it is not an oversight: that mode exists to present
     * the Bangladeshi civil calendar in its own script, and its month names (`বৈশাখ`) are the
     * calendar's own rather than an alternative rendering of a Gregorian month, so there is no second
     * language to render them in. The stored [dateLanguage] still wins for the other two grids, because
     * that setting is a deliberate statement about the dates themselves — an explicit `ENGLISH` gives
     * `Ramadan 1445` even on a Bengali phone, and an explicit `BANGLA` gives `রমজান ১৪৪৫` on an English
     * one.
     */
    fun gridLocale(
        system: CalendarSystem,
        dateLanguage: DateLanguagePreference,
        systemDefault: Locale = Locale.getDefault(),
    ): Locale = when (system) {
        CalendarSystem.GREGORIAN -> localeFor(dateLanguage, systemDefault)
        CalendarSystem.BANGLA -> Locale(BANGLA_LANGUAGE)
        CalendarSystem.HIJRI -> localeFor(dateLanguage, systemDefault)
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
     * is the platform's own, so its month names and word order are already correct for every locale
     * the device supports. The digits are the one part not left to it — see [localizeDigits].
     */
    fun gregorian(date: LocalDate, locale: Locale): String = localizeDigits(
        date.format(DateTimeFormatter.ofPattern(GREGORIAN_PATTERN, locale).withLocale(locale)),
        locale,
    )

    /** Localised weekday name, for example `Sunday` or `রবিবার`. */
    fun weekday(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern(WEEKDAY_PATTERN, locale).withLocale(locale))

    /**
     * Month and year of a Gregorian month, for the calendar header.
     *
     * `অক্টোবর ২০২৬` in Bengali and `October 2026` in English. The year goes through the same
     * [localizeDigits] step as [gregorian], because this is the string that sits directly above the
     * grid's day numbers: a Bengali calendar whose heading read `অক্টোবর 2026` over cells reading
     * `১ ২ ৩` would be mixing two numeral systems within one screen.
     */
    fun gregorianMonth(date: LocalDate, locale: Locale): String = localizeDigits(
        date.format(DateTimeFormatter.ofPattern(MONTH_PATTERN, locale).withLocale(locale)),
        locale,
    )

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

    /**
     * Month and year of a Hijri month, for the calendar heading, for example `Ramadan 1445` or
     * `রমজান ১৪৪৫` — the heading follows the rendering locale like every other calendar label.
     */
    fun hijriMonth(date: HijriDate, locale: Locale): String =
        "${hijriMonthName(date.month, locale)} ${numerals(date.year, locale)}"

    /** `1 Ramadan 1447 AH` — the era marker used where the calendar system must be unmistakable. */
    fun hijriWithEra(date: HijriDate, locale: Locale): String = "${hijri(date, locale)} $HIJRI_ERA"

    /**
     * [text] with every Western decimal digit written in [locale]'s own numerals.
     *
     * Only the digits the platform left in Western form are replaced, and Bengali and Arabic-Indic
     * digits are already outside `'0'..'9'`, so a string that arrives correctly localised is returned
     * unchanged. That is what makes this safe to apply to platform output on both Android and the JVM.
     */
    private fun localizeDigits(text: String, locale: Locale): String = when (locale.language) {
        BANGLA_LANGUAGE -> BanglaNumerals.substitute(text)
        ARABIC_LANGUAGE -> ArabicNumerals.substitute(text)
        else -> text
    }

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
    fun format(value: Int): String = substitute(value.toString())

    /**
     * [text] with each Western digit replaced by its Bangla digit, and everything else left as it is.
     *
     * The same table as [format], applied to a string the platform formatted rather than to a number:
     * a month heading keeps its month name, its spaces and its punctuation and only changes script.
     */
    fun substitute(text: String): String = substituteDigits(text, DIGITS)
}

/**
 * Arabic-Indic numerals (٠–٩).
 *
 * The same shape as [BanglaNumerals] and for the same reasons: composing the digits from a table keeps
 * them in the same script as the month name beside them on every device. This table is chosen only for a
 * genuinely Arabic rendering locale — the Hijri *mode* is localised like the other modes, so an English
 * or Bengali calendar uses the Western or Bengali table and an Arabic-Indic digit never leaks into it.
 * See [CalendarFormatting.gridLocale].
 */
object ArabicNumerals {

    private val DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

    /** [value] with each decimal digit replaced by its Arabic-Indic digit. A leading `-` is preserved. */
    fun format(value: Int): String = substitute(value.toString())

    /** [text] with each Western digit replaced by its Arabic-Indic digit, everything else untouched. */
    fun substitute(text: String): String = substituteDigits(text, DIGITS)
}

/**
 * Shared digit-table substitution used by both numeral tables.
 *
 * Character by character rather than number by number, so it can be applied to a date the platform
 * formatted: every Western digit becomes the table's digit and every other character — a `-`, a
 * space, a Bengali letter, an Arabic letter, a Bengali digit already in place — passes through.
 */
private fun substituteDigits(text: String, digits: CharArray): String = text.map { character ->
    val index = character - '0'
    if (index in digits.indices) digits[index] else character
}.joinToString(separator = "")
