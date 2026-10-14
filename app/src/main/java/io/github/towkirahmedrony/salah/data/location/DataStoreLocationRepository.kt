package io.github.towkirahmedrony.salah.data.location

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityKind
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityRef
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
 *
 * The same applies to the keys added for the Bangladesh area system (country, the division/district/
 * upazila ids and names, and the dataset version): an older store has none of them, reads as a
 * location whose area is unknown, and is validated from its coordinates instead. That is why the
 * location screen can be opened on a device that has been running Salah since before areas existed,
 * and why nothing is migrated destructively.
 *
 * The locality keys added for the neighbourhood layer are the same story a third time: absent from an
 * older store, read as "no locality was established", and written or removed as a set so a locality can
 * never survive beside a coordinate it does not belong to.
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
        acquiredAtEpochMillis: Long?,
        countryCode: String?,
        area: BangladeshAreaRef?,
        locality: BangladeshLocalityRef?,
        datasetVersion: String?,
    ): Boolean = store(
        validated = LocationInfo.create(
            label = label,
            latitude = latitude,
            longitude = longitude,
            timeZoneId = timeZoneId,
            origin = LocationOrigin.DEVICE_GPS,
            accuracyMetres = accuracyMetres,
            // A non-positive value is "the provider did not set a time", not the epoch: storing the
            // epoch would age the fix as stale-with-a-time, and storing now() would age it as fresh.
            // Either would be a claim about when the fix was taken that the platform did not make.
            acquiredAt = acquiredAtEpochMillis
                ?.takeIf { it > 0L }
                ?.let { Instant.ofEpochMilli(it) },
            countryCode = countryCode,
            area = area,
            locality = locality,
            datasetVersion = datasetVersion,
        ),
    )

    override suspend fun saveAreaLocation(
        latitude: Double,
        longitude: Double,
        label: String,
        reference: BangladeshAreaRef,
        datasetVersion: String,
    ): Boolean = store(
        validated = LocationInfo.create(
            label = label,
            latitude = latitude,
            longitude = longitude,
            // Not a parameter: every area the bundled dataset defines is in Bangladesh, and Bangladesh
            // has a single zone. See LocationRepository.saveAreaLocation.
            timeZoneId = BangladeshLocationPolicy.TIME_ZONE_ID,
            origin = LocationOrigin.AREA_SELECTION,
            countryCode = BangladeshLocationPolicy.COUNTRY_CODE,
            area = reference,
            datasetVersion = datasetVersion,
        ),
    )

    override suspend fun saveMapLocation(
        latitude: Double,
        longitude: Double,
        label: String,
        countryCode: String?,
        area: BangladeshAreaRef?,
        datasetVersion: String?,
    ): Boolean = store(
        validated = LocationInfo.create(
            label = label,
            latitude = latitude,
            longitude = longitude,
            // Bangladesh's single zone, not the device's: a map selection is only offered for points the
            // country policy accepts. See LocationRepository.saveMapLocation.
            timeZoneId = BangladeshLocationPolicy.TIME_ZONE_ID,
            origin = LocationOrigin.MAP_SELECTION,
            // No accuracy and no acquisition time: a picked point is not an observation. Passing null
            // here is also what removes any accuracy or timestamp left by a previous device fix, so a
            // stored map point can never inherit a fix's certainty claim.
            countryCode = countryCode,
            area = area,
            // Never set from this path: a locality is stored only when the resolver verified it against
            // the district it was attributed to, and a picked point has no accuracy for that decision.
            locality = null,
            datasetVersion = datasetVersion,
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
            preferences.remove(Keys.COUNTRY_CODE)
            preferences.remove(Keys.DATASET_VERSION)
            preferences.remove(Keys.DIVISION_ID)
            preferences.remove(Keys.DIVISION_NAME)
            preferences.remove(Keys.DISTRICT_ID)
            preferences.remove(Keys.DISTRICT_NAME)
            preferences.remove(Keys.UPAZILA_ID)
            preferences.remove(Keys.UPAZILA_NAME)
            preferences.remove(Keys.LOCALITY_NAME)
            preferences.remove(Keys.LOCALITY_DISTRICT_ID)
            preferences.remove(Keys.LOCALITY_KIND)
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
            validated.countryCode?.let { preferences[Keys.COUNTRY_CODE] = it }
                ?: preferences.remove(Keys.COUNTRY_CODE)

            // Written as a set or removed as a set. A location either belongs to the hierarchy that was
            // saved with it or has no hierarchy at all: leaving a division behind while replacing the
            // upazila would attach one area's name to another area's coordinate, which is worse than
            // having no area recorded.
            val area = validated.area
            if (area == null) {
                preferences.remove(Keys.DIVISION_ID)
                preferences.remove(Keys.DIVISION_NAME)
                preferences.remove(Keys.DISTRICT_ID)
                preferences.remove(Keys.DISTRICT_NAME)
                preferences.remove(Keys.UPAZILA_ID)
                preferences.remove(Keys.UPAZILA_NAME)
                preferences.remove(Keys.DATASET_VERSION)
            } else {
                preferences[Keys.DIVISION_ID] = area.divisionId
                preferences[Keys.DIVISION_NAME] = area.divisionName
                preferences[Keys.DISTRICT_ID] = area.districtId
                preferences[Keys.DISTRICT_NAME] = area.districtName
                area.upazilaId?.let { preferences[Keys.UPAZILA_ID] = it }
                    ?: preferences.remove(Keys.UPAZILA_ID)
                area.upazilaName?.let { preferences[Keys.UPAZILA_NAME] = it }
                    ?: preferences.remove(Keys.UPAZILA_NAME)
                validated.datasetVersion?.let { preferences[Keys.DATASET_VERSION] = it }
                    ?: preferences.remove(Keys.DATASET_VERSION)
            }

            // Written as a set or removed as a set, exactly like the hierarchy above: a locality without
            // the district that verified it could not be checked against a position, and a district with
            // no locality name is not a locality. A manual selection or a manual entry therefore clears
            // it rather than leaving the previous user's neighbourhood attached to a different position.
            val locality = validated.locality
            if (locality == null) {
                preferences.remove(Keys.LOCALITY_NAME)
                preferences.remove(Keys.LOCALITY_DISTRICT_ID)
                preferences.remove(Keys.LOCALITY_KIND)
            } else {
                preferences[Keys.LOCALITY_NAME] = locality.name
                preferences[Keys.LOCALITY_DISTRICT_ID] = locality.districtId
                preferences[Keys.LOCALITY_KIND] = locality.kind.name
            }
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
            countryCode = this[Keys.COUNTRY_CODE],
            area = readArea(),
            locality = readLocality(),
            datasetVersion = this[Keys.DATASET_VERSION],
        )
    }

    /**
     * Rebuilds the stored hierarchy, or `null` when the store holds no complete one.
     *
     * A partial hierarchy (a division with no district, say) is reported as no area rather than as an
     * area with blanks: half a hierarchy is not a hierarchy, and the screens treat a missing area as
     * "unknown, validate from the coordinates" rather than rendering empty names.
     */
    private fun Preferences.readArea(): BangladeshAreaRef? {
        val divisionId = this[Keys.DIVISION_ID] ?: return null
        val divisionName = this[Keys.DIVISION_NAME] ?: return null
        val districtId = this[Keys.DISTRICT_ID] ?: return null
        val districtName = this[Keys.DISTRICT_NAME] ?: return null
        return BangladeshAreaRef(
            divisionId = divisionId,
            divisionName = divisionName,
            districtId = districtId,
            districtName = districtName,
            upazilaId = this[Keys.UPAZILA_ID],
            upazilaName = this[Keys.UPAZILA_NAME],
        )
    }

    /**
     * Rebuilds the stored locality, or `null` when the store holds none.
     *
     * All three parts are required: a name with no district could not be checked against the position it
     * describes, and an unrecognised kind is a value written by a build that knew a locality this one does
     * not, which is reported as no locality rather than downgraded to a plausible one.
     */
    private fun Preferences.readLocality(): BangladeshLocalityRef? {
        val name = this[Keys.LOCALITY_NAME] ?: return null
        val districtId = this[Keys.LOCALITY_DISTRICT_ID] ?: return null
        val kind = this[Keys.LOCALITY_KIND]
            ?.let { stored -> BangladeshLocalityKind.entries.firstOrNull { it.name == stored } }
            ?: return null
        return BangladeshLocalityRef(name = name, districtId = districtId, kind = kind)
    }

    private object Keys {
        val LABEL = stringPreferencesKey("location_label")
        val LATITUDE = doublePreferencesKey("location_latitude")
        val LONGITUDE = doublePreferencesKey("location_longitude")
        val TIME_ZONE_ID = stringPreferencesKey("location_time_zone_id")
        val ORIGIN = stringPreferencesKey("location_origin")
        val ACCURACY_METRES = doublePreferencesKey("location_accuracy_metres")
        val ACQUIRED_AT_EPOCH_MILLIS = longPreferencesKey("location_acquired_at_epoch_millis")
        val COUNTRY_CODE = stringPreferencesKey("location_country_code")
        val DATASET_VERSION = stringPreferencesKey("location_dataset_version")
        val DIVISION_ID = stringPreferencesKey("location_division_id")
        val DIVISION_NAME = stringPreferencesKey("location_division_name")
        val DISTRICT_ID = stringPreferencesKey("location_district_id")
        val DISTRICT_NAME = stringPreferencesKey("location_district_name")
        val UPAZILA_ID = stringPreferencesKey("location_upazila_id")
        val UPAZILA_NAME = stringPreferencesKey("location_upazila_name")
        val LOCALITY_NAME = stringPreferencesKey("location_locality_name")
        val LOCALITY_DISTRICT_ID = stringPreferencesKey("location_locality_district_id")
        val LOCALITY_KIND = stringPreferencesKey("location_locality_kind")
    }
}
