package io.github.towkirahmedrony.salah.navigation

import io.github.towkirahmedrony.salah.feature.tools.ToolsEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom navigation's structure, asserted without a device.
 *
 * The bar renders `SalahDestination.bottomBarDestinations` in order and opens each item's own route, and
 * the Tools screen renders `ToolsEntry.entries` and opens each entry's own destination. Those two lists
 * are therefore the whole navigation contract, and these are the properties that make them safe to draw:
 *
 *  - the bar is exactly Home, Calendar, Tools and Settings, in that order — the membership *and* the
 *    order come from the destination enum, so a feature added to it cannot quietly push a tab to the
 *    end;
 *  - Prayer is not on the bar but is still a declared destination on its original route, which is what
 *    keeps Home's "View all" and "View details" working;
 *  - every route is declared once, so no destination can be shadowed by another one's route and a tap
 *    cannot land on the wrong screen (`fromRoute` returns the first match, so a duplicate route would
 *    silently hide a member);
 *  - every Tools row opens a declared destination of its own, and not one the user already has in the
 *    bar — a row that opened Home, Calendar, Tools or Settings would be a control that appears to do
 *    nothing;
 *  - the screens that were reachable from the bar before this change are all still reachable through
 *    one of the two lists, so removing a tab reorganised navigation instead of stranding a screen.
 *
 * What these tests cannot see is Compose: they assert the declarations the UI is drawn from, not the
 * rendered pixels. `SalahApp` resolves each item's glyph in a `when`, so a missing icon is a compile
 * error rather than something these assertions would have to catch.
 */
class SalahBottomNavigationTest {

    @Test
    fun `the bottom bar is Home, Calendar, Tools and Settings in that order`() {
        assertEquals(
            "the bottom bar's items or their order changed",
            listOf(
                SalahDestination.HOME,
                SalahDestination.CALENDAR,
                SalahDestination.TOOLS,
                SalahDestination.SETTINGS,
            ),
            SalahDestination.bottomBarDestinations,
        )
    }

    @Test
    fun `Prayer left the bar but kept its route and its screen`() {
        assertFalse(
            "prayer is still a bottom-bar item",
            SalahDestination.PRAYER.inBottomBar,
        )
        assertFalse(
            "prayer is still drawn in the bottom bar",
            SalahDestination.PRAYER in SalahDestination.bottomBarDestinations,
        )
        // The route is what every remaining entry point navigates with, so it must not have moved.
        assertEquals("prayer", SalahDestination.PRAYER.route)
        assertEquals(
            "the prayer route no longer resolves to the prayer destination",
            SalahDestination.PRAYER,
            SalahDestination.fromRoute(SalahDestination.PRAYER.route),
        )
    }

    @Test
    fun `Tools is a bottom-bar destination that is also declared on its own route`() {
        assertTrue("tools is not in the bottom bar", SalahDestination.TOOLS.inBottomBar)
        assertTrue(
            "tools is not drawn in the bottom bar",
            SalahDestination.TOOLS in SalahDestination.bottomBarDestinations,
        )
        assertEquals(
            "the tools route no longer resolves to the tools destination",
            SalahDestination.TOOLS,
            SalahDestination.fromRoute(SalahDestination.TOOLS.route),
        )
    }

    @Test
    fun `every destination has its own route and resolves back from it`() {
        val destinations = SalahDestination.entries

        assertEquals(
            "two destinations share a route, so one of them can never be navigated to",
            destinations.size,
            destinations.map { destination -> destination.route }.distinct().size,
        )

        destinations.forEach { destination ->
            assertTrue(
                "${destination.name} has a blank route",
                destination.route.isNotBlank(),
            )
            assertEquals(
                "${destination.name}'s route resolves to a different destination",
                destination,
                SalahDestination.fromRoute(destination.route),
            )
        }
    }

    @Test
    fun `every Tools entry opens a declared destination of its own`() {
        val destinations = ToolsEntry.entries.map { entry -> entry.destination }

        assertEquals(
            "two tools open the same screen",
            destinations.size,
            destinations.distinct().size,
        )

        ToolsEntry.entries.forEach { entry ->
            assertEquals(
                "no destination is declared for route \"${entry.destination.route}\"",
                entry.destination,
                SalahDestination.fromRoute(entry.destination.route),
            )
            assertNotEquals(
                "a tool opens the Tools screen it is listed on",
                SalahDestination.TOOLS,
                entry.destination,
            )
            assertFalse(
                "${entry.name} opens a bottom-bar tab, which the user already has",
                entry.destination.inBottomBar,
            )
        }
    }

    @Test
    fun `every Tools entry is named and described once`() {
        val titles = ToolsEntry.entries.map { entry -> entry.titleRes }
        val descriptions = ToolsEntry.entries.map { entry -> entry.descriptionRes }

        assertEquals("two tools show the same title", titles.size, titles.distinct().size)
        assertEquals(
            "two tools show the same description",
            descriptions.size,
            descriptions.distinct().size,
        )
        ToolsEntry.entries.forEach { entry ->
            assertNotEquals(
                "${entry.name} shows its title as its own description",
                entry.titleRes,
                entry.descriptionRes,
            )
        }
    }

    @Test
    fun `the Tasbih counter is offered as a tool and is not a bottom-bar tab`() {
        // The counter is reached from Tools and from nowhere else, so its row there is the whole of its
        // navigation contract: one row, opening a destination that is not a tab the user already has.
        assertEquals(
            "no Tools row opens the Tasbih counter",
            1,
            ToolsEntry.entries.count { entry -> entry.destination == SalahDestination.TASBIH },
        )
        assertFalse(
            "the counter is a bottom-bar tab, which the user already has",
            SalahDestination.TASBIH.inBottomBar,
        )
        assertEquals("tasbih", SalahDestination.TASBIH.route)
    }

    @Test
    fun `every screen that was reachable from the bar is still reachable`() {
        // The bar before this change was Home, Prayer, Calendar and Settings. Three of them are still
        // items; Prayer is the one that moved, and it must have moved to Tools rather than nowhere.
        listOf(
            SalahDestination.HOME,
            SalahDestination.CALENDAR,
            SalahDestination.SETTINGS,
        ).forEach { destination ->
            assertTrue(
                "${destination.name} is no longer in the bottom bar",
                destination in SalahDestination.bottomBarDestinations,
            )
        }

        val tools = ToolsEntry.entries.map { entry -> entry.destination }
        assertTrue(
            "the prayer screen is no longer reachable from Tools",
            SalahDestination.PRAYER in tools,
        )
        // The two screens that were only ever reached from Home's daily tools are on Tools too, so the
        // new tab did not make them harder to find than they were.
        assertTrue(
            "Qibla is no longer reachable from Tools",
            SalahDestination.QIBLA in tools,
        )
        assertTrue(
            "Ramadan is no longer reachable from Tools",
            SalahDestination.RAMADAN in tools,
        )
    }
}
