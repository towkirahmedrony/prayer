package io.github.towkirahmedrony.salah.feature.location

import io.github.towkirahmedrony.salah.core.domain.location.AreaResolution
import io.github.towkirahmedrony.salah.core.domain.location.AreaSelection
import io.github.towkirahmedrony.salah.core.domain.location.AreaSuggestion
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.location.LocationDisplayName
import io.github.towkirahmedrony.salah.core.domain.location.LocationFormatting
import io.github.towkirahmedrony.salah.core.domain.location.LocationFreshness
import io.github.towkirahmedrony.salah.core.domain.location.LocationNameLanguage
import io.github.towkirahmedrony.salah.core.domain.location.LocationSearchResult
import io.github.towkirahmedrony.salah.core.domain.qibla.LocationPermissionState
import io.github.towkirahmedrony.salah.core.model.BangladeshArea
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityRef
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.feature.common.LocationFormState

/**
 * Transient feedback shown after an action.
 *
 * Every failure the platform can produce is a distinct constant rather than a generic "something went
 * wrong", because the user's next step differs: a denied permission is answered with manual selection,
 * disabled location services with a settings shortcut, and a timeout with a retry in the open air.
 */
sealed interface LocationMessage {

    /** A location was stored. */
    data object Saved : LocationMessage

    /** A save was rejected before it reached the repository. */
    data object SaveRejected : LocationMessage

    /** The user declined the permission prompt. Manual selection is still available. */
    data object PermissionDenied : LocationMessage

    /** Location is switched off for the whole device. */
    data object ServicesDisabled : LocationMessage

    /** No fix arrived within the timeout. */
    data object TimedOut : LocationMessage

    /** The provider reported a failure. */
    data object ProviderFailed : LocationMessage

    /**
     * The device is outside Bangladesh, so the fix was not saved.
     *
     * Carries the distance beyond the border so the screen can distinguish a few hundred metres from
     * another continent — the two need different words. `null` means the distance was not measured: the
     * boundary file was unavailable and only the country's bounding rectangle could be checked.
     */
    data class OutsideSupportedCountry(val distanceMetres: Double?) : LocationMessage

    /** The stored location was removed. */
    data object Cleared : LocationMessage

    /** The dataset is unavailable, so a manual area cannot be chosen. */
    data object DatasetUnavailable : LocationMessage
}

/**
 * A device fix that has been obtained and checked, waiting for the user to confirm it.
 *
 * Held as a *pending* value rather than saved immediately, because the coordinates are an observation
 * and the area is a best guess: saving on sight would record a position the user never saw, and the
 * requirement is that a fix is confirmed before it replaces an existing location.
 */
