package io.github.towkirahmedrony.salah.feature.mosque

import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueDataOrigin
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueWithDistance
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapArea
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapProgress
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapRegion
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

    /** The user stopped an offline map download; its partial tiles were discarded. */
    OFFLINE_MAP_CANCELLED,
}

/** The state of an offline map download. */
sealed interface OfflineMapDownload {

    /** Nothing is downloading. A saved region is reported by [OfflineMapUiState.coveringRegion]. */
    data object None : OfflineMapDownload

    /** A download is running; [progress] is what the engine last reported. */
    data class InProgress(val progress: OfflineMapProgress) : OfflineMapDownload

    /** The last download failed. The previous regions, if any, are untouched. */
    data class Failed(val reason: OfflineMapFailure) : OfflineMapDownload
}

/**
 * Offline map availability for the map view.
 *
 * [plan] is the download the current centre would need; it is a plan, not a promise, and is `null`
 * when the area is too large or no centre is known. [coveringRegion] is a stored region containing the
 * centre — that is what makes the map usable with no connection.
 */
data class OfflineMapUiState(
    val regions: List<OfflineMapRegion> = emptyList(),
    val coveringRegion: OfflineMapRegion? = null,
    val plan: OfflineMapArea.Plan? = null,
    val download: OfflineMapDownload = OfflineMapDownload.None,
) {
    /** True while a download is running. */
    val isDownloading: Boolean get() = download is OfflineMapDownload.InProgress

    /** True when the current centre has genuinely stored tiles. */
    val isAvailable: Boolean get() = coveringRegion != null

    /** The size the finished map is expected to take, when a plan exists. */
    val estimatedBytes: Long? get() = plan?.takeIf { it.isWithinLimit }?.estimatedBytes
}

/**
 * Everything the mosque finder draws.
 *
 * **Numeric, not prose.** Distances, the mosque count, storage sizes and the failure reason cross this
 * boundary as values; the composable turns them into the interface language's words and numerals.
 * That keeps the view model free of Android resources and lets its tests assert the state directly.
 *
 * **The result conditions are separate.** [isLoading] is a first load with nothing to show yet,
 * [isRefreshing] is a refresh beside a list that stays on screen, [failure] is a load that produced no
 * usable list, [mosques] empty with neither is the honest empty result, [isOfflineEmpty] is the
 * offline answer with no saved data, and [refreshFailure] beside a non-empty list is "these results
 * are real, but the refresh that would have updated them did not succeed". Collapsing them would make
 * one of those cases lie.
 *
 * **Offline is a first-class state, not a failure.** [isOnline] comes from the connectivity monitor;
 * a cached list is drawn while [isRefreshing] is true instead of waiting behind a spinner.
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
    /** True while the first load for a centre is in flight and there is nothing to draw yet. */
    val isLoading: Boolean = false,
    /** True while an existing list is being refreshed, so the list can stay on screen. */
    val isRefreshing: Boolean = false,
    /** The last known connectivity, as reported by the platform monitor. */
    val isOnline: Boolean = true,
    /** True when the cache exists but could not be read — a device problem, not an empty area. */
    val cacheUnavailable: Boolean = false,
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
    val offlineMap: OfflineMapUiState = OfflineMapUiState(),
) {
    /** The mosque whose detail card is open, or `null` when nothing is selected. */
    val selected: MosqueWithDistance?
        get() = selectedPlaceId?.let { id -> mosques.firstOrNull { it.mosque.placeId == id } }

    /** True when the screen has a list to draw, whatever its origin. */
    val hasResults: Boolean get() = mosques.isNotEmpty()

    /** True when a search has produced an answer and it was empty. */
    val isEmptyResult: Boolean
        get() = !isLoading && !isRefreshing && failure == null && mosques.isEmpty() && isOnline

    /**
     * True when the device is offline, the cache holds nothing for this centre and no request is
     * outstanding. The screen shows an offline empty state for it — never a spinner.
     */
    val isOfflineEmpty: Boolean
        get() = center != null &&
            !isOnline &&
            !isLoading &&
            !isRefreshing &&
            failure == null &&
            mosques.isEmpty()

    /** True when the map can be drawn from stored tiles even with no connection. */
    val isMapAvailableOffline: Boolean get() = offlineMap.isAvailable
}
