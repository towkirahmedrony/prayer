package io.github.towkirahmedrony.salah.core.domain.qibla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which location provider Salah asks, and in what order.
 *
 * The ordering is a real product decision rather than an implementation detail: when the user has
 * granted only approximate location there is no reason to power up the GNSS receiver for a bearing
 * that barely notices a two-kilometre error, so the network provider leads. When the user has asked
 * for precise location, they get it.
 */
class LocationProviderSelectionTest {

    @Test
    fun `precise location asks the GNSS receiver first`() {
        assertEquals(
            listOf(LocationSource.GPS, LocationSource.NETWORK, LocationSource.PASSIVE),
            LocationProviderSelection.candidates(LocationPrecision.PRECISE),
        )
    }

    @Test
    fun `approximate location asks the cheapest provider first`() {
        // Battery and privacy in one decision: a coarse fix is enough for a Qibla bearing, so the
        // high-power provider is the fallback rather than the first choice.
        assertEquals(
            listOf(LocationSource.NETWORK, LocationSource.PASSIVE, LocationSource.GPS),
            LocationProviderSelection.candidates(LocationPrecision.APPROXIMATE),
        )
    }

    @Test
    fun `every precision offers every provider exactly once`() {
        LocationPrecision.entries.forEach { precision ->
            val candidates = LocationProviderSelection.candidates(precision)

            assertEquals(
                "coverage for $precision",
                LocationSource.entries.toSet(),
                candidates.toSet(),
            )
            assertEquals("duplicates for $precision", candidates.size, candidates.toSet().size)
        }
    }

    @Test
    fun `the best enabled provider is the first candidate that is switched on`() {
        val selected = LocationProviderSelection.bestEnabled(LocationPrecision.PRECISE) { source ->
            source != LocationSource.GPS
        }

        assertEquals(LocationSource.NETWORK, selected)
    }

    @Test
    fun `a device with only the last resort available still gets a provider`() {
        // A device indoors with no GNSS fix and no network location, but which can still be told
        // where it is by another app: the passive provider is the honest answer rather than a
        // failure.
        val selected = LocationProviderSelection.bestEnabled(LocationPrecision.PRECISE) { source ->
            source == LocationSource.PASSIVE
        }

        assertEquals(LocationSource.PASSIVE, selected)
    }

    @Test
    fun `no enabled provider is reported as nothing rather than guessed at`() {
        assertNull(LocationProviderSelection.bestEnabled(LocationPrecision.PRECISE) { false })
        assertNull(LocationProviderSelection.bestEnabled(LocationPrecision.APPROXIMATE) { false })
    }

    @Test
    fun `the ordering respects the requested precision when every provider is available`() {
        val enabled = { _: LocationSource -> true }

        assertEquals(
            LocationSource.GPS,
            LocationProviderSelection.bestEnabled(LocationPrecision.PRECISE, enabled),
        )
        assertEquals(
            LocationSource.NETWORK,
            LocationProviderSelection.bestEnabled(LocationPrecision.APPROXIMATE, enabled),
        )
    }

    @Test
    fun `the two orderings prefer different providers`() {
        // A regression guard on the decision itself: if someone collapses the two orders into one,
        // this fails and the reason has to be argued rather than quietly lost.
        assertTrue(
            LocationProviderSelection.candidates(LocationPrecision.PRECISE).first() !=
                LocationProviderSelection.candidates(LocationPrecision.APPROXIMATE).first(),
        )
    }
}
