package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.LocationInfo

/** Why Salah is asking for a location before it can show location-dependent screens. */
enum class LocationSetupReason {

    /** Nothing has been chosen yet — a fresh install, or the location was cleared. */
    NO_LOCATION,

    /** A location is stored, but it is not in Bangladesh, which this build supports. */
    OUTSIDE_SUPPORTED_COUNTRY,

    /**
     * A location is stored and is in Bangladesh, but the area it was chosen from is not in the
     * dataset this build ships.
     *
     * Reported rather than ignored, because the alternative is displaying an area name that the app
     * can no longer verify and offering it as if it were still selectable.
     */
    AREA_NOT_IN_DATASET,
}

/**
 * Whether Salah can use the stored location, derived rather than remembered.
 *
 * **Why this is derived.** The obvious alternative is a `location_setup_complete` boolean written when
 * the user finishes setup. A flag like that can disagree with reality in both directions — set while
 * the location is missing, or cleared while a perfectly good one is stored — and every disagreement is
 * a bug the user sees as either a setup screen they already completed or a Home screen with no times.
 * Deriving the answer from the location itself means the two can never disagree: setup is complete
 * exactly when a usable location exists.
 *
 * That also gives the right behaviour for the cases the requirement calls out:
 *
 *  - a device that already had a location from an earlier build is not sent through setup again;
 *  - a user who declined GPS and chose an area manually is complete, because no permission state is
 *    part of this evaluation;
 *  - a location cleared in Android settings or by the user returns the app to setup, because the
 *    repository then emits `null`;
 *  - a stored location the current dataset no longer recognises is revalidated and reported.
 */
sealed interface LocationSetupState {

    /** The dataset is still being read. The shell shows a loading state, not a decision. */
    data object Checking : LocationSetupState

    /** A usable location exists; location-dependent screens may be shown. */
    data class Complete(val location: LocationInfo) : LocationSetupState

    /** A location must be chosen before the app can compute anything location-dependent. */
    data class Required(
        val reason: LocationSetupReason,
        /** The location that is stored but unusable, so the screen can explain what was rejected. */
        val stored: LocationInfo? = null,
    ) : LocationSetupState

    /**
     * The bundled dataset could not be read and no location is stored, so neither option can be
     * completed.
     *
     * A distinct state rather than an empty list of divisions: the app says the dataset is unavailable
     * and why, instead of showing a division picker with nothing in it.
     */
    data class DatasetUnavailable(val detail: String) : LocationSetupState

    /** True when the setup screen must be shown. */
    val requiresSetup: Boolean
        get() = this is Required || this is DatasetUnavailable
}

/**
 * Decides [LocationSetupState] from the stored location and the bundled dataset.
 *
 * Pure and synchronous: the caller supplies the current location and the already-parsed dataset, so
 * this is directly testable and contains no clock, no coroutine and no platform call.
 */
object LocationSetup {

    /**
     * Evaluates [stored] against [dataset].
     *
     * @param dataset the bundled geography, or `null` while it is still being read. A location can be
     *   accepted without it — the coarse bounds check needs no boundary file — which is deliberate: a
     *   dataset that failed to load must not lock a user out of an app whose coordinates are already
     *   valid.
     */
    fun evaluate(stored: LocationInfo?, dataset: BangladeshLocationDataset?): LocationSetupState {
        if (stored == null) {
            return if (dataset == null || !dataset.isUsable) {
                LocationSetupState.DatasetUnavailable(
                    detail = "the bundled Bangladesh location dataset could not be read",
                )
            } else {
                LocationSetupState.Required(reason = LocationSetupReason.NO_LOCATION)
            }
        }

        // Cheap and certain first: a coordinate outside the country's bounding rectangle is outside the
        // country, and saying so needs no dataset.
        if (!BangladeshLocationPolicy.isWithinCoarseBounds(stored.point)) {
            return LocationSetupState.Required(
                reason = LocationSetupReason.OUTSIDE_SUPPORTED_COUNTRY,
                stored = stored,
            )
        }

        if (dataset == null || !dataset.isUsable) return LocationSetupState.Complete(stored)

        val check = BangladeshLocationPolicy.check(stored.point, dataset.boundaryRings)
        if (check is BangladeshCheck.Outside) {
            return LocationSetupState.Required(
                reason = LocationSetupReason.OUTSIDE_SUPPORTED_COUNTRY,
                stored = stored,
            )
        }

        // A boundary that could not be consulted is not a reason to reject a location that passed the
        // coarse check; it is a reason to accept it and let the location screen report the limitation.
        val area = stored.area
        if (area != null && !dataset.recognises(area)) {
            return LocationSetupState.Required(
                reason = LocationSetupReason.AREA_NOT_IN_DATASET,
                stored = stored,
            )
        }

        return LocationSetupState.Complete(stored)
    }

    private val LocationInfo.point: GeoPoint
        get() = GeoPoint(latitude = latitude, longitude = longitude)
}
