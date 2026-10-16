package io.github.towkirahmedrony.salah.core.domain.tasbih

import io.github.towkirahmedrony.salah.core.model.TasbihSession
import io.github.towkirahmedrony.salah.testing.tasbihSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counting rules, asserted directly.
 *
 * Every promise the feature makes about what a count *does* is one of these functions, so this is where
 * those promises are checked rather than through a screen: a count never goes negative, the target's
 * completion is stamped once, counting past the target is allowed, a reset keeps the target and the
 * session's identity, and a new session takes a new identifier while carrying the target forward.
 *
 * No Android, no clock, no store: the moment each operation happens at is a parameter, so every timestamp
 * below is an exact value rather than a range.
 */
class TasbihCounterTest {

    @Test
    fun `a count increases the count by one and stamps the moment`() {
        val session = tasbihSession(count = 4, startedAtMillis = 100L, updatedAtMillis = 100L)

        val counted = TasbihCounter.counted(session, nowMillis = 250L)

        assertEquals(5, counted.count)
        assertEquals(250L, counted.updatedAtMillis)
        // Nothing else moved: the target, the identifier and the start are the session's own facts.
        assertEquals(session.target, counted.target)
        assertEquals(session.id, counted.id)
        assertEquals(session.startedAtMillis, counted.startedAtMillis)
    }

    @Test
    fun `a count does not go below zero`() {
        val session = tasbihSession(count = 0)

        val undone = TasbihCounter.undone(session, nowMillis = 500L)

        assertEquals(0, undone.count)
        // Nothing counted means nothing changed, including the "last updated" stamp: a write that changes
        // no value should not claim to have changed one.
        assertEquals(session.updatedAtMillis, undone.updatedAtMillis)
    }

    @Test
    fun `taking back a count is the inverse of counting it`() {
        val session = tasbihSession(count = 7, target = 33)

        val roundTrip = TasbihCounter.undone(TasbihCounter.counted(session, 300L), 400L)

        assertEquals(session.count, roundTrip.count)
        assertEquals(session.target, roundTrip.target)
        assertEquals(session.startedAtMillis, roundTrip.startedAtMillis)
        assertFalse(roundTrip.isComplete)
        assertNull(roundTrip.completedAtMillis)
    }

    @Test
    fun `reaching the target completes the session and stamps the moment it was reached`() {
        val session = tasbihSession(count = 32, target = 33)

        val counted = TasbihCounter.counted(session, nowMillis = 900L)

        assertEquals(33, counted.count)
        assertTrue(counted.isComplete)
        assertEquals(900L, counted.completedAtMillis)
    }

    @Test
    fun `counting past the target is allowed and keeps the first completion time`() {
        val completed = TasbihCounter.counted(tasbihSession(count = 32, target = 33), nowMillis = 900L)

        val beyond = TasbihCounter.counted(completed, nowMillis = 1_500L)

        assertEquals(34, beyond.count)
        assertTrue(beyond.isComplete)
        // The moment the target was met is a fact later taps cannot change.
        assertEquals(900L, beyond.completedAtMillis)
        assertEquals(1_500L, beyond.updatedAtMillis)
    }

    @Test
    fun `dropping back below the target un-completes the session`() {
        val completed = tasbihSession(count = 33, target = 33, completedAtMillis = 900L)

        val reduced = TasbihCounter.undone(completed, nowMillis = 1_000L)

        assertEquals(32, reduced.count)
        assertFalse(reduced.isComplete)
        // An incomplete session carrying a completion time would make the history lie.
        assertNull(reduced.completedAtMillis)
    }

    @Test
    fun `resetting clears the count, keeps the target and the identity, and restarts the clock`() {
        val session = tasbihSession(
            id = "session-7",
            count = 41,
            target = 99,
            startedAtMillis = 100L,
            updatedAtMillis = 800L,
            completedAtMillis = 500L,
        )

        val reset = TasbihCounter.reset(session, nowMillis = 2_000L)

        assertEquals(0, reset.count)
        assertEquals(99, reset.target)
        // A reset is not a new session: the identity is what the history and the store key on.
        assertEquals("session-7", reset.id)
        assertEquals(2_000L, reset.startedAtMillis)
        assertEquals(2_000L, reset.updatedAtMillis)
        assertNull(reset.completedAtMillis)
    }

