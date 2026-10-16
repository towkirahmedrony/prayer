package io.github.towkirahmedrony.salah.core.model

import io.github.towkirahmedrony.salah.testing.tasbihSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session's invariants, and the one function that restores them.
 *
 * [TasbihSession.normalized] is the boundary every stored payload passes through, so this is where "a
 * corrupted store cannot break the app" is actually established: an out-of-range count or target, a missing
 * identifier, and a completion time that contradicts the count are each repaired, and each repair is
 * asserted here rather than assumed.
 */
class TasbihSessionTest {

    @Test
    fun `a fresh install begins with the shipped default target and nothing counted`() {
        val unstarted = TasbihSession.unstarted(nowMillis = 42L)

        assertEquals(TasbihSession.DEFAULT_SESSION_ID, unstarted.id)
        assertEquals(0, unstarted.count)
        assertEquals(TasbihSession.DEFAULT_TARGET, unstarted.target)
        assertEquals(unstarted.startedAtMillis, unstarted.updatedAtMillis)
        assertNull(unstarted.completedAtMillis)
        // The shipped default is also the first target offered, so the two cannot drift apart.
        assertEquals(TasbihSession.DEFAULT_TARGET, TasbihSession.TARGET_CHOICES.first())
    }

    @Test
    fun `a count above the ceiling or below zero is clamped`() {
        assertEquals(0, tasbihSession(count = -12).normalized().count)
        assertEquals(TasbihSession.MAX_COUNT, tasbihSession(count = Int.MAX_VALUE).normalized().count)
    }

    @Test
    fun `a target outside the supported range is clamped`() {
        assertEquals(TasbihSession.MIN_TARGET, tasbihSession(target = 0).normalized().target)
        assertEquals(TasbihSession.MIN_TARGET, tasbihSession(target = Int.MIN_VALUE).normalized().target)
        assertEquals(TasbihSession.MAX_TARGET, tasbihSession(target = Int.MAX_VALUE).normalized().target)
    }

    @Test
    fun `a blank identifier is replaced rather than kept`() {
        // An empty identifier is one two sessions could share, which is exactly what the history must not
        // contain.
        assertEquals(TasbihSession.DEFAULT_SESSION_ID, tasbihSession(id = "").normalized().id)
        assertEquals(TasbihSession.DEFAULT_SESSION_ID, tasbihSession(id = "   ").normalized().id)
    }

    @Test
    fun `a completed session with no completion time is given the moment it was last updated`() {
        val repaired = tasbihSession(count = 33, target = 33, completedAtMillis = null)
            .copy(startedAtMillis = 100L, updatedAtMillis = 900L)
            .normalized()

        assertTrue(repaired.isComplete)
        assertEquals(900L, repaired.completedAtMillis)
    }

    @Test
    fun `an incomplete session carrying a completion time has it removed`() {
        val repaired = tasbihSession(count = 5, target = 33, completedAtMillis = 700L).normalized()

        assertFalse(repaired.isComplete)
        assertNull(repaired.completedAtMillis)
    }

    @Test
    fun `an update time before the start is pulled forward to the start`() {
        val repaired = tasbihSession(startedAtMillis = 5_000L, updatedAtMillis = 1_000L).normalized()

        assertEquals(5_000L, repaired.updatedAtMillis)
        assertTrue(repaired.updatedAtMillis >= repaired.startedAtMillis)
    }

    @Test
    fun `normalising an already-valid session changes nothing`() {
        val valid = tasbihSession(
            id = "session-3",
            count = 12,
            target = 99,
            startedAtMillis = 100L,
            updatedAtMillis = 400L,
        )

        assertEquals(valid, valid.normalized())
        // Idempotent, so applying it twice is the same as applying it once.
        assertEquals(valid.normalized(), valid.normalized().normalized())
    }

    @Test
    fun `progress is clamped between zero and one`() {
        assertEquals(0f, tasbihSession(count = 0, target = 33).progress, 0.0001f)
        assertEquals(0.5f, tasbihSession(count = 5, target = 10).progress, 0.0001f)
        assertEquals(1f, tasbihSession(count = 10, target = 10).progress, 0.0001f)
        // Counting past the target reads as a full bar rather than an over-full one.
        assertEquals(1f, tasbihSession(count = 99, target = 33).progress, 0.0001f)
    }

    @Test
    fun `progress is safe against a target that cannot be divided by`() {
        // Unreachable through the counter, which clamps the target to at least one, but a stored payload
        // could carry it before normalisation, and a division by zero here would be a crash.
        assertEquals(0f, tasbihSession(count = 4, target = 0).progress, 0.0001f)
    }

    @Test
    fun `what remains to the target never goes negative`() {
        assertEquals(33, tasbihSession(count = 0, target = 33).remainingToTarget)
        assertEquals(1, tasbihSession(count = 32, target = 33).remainingToTarget)
        assertEquals(0, tasbihSession(count = 33, target = 33).remainingToTarget)
        assertEquals(0, tasbihSession(count = 40, target = 33).remainingToTarget)
    }

    @Test
    fun `only a session at the ceiling refuses another count`() {
        assertTrue(tasbihSession(count = TasbihSession.MAX_COUNT - 1).canCount)
        assertFalse(tasbihSession(count = TasbihSession.MAX_COUNT).canCount)
    }

    @Test
    fun `the offered targets are all inside the supported range and distinct`() {
        assertEquals(
            TasbihSession.TARGET_CHOICES.size,
            TasbihSession.TARGET_CHOICES.distinct().size,
        )
        TasbihSession.TARGET_CHOICES.forEach { target ->
            assertEquals(target, TasbihSession.clampTarget(target))
        }
    }
}
