package io.github.towkirahmedrony.salah.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

/**
 * [CountdownFormatter.formatCompactRemaining]: the countdown rendering that never says `0m` while time
 * is still left. [CountdownFormatter.formatCompact] is deliberately left as it was, because the day and
 * night lengths use it and are measurements, not countdowns.
 */
class CountdownFormatterRemainingTest {

    @Test
    fun `an exhausted countdown reads 0m`() {
        assertEquals("0m", CountdownFormatter.formatCompactRemaining(Duration.ZERO))
    }

    @Test
    fun `a negative duration is clamped to 0m`() {
        assertEquals("0m", CountdownFormatter.formatCompactRemaining(Duration.ofMinutes(-5)))
    }

    @Test
    fun `30 seconds left reads under a minute`() {
        assertEquals("<1m", CountdownFormatter.formatCompactRemaining(Duration.ofSeconds(30)))
    }

    @Test
    fun `59 seconds left reads under a minute`() {
        assertEquals("<1m", CountdownFormatter.formatCompactRemaining(Duration.ofSeconds(59)))
    }

    @Test
    fun `a fraction of a second left still reads under a minute`() {
        assertEquals("<1m", CountdownFormatter.formatCompactRemaining(Duration.ofMillis(1)))
    }

    @Test
    fun `exactly 60 seconds reads 1m`() {
        assertEquals("1m", CountdownFormatter.formatCompactRemaining(Duration.ofSeconds(60)))
    }

    @Test
    fun `an hour and minutes reads exactly as formatCompact does`() {
        val remaining = Duration.ofHours(1).plusMinutes(17).plusSeconds(42)

        assertEquals("1h 17m", CountdownFormatter.formatCompactRemaining(remaining))
        assertEquals(CountdownFormatter.formatCompact(remaining), CountdownFormatter.formatCompactRemaining(remaining))
    }

    @Test
    fun `formatCompact itself is unchanged and still reads 0m under a minute`() {
        assertEquals("0m", CountdownFormatter.formatCompact(Duration.ofSeconds(30)))
        assertEquals("0m", CountdownFormatter.formatCompact(Duration.ofSeconds(59)))
    }
}