    @Test
    fun `changing the target keeps the count and re-derives completion`() {
        val session = tasbihSession(count = 40, target = 99)

        val lowered = TasbihCounter.retargeted(session, target = 33, nowMillis = 700L)
        assertEquals(40, lowered.count)
        assertEquals(33, lowered.target)
        // The count had already passed the new target, so the session is complete the moment it is lowered.
        assertTrue(lowered.isComplete)
        assertEquals(700L, lowered.completedAtMillis)

        val raised = TasbihCounter.retargeted(lowered, target = 100, nowMillis = 800L)
        assertEquals(40, raised.count)
        assertEquals(100, raised.target)
        // And raising it past the count takes the completion away again.
        assertFalse(raised.isComplete)
        assertNull(raised.completedAtMillis)
    }

    @Test
    fun `a target outside the supported range is brought into it`() {
        val session = tasbihSession(count = 10, target = 33)

        assertEquals(TasbihSession.MIN_TARGET, TasbihCounter.retargeted(session, target = 0, 100L).target)
        assertEquals(TasbihSession.MIN_TARGET, TasbihCounter.retargeted(session, target = -5, 100L).target)
        assertEquals(
            TasbihSession.MAX_TARGET,
            TasbihCounter.retargeted(session, target = Int.MAX_VALUE, 100L).target,
        )
    }

    @Test
    fun `a new session takes a new identifier, starts at zero and carries the target forward`() {
        val previous = tasbihSession(id = "session-1", count = 33, target = 99, startedAtMillis = 100L)

        val restarted = TasbihCounter.restarted(previous, id = "session-2", nowMillis = 5_000L)

        assertEquals("session-2", restarted.id)
        assertEquals(0, restarted.count)
        // The one setting a user expects to survive: re-picking the target after every session would be a
        // tax on the one action this feature exists for.
        assertEquals(99, restarted.target)
        assertEquals(5_000L, restarted.startedAtMillis)
        assertNull(restarted.completedAtMillis)
    }

    @Test
    fun `only a session with something counted can be archived`() {
        assertNull(TasbihCounter.archivable(tasbihSession(count = 0)))

        val counted = tasbihSession(id = "session-9", count = 3)
        assertEquals(counted, TasbihCounter.archivable(counted))
        // An unfinished count is something the user did, so it is archived like any other.
        assertEquals(3, TasbihCounter.archivable(counted)?.count)
    }

    @Test
    fun `the history keeps the newest sessions up to the retention limit`() {
        val history = (1..TasbihSession.HISTORY_LIMIT + 5).map { index -> tasbihSession(id = "session-$index") }

        val retained = TasbihCounter.retained(history)

        assertEquals(TasbihSession.HISTORY_LIMIT, retained.size)
        assertEquals("session-1", retained.first().id)
        assertEquals("session-${TasbihSession.HISTORY_LIMIT}", retained.last().id)
    }

    @Test
    fun `a history shorter than the limit is left alone`() {
        val history = listOf(tasbihSession(id = "session-1"), tasbihSession(id = "session-2"))

        assertEquals(history, TasbihCounter.retained(history))
    }

    @Test
    fun `counting at the ceiling changes nothing and never overflows`() {
        val atCeiling = tasbihSession(count = TasbihSession.MAX_COUNT)

        val counted = TasbihCounter.counted(atCeiling, nowMillis = 9_999L)

        // The cap is checked before the addition, so `count + 1` cannot wrap to a negative number.
        assertEquals(TasbihSession.MAX_COUNT, counted.count)
        assertTrue(counted.count > 0)
        assertEquals(atCeiling, counted)
        assertFalse(counted.canCount)
    }

    @Test
    fun `a count just below the ceiling still counts`() {
        val counted = TasbihCounter.counted(
            tasbihSession(count = TasbihSession.MAX_COUNT - 1),
            nowMillis = 1L,
        )

        assertEquals(TasbihSession.MAX_COUNT, counted.count)
    }

    @Test
    fun `a clock that steps backwards cannot move a timestamp before the session`() {
        val session = tasbihSession(startedAtMillis = 5_000L, updatedAtMillis = 5_000L)

        val counted = TasbihCounter.counted(session, nowMillis = 1_000L)

        assertEquals(1, counted.count)
        // The update is recorded, but it cannot claim to have happened four seconds before the session it
        // belongs to started.
        assertEquals(5_000L, counted.updatedAtMillis)
        assertTrue(counted.updatedAtMillis >= counted.startedAtMillis)
    }
}
