package io.github.towkirahmedrony.salah.data.location

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityKind
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityRef
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/**
 * Exercises the real DataStore implementation, not a fake.
 *
 * Two things matter and both are about not losing the user's position: a stored location survives a
 * repository recreation, and the *provenance* of that location survives with it — because a device
 * fix that has lost its timestamp would have to be presented as a current reading, which is exactly
 * the claim the Qibla screen must not make.
 */
class DataStoreLocationRepositoryTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreLocationRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("salah-preferences.preferences_pb")
        }
        repository = DataStoreLocationRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `nothing is configured before anything is stored`() = runTest {
        assertNull(repository.location.first())
    }

    @Test
    fun `a manually entered location round trips`() = runTest {
        val saved = repository.saveManualLocation(
            label = "  Dhaka  ",
            latitude = 23.8103,
            longitude = 90.4125,
            timeZoneId = " Asia/Dhaka ",
        )

        assertTrue(saved)

        val stored = requireNotNull(repository.location.first())
        assertEquals("Dhaka", stored.label)
        assertEquals(23.8103, stored.latitude, 1e-9)
        assertEquals(90.4125, stored.longitude, 1e-9)
        assertEquals("Asia/Dhaka", stored.timeZoneId)
        assertEquals(LocationOrigin.MANUAL_ENTRY, stored.origin)

        // A manual entry carries no acquisition metadata, and must not acquire any by accident.
        assertNull(stored.accuracyMetres)
        assertNull(stored.acquiredAt)
    }

    @Test
    fun `a device fix round trips with its origin, accuracy and time`() = runTest {
        val taken = Instant.parse("2026-10-12T08:55:00Z")

        val saved = repository.saveDeviceLocation(
            label = "Current location",
            latitude = 51.5074,
            longitude = -0.1278,
            timeZoneId = "Europe/London",
            accuracyMetres = 18.5,
            acquiredAtEpochMillis = taken.toEpochMilli(),
        )

        assertTrue(saved)

        val stored = requireNotNull(repository.location.first())
        assertEquals(LocationOrigin.DEVICE_GPS, stored.origin)
        assertEquals(18.5, requireNotNull(stored.accuracyMetres), 1e-9)
        assertEquals(taken, stored.acquiredAt)
    }

    @Test
    fun `invalid input is rejected and nothing is written`() = runTest {
        assertFalse(
            repository.saveManualLocation(
                label = "Nowhere",
                latitude = 91.0,
                longitude = 0.0,
                timeZoneId = "UTC",
            ),
        )
        assertFalse(
            repository.saveManualLocation(
                label = "Nowhere",
                latitude = 0.0,
                longitude = -181.0,
                timeZoneId = "UTC",
            ),
        )
        assertFalse(
            repository.saveManualLocation(
                label = "   ",
                latitude = 0.0,
                longitude = 0.0,
                timeZoneId = "UTC",
            ),
        )
        assertFalse(
            repository.saveManualLocation(
                label = "Nowhere",
                latitude = 0.0,
                longitude = 0.0,
                timeZoneId = "Not/AZone",
            ),
        )

        assertNull(repository.location.first())
    }

    @Test
    fun `a manual entry clears device metadata left by an earlier fix`() = runTest {
        // Otherwise a position the user typed would inherit the accuracy of a position the device
        // observed, which would be a precision claim about coordinates nobody measured.
        repository.saveDeviceLocation(
            label = "Current location",
            latitude = 51.5074,
            longitude = -0.1278,
            timeZoneId = "Europe/London",
            accuracyMetres = 18.5,
            acquiredAtEpochMillis = Instant.parse("2026-10-12T08:55:00Z").toEpochMilli(),
        )

        repository.saveManualLocation(
            label = "London",
            latitude = 51.5,
            longitude = -0.12,
            timeZoneId = "Europe/London",
        )

        val stored = requireNotNull(repository.location.first())
        assertEquals(LocationOrigin.MANUAL_ENTRY, stored.origin)
        assertNull(stored.accuracyMetres)
        assertNull(stored.acquiredAt)
    }

    @Test
    fun `an implausible accuracy is discarded rather than stored`() = runTest {
        // "±0 m" and "±NaN" would both render as a precision claim Salah cannot support.
        listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { accuracy ->
            repository.saveDeviceLocation(
                label = "Current location",
                latitude = 23.8,
                longitude = 90.4,
                timeZoneId = "Asia/Dhaka",
                accuracyMetres = accuracy,
                acquiredAtEpochMillis = 0L,
            )

            assertNull(
                "accuracy $accuracy should have been discarded",
                repository.location.first()?.accuracyMetres,
            )
        }
    }

    @Test
    fun `the location survives a repository recreation`() = runTest {
        repository.saveManualLocation(
            label = "Dhaka",
            latitude = 23.8103,
            longitude = 90.4125,
            timeZoneId = "Asia/Dhaka",
        )

        // A second instance over the same file: this is the process-death case.
        val recreated = DataStoreLocationRepository(dataStore)

        val stored = requireNotNull(recreated.location.first())
        assertEquals("Dhaka", stored.label)
        assertEquals(23.8103, stored.latitude, 1e-9)
    }

    @Test
    fun `a device fix round trips with the locality it was matched to`() = runTest {
        val saved = repository.saveDeviceLocation(
            label = "Mirpur, Dhaka",
            latitude = 23.8124,
            longitude = 90.3641,
            timeZoneId = "Asia/Dhaka",
            accuracyMetres = 18.5,
            acquiredAtEpochMillis = 1_000L,
            area = BangladeshAreaRef(
                divisionId = "BD30",
                divisionName = "Dhaka",
                districtId = "BD3026",
                districtName = "Dhaka",
                upazilaId = "BD30262500",
                upazilaName = "Dhaka North City Corporation",
            ),
            locality = BangladeshLocalityRef(
                name = "Mirpur",
                districtId = "BD3026",
                kind = BangladeshLocalityKind.SUBURB,
            ),
            datasetVersion = "test-dataset",
        )

        assertTrue(saved)

        val stored = requireNotNull(repository.location.first())
        val locality = requireNotNull(stored.locality) { "the locality must survive the write" }
        assertEquals("Mirpur", locality.name)
        assertEquals("BD3026", locality.districtId)
        assertEquals(BangladeshLocalityKind.SUBURB, locality.kind)
        // The locality is recorded *beside* the hierarchy, not instead of it.
        assertEquals("BD30262500", stored.area?.upazilaId)
    }

    @Test
    fun `a write without a locality clears one an earlier fix recorded`() = runTest {
        // A locality describes the coordinates it was matched against. Leaving one behind while storing
        // a different position would attach a neighbourhood the user has left to where they are now —
        // the same rule the hierarchy is written and removed as a set by.
        repository.saveDeviceLocation(
            label = "Mirpur, Dhaka",
            latitude = 23.8124,
            longitude = 90.3641,
            timeZoneId = "Asia/Dhaka",
            accuracyMetres = 18.5,
            acquiredAtEpochMillis = 1_000L,
            locality = BangladeshLocalityRef(
                name = "Mirpur",
                districtId = "BD3026",
                kind = BangladeshLocalityKind.SUBURB,
            ),
        )
        assertNotNull(repository.location.first()?.locality)

        repository.saveManualLocation(
            label = "Office",
            latitude = 23.7461,
            longitude = 90.3742,
            timeZoneId = "Asia/Dhaka",
        )

        val stored = requireNotNull(repository.location.first())
        assertNull("a typed location has no matched neighbourhood", stored.locality)

        // And a device fix that matched nothing clears it too.
        repository.saveDeviceLocation(
            label = "GPS location",
            latitude = 23.7461,
            longitude = 90.3742,
            timeZoneId = "Asia/Dhaka",
            accuracyMetres = 4_000.0,
            acquiredAtEpochMillis = 2_000L,
        )
        assertNull(repository.location.first()?.locality)
    }

    @Test
    fun `a store written before localities existed reads as no locality`() = runTest {
        // The migration case for the new keys: no version is needed, because "no locality recorded" is
        // exactly what such a store contains, and the display policy falls back to the hierarchy.
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("location_label")] = "Dhaka"
            preferences[doublePreferencesKey("location_latitude")] = 23.8103
            preferences[doublePreferencesKey("location_longitude")] = 90.4125
            preferences[stringPreferencesKey("location_time_zone_id")] = "Asia/Dhaka"
            preferences[stringPreferencesKey("location_origin")] = LocationOrigin.DEVICE_GPS.name
        }

        assertNull(requireNotNull(repository.location.first()).locality)
    }

    @Test
    fun `clearing removes the location and leaves other preferences alone`() = runTest {
        val themeKey = stringPreferencesKey("theme_mode")
        dataStore.edit { it[themeKey] = "DARK" }

        repository.saveDeviceLocation(
            label = "Current location",
            latitude = 23.8,
            longitude = 90.4,
            timeZoneId = "Asia/Dhaka",
            accuracyMetres = 12.0,
            acquiredAtEpochMillis = 1_000L,
        )
        assertTrue("the location should be stored before clearing", repository.location.first() != null)

        repository.clearLocation()

        assertNull(repository.location.first())

        // The theme survives, and no location key is left behind for a later read to trip over.
        assertEquals("DARK", dataStore.data.first()[themeKey])
    }

    @Test
    fun `a store written before origins were recorded reads as a manual entry`() = runTest {
        // The migration case, and the reason no schema version is needed: a store from an earlier
        // build simply lacks the newer keys, and a manual entry is the only thing it could have
        // contained.
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("location_label")] = "Dhaka"
            preferences[doublePreferencesKey("location_latitude")] = 23.8103
            preferences[doublePreferencesKey("location_longitude")] = 90.4125
            preferences[stringPreferencesKey("location_time_zone_id")] = "Asia/Dhaka"
        }

        val stored = requireNotNull(repository.location.first())
        assertEquals(LocationOrigin.MANUAL_ENTRY, stored.origin)
        assertNull(stored.acquiredAt)
        assertNull(stored.accuracyMetres)
    }

    @Test
    fun `an unrecognised stored origin falls back to a manual entry`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("location_label")] = "Dhaka"
            preferences[doublePreferencesKey("location_latitude")] = 23.8103
            preferences[doublePreferencesKey("location_longitude")] = 90.4125
            preferences[stringPreferencesKey("location_time_zone_id")] = "Asia/Dhaka"
            preferences[stringPreferencesKey("location_origin")] = "SOMETHING_ELSE"
        }

        assertEquals(
            LocationOrigin.MANUAL_ENTRY,
            requireNotNull(repository.location.first()).origin,
        )
    }

    @Test
    fun `a partially written location reads as no location rather than as a bad one`() = runTest {
        // Only a label: there is no position to report, and inventing one would be worse than
        // reporting nothing.
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("location_label")] = "Dhaka"
        }

        assertNull(repository.location.first())
    }
}
