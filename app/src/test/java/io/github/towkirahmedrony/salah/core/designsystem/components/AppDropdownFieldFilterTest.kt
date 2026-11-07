package io.github.towkirahmedrony.salah.core.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of `AppDropdownField` that is logic rather than layout: which lists are searchable,
 * and what a typed query keeps.
 *
 * The field itself needs a device to be seen, and Salah has no Compose UI test harness, so the parts
 * that can be wrong without looking wrong are asserted here: the threshold that decides whether a
 * long upazila/thana list gets a search box at all, and the matching rule applied as the user types.
 * Both are plain functions of a list and a string, which is exactly why they were written outside the
 * composable — a filter that only exists inside a popup cannot be tested.
 *
 * Matching is deliberately a **substring** match, not a prefix one: a user typing "sadar" is looking
 * for `Bandarban Sadar`, and a prefix-only filter would tell them there is nothing there. It is also
 * case-insensitive for the Latin names, and matches Bangla names on their own characters, because the
 * dataset carries both and the screen renders whichever language is active.
 */
class AppDropdownFieldFilterTest {

    @Test
    fun `a list at the threshold is not searchable`() {
        assertFalse(
            "a list of exactly ${DROPDOWN_SEARCH_THRESHOLD} items is short enough to scan",
            dropdownSearchApplies(DROPDOWN_SEARCH_THRESHOLD),
        )
    }

    @Test
    fun `a list past the threshold is searchable`() {
        assertTrue(
            "a long upazila/thana list must offer search",
            dropdownSearchApplies(DROPDOWN_SEARCH_THRESHOLD + 1),
        )
    }

    @Test
    fun `a blank query keeps every item`() {
        val items = listOf(item("dhaka", "Dhaka"), item("gazipur", "Gazipur"))

        assertEquals(items, dropdownItemsFor(items, "   "))
    }

    @Test
    fun `a query matches a name it appears inside, ignoring case`() {
        val items = listOf(
            item("bandarban-sadar", "Bandarban Sadar"),
            item("bandarban", "Bandarban"),
        )

        assertEquals(listOf(items[0]), dropdownItemsFor(items, "SADAR"))
        assertEquals(listOf(items[1]), dropdownItemsFor(items, "bAndArBaN").take(1))
    }

    @Test
    fun `a query matches a bangla name on its own characters`() {
        val items = listOf(item("dhaka", "ঢাকা"), item("gazipur", "গাজীপুর"))

        assertEquals(listOf(items[0]), dropdownItemsFor(items, "ঢাকা"))
        assertEquals(listOf(items[0]), dropdownItemsFor(items, "ঢা"))
    }

    @Test
    fun `a query that matches nothing yields no items`() {
        val items = listOf(item("dhaka", "Dhaka"))

        assertTrue(dropdownItemsFor(items, "Sylhet").isEmpty())
    }

    @Test
    fun `a query is trimmed before it is matched`() {
        val items = listOf(item("dhaka", "Dhaka"))

        assertEquals(items, dropdownItemsFor(items, "  dhak  "))
    }

    private fun item(id: String, label: String) = DropdownItem(id = id, label = label)
}