data class PendingDeviceFix(
    val point: GeoPoint,
    val accuracyMetres: Double?,
    /**
     * When the platform recorded the fix, or `null` when it did not report one.
     *
     * Nullable so "unknown" is representable: a fix with no time is aged as
     * [LocationFreshness.UNKNOWN] and offered a refresh, rather than being presented as a position
     * obtained just now.
     */
    val acquiredAtEpochMillis: Long?,
    /** What could be said about which area this position is in. */
    val resolution: AreaResolution,

    /**
     * The named locality the position was matched to, or `null` when none could be established.
     *
     * Best-effort and independent of [resolution]: a fix can be attributed to a district and to no
     * locality inside it (the usual case outside a city), and the screen then says which district it is
     * in without implying it knows the neighbourhood.
     */
    val locality: BangladeshLocalityRef? = null,
    /**
     * The country code to store with this fix, or `null` when the boundary check could not run or did
     * not establish containment.
     *
     * `null` is not a failure: a fix can only be *verified* as Bangladeshi against the bundled
     * boundary, and storing `BD` when only the coarse rectangle or the boundary tolerance accepted the
     * position would record a check that never established containment.
     */
    val countryCode: String? = null,
    /** True when the position is strictly inside the bundled national boundary. */
    val isCountryVerified: Boolean = false,
    /**
     * True when the position is outside the boundary polygon but within the tolerance.
     *
     * Tracked separately from [isCountryVerified] so the screen can say which allowance was applied
     * instead of implying the point was found inside the country.
     */
    val isWithinBoundaryTolerance: Boolean = false,
) {
    /** The area the fix was attributed to, or `null` when it could not be. */
    val suggestion: AreaSuggestion? get() = (resolution as? AreaResolution.Suggested)?.suggestion

    /** The reference the fix would be saved with, if the user accepts it. */
    val suggestedArea: BangladeshAreaRef? get() = suggestion?.reference

    /** True when the area is a nearest-centre match rather than a surveyed position. */
    val isAreaApproximate: Boolean get() = suggestion != null

    /**
     * The name this fix would be stored under, composed by the shared display policy.
     *
     * The confirmation card is showing the user the record they are about to accept, so it shows the
     * same name the rest of the app will use rather than the administrative hierarchy on its own.
     */
    val displayName: String
        get() = LocationDisplayName.of(
            origin = LocationOrigin.DEVICE_GPS,
            storedLabel = LocationViewModel.DEVICE_LABEL,
            area = suggestedArea,
            locality = locality,
        )

    /** The coordinates as they would be stored, for the advanced details line. */
    val coordinateText: String get() = LocationFormatting.coordinates(point)

    /** The reported accuracy, formatted, or `null` when the provider gave none. */
    val accuracyText: String? get() = LocationFormatting.accuracy(accuracyMetres)

    /** How far the fix was from the matched centre, or `null` when nothing was matched. */
    val matchDistanceText: String?
        get() = suggestion?.let { LocationFormatting.distance(it.distanceMetres) }

    /** How far the fix was from the nearest centre known, when no area could be attributed. */
    val nearestDistanceText: String?
        get() = (resolution as? AreaResolution.Unresolved)?.nearestDistanceMetres
            ?.let { LocationFormatting.distance(it) }

    /** True when the dataset itself is missing, which is a different problem from a distant fix. */
    val isDatasetUnavailable: Boolean get() = resolution is AreaResolution.DatasetUnavailable
}

/**
 * The stored location, reduced to what the management screen displays.
 *
 * Flags rather than pre-rendered sentences, because the wording belongs to the string resources: this
 * carries the *facts* (which origin, which country, whether the record was verified) and the screen
 * decides how to say them.
 */
data class StoredLocationUi(
    val location: LocationInfo,
    val origin: LocationOrigin,
    val countryCode: String?,
    val area: BangladeshAreaRef?,
    /**
     * [area]'s three levels, each in the language the screen is rendering in, resolved through the
     * bundled dataset when it still knows the ids and falling back to the names stored with the record.
     * `null` at a level means the record has no value for it, which is what the screen shows as
     * "unknown". Kept as three values rather than one label so each row keeps its own "unknown" answer.
     */
    val divisionDisplayName: String?,
    val districtDisplayName: String?,
    val upazilaDisplayName: String?,
    /**
     * The named locality verified as containing this location, when one was established.
     *
     * Kept beside [area] rather than folded into it, because the two say different things: the area is
     * the administrative hierarchy the dataset publishes, and this is the informal place name inside it.
     */
    val locality: BangladeshLocalityRef?,
    val timeZoneId: String,
    val point: GeoPoint,
    val accuracyText: String?,
    val coordinateText: String,
    val freshness: LocationFreshness,
    /**
     * True when the stored coordinates are strictly inside the bundled national boundary, or when the
     * boundary was unavailable and only the coarse bounds could be checked.
     */
    val isVerifiedInSupportedCountry: Boolean,
    /**
     * True when the stored coordinates are outside the boundary polygon but within the tolerance.
     *
     * Distinct from [isVerifiedInSupportedCountry] so the screen can state that the position was
     * accepted as a tolerance case rather than as a confirmed position inside Bangladesh.
     */
    val isWithinBoundaryTolerance: Boolean,
    /** True when the coordinates are outside the country's bounding rectangle. */
    val isKnownOutsideSupportedCountry: Boolean,
)

