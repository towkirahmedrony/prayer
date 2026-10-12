package io.github.towkirahmedrony.salah.core.domain.location

/**
 * The result of asking whether a coordinate is a location Salah supports.
 *
 * Four outcomes, and the differences between them matter: a point *inside* the boundary is a different
 * claim from a point *within the tolerance* of it, and both are a different answer from "we checked"
 * (outside) and "we could not check" (unavailable). Only [Outside] justifies telling the user their
 * location is unsupported, and only [Inside] is reported as confirmed containment.
 */
sealed interface BangladeshCheck {

    /**
     * The coordinate is **strictly inside** the national boundary polygon.
     *
     * This is the only outcome that establishes Bangladesh, so it is the only one that may be reported
     * to the user as a confirmed position inside the country.
     */
    data object Inside : BangladeshCheck

    /**
     * The coordinate is outside the boundary polygon but within [BOUNDARY_TOLERANCE_METRES] of it.
     *
     * The tolerance exists for GPS and boundary-simplification error, and it cuts both ways: a genuine
     * Bangladeshi position a few hundred metres outside the simplified line lands here, and so does a
     * position a few kilometres *inside* a neighbouring country. It is therefore deliberately a
     * distinct outcome from [Inside] — accepted so that a border-adjacent fix is not thrown away, but
     * never described as a verified position inside Bangladesh. [distanceMetres] is how far beyond the
     * boundary the coordinate lies.
     */
    data class WithinBoundaryTolerance(val distanceMetres: Double) : BangladeshCheck

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
 *
 * **Why tolerance-acceptance is not the same as containment.** Because the tolerance cuts both ways, a
 * point within it may be a real Bangladeshi position just outside the simplified line *or* a point
 * just over the border in another country — the tolerance cannot tell them apart. Reporting both as
 * "inside Bangladesh" would make a false claim about the second. So the two are distinct outcomes:
 * [BangladeshCheck.Inside] is strict containment, and [BangladeshCheck.WithinBoundaryTolerance] is the
 * uncertainty allowance, which callers accept for *keeping* a position but never present as verified
 * containment. The alternative — tightening the tolerance away — would reject genuine border villages
 * instead, and would not fix the ambiguity, only move it.
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
     * tolerance is the margin that keeps a border-adjacent village from being told it is abroad.
     *
     * It is **not** a claim of containment, and it cannot be: the tolerance is symmetric, so territory
     * a few kilometres inside a neighbouring country falls within it too. The bundled data confirms
     * this — the Indian city of Agartala is about 3.4 km from the simplified line and so is inside the
     * tolerance. That is exactly why [BangladeshCheck.WithinBoundaryTolerance] is kept distinct from
     * [BangladeshCheck.Inside] and is never reported as a verified position inside Bangladesh.
     */
    const val BOUNDARY_TOLERANCE_METRES: Double = 5_000.0

    /**
     * Checks [point] against [boundaryRings].
     *
     * The polygon test comes first and the distance test only decides the answer when the polygon says
     * "outside", so the common cases cost one traversal. Strict containment returns
     * [BangladeshCheck.Inside]; an outside point within the tolerance returns
     * [BangladeshCheck.WithinBoundaryTolerance]; anything further out returns
     * [BangladeshCheck.Outside].
     */
    fun check(point: GeoPoint, boundaryRings: List<List<GeoPoint>>): BangladeshCheck {
        if (!point.isFinite) return BangladeshCheck.BoundaryUnavailable
        if (boundaryRings.isEmpty()) return BangladeshCheck.BoundaryUnavailable

        if (GeoMath.isInsideAny(point, boundaryRings)) return BangladeshCheck.Inside

        val distance = GeoMath.distanceToBoundaryMetres(point, boundaryRings)
        if (distance <= BOUNDARY_TOLERANCE_METRES) {
            return BangladeshCheck.WithinBoundaryTolerance(distanceMetres = distance)
        }
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
