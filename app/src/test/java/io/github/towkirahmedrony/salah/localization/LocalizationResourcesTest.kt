package io.github.towkirahmedrony.salah.localization

import io.github.towkirahmedrony.salah.core.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Bengali resource file, checked as a file.
 *
 * The Calendar screen is localised through Android string resources, so "the Calendar is in Bengali" is
 * a statement about two XML files agreeing — and that is exactly the kind of statement a compiler
 * cannot check: a missing key compiles, ships, and silently falls back to English at runtime. Android
 * lint notices `MissingTranslation`, but this repository answers that check with an ignore (the
 * translation is deliberately partial for the *explanatory* wording), so lint would stay quiet about a
 * Calendar string that was forgotten too.
 *
 * This test closes that gap for the one screen the policy does not allow to be partial: every
 * `calendar_*` key the English file defines must have a Bengali value, the Bengali values must be
 * non-blank and carry Bengali script, and no Bengali key may be an orphan the default does not define
 * (which is what `ExtraTranslation` would reject, and what a renamed key leaves behind).
 *
 * It reads the real files from `app/src/main/res`, found by walking up from the working directory, so
 * it checks what ships rather than a copy. No Android API is involved: the files are parsed with a
 * plain regex over the `<string name="…">value</string>` shape these files use.
 *
 * The same treatment was later extended to the two surfaces this change localised: **Tools** and
 * **Settings**. Every `tools_*`, `settings_*`, calculation-option and location string, and every
 * notification string, is asserted to have a non-blank Bengali value written in Bengali script, with
 * its format arguments intact — and a scan of the Tools, Settings, Notifications, Location, Qibla and
 * Ramadan composables asserts none of them renders a hardcoded sentence. Home, the Calendar, Tools and
 * every Settings screen are therefore localised in full, not partially.
 */
class LocalizationResourcesTest {

    @Test
    fun `the Bengali resources live in the folder the Bengali language identifies`() {
        // The identifier in the preference and the resource qualifier are the same string. If they ever
        // diverge, selecting Bengali would switch to a folder Android never looks in.
        assertEquals("bn", AppLanguage.BANGLA.languageTag)

        val bengaliFile = File(resourceDirectory(), "values-${AppLanguage.BANGLA.languageTag}/strings.xml")
        assertTrue(
            "the Bengali resource file is missing: ${bengaliFile.absolutePath}",
            bengaliFile.isFile,
        )
    }

