package io.github.towkirahmedrony.salah.data.tasbih

import io.github.towkirahmedrony.salah.core.model.TasbihSession
import io.github.towkirahmedrony.salah.testing.tasbihSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of a session, including everything that can be wrong with it.
 *
 * Two properties matter more than the round trip. The first is that **decoding is total**: a payload this
 * codec did not write produces `null` rather than an exception or a session built from whatever it could
 * salvage, because a session read with the wrong count is indistinguishable from the truth while a session
 * reported as absent is recoverable. The second is that **history decoding is per entry**: one damaged
 * session costs the user that session, not the list.
 */
class TasbihSessionCodecTest {

    @Test
    fun `a session survives a round trip`() {
        val session = tasbihSession(
            id = "session-1",
            count = 34,
            target = 99,
            startedAtMillis = 1_700_000_000_000L,
            updatedAtMillis = 1_700_000_005_000L,
            completedAtMillis = 1_700_000_004_000L,
        )

        assertEquals(session, TasbihSessionCodec.decode(TasbihSessionCodec.encode(session)))
    }

    @Test
    fun `a session that has not reached its target round trips its absent completion time`() {
        val session = tasbihSession(id = "session-2", count = 3, target = 33)

        val decoded = TasbihSessionCodec.decode(TasbihSessionCodec.encode(session))

        assertEquals(session, decoded)
        assertNull(decoded?.completedAtMillis)
    }

    @Test
    fun `the encoded form is the documented six fields`() {
        val encoded = TasbihSessionCodec.encode(
            tasbihSession(id = "abc", count = 2, target = 33, startedAtMillis = 10L, updatedAtMillis = 20L),
        )

        assertEquals(
            listOf("abc", "2", "33", "10", "20", "-"),
            encoded.split(TasbihSessionCodec.FIELD_SEPARATOR),
        )
    }

    @Test
    fun `an out of range session is normalised on the way in rather than written as it stands`() {
        val encoded = TasbihSessionCodec.encode(tasbihSession(count = Int.MAX_VALUE, target = -1))

        val decoded = TasbihSessionCodec.decode(encoded)

        assertEquals(TasbihSession.MAX_COUNT, decoded?.count)
        assertEquals(TasbihSession.MIN_TARGET, decoded?.target)
    }

    @Test
    fun `nothing is decoded from nothing`() {
        assertNull(TasbihSessionCodec.decode(null))
        assertNull(TasbihSessionCodec.decode(""))
    }

    @Test
    fun `a payload with the wrong number of fields is rejected`() {
        assertNull(TasbihSessionCodec.decode("session-1|5|33"))
        assertNull(TasbihSessionCodec.decode("session-1|5|33|0|0|-|extra"))
    }

    @Test
    fun `a payload whose numbers are not numbers is rejected`() {
        assertNull(TasbihSessionCodec.decode("session-1|five|33|0|0|-"))
        assertNull(TasbihSessionCodec.decode("session-1|5|33|soon|0|-"))
        assertNull(TasbihSessionCodec.decode("session-1|5|33|0|0|never"))
    }

    @Test
    fun `a payload with a blank identifier is rejected`() {
        // A blank identifier is one two sessions could share. normalizing would replace it, but the point of
        // rejecting is that this payload is not one this codec wrote, so its other fields are not to be
        // trusted either.
        assertNull(TasbihSessionCodec.decode("|5|33|0|0|-"))
    }

    @Test
    fun `a payload whose identifier carries the entry separator is rejected`() {
        // Otherwise an identifier could split into a second session when it is written into a history.
        assertNull(TasbihSessionCodec.decode("session-1${TasbihSessionCodec.ENTRY_SEPARATOR}x|5|33|0|0|-"))
    }

    @Test
    fun `a completion time that contradicts the count is repaired on decode`() {
        val completed = TasbihSessionCodec.decode("session-1|33|33|0|900|-")
        assertEquals(900L, completed?.completedAtMillis)

        val incomplete = TasbihSessionCodec.decode("session-1|5|33|0|900|700")
        assertNull(incomplete?.completedAtMillis)
    }

    @Test
    fun `an empty history decodes to an empty list`() {
        assertTrue(TasbihSessionCodec.decodeHistory(null).isEmpty())
        assertTrue(TasbihSessionCodec.decodeHistory("").isEmpty())
        assertTrue(TasbihSessionCodec.decodeHistory("   ").isEmpty())
    }

    @Test
    fun `a history round trips in order and keeps every session distinct`() {
        val history = listOf(
            tasbihSession(id = "session-3", count = 33, target = 33, startedAtMillis = 300L),
            tasbihSession(id = "session-2", count = 12, target = 99, startedAtMillis = 200L),
            tasbihSession(id = "session-1", count = 4, target = 33, startedAtMillis = 100L),
        )

        val decoded = TasbihSessionCodec.decodeHistory(TasbihSessionCodec.encodeHistory(history))

        // Newest first, and not re-sorted: the stored order is the order.
        assertEquals(history, decoded)
        assertEquals(history.map { it.id }, decoded.map { it.id })
    }

    @Test
    fun `one damaged entry costs only that entry`() {
        val good = TasbihSessionCodec.encode(tasbihSession(id = "session-1", count = 7))
        val damaged = "not-a-session"

        val decoded = TasbihSessionCodec.decodeHistory(
            listOf(good, damaged, TasbihSessionCodec.encode(tasbihSession(id = "session-3", count = 9)))
                .joinToString(TasbihSessionCodec.ENTRY_SEPARATOR),
        )

        assertEquals(listOf("session-1", "session-3"), decoded.map { it.id })
    }

    @Test
    fun `a history of nothing but damaged entries decodes to nothing`() {
        val decoded = TasbihSessionCodec.decodeHistory(
            listOf("", "x|y", "1|2|3").joinToString(TasbihSessionCodec.ENTRY_SEPARATOR),
        )

        assertTrue(decoded.isEmpty())
    }
}
