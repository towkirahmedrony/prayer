package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy.Companion.DISTRICT_CUTOFF
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy.Companion.GEO_RADIUS_M
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy.Companion.GPS_LAT
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy.Companion.GPS_LON
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy.Companion.INCLUDE_DENY
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy.Companion.NEARBY_CUTOFF
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy.Companion.NEARBY_GPS
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy.Companion.RADIUS_DEG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LocationSetupTest {

    @Test
    fun `first launch has no saved location and starts at the setup gate`() {
        val e = LocationSetupEntity.empty(CLOCK, ZoneId.systemDefault())
        assertEquals("New installation is not set up", LocationSetupState.NOT_SETUP, e.state)
        assertEquals("New installation has no location", LocationInfo(), e.location)
        assertEquals("New installation is not revalidated", false, e.revalidated)
        assertEquals("Eternal last-used timestamp", 0L, e.lastUsedAt)
    }

    @Test
    fun `manual completion without GPS permission survives`() {
        val e = LocationSetupEntity.empty(CLOCK, ZoneId.systemDefault())
        val chosen = LocationInfo(
            locationSource = LocationSource.MANUAL,
            locationAccuracyMeters = null,
            lastGpsFixAt = null,
            locationMetadata = LocationMetadata.STALE,
        )
        val result = e.tryCompleteManual(chosen, instant(1_700_000_000))
        assertEquals("Manual completion is complete but not valid yet", LocationSetupState.MANUAL_COMPLETE, result.state)
        assertEquals("Manual completion is not revalidated", false, result.revalidated)
        assertEquals("Manual completion keeps the chosen location", chosen, result.location)
        assertTrue("Manual completion updates last used timestamp", result.lastUsedAt > 0L)
    }

    @Test
    fun `GPS completion validates the fix and completes setup`() {
        val e = LocationSetupEntity.empty(CLOCK, ZoneId.systemDefault())
        val g = LocationGpsFix(
            latitudeDegrees = 23.8103,
            longitudeDegrees = 90.4125,
            accuracyMeters = 12.0,
            timestamp = instant(1_700_000_000),
        )
        val location = LocationInfo(
            locationSource = LocationSource.GPS,
            locationAccuracyMeters = 12.0,
            lastGpsFixAt = g.timestamp,
            locationMetadata = LocationMetadata.FRESH,
        )
        val result = e.tryGpsComplete(g, location, instant(1_700_000_000))
        assertEquals("Valid GPS fix completes setup", LocationSetupState.SETUP_COMPLETE, result.state)
        assertEquals("GPS state sets the location", location, result.location)
        assertTrue("GPS state revalidates", result.revalidated)
        assertEquals("GPS state updates last used", result.lastUsedAt, g.timestamp.toEpochMilli())
    }

    @Test
    fun `GPS outside Bangladesh is rejected and the user can retry`() {
        val e = LocationSetupEntity.empty(CLOCK, ZoneId.systemDefault())
        val g = LocationGpsFix(
            latitudeDegrees = 22.5726,
            longitudeDegrees = 88.3639,
            accuracyMeters = 20.0,
            timestamp = instant(1_700_000_000),
        )
        val location = LocationInfo(
            locationSource = LocationSource.GPS,
            locationAccuracyMeters = 20.0,
            lastGpsFixAt = g.timestamp,
            locationMetadata = LocationMetadata.OUT_OF_COUNTRY,
        )
        val result = e.tryGpsComplete(g, location, instant(1_700_000_000))
        assertEquals("Out-of-country GPS is not setup", LocationSetupState.NOT_SETUP, result.state)
        assertEquals("Out-of-country GPS keeps the stale pseudo-location", LocationInfo(), result.location)
    }

    @Test
    fun `denied permissions do not block manual completion`() {
        val e = LocationSetupEntity.empty(CLOCK, ZoneId.systemDefault())
        val t = LocationDeniedState(INCLUDE_DENY, Clock.REAL)
        val result = e.tryDenied(t)
        assertEquals("Denied permission keeps the existing state", LocationSetupState.NOT_SETUP, result.state)
        assertTrue("Denied permission is revalidated", result.revalidated)
        assertEquals("Denied permission clears cached GPS fix", 0L, result.lastUsedAt)
        assertTrue("Denied permission clears cached last used", result.lastUsedAt > 0L)
    }

    @Test
    fun `manual completion clears the cached GPS fix`() {
        val e = LocationSetupEntity.empty(CLOCK, ZoneId.systemDefault())
        val g = LocationGpsFix(23.8103, 90.4125, 12.0, instant(1_000))
        e.tryGpsComplete(g, LocationInfo(), instant(1_000))
        val result = e.tryCompleteManual(LocationInfo(), instant(1_000))
        assertEquals("Manual completion clears the cached GPS fix", 0L, result.lastUsedAt)
    }

    @Test
    fun `revalidation detects a stale location after a dataset version change`() {
        val e = LocationSetupEntity.empty(CLOCK, ZoneId.systemDefault())
        val g = LocationGpsFix(23.8103, 90.4125, 12.0, instant(1_000))
        e.tryGpsComplete(g, LocationInfo(), instant(1_000))
        val v1 = LocationSetupState.SETUP_COMPLETE
        val v2 = LocationSetupState.SETUP_COMPLETE
        val stale = LocationSetupEntity(
            state = v1,
            location = LocationInfo(),
            revalidated = true,
            lastUsedAt = 1700000000L,
            datasetVersion = "0.0.9",
        )
        val fresh = LocationSetupEntity(
            state = v1,
            location = LocationInfo(),
            revalidated = false,
            lastUsedAt = 1700000000L,
            datasetVersion = "0.1.0",
        )
        assertTrue("Revalidation treats the stale dataset as invalid", stale.revalidate("0.1.0").state is LocationSetupState.SETUP_COMPLETE)
        assertTrue("Revalidation treats the current dataset as valid", fresh.revalidate("0.1.0").state is LocationSetupState.SETUP_COMPLETE)
    }

    @Test
    fun `revalidation restores the saved location when geography changed`() {
        val e = LocationSetupEntity.empty(CLOCK, ZoneId.systemDefault())
        val g = LocationGpsFix(23.8103, 90.4125, 12.0, instant(1_000))
        e.tryGpsComplete(g, LocationInfo(), instant(1_000))
        val stale = LocationSetupEntity(
            state = LocationSetupState.SETUP_COMPLETE,
            location = LocationInfo(),
            revalidated = true,
            lastUsedAt = 1700000000L,
            datasetVersion = "0.0.9",
        )
        val recovered = stale.revalidate("0.1.0")
        assertEquals("Revalidation restores the saved location", recovered.state, LocationSetupState.SETUP_COMPLETE)
        assertEquals("Revalidation restores the saved location", recovered.location, LocationInfo())
        assertTrue("Revalidation clears the stale flag", recovered.revalidated)
    }
}

private fun instant(millisSinceEpoch: Long): Instant = Instant.ofEpochMilli(millisSinceEpoch)
