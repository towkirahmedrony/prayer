package io.github.towkirahmedrony.salah.core.model

import java.util.Locale

/**
 * The language Salah's interface is written in.
 *
 * **A preference, not a build-time choice.** Before this existed Salah resolved its strings from the
 * device locale alone, so a user whose phone was set to English had no way to read the interface in
 * Bengali, and the Bangla translation was only ever reachable by changing the whole device. The
 * selector on the General settings screen writes this value, and the shell applies it to every screen.
 *
 * **Separate from every calendar setting.** This decides the words; it decides no date. Which calendar
 * the Calendar screen shows is [CalendarSystem], the script and numerals dates are written in is
 * [DateLanguagePreference], and the Hijri offset is [CalendarSettings.hijriAdjustmentDays]. Selecting
 * Bengali here therefore cannot move a date, change a calendar system or reschedule a reminder — see
 * `docs/LOCALIZATION.md`.
 *
 * **[SYSTEM] is the default, and it is the previous behaviour.** With no stored preference the app
 * resolves strings exactly as it did before this preference existed: from the device's own locale.
 * That is why no existing install changes language on upgrade.
 *
 * The persisted value is the enum's [name], the same convention every other Salah preference uses, so
 * a store written by a future version carrying a language this build does not know falls back to
 * [DEFAULT] rather than failing. [languageTag] is the BCP-47 subtag a locale is built from and never a
 * display label: the labels live in string resources, so a translated label can never be stored as the
 * preference.
 */
enum class AppLanguage(val languageTag: String?) {

    /** Follow the device's own locale, which is what Salah did before a selector existed. */
    SYSTEM(null),

    /** English interface text. */
    ENGLISH("en"),

    /** Bangladeshi Bengali interface text. */
    BANGLA("bn"),
    ;

    /**
     * The locale this language renders in, or `null` when it follows the device.
     *
     * The `null` is what keeps [SYSTEM] genuinely hands-off: callers fall back to `Locale.getDefault()`
     * rather than to a pinned locale, so a device set to Bengali still gets Bengali without the user
     * choosing anything.
     */
    fun locale(): Locale? = languageTag?.let(Locale::forLanguageTag)

    companion object {

        /** The language a fresh install starts on, and the fallback for an unknown stored value. */
        val DEFAULT: AppLanguage = SYSTEM

        /**
         * The language whose persisted [name] is [identifier], or [DEFAULT] when there is none.
         *
         * Accepts `null` because a preference that has never been written reads as absent, and an
         * unknown name because a store written by another version must not make the settings screen
         * unusable.
         */
        fun fromIdentifier(identifier: String?): AppLanguage =
            entries.firstOrNull { it.name == identifier } ?: DEFAULT
    }
}
