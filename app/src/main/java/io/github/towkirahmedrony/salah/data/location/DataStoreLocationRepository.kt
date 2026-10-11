package io.github.towkirahmedrony.salah.data.location

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant

/**
 * Stores the user's location locally with DataStore.
 *
 * Nothing here depends on the network or on a location permission, so a configured location is
 * always available offline. Invalid input is rejected by [LocationInfo.create] and reported to
 * the caller as `false`; the stored value is never partially written.
 *
 * **Provenance is part of the value.** A manually typed position and a position the device observed
 * are both stored, but with their origin, reported accuracy and acquisition time, so consumers can
 * tell a live fix from a saved one. Writing them together in one `edit` block keeps that atomic: a
 * stored location can never be a device fix whose timestamp belongs to the previous reading.
 *
 * **No schema version is needed.** DataStore Preferences is a key–value store, and every new key
 * here is optional. A store written by an earlier build simply lacks [Keys.ORIGIN] and the
 * acquisition keys, and reads as a manual entry with unknown freshness — which is exactly what such
 * a store contains. Existing locations are therefore preserved rather than migrated or discarded.
 */
class DataStoreLocationRepository(
    private val dataStore: DataStore<Preferences>,
) : LocationRepository {

    override val location: Flow<LocationInfo?> = dataStore.data
        .catch { error ->
            // A corrupt or unreadable store must not crash the app: treat it as "no location".
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toLocation() }

    override suspend fun saveManualLocation(
        label: String,
        latitude: Double,
        longitude: Double,
        timeZoneId: String,
    ): Boolean = store(
        validated = LocationInfo.create(
            label = label,
            latitude = latitude,
            longitude = longitude,
            timeZoneId = timeZoneId,
            origin = LocationOrigin.MANUAL_ENTRY,
        ),
    )

    override suspend fun saveDeviceLocation(
        label: String,
        latitude: Double,
        longitude: Double,
        timeZoneId: String,
        accuracyMetres: Double?,
        acquiredAtEpochMillis: Long,
    ): Boolean = store(
        validated = LocationInfo.create(
            label = label,
            latitude = latitude,
            longitude = longitude,
            timeZoneId = timeZoneId,
            origin = LocationOrigin.DEVICE_GPS,
            accuracyMetres = accuracyMetres,
            acquiredAt = Instant.ofEpochMilli(acquiredAtEpochMillis),
        ),
    )

    override suspend fun clearLocation() {
        // Only the location keys are removed: user preferences such as the theme are kept.
        dataStore.edit { preferences ->
            preferences.remove(Keys.LABEL)
            preferences.remove(Keys.LATITUDE)
            preferences.remove(Keys.LONGITUDE)
            preferences.remove(Keys.TIME_ZONE_ID)
            preferences.remove(Keys.ORIGIN)
            preferences.remove(Keys.ACCURACY_METRES)
            preferences.remove(Keys.ACQUIRED_AT_EPOCH_MILLIS)
        }
    }

    private suspend fun store(validated: LocationInfo?): Boolean {
        if (validated == null) return false

        dataStore.edit { preferences ->
            preferences[Keys.LABEL] = validated.label
            preferences[Keys.LATITUDE] = validated.latitude
            preferences[Keys.LONGITUDE] = validated.longitude
            preferences[Keys.TIME_ZONE_ID] = validated.timeZoneId
            preferences[Keys.ORIGIN] = validated.origin.name

            // A manual entry has neither accuracy nor a time, so any previous device-fix metadata
            // must go. Leaving it would attach a stale accuracy to a position the user typed.
            validated.accuracyMetres?.let { preferences[Keys.ACCURACY_METRES] = it }
                ?: preferences.remove(Keys.ACCURACY_METRES)
            validated.acquiredAt?.let { preferences[Keys.ACQUIRED_AT_EPOCH_MILLIS] = it.toEpochMilli() }
                ?: preferences.remove(Keys.ACQUIRED_AT_EPOCH_MILLIS)
        }
        return true
    }

    private fun Preferences.toLocation(): LocationInfo? {
        val label = this[Keys.LABEL] ?: return null
        val latitude = this[Keys.LATITUDE] ?: return null
        val longitude = this[Keys.LONGITUDE] ?: return null
        val timeZoneId = this[Keys.TIME_ZONE_ID] ?: return null

        // A store written before origins were recorded, or one holding an unrecognised value, is
        // read as a manual entry: that is the only origin such a store could have contained.
        val origin = this[Keys.ORIGIN]
            ?.let { stored -> LocationOrigin.entries.firstOrNull { it.name == stored } }
            ?: LocationOrigin.MANUAL_ENTRY

        return LocationInfo.create(
            label = label,
            latitude = latitude,
            longitude = longitude,
            timeZoneId = timeZoneId,
            origin = origin,
            accuracyMetres = this[Keys.ACCURACY_METRES],
            acquiredAt = this[Keys.ACQUIRED_AT_EPOCH_MILLIS]?.let { Instant.ofEpochMilli(it) },
        )
    }

    private object Keys {
        val LABEL = stringPreferencesKey("location_label")
        val LATITUDE = doublePreferencesKey("location_latitude")
        val LONGITUDE = doublePreferencesKey("location_longitude")
        val TIME_ZONE_ID = stringPreferencesKey("location_time_zone_id")
        val ORIGIN = stringPreferencesKey("location_origin")
        val ACCURACY_METRES = doublePreferencesKey("location_accuracy_metres")
        val ACQUIRED_AT_EPOCH_MILLIS = longPreferencesKey("location_acquired_at_epoch_millis")
    }
}
