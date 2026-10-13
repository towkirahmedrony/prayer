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

    /** Every `%n$s`/`%n$d` placeholder in [value], in order, so a renumbered one is caught. */
    private fun formatArguments(value: String): List<String> =
        FORMAT_ARGUMENT.findAll(value).map { match -> match.value }.toList()

    private companion object {
        const val SEARCH_DEPTH = 5

        val RELATIVE_CANDIDATES = listOf(
            "app/src/main/res",
            "src/main/res",
            "res",
        )

        /** The `<string name="key">value</string>` shape these files use, values excluded. */
        val STRING_ENTRY = Regex(
            """<string\s+name="([A-Za-z0-9_]+)"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val FORMAT_ARGUMENT = Regex("%(\\d+)${'$'}[ds]")
    }
}

/** True when [this] is in the Bengali Unicode block (U+0980–U+09FF). */
private fun Char.inBengaliBlock(): Boolean = code in 0x0980..0x09FF
