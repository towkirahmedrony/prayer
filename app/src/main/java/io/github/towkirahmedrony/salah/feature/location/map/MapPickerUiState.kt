package io.github.towkirahmedrony.salah.feature.location.map

import io.github.towkirahmedrony.salah.core.domain.location.BangladeshCheck
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshMapViewport
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.location.GeocodedAddress
import io.github.towkirahmedrony.salah.core.domain.location.LocationFormatting
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef

/**
 * The state of the address lookup for the currently selected point.
 *
 * The four states are kept apart because the screen says something different for each: a lookup in
 * flight, an address that was found, a lookup that failed, and a failure that is actually "the provider
 * knows nothing here" rather than "the network is down". None of them blocks the confirm button — the
 * coordinates are the fact, and the address is information about it.
 */
sealed interface MapAddressState {

    /** No lookup has run for the current point yet. */
    data object Idle : MapAddressState

    /** A lookup is in flight (debounced: a short pause after the last map movement). */
    data object Loading : MapAddressState

    /** The provider returned an address. */
    data class Resolved(val address: GeocodedAddress) : MapAddressState

    /**
     * No address was obtained. [reason] is either a provider reason code or [NO_ADDRESS] when the
     * provider answered but knows nothing about the point.
     */
    data class Failed(val reason: String) : MapAddressState {
        /** True when the provider answered and simply has no address for the place. */
        val isPlaceUnknown: Boolean get() = reason == NO_ADDRESS
    }

    companion object {
        /** The reason code used when a lookup returned a well-formed response with no address. */
        const val NO_ADDRESS: String = "no_address"
    }
}

/** Transient feedback shown after an action on the map picker. */
sealed interface MapPickerMessage {

    /** The confirmed point was stored. */
    data object Saved : MapPickerMessage

    /** The save was rejected by validation. */
    data object Rejected : MapPickerMessage

    /** The selected point is outside Bangladesh, so nothing was stored. */
    data class OutsideCountry(val distanceMetres: Double?) : MapPickerMessage

    /** The bundled dataset could not be read, so a point cannot be validated against the country. */
    data object DatasetUnavailable : MapPickerMessage

    /** The device fix could not be used. */
    data object PermissionDenied : MapPickerMessage
    data object ServicesDisabled : MapPickerMessage
    data object TimedOut : MapPickerMessage
    data object ProviderFailed : MapPickerMessage
}

/**
 * A one-shot request to move the map camera.
 *
 * Held in the state rather than delivered as an event so it survives recomposition: the screen applies
 * it and calls [MapPickerViewModel.onCameraRequestConsumed], which is what keeps "move the camera" from
 * being re-applied on every frame. The selection itself is *not* in here: the camera lands, the camera
 * idle listener reports the new centre, and the selection follows from the same path a drag uses — so a
 * programmatic move and a user gesture cannot diverge.
 */
data class MapCameraRequest(
    val point: GeoPoint,
    val zoom: Double,
)

/**
 * Everything the map picker renders.
 *
 * **Where a value comes from is not ambiguous.** The coordinates and the confirm action come from the
 * point the user placed; the boundary flags come from the bundled national polygon
 * ([BangladeshLocationPolicy]); the administrative row comes from the *offline* resolution against the
 * bundled dataset; and the place/locality/road come from the online lookup. A missing online component
 * leaves its row out rather than borrowing a value from another source.
 */
data class MapPickerUiState(
    /** True while the bundled dataset is still being read. */
    val isLoading: Boolean = true,
    /** False when the dataset could not be read or is unusable, in which case nothing can be confirmed. */
    val isDatasetUsable: Boolean = false,
    /** The dataset version, shown so the user can tell which geography a resolved area came from. */
    val datasetVersion: String? = null,
    /** Where the camera opens: the stored location when there is one, else Bangladesh's centre. */
    val initialPoint: GeoPoint = BangladeshMapViewport.CENTER,
    /** The zoom the camera opens at. */
    val initialZoom: Double = BangladeshMapViewport.INITIAL_ZOOM,
    /** The point under the marker. Always present once the screen has started. */
    val selected: GeoPoint? = null,
    /** The country-policy classification of [selected]. */
    val boundary: BangladeshCheck = BangladeshCheck.BoundaryUnavailable,
    /** The administrative area the point resolved to offline, when it could be resolved. */
    val area: BangladeshAreaRef? = null,
    /** The state of the online address lookup for [selected]. */
    val address: MapAddressState = MapAddressState.Idle,
    /** True while the confirmed point is being written. */
    val isSaving: Boolean = false,
    /** True once the point has been stored, which is when the screen leaves. */
    val isSaved: Boolean = false,
    /** A pending camera move, or `null`. */
    val cameraRequest: MapCameraRequest? = null,
    /** True while a device fix is being requested. */
    val isAcquiring: Boolean = false,
    /** The last message, until it is dismissed. */
    val message: MapPickerMessage? = null,
) {
    /** The selected coordinates, formatted for display, or an empty string before the first fix. */
    val coordinateText: String
        get() = selected?.let { LocationFormatting.coordinates(it) } ?: ""

    /** True when the point is inside the boundary polygon but only within the tolerance band. */
    val isWithinBoundaryTolerance: Boolean get() = boundary is BangladeshCheck.WithinBoundaryTolerance

    /** True when the point was strictly inside the bundled national boundary. */
    val isBoundaryVerified: Boolean get() = boundary is BangladeshCheck.Inside

    /**
     * True when the point may be confirmed: inside the polygon, within its tolerance, or — only when the
     * boundary could not be read at all — inside the country's coarse rectangle, which can reject with
     * certainty but never establish containment.
     *
     * A point beyond the tolerance is never confirmable, and this is what makes it impossible to save a
     * location outside Bangladesh rather than merely discouraged.
     */
    val isConfirmable: Boolean
        get() {
            val point = selected ?: return false
            return when (boundary) {
                is BangladeshCheck.Inside -> true
                is BangladeshCheck.WithinBoundaryTolerance -> true
                is BangladeshCheck.Outside -> false
                BangladeshCheck.BoundaryUnavailable ->
                    BangladeshLocationPolicy.isWithinCoarseBounds(point)
            }
        }

    /** True when the selected point is known to be outside Bangladesh. */
    val isOutsideCountry: Boolean get() = boundary is BangladeshCheck.Outside

    /** True when a save can be started now. */
    val canSave: Boolean get() = selected != null && isConfirmable && !isSaving
}
