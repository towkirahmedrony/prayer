package io.github.towkirahmedrony.salah.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The interface-language preference's contract.
 *
 * Three properties matter, and each of them is something a later change could quietly break:
 *
 *  - the identifiers stay the stable `en`/`bn` subtags, because they are what a locale is built from
 *    and what the `values-*` resource folders are keyed by — a translated label must never become the
 *    stored value;
 *  - [AppLanguage.SYSTEM] resolves to *no* locale rather than to a pinned one, which is what keeps the
 *    default a genuine "follow the device" instead of an implicit "always English";
 *  - an unknown stored identifier falls back to the default instead of throwing, because a store
 *    written by another version of the app must not be able to make the settings screen unusable.
 */
class AppLanguageTest {

    @Test
    fun `the identifiers are the stable language subtags, not the display labels`() {
        assertEquals("en", AppLanguage.ENGLISH.languageTag)
        assertEquals("bn", AppLanguage.BANGLA.languageTag)
    }

    @Test
    fun `the selector offers exactly the two explicit languages`() {
        val explicit = AppLanguage.entries.filter { it.languageTag != null }

        assertEquals(listOf(AppLanguage.ENGLISH, AppLanguage.BANGLA), explicit)
        assertTrue(
            "the selector must be able to reach English",
            AppLanguage.entries.contains(AppLanguage.ENGLISH),
        )
        assertTrue(
            "the selector must be able to reach Bengali",
            AppLanguage.entries.contains(AppLanguage.BANGLA),
        )
    }

    @Test
    fun `following the device resolves to no locale at all`() {
        // `null` is the point: callers fall back to `Locale.getDefault()`, so a device set to Bengali
        // still gets Bengali without the user choosing anything.
        assertNull(AppLanguage.SYSTEM.locale())
    }

    @Test
    fun `each explicit language resolves to its own locale`() {
        assertEquals(Locale.forLanguageTag("en"), AppLanguage.ENGLISH.locale())
        assertEquals(Locale.forLanguageTag("bn"), AppLanguage.BANGLA.locale())
    }

    @Test
    fun `an unknown or absent stored identifier falls back to following the device`() {
        assertEquals(AppLanguage.DEFAULT, AppLanguage.fromIdentifier(null))
        assertEquals(AppLanguage.DEFAULT, AppLanguage.fromIdentifier("KLINGON"))
        assertEquals(AppLanguage.DEFAULT, AppLanguage.fromIdentifier(""))
    }

    @Test
    fun `a stored identifier round-trips through the enum name`() {
        // The persisted value is the enum's `name`, the same convention every other Salah preference
        // uses, so what the repository writes is what it reads back.
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromIdentifier(language.name))
        }
        assertEquals(AppLanguage.SYSTEM, AppLanguage.DEFAULT)
    }
}
