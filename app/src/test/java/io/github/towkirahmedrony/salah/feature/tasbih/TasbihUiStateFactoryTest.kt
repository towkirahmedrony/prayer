package io.github.towkirahmedrony.salah.feature.tasbih

import io.github.towkirahmedrony.salah.core.model.TasbihSession
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import io.github.towkirahmedrony.salah.testing.tasbihSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * What the screen actually receives, including the parts that only a Bengali interface shows.
 *
 * The numerals are the reason this class exists as a class rather than as formatting inside a composable:
 * "the counter reads ৩৩ in Bengali" is a statement about a value, and a statement about a value can be
 * asserted here without a device, a composition or a resource context.
 */
class TasbihUiStateFactoryTest {

    private val factory = TasbihUiStateFactory()
    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")

    private fun ready(
        session: TasbihSession = tasbihSession(),
        history: List<TasbihSession> = emptyList(),
        hapticsEnabled: Boolean = true,
        locale: Locale = Locale.ENGLISH,
        timeFormat: TimeFormatPreference = TimeFormatPreference.TWENTY_FOUR_HOUR,
        message: TasbihMessage? = null,
    ): TasbihUiState.Ready = factory.create(
        session = session,
        history = history,
        hapticsEnabled = hapticsEnabled,
        locale = locale,
        timeFormat = timeFormat,
        zone = zone,
        message = message,
    )

    @Test
    fun `an English interface writes the count and the target in Western numerals`() {
        val state = ready(session = tasbihSession(count = 7, target = 99))

        assertEquals("7", state.countDigits)
        assertEquals("99", state.targetDigits)
        assertEquals("92", state.remainingDigits)
        assertEquals("30", state.historyLimitDigits)
    }

    @Test
    fun `a Bengali interface writes them in Bengali numerals`() {
        val state = ready(
            session = tasbihSession(count = 7, target = 99),
            locale = Locale.forLanguageTag("bn"),
        )

        assertEquals("৭", state.countDigits)
        assertEquals("৯৯", state.targetDigits)
        assertEquals("৯২", state.remainingDigits)
        // The retention rule is stated with the same numerals as the counts it is talking about.
        assertEquals("৩০", state.historyLimitDigits)
    }

    @Test
    fun `progress is the fraction of the target that has been counted`() {
        assertEquals(0.5f, ready(session = tasbihSession(count = 5, target = 10)).progress, 0.0001f)
        // Clamped, so counting past the target is still a full bar rather than an over-full one.
        assertEquals(1f, ready(session = tasbihSession(count = 40, target = 10)).progress, 0.0001f)
    }

    @Test
    fun `a completed session reports completion and stops counting what remains`() {
        val state = ready(session = tasbihSession(count = 33, target = 33, completedAtMillis = 500L))

        assertTrue(state.isComplete)
        // "0 to go" beside a completion notice would be two ways of saying the same thing.
        assertNull(state.remainingDigits)
        assertFalse(state.hasCountedPastTarget)
    }

    @Test
    fun `a session counted past its target says so`() {
        val state = ready(session = tasbihSession(count = 35, target = 33, completedAtMillis = 500L))

        assertTrue(state.isComplete)
        assertTrue(state.hasCountedPastTarget)
    }

    @Test
    fun `the counters that can be disabled are disabled for the right reasons`() {
        val empty = ready(session = tasbihSession(count = 0, target = 33))
        assertFalse("nothing counted means nothing to take back", empty.canUndo)
        assertTrue(empty.canCount)

        val atCeiling = ready(session = tasbihSession(count = TasbihSession.MAX_COUNT, target = 33))
        assertFalse("the ceiling is the one count that cannot be recorded", atCeiling.canCount)
        assertTrue(atCeiling.canUndo)
    }

    @Test
    fun `the offered targets are in order with the session's own marked`() {
        val state = ready(session = tasbihSession(target = 99))

        assertEquals(TasbihSession.TARGET_CHOICES, state.targetChoices.map { it.target })
        assertEquals(listOf(false, true, false, false, false), state.targetChoices.map { it.selected })
        assertEquals("99", state.targetChoices.first { it.selected }.label)
    }

