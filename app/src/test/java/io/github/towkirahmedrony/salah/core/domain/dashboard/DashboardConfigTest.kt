package io.github.towkirahmedrony.salah.core.domain.dashboard

import io.github.towkirahmedrony.salah.core.model.DashboardCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the Home dashboard's arrangement rules: the default order, showing and hiding a card,
 * moving one within the order, and — most importantly — the recovery of a layout that a different
 * version of the app, or a corrupted store, left behind.
 *
 * These are pure Kotlin tests: no Android, no coroutines, no store. Everything the view model relies
 * on to turn a persisted value into something the screen can render is decided here.
 */
class DashboardConfigTest {

    private val default = DashboardConfig.DEFAULT

    @Test
    fun `the default layout shows the daily cards in declaration order and hides the occasional ones`() {
        assertEquals(DashboardCard.entries.map { it.id }, default.order)
        assertEquals(
            DashboardCard.entries.filter { it.defaultEnabled }.toSet(),
            default.enabledCards().toSet(),
        )

        // The occasional readings are off by default, so a fresh install opens on the daily routine.
        assertFalse(default.isEnabled(DashboardCard.SOLAR_GEOMETRY))
        assertFalse(default.isEnabled(DashboardCard.QIBLA))
        assertTrue(default.isEnabled(DashboardCard.NEXT_PRAYER))
        assertTrue(default.isEnabled(DashboardCard.TODAY_TIMETABLE))
    }

    @Test
    fun `hiding a card removes it from the enabled set but keeps its position`() {
        val hidden = default.withCardEnabled(DashboardCard.FORBIDDEN_TIMES, enabled = false)

        assertFalse(hidden.isEnabled(DashboardCard.FORBIDDEN_TIMES))
        assertFalse(hidden.enabledCards().contains(DashboardCard.FORBIDDEN_TIMES))
        // Position is preserved: the order is unchanged, only the hidden set grew.
        assertEquals(default.order, hidden.order)
        // And nothing else moved.
        assertTrue(hidden.isEnabled(DashboardCard.NIGHT_PERIODS))
    }

    @Test
    fun `showing a hidden card restores it without moving it`() {
        val hidden = default.withCardEnabled(DashboardCard.CALENDAR, enabled = false)
        val shown = hidden.withCardEnabled(DashboardCard.CALENDAR, enabled = true)

        assertTrue(shown.isEnabled(DashboardCard.CALENDAR))
        assertEquals(default.order, shown.order)
        assertEquals(default.hidden, shown.hidden)
    }

    @Test
    fun `a card moved up swaps with the one before it, and clamps at the top`() {
        val moved = default.moved(DashboardCard.TODAY_TIMETABLE, delta = -1)

        assertEquals(
            listOf(DashboardCard.TODAY_TIMETABLE.id, DashboardCard.NEXT_PRAYER.id),
            moved.order.take(2),
        )
        // Moving the first card further up changes nothing.
        assertEquals(moved, moved.moved(DashboardCard.TODAY_TIMETABLE, delta = -1))
    }

    @Test
    fun `a card moved down clamps at the bottom`() {
        val last = DashboardCard.entries.last()
        val moved = default.moved(last, delta = 1)
        assertEquals(default, moved)
    }

    @Test
    fun `moving a card keeps every card exactly once and the hidden set unchanged`() {
        val custom = default.withCardEnabled(DashboardCard.QIBLA, enabled = true)
        val moved = custom.moved(DashboardCard.DAILY_TOOLS, delta = -2)

        assertEquals(DashboardCard.entries.size, moved.order.size)
        assertEquals(DashboardCard.entries.size, moved.order.toSet().size)
        assertEquals(custom.hidden, moved.hidden)
        assertEquals(DashboardCard.entries.toSet(), moved.order.mapNotNull(DashboardCard::fromId).toSet())
    }

    @Test
    fun `resetting restores the documented default layout`() {
        val customized = default
            .withCardEnabled(DashboardCard.QIBLA, enabled = true)
            .withCardEnabled(DashboardCard.NEXT_PRAYER, enabled = false)
            .moved(DashboardCard.DAILY_TOOLS, delta = -3)

        assertNotEquals(default, customized)
        assertEquals(default, customized.resetToDefault())
    }

    @Test
    fun `duplicate identifiers are removed, keeping the first position`() {
        val stored = DashboardConfig(
            order = listOf(
                DashboardCard.DAILY_TOOLS.id,
                DashboardCard.NEXT_PRAYER.id,
                DashboardCard.DAILY_TOOLS.id,
            ),
        ).normalized()

        assertEquals(DashboardCard.entries.size, stored.order.size)
        assertEquals(DashboardCard.DAILY_TOOLS.id, stored.order.first())
        assertEquals(1, stored.order.count { it == DashboardCard.DAILY_TOOLS.id })
    }

    @Test
    fun `unknown identifiers are dropped and the remaining order is preserved`() {
        val stored = DashboardConfig(
            order = listOf("retired_card", DashboardCard.NIGHT_PERIODS.id, "another_ghost"),
        ).normalized()

        assertFalse(stored.order.contains("retired_card"))
        assertFalse(stored.order.contains("another_ghost"))
        // The known card kept its relative position (it is now first).
        assertEquals(DashboardCard.NIGHT_PERIODS.id, stored.order.first())
        // Every supported card is present exactly once.
        assertEquals(DashboardCard.entries.map { it.id }.toSet(), stored.order.toSet())
    }

    @Test
    fun `a newly introduced card is appended without moving the saved ones`() {
        // Simulates a layout written before two cards existed.
        val saved = listOf(
            DashboardCard.NEXT_PRAYER.id,
            DashboardCard.TODAY_TIMETABLE.id,
        )
        val normalized = DashboardConfig(order = saved).normalized()

        assertEquals(saved, normalized.order.take(2))
        assertEquals(DashboardCard.entries.map { it.id }.toSet(), normalized.order.toSet())
    }

    @Test
    fun `a hidden identifier for a card that no longer exists is dropped`() {
        val normalized = DashboardConfig(
            hidden = setOf("retired_card", DashboardCard.QIBLA.id),
        ).normalized()

        assertFalse(normalized.hidden.contains("retired_card"))
        assertTrue(normalized.isEnabled(DashboardCard.QIBLA).not())
    }

    @Test
    fun `an unknown version is stamped to the current one`() {
        val normalized = DashboardConfig(version = 99, order = emptyList()).normalized()

        assertEquals(DashboardConfig.CURRENT_VERSION, normalized.version)
        assertEquals(DashboardCard.entries.map { it.id }, normalized.order)
    }

    @Test
    fun `normalizing an already-normal layout returns the same instance`() {
        // `===` on purpose: not writing when nothing changed depends on this being the same object.
        assertTrue("normalization must be idempotent", default.normalized() === default)
        // A no-op move also returns the same value, so a dead tap writes nothing.
        assertEquals(default, default.moved(DashboardCard.NEXT_PRAYER, delta = -1))
    }
}
