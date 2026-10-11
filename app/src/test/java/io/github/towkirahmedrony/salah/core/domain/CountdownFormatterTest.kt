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
}