/**
 * Everything the location setup and location management screens render.
 *
 * One state for both screens: they differ in framing, not in logic. The setup screen says "choose a
 * location to continue" and the management screen says "this is your location, change it here", but
 * both offer the same two ways to choose and the same validation, and duplicating them would be two
 * implementations of one rule set.
 */
data class LocationUiState(
    /** True while the bundled dataset and the stored location are still being read. */
    val isLoading: Boolean = true,
    /** The dataset's version string, shown so a user can tell which geography they are choosing from. */
    val datasetVersion: String? = null,
    /** False when the dataset could not be read or is unusable. */
    val isDatasetUsable: Boolean = false,
    /** Why the dataset is unusable, when it is. */
    val datasetDetail: String? = null,
    /** The location currently stored, if any. */
    val stored: StoredLocationUi? = null,
    /** The in-progress manual hierarchy selection. */
    val selection: AreaSelection = AreaSelection.EMPTY,
    /** The divisions to offer. Never empty unless the dataset is unusable. */
    val divisionOptions: List<BangladeshArea> = emptyList(),
    /** The districts of the selected division, or none before one is chosen. */
    val districtOptions: List<BangladeshArea> = emptyList(),
    /** The upazilas of the selected district, or none before one is chosen. */
    val upazilaOptions: List<BangladeshArea> = emptyList(),
    /**
     * The chosen areas' names, deepest first, in the current language, or `null` while the selection is
     * incomplete. Resolved through the dataset so the names follow the active language rather than the
     * English names stored with a reference.
     */
    val selectionSummary: String? = null,
    /** The chosen area's published coordinate, which is what a manual save would store. */
    val selectionCoordinateText: String? = null,
    /** True once a complete hierarchy has been chosen and can be saved. */
    val canSaveSelection: Boolean = false,
    /** True while the manual picker is open. */
    val isPickerOpen: Boolean = false,
    /** A device fix waiting to be confirmed, if any. */
    val pendingFix: PendingDeviceFix? = null,
    /** True while a fix is being requested. */
    val isAcquiring: Boolean = false,
    /** Whether automatic location is possible, and at what precision. */
    val permissionState: LocationPermissionState = LocationPermissionState.DENIED,
    /** True while the manual-coordinate form is open. */
    val isManualFormOpen: Boolean = false,
    /**
     * Raw text of the manual-coordinate form.
     *
     * The third way to give Salah a location, and the only one that accepts a position the bundled area
     * dataset cannot express. Held as entered until it is saved, when the repository validates it; the
     * fields are not parsed as the user types, so a half-typed `23.` or a lone `-` is representable.
     */
    val manualForm: LocationFormState = LocationFormState(),
    /** The last message, until it is dismissed. */
    val message: LocationMessage? = null,
    /**
     * The language the picker's location *names* are shown and searched in.
     *
     * Set by the screens from the resources they are rendering with, so it tracks
     * `AppLanguage` — including "follow the device" — exactly as `stringResource` does. Everything
     * that is a label rather than a place name comes from the string resources instead; this is only
     * about the dataset's own Bangla and English names.
     */
    val nameLanguage: LocationNameLanguage = LocationNameLanguage.ENGLISH,
    /** The search field's current text, before normalization. */
    val searchQuery: String = "",
    /** The results for [searchQuery], best match first. Empty while the query is blank or matched nothing. */
    val searchResults: List<LocationSearchResult> = emptyList(),
    /** True while a search is queued (debouncing) or running. */
    val isSearching: Boolean = false,
    /** True when the last search could not run — the dataset is unreadable, or the search failed. */
    val searchFailed: Boolean = false,
) {
    /** True when a manual selection can be started at all. */
    val canChooseArea: Boolean get() = isDatasetUsable

    /** True when the stored location is outside Bangladesh and must be replaced. */
    val isStoredLocationUnsupported: Boolean get() = stored?.isKnownOutsideSupportedCountry == true

    /** True when the search field holds anything worth searching for. */
    val hasSearchQuery: Boolean get() = searchQuery.isNotBlank()
}
