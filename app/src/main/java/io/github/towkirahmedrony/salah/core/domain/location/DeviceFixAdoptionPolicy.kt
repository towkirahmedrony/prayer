package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityRef

/** Why a device fix must not replace the location Salah already has stored. */
enum class DeviceFixRejectionReason {

    /**
     * The fix is not strictly inside the bundled national boundary.
     *
     * Includes positions within the boundary tolerance: the tolerance is an allowance for *keeping* a
     * location Salah already holds (a GPS or simplification error at the border), not for *adopting* a
     * new one, because it cannot distinguish a border village from territory just over the line.
     */
    OUTSIDE_BANGLADESH,

    /** The fix is inside Bangladesh but too far from every published centre to name an area. */
    AREA_UNRESOLVED,

    /** The bundled dataset, or its boundary, could not be read, so the fix could not be checked. */
    DATASET_UNAVAILABLE,
}

/**
 * What may be done with a device fix given Salah's Bangladesh-only policy and bundled geography.
 *
 * [Accepted] carries everything needed to store the fix, so the store path cannot forget the country,
 * the area or the dataset version. [Rejected] carries why, so the UI can explain it and, crucially,
 * leave the existing stored location alone.
 */
sealed interface DeviceFixAdoption {

    /** The fix is verified in Bangladesh and attributed to an area, so it may replace the location. */
    data class Accepted(
        val reference: BangladeshAreaRef,
        val countryCode: String,
        val datasetVersion: String,
        /**
         * The named locality the fix was matched to, when one could be matched at its accuracy.
         *
         * Carried here rather than left to the caller so the two entry points that adopt a fix
         * silently — this policy — and the one that shows it for confirmation produce the same record,
         * including the name. A fix that could only be attributed to an area keeps `null` and is stored
         * with the area's name, which is a coarser answer and still a true one.
         */
        val locality: BangladeshLocalityRef? = null,
    ) : DeviceFixAdoption

    /** The fix must not replace the stored location. [distanceMetres] is set when it was measured. */
    data class Rejected(
        val reason: DeviceFixRejectionReason,
        val distanceMetres: Double? = null,
    ) : DeviceFixAdoption
}

/**
 * Decides whether a silently acquired device fix may replace the stored location.
 *
 * **Why this exists.** The Qibla screen can save a device fix without the user confirming it, unlike
 * the location setup flow, which shows the fix and waits for a tap. Without a shared rule the Qibla
 * path could write a position the setup flow would refuse — an unvalidated fix, in another country,
 * replacing a location the user deliberately chose.
 *
 * **The rule, and why it is stricter than the setup flow.** A stored location is replaced only when the
 * fix is strictly inside the bundled boundary *and* resolves to an administrative area. Two cases the
 * setup flow tolerates are refused here:
 *
 *  - a fix within the boundary tolerance, because it is geometrically outside Bangladesh and may be in
 *    a neighbouring country, and there is no confirmation step to catch that; and
 *  - a fix that cannot be attributed to an area, because Salah then cannot describe where the user is,
 *    and a described location already exists.
 *
 * In both cases the existing location is preserved rather than overwritten — the failure mode the task
 * requires avoiding — and the user can still change the location from the location screen, where the
 * same fix is shown for confirmation.
 *
 * Pure and synchronous: it takes the point, the fix's reported accuracy and the already-parsed dataset
 * and returns a value, so the decision is directly testable with no device, no permission and no
 * coroutine.
 */
object DeviceFixAdoptionPolicy {

    /**
     * Decides the outcome for [point] against [dataset], which may be `null` when it did not load.
     *
     * @param accuracyMetres the fix's reported horizontal accuracy, or `null` when the provider did not
     *   report one. It does not affect whether the fix is adopted — that is the country and area rule —
     *   but it does decide whether a locality may be named for it, because a fix accurate only to a
     *   kilometre or two cannot be attributed to a neighbourhood.
     */
    fun decide(
        point: GeoPoint,
        accuracyMetres: Double?,
        dataset: BangladeshLocationDataset?,
    ): DeviceFixAdoption {
        if (!point.isFinite) {
            return DeviceFixAdoption.Rejected(DeviceFixRejectionReason.DATASET_UNAVAILABLE)
        }
        if (dataset == null || !dataset.isUsable) {
            return DeviceFixAdoption.Rejected(DeviceFixRejectionReason.DATASET_UNAVAILABLE)
        }

        return when (val check = BangladeshLocationPolicy.check(point, dataset.boundaryRings)) {
            BangladeshCheck.Inside -> adopt(point, accuracyMetres, dataset)
            is BangladeshCheck.WithinBoundaryTolerance ->
                DeviceFixAdoption.Rejected(DeviceFixRejectionReason.OUTSIDE_BANGLADESH, check.distanceMetres)
            is BangladeshCheck.Outside ->
                DeviceFixAdoption.Rejected(DeviceFixRejectionReason.OUTSIDE_BANGLADESH, check.distanceMetres)
            BangladeshCheck.BoundaryUnavailable ->
                DeviceFixAdoption.Rejected(DeviceFixRejectionReason.DATASET_UNAVAILABLE)
        }
    }

    private fun adopt(
        point: GeoPoint,
        accuracyMetres: Double?,
        dataset: BangladeshLocationDataset,
    ): DeviceFixAdoption =
        when (val resolution = AreaResolver.resolve(point, dataset)) {
            is AreaResolution.Suggested -> DeviceFixAdoption.Accepted(
                reference = resolution.suggestion.reference,
                countryCode = BangladeshLocationPolicy.COUNTRY_CODE,
                datasetVersion = dataset.version,
                locality = localityFor(point, accuracyMetres, resolution.suggestion.reference, dataset),
            )

            is AreaResolution.Unresolved -> DeviceFixAdoption.Rejected(
                reason = DeviceFixRejectionReason.AREA_UNRESOLVED,
                distanceMetres = resolution.nearestDistanceMetres,
            )

            AreaResolution.DatasetUnavailable ->
                DeviceFixAdoption.Rejected(DeviceFixRejectionReason.DATASET_UNAVAILABLE)
        }

    /**
     * The locality to record with an accepted fix, or `null` when none can be established.
     *
     * Deliberately not a rejection condition: the administrative area is already sufficient to describe
     * the location, and refusing a verified fix because the finer name is unavailable would throw away a
     * valid position to avoid a coarser label.
     */
    private fun localityFor(
        point: GeoPoint,
        accuracyMetres: Double?,
        reference: BangladeshAreaRef,
        dataset: BangladeshLocationDataset,
    ): BangladeshLocalityRef? =
        (LocalityResolver.resolve(point, accuracyMetres, reference.districtId, dataset)
            as? LocalityResolution.Matched)?.suggestion?.reference
}