    @Test
    fun `a target the picker does not offer is shown rather than replaced by one it does`() {
        // A value written by an older build, or restored from a backup. Silently rewriting it would change
        // what the user is counting towards.
        val state = ready(session = tasbihSession(target = 7))

        assertEquals(7, state.target)
        assertTrue(state.targetChoices.any { it.target == 7 && it.selected })
        assertEquals(
            (TasbihSession.TARGET_CHOICES + 7).sorted(),
            state.targetChoices.map { it.target },
        )
    }

    @Test
    fun `a finished session is rendered with its count, its target, its outcome and its time`() {
        val archived = tasbihSession(
            id = "session-2",
            count = 33,
            target = 33,
            startedAtMillis = Instant.parse("2026-10-16T06:30:00Z").toEpochMilli(),
            completedAtMillis = Instant.parse("2026-10-16T06:31:00Z").toEpochMilli(),
        )

        val row = ready(history = listOf(archived)).history.single()

        assertEquals("session-2", row.id)
        assertEquals("33", row.countDigits)
        assertEquals("33", row.targetDigits)
        assertTrue(row.isComplete)
        // The device's own zone, so the time is the wall clock the user was looking at: 06:30 UTC is 12:30
        // in Dhaka.
        assertEquals("16 Oct 2026 · 12:30", row.timestampLabel)
    }

    @Test
    fun `a Bengali history row is written in Bengali script, digits included`() {
        val archived = tasbihSession(
            id = "session-2",
            count = 33,
            target = 33,
            startedAtMillis = Instant.parse("2026-10-16T06:30:00Z").toEpochMilli(),
        )

        val row = ready(history = listOf(archived), locale = Locale.forLanguageTag("bn")).history.single()

        // The date comes from the platform with its month name, so only what this class decides is asserted:
        // the time is in Bengali numerals, and no Western digit survives anywhere in the label.
        assertTrue("the time is not in Bengali numerals: ${row.timestampLabel}", row.timestampLabel.contains("১২:৩০"))
        assertFalse(
            "a Western digit survived into a Bengali label: ${row.timestampLabel}",
            row.timestampLabel.any { character -> character in '0'..'9' },
        )
        assertTrue(row.timestampLabel.contains("১৬"))
        assertTrue(row.timestampLabel.contains("২০২৬"))
    }

    @Test
    fun `the clock format follows the app's own preference`() {
        val archived = tasbihSession(
            startedAtMillis = Instant.parse("2026-10-16T06:30:00Z").toEpochMilli(),
        )

        val twelveHour = ready(
            history = listOf(archived),
            timeFormat = TimeFormatPreference.TWELVE_HOUR,
        ).history.single()

        assertTrue(twelveHour.timestampLabel.contains("12:30"))
        assertTrue(twelveHour.timestampLabel.contains("PM"))
    }

    @Test
    fun `the haptics preference and the transient message are passed through`() {
        val state = ready(hapticsEnabled = false, message = TasbihMessage.SAVE_FAILED)

        assertFalse(state.hapticsEnabled)
        assertEquals(TasbihMessage.SAVE_FAILED, state.message)
    }

    @Test
    fun `an out of range session is normalised before it is rendered`() {
        // The store normalises on the way out, but a session built by a test — or by a newer build — must
        // not reach the screen in a state it cannot draw.
        val state = ready(session = tasbihSession(count = Int.MAX_VALUE, target = -4))

        assertEquals(TasbihSession.MAX_COUNT.toString(), state.countDigits)
        assertEquals(TasbihSession.MIN_TARGET.toString(), state.targetDigits)
        assertTrue(state.isComplete)
    }

    @Test
    fun `the state carries the session's identity so a write can be attributed to it`() {
        assertEquals("session-42", ready(session = tasbihSession(id = "session-42")).sessionId)
    }
}
