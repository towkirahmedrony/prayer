package io.github.towkirahmedrony.salah.feature.mosque

import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueDataOrigin
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueWithDistance
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState

/**
 * The two ways the same result set is presented.
 *
 * The list is the default because it is the one that works when the map cannot: a missing key, a
 * failed style request or a device without a working renderer leaves the distances and directions
 * fully usable. The map is a second view of the same data, not a second source.
 */
enum class MosqueViewMode {
    LIST,
    MAP,
}

/** Where the current search centre came from. */
enum class MosqueSearchCenterOrigin {
    /** The app's one saved location — the same place prayer times are calculated for. */
    SAVED_LOCATION,

    /** A one-shot fix obtained for this screen; deliberately not written back to the location store. */
    CURRENT_LOCATION,
}

/**
 * A transient, user-facing condition that does not replace the results.
 *
 * Kept as an enum rather than a sentence so the composable resolves it from a string resource and the
 * notice is localised with the rest of the screen.
 */
enum class MosqueNotice {
    PERMISSION_DENIED,
    LOCATION_SERVICES_DISABLED,
    LOCATION_TIMED_OUT,
    LOCATION_UNAVAILABLE,
    DIRECTIONS_UNAVAILABLE,
}

/**
 * Everything the mosque finder draws.
 *
 * **Numeric, not prose.** Distances, the mosque count and the failure reason cross this boundary as
 * values; the composable turns them into the interface language's words and numerals. That keeps the
 * view model free of Android resources and lets its tests assert the state directly.
 *
 * **The four result conditions are separate.** [isLoading] is a request in flight, [failure] is a
 * load that produced no usable list, [mosques] empty with neither is the honest empty result, and
 * [refreshFailure] beside a non-empty list is "these results are real, but the refresh that would
 * have updated them did not succeed". Collapsing them would make one of those cases lie.
 */
data class MosqueFinderUiState(
    /** The centre the current list describes, or `null` while no centre has been established. */
    val center: GeoPoint? = null,
    /**
     * The saved location's own label, when the centre came from it. A current-location centre is
     * named by [centerOrigin] instead, because its label would be the same words in every language.
     */
    val centerLabel: String? = null,
    val centerOrigin: MosqueSearchCenterOrigin = MosqueSearchCenterOrigin.SAVED_LOCATION,
    val radiusMetres: Int = 0,
    val permissionState: LocationPermissionState = LocationPermissionState.DENIED,
    /** True while the platform is being asked for a one-shot position. */
    val isAcquiringLocation: Boolean = false,
    /** True while the first load for a centre is in flight. */
    val isLoading: Boolean = false,
    /** True while an existing list is being refreshed, so the list can stay on screen. */
    val isRefreshing: Boolean = false,
    val viewMode: MosqueViewMode = MosqueViewMode.LIST,
    val mosques: List<MosqueWithDistance> = emptyList(),
    val selectedPlaceId: String? = null,
    val origin: MosqueDataOrigin? = null,
    val cachedAtEpochMillis: Long? = null,
    /** A load that produced no usable list at all. */
    val failure: MosqueFailure? = null,
    /** Why a refresh failed while a cached or earlier list is still being shown. */
    val refreshFailure: MosqueFailure? = null,
    val notice: MosqueNotice? = null,
) {
    /** The mosque whose detail card is open, or `null` when nothing is selected. */
    val selected: MosqueWithDistance?
        get() = selectedPlaceId?.let { id -> mosques.firstOrNull { it.mosque.placeId == id } }

    /** True when the screen has a list to draw, whatever its origin. */
    val hasResults: Boolean get() = mosques.isNotEmpty()

    /** True when a search has produced an answer and it was empty. */
    val isEmptyResult: Boolean
        get() = !isLoading && !isRefreshing && failure == null && mosques.isEmpty()
}