    @Test
    fun `every Calendar string the English resources define has a Bengali translation`() {
        val english = englishStrings()
        val calendarKeys = english.keys.filter { it.startsWith("calendar_") }

        assertTrue("the English resources define no calendar strings at all", calendarKeys.isNotEmpty())

        val bengali = bengaliStrings()
        val missing = calendarKeys.filterNot { bengali.containsKey(it) }.sorted()

        assertTrue(
            "the Calendar screen is not fully localised; these keys have no Bengali value: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the Calendar screen's shared states are available in Bengali too`() {
        // The loading, error and retry text and the out-of-range explanation are shared resources
        // rather than calendar_-prefixed ones, so the prefix check above cannot cover them.
        val bengali = bengaliStrings()

        listOf(
            "state_loading",
            "state_error_title",
            "action_retry",
            "action_back",
            "reason_calendar_out_of_range",
            "reason_calendar_not_implemented",
        ).forEach { key ->
            assertTrue("$key has no Bengali value", bengali.containsKey(key))
        }
    }

    @Test
    fun `no Bengali Calendar value is blank`() {
        bengaliStrings()
            .filterKeys { it.startsWith("calendar_") }
            .forEach { (key, value) ->
                assertTrue("$key has an empty Bengali value", value.isNotBlank())
            }
    }

    @Test
    fun `the labels a reader navigates with are actually written in Bengali script`() {
        // Non-blank is not enough: an English sentence pasted under a Bengali key would pass the check
        // above and still leave the screen half in English.
        val bengali = bengaliStrings()

        listOf(
            "nav_calendar",
            "calendar_today_label",
            "calendar_previous_month",
            "calendar_next_month",
            "calendar_go_to_today",
            "calendar_selected_day",
            "settings_language_title",
        ).forEach { key ->
            val value = bengali.getValue(key)
            assertTrue(
                "$key is still written in Latin script: $value",
                value.any { character -> character.inBengaliBlock() },
            )
        }
    }

    @Test
    fun `the Home and navigation labels the language preference switches are available in Bengali`() {
        // The language preference drives Home and the bottom bar as well as the Calendar, so the
        // strings that make that switch visible have to exist; otherwise "select Bengali" would look
        // like it did nothing at all.
        val bengali = bengaliStrings()

        listOf(
            "nav_home",
            "nav_settings",
            "home_current_prayer_title",
            "home_gregorian_label",
            "prayer_fajr",
        ).forEach { key ->
            assertTrue("$key has no Bengali value, so Home would not change language", bengali.containsKey(key))
        }
    }

    @Test
    fun `the language selector's own labels exist in both languages`() {
        val english = englishStrings()
        val bengali = bengaliStrings()

        listOf(
            "settings_language_title",
            "settings_language_body",
            "settings_language_system",
            "settings_language_english",
            "settings_language_bangla",
            "settings_language_save_failed",
        ).forEach { key ->
            assertTrue("$key is missing from the English resources", english.containsKey(key))
            assertTrue("$key is missing from the Bengali resources", bengali.containsKey(key))
            assertTrue("$key has an empty English value", english.getValue(key).isNotBlank())
            assertTrue("$key has an empty Bengali value", bengali.getValue(key).isNotBlank())
        }
    }

    @Test
    fun `no Bengali resource is an orphan the English default does not define`() {
        val orphans = (bengaliStrings().keys - englishStrings().keys).sorted()

        assertTrue(
            "these Bengali strings have no English counterpart, which lint rejects as ExtraTranslation: $orphans",
            orphans.isEmpty(),
        )
    }

    @Test
    fun `a Bengali Calendar string keeps the format arguments of its English original`() {
        // A translation that drops or renumbers a placeholder is the failure that reaches production
        // as a crash (IllegalFormatException) rather than as a missing word, and lint's format checks do
        // not compare a string against its translation.
        val english = englishStrings()
        val bengali = bengaliStrings()

        english
            .filterKeys { it.startsWith("calendar_") }
            .forEach { (key, value) ->
                val translated = bengali[key] ?: return@forEach
                assertEquals(
                    "$key changes its format arguments in Bengali",
                    formatArguments(value),
                    formatArguments(translated),
                )
            }
    }

    // --- Home: the second screen localised in full -------------------------------

    @Test
    fun `every string the Home screen can render has a Bengali translation`() {
        // The Home composition tree plus the prayer screen its two actions open. Home is localised in
        // full, so a key added to `values/` for one of these components and forgotten here is a
        // regression this test is for: Android would fall back to English silently, and the
        // `MissingTranslation` ignore in app/lint.xml would stay quiet about it.
        val bengali = bengaliStrings()
        val missing = HOME_STRINGS.filterNot { bengali.containsKey(it) }.sorted()

        assertTrue(
            "the Home screen is not fully localised; these keys have no Bengali value: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `no Home string has an empty Bengali value`() {
        val bengali = bengaliStrings()
        val blank = HOME_STRINGS.filter { bengali[it]?.isBlank() == true }.sorted()

        assertTrue("these Home strings have an empty Bengali value: $blank", blank.isEmpty())
    }

    @Test
    fun `the Home screen's own words are written in Bengali script`() {
        // Non-blank is not enough: an English sentence pasted under a Bengali key would leave the
        // screen it was meant to localise exactly as it was. The punctuation-only keys are excluded
        // because they hold a separator and placeholders rather than words, and legitimately translate
        // to themselves.
        val bengali = bengaliStrings()
        val latinOnly = HOME_STRINGS
            .filterNot { it in HOME_PUNCTUATION_ONLY }
            // `orEmpty` rather than `getValue`: an absent key is reported by the completeness test
            // above, and this one fails on the key by name instead of throwing from here.
            .filter { key -> bengali[key].orEmpty().none { character -> character.inBengaliBlock() } }

        assertTrue(
            "these Home strings are still written in Latin script: $latinOnly",
            latinOnly.isEmpty(),
        )
    }

    @Test
    fun `a Bengali Home string keeps the format arguments of its English original`() {
        // Same failure mode as the Calendar one, and just as invisible to lint: a dropped or renumbered
        // placeholder reaches production as an `IllegalFormatException`, not as a missing word.
        val english = englishStrings()
        val bengali = bengaliStrings()

        HOME_STRINGS.forEach { key ->
            val original = english[key] ?: return@forEach
            val translated = bengali[key] ?: return@forEach
            assertEquals(
                "$key changes its format arguments in Bengali",
                formatArguments(original),
                formatArguments(translated),
            )
        }
    }

    @Test
    fun `no Home composable renders a hardcoded sentence`() {
        // The audit that produced HOME_STRINGS found its remaining English in three places: labels
        // pasted straight into a composable, keys with no Bengali value, and one sentence assembled by
        // the state factory. Resources now cover all three, and this is the guard that keeps the first
        // of them from coming back: a sentence is a literal containing a space, and no Composable under
        // `feature/home` may render one.
        //
        // The three Android-free files of the package are excluded on purpose — they hold identifiers
        // (navigation routes, `LazyColumn` item keys, start-up trace marks), never rendered text — and
        // the check reads code only, with comments stripped, because the package is documented heavily
        // and its prose quotes the very labels the check is about.
        val offenders = homeSourceFiles().flatMap { file ->
            codeLiterals(file.readText())
                .filter { literal -> literal.contains(' ') }
                .map { literal -> "${file.name}: \"$literal\"" }
        }

        assertTrue(
            "these Home composables render a hardcoded sentence instead of a string resource: $offenders",
            offenders.isEmpty(),
        )
    }

    // --- Tools and Settings: the surfaces completed after Home and the Calendar -----

    @Test
    fun `every Tools and Settings string the English resources define has a Bengali translation`() {
        // A prefix rather than a hand-listed set, so a string added to the Tools or Settings screens
        // without a Bengali value fails here instead of silently falling back to English. The prefixes
        // cover the Tools page and its destination screens, the Settings overview and every category
        // it opens, the calculation options, the notification strings and the location strings.
        val scoped = scopedToolsAndSettingsKeys()

        assertTrue(
            "the English resources define no Tools or Settings strings at all",
            scoped.isNotEmpty(),
        )

        val bengali = bengaliStrings()
        val missing = scoped.filterNot { bengali.containsKey(it) }.sorted()

        assertTrue(
            "the Tools and Settings screens are not fully localised; these keys have no Bengali " +
                "value: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `no Tools or Settings string is blank in Bengali`() {
        val bengali = bengaliStrings()
        val blank = scopedToolsAndSettingsKeys().filter { bengali[it]?.isBlank() == true }.sorted()

        assertTrue("these Tools or Settings strings have an empty Bengali value: $blank", blank.isEmpty())
    }

    @Test
    fun `the Tools and Settings words are written in Bengali script`() {
        // Non-blank is not enough: an English sentence pasted under a Bengali key would leave the
        // screen it was meant to localise exactly as it was. The three keys that legitimately carry no
        // Bengali words are excluded — they hold a format placeholder, a separator or a language's own
        // name (`English`), so demanding Bengali script in them would fail a correct translation.
        val bengali = bengaliStrings()
        val latinOnly = scopedToolsAndSettingsKeys()
            .filterNot { it in TOOLS_SETTINGS_LATIN_ONLY }
            .filter { key -> bengali[key].orEmpty().none { character -> character.inBengaliBlock() } }

        assertTrue(
            "these Tools or Settings strings are still written in Latin script: $latinOnly",
            latinOnly.isEmpty(),
        )
    }

    @Test
    fun `a Bengali Tools or Settings string keeps the format arguments of its English original`() {
        // The same failure mode as the Calendar and Home checks: a dropped or renumbered placeholder
        // reaches production as an `IllegalFormatException` rather than as a missing word.
        val english = englishStrings()
        val bengali = bengaliStrings()

        scopedToolsAndSettingsKeys().forEach { key ->
            val original = english[key] ?: return@forEach
            val translated = bengali[key] ?: return@forEach
            assertEquals(
                "$key changes its format arguments in Bengali",
                formatArguments(original),
                formatArguments(translated),
            )
        }
    }

    @Test
    fun `the Tools page's own strings exist in both languages`() {
        // The Tools screen is a list of destinations, so its own copy is small and stable: the heading,
        // the one-line description, the three tool names and descriptions, and the note that states
        // what Salah does not offer. Every one of them has to exist in both files, or the screen would
        // be half in one language whichever is selected.
        val english = englishStrings()
        val bengali = bengaliStrings()

        listOf(
            "nav_tools",
            "tools_subtitle",
            "tools_prayer_times_title",
            "tools_prayer_times_description",
            "shortcut_qibla",
            "shortcut_qibla_subtitle",
            "nav_ramadan",
            "shortcut_ramadan_subtitle",
            "tools_unavailable_note",
        ).forEach { key ->
            assertTrue("$key is missing from the English resources", english.containsKey(key))
            assertTrue("$key is missing from the Bengali resources", bengali.containsKey(key))
            assertTrue("$key has an empty English value", english.getValue(key).isNotBlank())
            assertTrue("$key has an empty Bengali value", bengali.getValue(key).isNotBlank())
        }
    }

    @Test
    fun `the Settings categories and their subpages are named in Bengali`() {
        // The overview draws a row per category and each row opens a screen; the names on the way in
        // and the headings of the screens themselves are the strings a reader navigates with, so they
        // are checked as a set rather than trusted.
        val bengali = bengaliStrings()

        listOf(
            "settings_title",
            "settings_category_general_title",
            "settings_theme_title",
            "settings_category_prayer_times_title",
            "settings_category_notifications_title",
            "nav_location",
            "calendar_settings_title",
            "settings_about_title",
        ).forEach { key ->
            val value = bengali.getValue(key)
            assertTrue(
                "$key is still written in Latin script: $value",
                value.any { character -> character.inBengaliBlock() },
            )
        }
    }

    @Test
    fun `no Tools or Settings composable renders a hardcoded sentence`() {
        // The audit that localised these screens found their copy in resources rather than in code, and
        // this is the guard that keeps it that way: a sentence is a literal with a space *and* a letter,
        // so a bare separator or whitespace literal (`" · "`, `" "`) is not treated as copy while a
        // pasted English sentence is. The Android-free logic files are excluded on purpose — they hold
        // route strings, log fields and diagnostic text, never rendered copy — and comments are
        // stripped first, because these packages are documented heavily and their prose quotes labels.
        val offenders = toolsSettingsSourceFiles().flatMap { file ->
            codeLiterals(file.readText())
                .filter { literal -> literal.contains(' ') && literal.any { character -> character.isLetter() } }
                .map { literal -> "${file.name}: \"$literal\"" }
        }

        assertTrue(
            "these Tools or Settings composables render a hardcoded sentence instead of a string " +
                "resource: $offenders",
            offenders.isEmpty(),
        )
    }

    // --- Reading the real files --------------------------------------------------

    private fun englishStrings(): Map<String, String> =
        parse(File(resourceDirectory(), "values/strings.xml"))

    private fun bengaliStrings(): Map<String, String> =
        parse(File(resourceDirectory(), "values-${AppLanguage.BANGLA.languageTag}/strings.xml"))

    /**
     * The `res` directory, found by walking up from the working directory.
     *
     * Android unit tests run with the module directory as their working directory while the same tests
     * are run from the repository root by the build, so both are tried. A missing directory fails with
     * the paths that were searched rather than silently producing empty maps that would make every
     * assertion below vacuous.
     */
    private fun resourceDirectory(): File {
        var base: File? = File(".").absoluteFile
        repeat(SEARCH_DEPTH) {
            val current = base ?: return@repeat
            RELATIVE_CANDIDATES
                .map { relative -> File(current, relative) }
                .firstOrNull { candidate -> File(candidate, "values/strings.xml").isFile }
                ?.let { return it }
            base = current.parentFile
        }
        error(
            "The Android resources were not found. Searched upwards from ${File(".").absolutePath} " +
                "for ${RELATIVE_CANDIDATES.joinToString()}.",
        )
    }

    private fun parse(file: File): Map<String, String> {
        assertTrue("missing resource file: ${file.absolutePath}", file.isFile)
        return STRING_ENTRY.findAll(file.readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
    }

    /**
     * The Home package's composable sources, found by walking up from the working directory exactly
     * like [resourceDirectory] does, so a missing directory fails loudly instead of making the
     * literal check vacuous.
     */
    private fun homeSourceFiles(): List<File> {
        var base: File? = File(".").absoluteFile
        repeat(SEARCH_DEPTH) {
            val current = base ?: return@repeat
            SOURCE_CANDIDATES
                .map { relative -> File(current, relative) }
                .firstOrNull { candidate -> candidate.isDirectory }
                ?.let { home ->
                    return home.walkTopDown()
                        .filter { file -> file.isFile && file.name.endsWith(".kt") }
                        .filterNot { file -> file.name in HOME_LOGIC_FILES }
                        .toList()
                }
            base = current.parentFile
        }
        error(
            "The Home sources were not found. Searched upwards from ${File(".").absolutePath} " +
                "for ${SOURCE_CANDIDATES.joinToString()}.",
        )
    }

    /**
     * The Tools, Settings, Notification, Location, Qibla and Ramadan sources, found by walking up from
     * the working directory exactly like [resourceDirectory] does.
     *
     * The `feature` directory is located, and then the packages the localised surfaces live in; a
     * missing directory fails loudly instead of making the literal check vacuous.
     */
    private fun toolsSettingsSourceFiles(): List<File> {
        var base: File? = File(".").absoluteFile
        repeat(SEARCH_DEPTH) {
            val current = base ?: return@repeat
            TOOLS_SETTINGS_SOURCE_CANDIDATES
                .map { relative -> File(current, relative) }
                .firstOrNull { candidate -> candidate.isDirectory }
                ?.let { feature ->
                    return TOOLS_SETTINGS_PACKAGES
                        .map { pkg -> File(feature, pkg) }
                        .filter { it.isDirectory }
                        .flatMap { directory ->
                            directory.walkTopDown()
                                .filter { file -> file.isFile && file.name.endsWith(".kt") }
                                .toList()
                        }
                        .filterNot { file -> file.name in TOOLS_SETTINGS_LOGIC_FILES }
                }
            base = current.parentFile
        }
        error(
            "The Tools and Settings sources were not found. Searched upwards from " +
                "${File(".").absolutePath} for ${TOOLS_SETTINGS_SOURCE_CANDIDATES.joinToString()}.",
        )
    }

    /** The English keys whose name begins with one of the Tools or Settings prefixes. */
    private fun scopedToolsAndSettingsKeys(): List<String> =
        englishStrings().keys
            .filter { key -> TOOLS_SETTINGS_PREFIXES.any { prefix -> key.startsWith(prefix) } }
            .sorted()

    /**
     * Every double-quoted literal in [source], with comments removed first.
     *
     * A raw read would find the labels this check is about inside the package's own KDoc, which quotes
     * them on purpose, so comments are stripped before the literals are collected.
     */
    private fun codeLiterals(source: String): List<String> {
        val withoutComments = BLOCK_COMMENT.replace(source, " ").lineSequence()
            .joinToString("\n") { line -> line.substringBefore("//") }
        return STRING_LITERAL.findAll(withoutComments).map { match -> match.groupValues[1] }.toList()
    }

    /** Every `%n$s`/`%n$d` placeholder in [value], in order, so a renumbered one is caught. */
    private fun formatArguments(value: String): List<String> =
        FORMAT_ARGUMENT.findAll(value).map { match -> match.value }.toList()

    private companion object {
        const val SEARCH_DEPTH = 5

        /**
         * The key prefixes owned by the Tools and Settings surfaces.
         *
         * `notification_` and `notifications_` are deliberately separate: the singular prefix covers
         * the reminder content and channel names Android draws, the plural one the settings screen.
         */
        val TOOLS_SETTINGS_PREFIXES = listOf(
            "tools_",
            "settings_",
            "method_",
            "asr_",
            "high_latitude_",
            "time_format_",
            "notification_",
            "notifications_",
            "location_",
            "qibla_",
            "ramadan_",
        )

        /**
         * The Tools/Settings keys that legitimately carry no Bengali word: bare placeholders (a
         * timestamp, a degree reading), a coordinate separator, and `English` — the language's own
         * name, which is written the same in both languages and must not be transliterated.
         */
        val TOOLS_SETTINGS_LATIN_ONLY = setOf(
            "settings_language_english",
            "qibla_location_updated",
            "qibla_coordinates",
            "qibla_location_line",
            "qibla_bearing_value_degrees",
        )

        /**
         * The packages the localised surfaces live in, relative to the `feature` directory.
         *
         * `common` is included because the calculation-option, location-source and unavailability
         * label mappings live there, and a hardcoded sentence in one of them would reach several of
         * these screens.
         */
        val TOOLS_SETTINGS_PACKAGES = listOf(
            "tools",
            "settings",
            "notifications",
            "location",
            "qibla",
            "ramadan",
            "common",
        )

        /**
         * The Tools/Settings packages' Android-free files, which hold no rendered text: the state
         * types, their factories, the view models, the route/resource enums and the label mappings.
         * They carry navigation routes, log-field names and diagnostic strings as literals, and none of
         * those is interface copy.
         */
        val TOOLS_SETTINGS_LOGIC_FILES = setOf(
            "ToolsEntry.kt",
            "SettingsCategory.kt",
            "SettingsUiState.kt",
            "SettingsViewModel.kt",
            "PrayerNotificationSettingsUiState.kt",
            "PrayerNotificationSettingsViewModel.kt",
            "LocationUiState.kt",
            "LocationViewModel.kt",
            "LocationFormState.kt",
            "LocationOriginLabels.kt",
            "DomainLabels.kt",
            "QiblaUiState.kt",
            "QiblaViewModel.kt",
            "QiblaLabels.kt",
            "RamadanUiState.kt",
            "RamadanUiStateFactory.kt",
            "RamadanViewModel.kt",
        )

        /** The `feature` directory, relative to the repository root or the module directory. */
        val TOOLS_SETTINGS_SOURCE_CANDIDATES = listOf(
            "app/src/main/java/io/github/towkirahmedrony/salah/feature",
            "src/main/java/io/github/towkirahmedrony/salah/feature",
        )

        val RELATIVE_CANDIDATES = listOf(
            "app/src/main/res",
            "src/main/res",
            "res",
        )

        /**
         * The Home feature's composable sources, relative to the repository root.
         *
         * `settings.gradle.kts` puts the sources under `app/`, and the module-relative path is what a
         * test run from the module directory resolves, so both are tried for the same reason
         * [RELATIVE_CANDIDATES] tries both resource roots.
         */
        val SOURCE_CANDIDATES = listOf(
            "app/src/main/java/io/github/towkirahmedrony/salah/feature/home",
            "src/main/java/io/github/towkirahmedrony/salah/feature/home",
        )

        /**
         * The Home package's Android-free files, which hold no rendered text: the state types, the state
         * factory and the view model. They carry navigation routes, `LazyColumn` item keys and start-up
         * trace marks as literals, and none of those is interface copy.
         */
        val HOME_LOGIC_FILES = setOf("HomeUiState.kt", "HomeUiStateFactory.kt", "HomeViewModel.kt")

        /** The `<string name="key">value</string>` shape these files use, values excluded. */
        val STRING_ENTRY = Regex(
            """<string\s+name="([A-Za-z0-9_]+)"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val FORMAT_ARGUMENT = Regex("%(\\d+)${'$'}[ds]")

        /** A block comment, KDoc included. */
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        /**
         * A single-line double-quoted Kotlin literal, without escapes or newlines.
         *
         * The pattern is written with escapes rather than as a raw string on purpose: a raw string
         * whose content both starts and ends with a quote would put four quotes in a row, and the
         * three-quote delimiter would be found inside them.
         */
        val STRING_LITERAL = Regex("\"([^\"\\\\\n]*)\"")

        /**
         * Every string the Home screen and its components can render.
         *
         * Grouped by how the value reaches the screen, so a key added to one of those paths has an
         * obvious place to be listed. The keys deliberately **absent** are the three marked
         * `translatable="false"` in the default file — `app_name` ("Salah"), `home_prayer_times_arabic`
         * (the Arabic heading) and `home_time_unavailable` (the "no time" dash) — plus the option names
         * of the calculation settings, which are the conventional names of calculation authorities and
         * are not rendered by the composed Home screen. See docs/LOCALIZATION.md section 9.
         */
        val HOME_STRINGS = listOf(
            // The header: the brand line, the place, the date line and the placeholder while it loads.
            "app_tagline",
            "home_weekday_and_date",
            "home_weekday_unknown",
            "home_location_title",
            "home_location_none_title",
            "home_location_none_body",
            "home_location_manual",
            "home_location_coordinates",
            "home_location_timezone",
            // The hero card: the period that is open now, or the one that is next.
            "home_hero_status_current",
            "home_hero_status_up_next",
            "home_hero_subtitle_active",
            "home_hero_subtitle_next",
            "home_hero_time_until",
            "home_hero_time_until_begins",
            "home_hero_time_remaining",
            "home_hero_starts",
            "home_hero_since_last_night",
            "home_hero_ends",
            "home_hero_ends_next_day",
            "home_hero_next_at",
            // The day's timetable strip and the two actions off the card.
            "home_today_prayer_times_title",
            "home_action_view_all",
            "home_action_view_details",
            "home_time_range",
            "home_ends_next_day",
            "home_adjusted_by",
            // The prohibited-time card.
            "home_prohibited_title",
            "home_prohibited_window",
            "prohibited_window_range",
            "home_prohibited_none",
            // The row of tool tiles, each named after the destination it opens.
            "home_daily_tools_title",
            "shortcut_qibla",
            "nav_calendar",
            "nav_ramadan",
            "nav_tools",
            // The prayer screen Home's two actions open.
            "nav_prayer",
            "home_prayer_timetable_title",
            "home_next_prayer_title",
            "home_next_prayer_none",
            "home_next_prayer_at",
            "home_next_prayer_remaining",
            // The prayer, solar-event and prohibited-interval names.
            "prayer_fajr",
            "prayer_dhuhr",
            "prayer_asr",
            "prayer_maghrib",
            "prayer_isha",
            "event_sunrise",
            "event_solar_noon",
            "event_sunset",
            "prohibited_kind_sunrise_ascent",
            "prohibited_kind_zenith_decline",
            "prohibited_kind_sunset_setting",
            // The states, the retry action and every reason Home can show in place of a value.
            "state_error_title",
            "action_retry",
            "reason_device_date_unavailable",
            "reason_no_location",
            "reason_calculation_not_configured",
            "reason_calculation_not_possible",
            "reason_rules_not_verified",
            "reason_calendar_not_implemented",
            "reason_calendar_out_of_range",
            "reason_network_unavailable",
            "reason_unknown",
        )

        /**
         * The Home keys that legitimately translate to themselves: a separator, a placeholder or a
         * numeric format. They carry no words, so requiring Bengali script in them would fail a correct
         * translation.
         */
        val HOME_PUNCTUATION_ONLY = setOf(
            "home_location_coordinates",
            "home_time_range",
            "home_weekday_and_date",
            "home_hero_next_at",
            "home_prohibited_window",
            "prohibited_window_range",
        )
    }
}

/** True when [this] is in the Bengali Unicode block (U+0980–U+09FF). */
private fun Char.inBengaliBlock(): Boolean = code in 0x0980..0x09FF
