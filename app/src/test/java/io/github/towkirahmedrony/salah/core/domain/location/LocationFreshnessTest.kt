package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * How old a stored position is, and what the screen is allowed to call it.
 *
 * The rule being enforced: a saved position is never presented as the user's current location. A
 * manually entered location is a *different* thing again — an assertion by the user, which does not
 * decay — and the tests pin that it is not treated as stale.
 */
class LocationFreshnessTest {

    private val now = Instant.parse("2026-10-12T09:00:00Z")

    @Test
    fun `a manually entered location is declared, not aged`() {
        // One year on, a hand-entered hometown is still exactly as valid as the day it was typed.
        val ancient = manual(acquiredAt = now.minus(Duration.ofDays(365)))

        assertEquals(LocationFreshness.DECLARED, LocationFreshness.of(ancient, now))
    }

    @Test
    fun `a manual entry with no timestamp is still declared`() {
        assertEquals(LocationFreshness.DECLARED, LocationFreshness.of(manual(acquiredAt = null), now))
    }

    @Test
    fun `a device fix with no recorded time cannot be aged`() {
        assertEquals(LocationFreshness.UNKNOWN, LocationFreshness.of(device(acquiredAt = null), now))
    }

    @Test
    fun `a device fix from the last ten minutes is fresh`() {
        assertEquals(LocationFreshness.FRESH, LocationFreshness.of(device(now), now))
        assertEquals(
            LocationFreshness.FRESH,
            LocationFreshness.of(device(now.minus(Duration.ofMinutes(1))), now),
        )
    }

    @Test
    fun `the fresh window boundary is inclusive`() {
        assertEquals(
            LocationFreshness.FRESH,
            LocationFreshness.of(device(now.minus(LocationFreshness.FRESH_WINDOW)), now),
        )
        assertEquals(
            LocationFreshness.RECENT,
            LocationFreshness.of(
                device(now.minus(LocationFreshness.FRESH_WINDOW).minusSeconds(1)),
                now,
            ),
        )
    }

    @Test
    fun `a device fix from the last day is recent`() {
        assertEquals(
            LocationFreshness.RECENT,
            LocationFreshness.of(device(now.minus(Duration.ofHours(3))), now),
        )
    }

    @Test
    fun `the recent window boundary is inclusive`() {
        assertEquals(
            LocationFreshness.RECENT,
            LocationFreshness.of(device(now.minus(LocationFreshness.RECENT_WINDOW)), now),
        )
        assertEquals(
            LocationFreshness.STALE,
            LocationFreshness.of(
                device(now.minus(LocationFreshness.RECENT_WINDOW).minusSeconds(1)),
                now,
            ),
        )
    }

    @Test
    fun `an older device fix is stale`() {
        assertEquals(
            LocationFreshness.STALE,
            LocationFreshness.of(device(now.minus(Duration.ofDays(30))), now),
        )
    }

    @Test
    fun `a timestamp in the future is treated as just taken, not as an error`() {
        // A device clock that moved backwards, or a provider with a skewed clock. The position is
        // still real, and refusing to use it would break the feature for a cosmetic reason.
        assertEquals(
            LocationFreshness.FRESH,
            LocationFreshness.of(device(now.plus(Duration.ofHours(2))), now),
        )
    }

    @Test
    fun `only ages that are not a live reading offer a refresh`() {
        assertFalse(LocationFreshness.DECLARED.shouldOfferRefresh)
        assertFalse(LocationFreshness.FRESH.shouldOfferRefresh)

        // A saved position is exactly the case where the user should be offered a fresh one.
        assertTrue(LocationFreshness.RECENT.shouldOfferRefresh)
        assertTrue(LocationFreshness.STALE.shouldOfferRefresh)
        assertTrue(LocationFreshness.UNKNOWN.shouldOfferRefresh)
    }

    @Test
    fun `the thresholds are the documented ones`() {
        assertEquals(Duration.ofMinutes(10), LocationFreshness.FRESH_WINDOW)
        assertEquals(Duration.ofHours(24), LocationFreshness.RECENT_WINDOW)
    }

    private fun manual(acquiredAt: Instant?): LocationInfo = requireNotNull(
        LocationInfo.create(
            label = "Dhaka",
            latitude = 23.8103,
            longitude = 90.4125,
            timeZoneId = "Asia/Dhaka",
            origin = LocationOrigin.MANUAL_ENTRY,
            acquiredAt = acquiredAt,
        ),
    )

    private fun device(acquiredAt: Instant?): LocationInfo = requireNotNull(
        LocationInfo.create(
            label = "Current location",
            latitude = 23.8103,
            longitude = 90.4125,
            timeZoneId = "Asia/Dhaka",
            origin = LocationOrigin.DEVICE_GPS,
            accuracyMetres = 12.0,
            acquiredAt = acquiredAt,
        ),
    )
}
