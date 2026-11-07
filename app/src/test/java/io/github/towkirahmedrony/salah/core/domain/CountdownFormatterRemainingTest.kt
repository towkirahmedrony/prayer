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

    // ---- formatClock ----------------------------------------------------------------------------

    @Test
    fun `the clock reading is always HH_MM_SS`() {
        assertEquals("00:40:12", CountdownFormatter.formatClock(Duration.ofMinutes(40).plusSeconds(12)))
        assertEquals("11:05:09", CountdownFormatter.formatClock(Duration.ofHours(11).plusMinutes(5).plusSeconds(9)))
        assertEquals("00:00:30", CountdownFormatter.formatClock(Duration.ofSeconds(30)))
    }

    @Test
    fun `an exhausted or negative duration reads 00_00_00 on the clock`() {
        assertEquals("00:00:00", CountdownFormatter.formatClock(Duration.ZERO))
        assertEquals("00:00:00", CountdownFormatter.formatClock(Duration.ofMinutes(-3)))
    }

    @Test
    fun `the clock keeps its width across an hour boundary where format does not`() {
        assertEquals("01:00:00", CountdownFormatter.formatClock(Duration.ofHours(1)))
        assertEquals("59:59", CountdownFormatter.format(Duration.ofMinutes(59).plusSeconds(59)))
        assertEquals("00:59:59", CountdownFormatter.formatClock(Duration.ofMinutes(59).plusSeconds(59)))
    }
}
