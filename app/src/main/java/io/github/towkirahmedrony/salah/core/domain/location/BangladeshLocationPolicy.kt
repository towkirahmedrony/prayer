package io.github.towkirahmedrony.salah.core.domain.location

/**
 * The result of asking whether a coordinate is a location Salah supports.
 *
 * Three outcomes, and the difference between the last two matters: "we checked and it is elsewhere"
 * is a different answer from "we could not check", and only one of them justifies telling the user
 * their location is unsupported.
 */
sealed interface BangladeshCheck {

    /** The coordinate is inside Bangladesh, within the stated tolerance. */
    data object Supported : BangladeshCheck

    /**
     * The coordinate is outside Bangladesh, [distanceMetres] beyond the boundary.
     *
     * Carries the distance so the UI can say how far outside rather than only that it is outside, which
     * is the difference between "you are a few hundred metres over the border" (a fix tolerance
     * question) and "you are several thousand kilometres away" (a different country).
     */
    data class Outside(val distanceMetres: Double) : BangladeshCheck

    /** The national boundary could not be read, so no coordinate can be checked against it. */
    data object BoundaryUnavailable : BangladeshCheck
}

/**
 * Salah's Bangladesh-only location policy, in one place.
 *
 * **Why the restriction exists.** Salah's prayer-time settings, its Hijri and Bangladesh (Bangla)
 * calendars, and the bundled administrative dataset are all Bangladesh-specific. Accepting a
 * coordinate elsewhere would mean computing times with a method chosen for Bangladesh and labelling
 * them with a calendar whose rules do not apply there — the app would look like it worked while being
 * wrong. So a location outside Bangladesh is refused, and refused *explicitly*, rather than being
 * quietly converted into a Bangladesh one.
 *
 * **Why it is isolated here.** Every rule that makes Salah Bangladesh-only lives in this object and
 * in the dataset it consults. Supporting another country would mean adding its boundary, its time
 * zone and its hierarchy — not editing screens or features, none of which contain a country check of
 * their own.
 *
 * **How the boundary test works, and its one honest caveat.** The bundled national boundary is
 * simplified to about 1 700 points, so a coordinate a few hundred metres either side of a border can
 * fall on the wrong side of the simplified line. Rather than pretend to sub-kilometre accuracy, the
 * test accepts anything inside the polygon *or* within [BOUNDARY_TOLERANCE_METRES] of its edge: the
 * tolerance is wider than the simplification error and far narrower than the distance at which a
 * wrong answer would matter, and the GPS screen additionally reports which area the position resolved
 * to so the user can see and correct the result.
 */
object BangladeshLocationPolicy {

    /** ISO 3166-1 alpha-2 code stored with every location Salah saves. */
    const val COUNTRY_CODE: String = "BD"

    /** The country every location must be in, by name, for display. */
    const val COUNTRY_NAME: String = "Bangladesh"

    /**
     * The IANA zone used for locations chosen in Bangladesh.
     *
     * The country has a single zone, so a manually selected area uses `Asia/Dhaka` rather than the
     * device's current zone: a user who selects Sylhet while travelling must get Sylhet's times, not
     * the times of wherever the phone is switched on.
     */
    const val TIME_ZONE_ID: String = "Asia/Dhaka"

    /**
     * How far outside the simplified boundary a coordinate may be and still count as inside.
     *
     * Five kilometres, chosen against two errors: the simplification itself moves the line by at most
     * a few hundred metres, and a GPS fix is uncertain by tens of metres; both are far below it. The
     * tolerance is not a licence to accept a neighbouring country's territory — at 5 km it cannot
     * reach any Bangladeshi city or district centre outside the border — it is the margin that keeps a
     * border-adjacent village from being told it is abroad.
     */
    const val BOUNDARY_TOLERANCE_METRES: Double = 5_000.0

    /**
     * Checks [point] against [boundaryRings].
     *
     * The polygon test comes first and the distance test only decides the answer when the polygon says
     * "outside", so the common cases cost one traversal.
     */
    fun check(point: GeoPoint, boundaryRings: List<List<GeoPoint>>): BangladeshCheck {
        if (!point.isFinite) return BangladeshCheck.BoundaryUnavailable
        if (boundaryRings.isEmpty()) return BangladeshCheck.BoundaryUnavailable

        if (GeoMath.isInsideAny(point, boundaryRings)) return BangladeshCheck.Supported

        val distance = GeoMath.distanceToBoundaryMetres(point, boundaryRings)
        if (distance <= BOUNDARY_TOLERANCE_METRES) return BangladeshCheck.Supported
        return BangladeshCheck.Outside(distanceMetres = distance)
    }

    /**
     * A coarse check used to *reject* quickly, never to accept: a coordinate outside this rectangle is
     * outside Bangladesh with certainty, because the rectangle contains the whole country.
     *
     * It exists for stored data, where the polygon cannot be consulted (no dataset loaded): rejecting a
     * coordinate 3 000 km away needs no boundary file, and saying "this stored location is not in
     * Bangladesh" is better than treating it as valid until the dataset happens to load.
     */
    fun isWithinCoarseBounds(point: GeoPoint): Boolean =
        point.isFinite &&
            point.latitude in COARSE_MIN_LATITUDE..COARSE_MAX_LATITUDE &&
            point.longitude in COARSE_MIN_LONGITUDE..COARSE_MAX_LONGITUDE

    /** Southern-most latitude of Bangladesh, with a margin. */
    const val COARSE_MIN_LATITUDE: Double = 20.0

    /** Northern-most latitude of Bangladesh, with a margin. */
    const val COARSE_MAX_LATITUDE: Double = 27.0

    /** Western-most longitude of Bangladesh, with a margin. */
    const val COARSE_MIN_LONGITUDE: Double = 87.5

    /** Eastern-most longitude of Bangladesh, with a margin. */
    const val COARSE_MAX_LONGITUDE: Double = 93.0
}
