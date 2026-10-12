package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshArea
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef

/**
 * The administrative area a device fix most likely belongs to.
 *
 * [matchedLevel] is carried because it is a precision statement the UI must not lose: a fix that
 * matched an upazila centre within [AreaResolver.UPAZILA_MATCH_METRES] is named at upazila level, and a
 * fix that only matched a district is named at district level. Showing the second as the first would
 * claim a finer resolution than the data supports.
 */
data class AreaSuggestion(
    val reference: BangladeshAreaRef,
    val matchedLevel: BangladeshAreaLevel,
    /** Distance from the fix to the matched area's published centre, in metres. */
    val distanceMetres: Double,
)

/**
 * What could be said about a device fix.
 *
 * [Unresolved] is a real, reported outcome rather than an error: when the fix is further from every
 * published centre than the thresholds allow, Salah says it cannot tell which area the position is in
 * and offers manual selection. It does not pick the nearest centre anyway — that is how a user in one
 * district is shown another district's name.
 */
sealed interface AreaResolution {

    /** An area was matched. [AreaSuggestion.matchedLevel] says how precisely. */
    data class Suggested(val suggestion: AreaSuggestion) : AreaResolution

    /**
     * No area could be attributed. [nearestDistanceMetres] is how far the closest published centre
     * was, or `null` when the dataset had no usable areas at all.
     */
    data class Unresolved(val nearestDistanceMetres: Double?) : AreaResolution

    /** The dataset is not loaded, so there is nothing to resolve against. */
    data object DatasetUnavailable : AreaResolution
}

/**
 * Resolves a coordinate to an administrative area, offline and without a network call.
 *
 * **Why nearest-centre rather than point-in-polygon.** Salah bundles representative points, not
 * upazila boundaries: the published boundaries are tens of megabytes, and shipping them would multiply
 * the app's data footprint by an order of magnitude to answer a question the centre distance already
 * answers to within a few kilometres. The consequence is stated rather than hidden — the answer is a
 * *nearest match*, it is labelled as approximate in the UI, it is reported at district level when the
 * upazila match is not close enough, and the user can always select the area manually instead.
 *
 * **Why the thresholds exist.** Attributing a fix to whichever centre happens to be nearest, however
 * far away, would produce confident nonsense: in the Sundarbans or on a char in the Meghna the nearest
 * centre can be 40 km away across water and in a different district. So an upazila is only named when
 * its centre is within [UPAZILA_MATCH_METRES], a district within [DISTRICT_MATCH_METRES], and beyond
 * that Salah says it cannot tell.
 *
 * **What it is not.** This is not reverse geocoding: no address is produced, no street is named, and
 * nothing is sent anywhere. It runs against the bundled dataset, so it works with the radio off.
 */
object AreaResolver {

    /**
     * How close a fix must be to an upazila's published centre to name that upazila.
     *
     * Upazila areas around Bangladesh are typically a few hundred square kilometres, so a centre within
     * 20 km is plausibly the containing unit — and when it is not, the error is a neighbouring upazila
     * whose own centre is also within a few kilometres, which makes the prayer-time difference seconds
     * to a minute. The label is presented as approximate for exactly this reason.
     */
    const val UPAZILA_MATCH_METRES: Double = 20_000.0

    /**
     * How close a fix must be to a district's published centre to name that district.
     *
     * Wider than [UPAZILA_MATCH_METRES] because districts are much larger; beyond it, Salah reports
     * that it cannot attribute the position at all.
     */
    const val DISTRICT_MATCH_METRES: Double = 75_000.0

    /**
     * Resolves [point] against [dataset].
     *
     * The upazila is tried first and only over the areas of the district whose own centre is nearest,
     * so the search is bounded by the hierarchy rather than by scanning every upazila in the country.
     */
    fun resolve(point: GeoPoint, dataset: BangladeshLocationDataset): AreaResolution {
        if (!dataset.isUsable) return AreaResolution.DatasetUnavailable
        if (!point.isFinite) return AreaResolution.Unresolved(nearestDistanceMetres = null)

        val nearestDistrict = nearest(point, dataset.districts) ?: return AreaResolution.Unresolved(null)
        val districtDistance = GeoMath.distanceMetres(point, nearestDistrict.point)
        if (districtDistance > DISTRICT_MATCH_METRES) {
            return AreaResolution.Unresolved(nearestDistanceMetres = districtDistance)
        }

        val nearestUpazila = nearest(point, dataset.upazilasOf(nearestDistrict.area.id))
        val upazilaDistance = nearestUpazila?.let { GeoMath.distanceMetres(point, it.point) }
        val useUpazila = nearestUpazila != null && upazilaDistance != null &&
            upazilaDistance <= UPAZILA_MATCH_METRES

        val reference = if (useUpazila) {
            dataset.referenceOf(nearestUpazila.area.id)
        } else {
            dataset.referenceOf(nearestDistrict.area.id)
        } ?: return AreaResolution.Unresolved(nearestDistanceMetres = districtDistance)

        return AreaResolution.Suggested(
            AreaSuggestion(
                reference = reference,
                matchedLevel = if (useUpazila) BangladeshAreaLevel.UPAZILA else BangladeshAreaLevel.DISTRICT,
                distanceMetres = if (useUpazila && upazilaDistance != null) upazilaDistance else districtDistance,
            ),
        )
    }

    private fun nearest(
        point: GeoPoint,
        candidates: List<BangladeshArea>,
    ): Match? = candidates
        .minByOrNull { GeoMath.distanceMetres(point, it.point) }
        ?.let { Match(it, it.point) }

    private class Match(val area: BangladeshArea, val point: GeoPoint)

    private val BangladeshArea.point: GeoPoint
        get() = GeoPoint(latitude = latitude, longitude = longitude)
}
