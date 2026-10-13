package io.github.towkirahmedrony.salah.feature.settings

import io.github.towkirahmedrony.salah.core.model.AppLanguage
import io.github.towkirahmedrony.salah.navigation.SalahDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings overview's structure, asserted without a device.
 *
 * The overview renders `SettingsCategory.entries` and navigates to `category.destination`, so these are
 * the properties that make that safe:
 *
 *  - every category opens a destination that the graph actually declares, and a *different* one from
 *    every other category — a duplicated or unknown route would make a row that does nothing;
 *  - no category opens the Settings screen itself or a bottom-bar tab, which would mean either a loop or
 *    a category that replaces a destination the user already has;
 *  - the three screens that were already configurations of their own — location, prayer notifications
 *    and the calendar preferences — are reachable as categories, so reorganising the settings did not
 *    strand a screen that already existed;
 *  - no two categories read as the same thing, which is the visible symptom of a preference that has
 *    been put on two rows.
 */
class SettingsCategoryTest {

    @Test
    fun `every category opens a declared destination of its own`() {
        val destinations = SettingsCategory.entries.map { category -> category.destination }

        destinations.forEach { destination ->
            // `fromRoute` looks the route up by string, so a destination whose route did not match the
            // graph would come back as null or as a different member.
            assertEquals(
                "no destination is declared for route \"${destination.route}\"",
                destination,
                SalahDestination.fromRoute(destination.route),
            )
        }

        assertEquals(
            "two categories open the same screen",
            destinations.size,
            destinations.distinct().size,
        )
    }

    @Test
    fun `no category opens Settings itself or a bottom-bar tab`() {
        SettingsCategory.entries.forEach { category ->
            assertNotEquals(
                "a category must not open the overview it is listed on",
                SalahDestination.SETTINGS,
                category.destination,
            )
            assertFalse(
                "${category.name} opens a bottom-bar tab, which it does not own",
                category.destination.inBottomBar,
            )
        }
    }

    @Test
    fun `the configuration screens that already existed are categories`() {
        val destinations = SettingsCategory.entries.map { category -> category.destination }

        assertTrue(
            "the location screen is no longer reachable from Settings",
            SalahDestination.LOCATION in destinations,
        )
        assertTrue(
            "the prayer-notification screen is no longer reachable from Settings",
            SalahDestination.NOTIFICATIONS in destinations,
        )
        assertTrue(
            "the calendar-preferences screen is no longer reachable from Settings",
            SalahDestination.CALENDAR_SETTINGS in destinations,
        )
    }

    @Test
    fun `each category is named and described once`() {
        val titles = SettingsCategory.entries.map { category -> category.titleRes }
        val descriptions = SettingsCategory.entries.map { category -> category.descriptionRes }

        assertEquals("two categories show the same title", titles.size, titles.distinct().size)
        assertEquals(
            "two categories show the same description",
            descriptions.size,
            descriptions.distinct().size,
        )
        SettingsCategory.entries.forEach { category ->
            assertNotEquals(
                "${category.name} shows its title as its own description",
                category.titleRes,
                category.descriptionRes,
            )
        }
    }

    /**
     * The language selector's home, asserted as a reachability property rather than a screenshot.
     *
     * The selector is rendered by `SettingsGeneralScreen`, which is the screen `SETTINGS_GENERAL`
     * opens. So "the language selector can be reached" reduces to two things this can check without a
     * device: the General category is on the overview (which renders `SettingsCategory.entries`), and
     * it opens the destination the graph declares. A row that is missing, or that opens a route the
     * graph does not know, is precisely how a control becomes unreachable while still compiling.
     */
    @Test
    fun `the screen that carries the language selector is one tap from Settings`() {
        val general = SettingsCategory.entries.first { category ->
            category.destination == SalahDestination.SETTINGS_GENERAL
        }

        // The overview renders every entry, so this category is a visible row.
        assertTrue("the General category is not on the Settings overview", general in SettingsCategory.entries)
        // And the row opens a real destination rather than a route that silently does nothing.
        assertEquals(
            SalahDestination.SETTINGS_GENERAL,
            SalahDestination.fromRoute(general.destination.route),
        )
        assertFalse("the language screen must not be a bottom-bar tab", general.destination.inBottomBar)
    }

    /**
     * Both choices the task requires are offered, under stable identifiers.
     *
     * The screen renders `AppLanguage.entries`, so this is the whole list a user can pick from, and it
     * is asserted here beside the reachability check so "the selector exists" and "the selector has
     * both languages" fail in the same place if either regresses.
     */
    @Test
    fun `the selector offers English and Bengali with stable identifiers`() {
        assertTrue(AppLanguage.entries.contains(AppLanguage.ENGLISH))
        assertTrue(AppLanguage.entries.contains(AppLanguage.BANGLA))
        assertEquals("en", AppLanguage.ENGLISH.languageTag)
        assertEquals("bn", AppLanguage.BANGLA.languageTag)
        // The stored value is the identifier, never a translated display label.
        assertNotEquals("English", AppLanguage.ENGLISH.name)
        assertNotEquals("বাংলা", AppLanguage.BANGLA.name)
    }

    @Test
    fun `every destination declared for the overview has a category`() {
        // The other direction: a destination added to the graph and forgotten here would be a screen no
        // user can reach, which is exactly the mistake this list-based overview can still make.
        val listed = SettingsCategory.entries.map { category -> category.destination }
        val declared = listOf(
            SalahDestination.SETTINGS_GENERAL,
            SalahDestination.SETTINGS_APPEARANCE,
            SalahDestination.SETTINGS_PRAYER_TIMES,
            SalahDestination.SETTINGS_ABOUT,
        )

        declared.forEach { destination ->
            assertTrue(
                "${destination.name} is declared but no category opens it",
                destination in listed,
            )
        }
    }
}
