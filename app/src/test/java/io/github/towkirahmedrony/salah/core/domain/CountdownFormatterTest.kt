package io.github.towkirahmedrony.salah.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class CountdownFormatterTest {

    @Test
    fun `formats durations longer than an hour as H MM SS`() {
        assertEquals(
            "2:05:03",
            CountdownFormatter.format(Duration.ofHours(2).plusMinutes(5).plusSeconds(3)),
        )
    }

    @Test
    fun `formats durations under an hour as MM SS`() {
        assertEquals("18:00", CountdownFormatter.format(Duration.ofMinutes(18)))
        assertEquals("01:30", CountdownFormatter.format(Duration.ofSeconds(90)))
    }

    @Test
    fun `clamps negative durations to zero`() {
        assertEquals("00:00", CountdownFormatter.format(Duration.ofMinutes(-5)))
    }

    @Test
    fun `formats zero as zero`() {
        assertEquals("00:00", CountdownFormatter.format(Duration.ZERO))
    }

    @Test
    fun `formats the compact reading as hours and minutes above an hour`() {
        assertEquals(
            "1h 17m",
            CountdownFormatter.formatCompact(Duration.ofHours(1).plusMinutes(17).plusSeconds(42)),
        )
        assertEquals(
            "11h 38m",
            CountdownFormatter.formatCompact(Duration.ofHours(11).plusMinutes(38)),
        )
    }

    @Test
    fun `formats the compact reading as minutes below an hour`() {
        assertEquals("17m", CountdownFormatter.formatCompact(Duration.ofMinutes(17).plusSeconds(59)))
        assertEquals("0m", CountdownFormatter.formatCompact(Duration.ZERO))
    }

    @Test
    fun `the compact reading drops seconds rather than rounding time up`() {
        // Two seconds past the minute still reads as that minute, never as the next one.
        assertEquals("5m", CountdownFormatter.formatCompact(Duration.ofMinutes(5).plusSeconds(2)))
    }

    @Test
    fun `the compact reading clamps negative durations to zero`() {
        assertEquals("0m", CountdownFormatter.formatCompact(Duration.ofMinutes(-5)))
    }
}
