package io.github.towkirahmedrony.salah.data.location

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Stores a manually entered location locally with DataStore.
 *
 * Nothing here depends on the network or on a location permission, so a configured location is
 * always available offline. Invalid input is rejected by [LocationInfo.create] and reported to
 * the caller as `false`; the stored value is never partially written.
 */
class DataStoreLocationRepository(
    private val dataStore: DataStore<Preferences>,
) : LocationRepository {

    override val location: Flow<LocationInfo?> = dataStore.data
        .catch { error ->
            // A corrupt or unreadable store must not crash the app: treat it as "no location".
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val label = preferences[Keys.LABEL] ?: return@map null
            val latitude = preferences[Keys.LATITUDE] ?: return@map null
            val longitude = preferences[Keys.LONGITUDE] ?: return@map null
            val timeZoneId = preferences[Keys.TIME_ZONE_ID] ?: return@map null
            LocationInfo.create(
                label = label,
                latitude = latitude,
                longitude = longitude,
                timeZoneId = timeZoneId,
                origin = LocationOrigin.MANUAL_ENTRY,
            )
        }

    override suspend fun saveManualLocation(
        label: String,
        latitude: Double,
        longitude: Double,
        timeZoneId: String,
    ): Boolean {
        val validated = LocationInfo.create(
            label = label,
            latitude = latitude,
            longitude = longitude,
            timeZoneId = timeZoneId,
            origin = LocationOrigin.MANUAL_ENTRY,
        ) ?: return false

        dataStore.edit { preferences ->
            preferences[Keys.LABEL] = validated.label
            preferences[Keys.LATITUDE] = validated.latitude
            preferences[Keys.LONGITUDE] = validated.longitude
            preferences[Keys.TIME_ZONE_ID] = validated.timeZoneId
        }
        return true
    }

    override suspend fun clearLocation() {
        // Only the location keys are removed: user preferences such as the theme are kept.
        dataStore.edit { preferences ->
            preferences.remove(Keys.LABEL)
            preferences.remove(Keys.LATITUDE)
            preferences.remove(Keys.LONGITUDE)
            preferences.remove(Keys.TIME_ZONE_ID)
        }
    }

    private object Keys {
        val LABEL = stringPreferencesKey("location_label")
        val LATITUDE = doublePreferencesKey("location_latitude")
        val LONGITUDE = doublePreferencesKey("location_longitude")
        val TIME_ZONE_ID = stringPreferencesKey("location_time_zone_id")
    }
}
