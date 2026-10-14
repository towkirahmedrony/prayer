package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshLocality
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityRef

/**
 * A locality the position was matched to, with how far the position was from it.
 *
 * [distanceMetres] is carried because it is a precision statement: the name is a *nearest* match inside
 * a stated radius, not a boundary containment, and the screen that confirms a fix says so rather than
 * implying the user was found inside a surveyed neighbourhood.
 */
data class LocalitySuggestion(
    val reference: BangladeshLocalityRef,
    val distanceMetres: Double,
)

/**
 * What could be said about the named locality a position is in.
 *
 * All three outcomes are normal. [NotPreciseEnough] is the one that exists because of the Android
 * permission model: an approximate fix (or one whose accuracy the platform did not report) cannot
 * distinguish one neighbourhood from the next, so Salah names the administrative area instead of
 * pretending it knows the street.
 */
sealed interface LocalityResolution {

    /** A named locality was matched. */
    data class Matched(val suggestion: LocalitySuggestion) : LocalityResolution

    /**
     * Nothing was matched: no locality lies within the match radius of this position, or there is no
     * locality layer for the district the position resolved to.
     *
     * [nearestDistanceMetres] is how far the closest locality in that district was, or `null` when there
     * were none to measure. It is reported rather than hidden so a diagnostic can tell "just outside the
     * radius" from "this district has no locality data at all".
     */
    data class Unmatched(val nearestDistanceMetres: Double?) : LocalityResolution

    /**
     * The position's own reported accuracy is too coarse to name a locality.
     *
     * Not a failure: it is the correct answer for a fix that is only accurate to a kilometre or two, and
     * it is what keeps an approximate permission from being presented as a precise one.
     */
    data object NotPreciseEnough : LocalityResolution
}

/**
 * Resolves a position to the named locality it is in, offline and without a network call.
 *
 * **Why nearest-point rather than point-in-polygon.** The locality layer carries published place points,
 * not boundaries: OpenStreetMap tags a locality with a single node (or a way with a centre), and the
 * boundary polygons that would be needed for containment are not part of a dataset this app could ship
 * at this granularity. The consequence is stated rather than hidden — the answer is a *nearest* match
 * inside [MATCH_METRES], it is only made when the position's own accuracy fits inside that radius, and
 * when it cannot be made the administrative area is named instead.
 *
 * **Why the district gate.** A locality is only considered if the district it was *verified* to be in is
 * the same district the administrative resolver attributed the position to. Without that, a position on
 * one side of a district border could be named after a place on the other side — precisely the "unrelated
 * nearby neighbourhood" the display policy must never produce. The verified district comes from the
 * dataset's generator, which tested the locality's point against the authoritative administrative
 * boundaries by point-in-polygon, never by proximity.
 *
 * **Why accuracy is part of the decision.** A match is only made when the whole accuracy circle fits
 * inside [MATCH_METRES] — `distance + accuracy <= MATCH_METRES`. A fix reported as accurate to ±2 km
 * therefore cannot be named after a place 1.9 km away, because the true position could be 3.9 km from it.
 * That is the rule that represents an approximate permission honestly instead of translating it into a
 * confident street name.
 *
 * **What it is not.** This is not reverse geocoding: no address is produced, no street is named, nothing
 * is sent anywhere, and no thana identity is claimed for the name. It runs against the bundled dataset,
 * so it works with the radio off.
 */
object LocalityResolver {

    /**
     * How close a position must be to a locality's published point to be named after it.
     *
     * Two kilometres is the scale of the units themselves: the suburbs, quarters and neighbourhoods in
     * the layer are typically one to four kilometres across, so a match inside two kilometres with an
     * accuracy that also fits is the place the user is standing in. Beyond it the next named place is as
     * likely as this one, so Salah says nothing rather than guessing — a wrong neighbourhood name is
     * worse than the district name it would replace.
     */
    const val MATCH_METRES: Double = 2_000.0

    /**
     * Resolves [point] against [dataset].
     *
     * @param accuracyMetres the position's reported horizontal accuracy, or `null` when the platform did
     *   not report one. An unknown accuracy cannot establish that a neighbourhood-sized match is
     *   meaningful, so it produces [LocalityResolution.NotPreciseEnough] rather than a name.
     * @param districtId the district the administrative resolver attributed this position to, or `null`
     *   when it could not attribute one. Without it there is nothing to verify a locality against.
     */
    fun resolve(
        point: GeoPoint,
        accuracyMetres: Double?,
        districtId: String?,
        dataset: BangladeshLocationDataset?,
    ): LocalityResolution {
        if (dataset == null || !dataset.hasLocalities) {
            return LocalityResolution.Unmatched(nearestDistanceMetres = null)
        }
        if (!point.isFinite) {
            return LocalityResolution.Unmatched(nearestDistanceMetres = null)
        }

        val candidates = dataset.localitiesOf(districtId)
        if (candidates.isEmpty()) {
            return LocalityResolution.Unmatched(nearestDistanceMetres = null)
        }

        val accuracy = accuracyMetres?.takeIf { value -> value.isFinite() && value > 0.0 }
            ?: return LocalityResolution.NotPreciseEnough

        val nearest = candidates.minBy { locality -> GeoMath.distanceMetres(point, locality.point) }
        val distance = GeoMath.distanceMetres(point, nearest.point)
        if (distance + accuracy > MATCH_METRES) {
            return LocalityResolution.Unmatched(nearestDistanceMetres = distance)
        }

        return LocalityResolution.Matched(
            LocalitySuggestion(
                reference = BangladeshLocalityRef.of(nearest),
                distanceMetres = distance,
            ),
        )
    }

    private val BangladeshLocality.point: GeoPoint
        get() = GeoPoint(latitude = latitude, longitude = longitude)
}
