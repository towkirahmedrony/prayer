package io.github.towkirahmedrony.salah.core.model

/**
 * What kind of place a locality name describes.
 *
 * The three values are OpenStreetMap's `place` values, kept rather than flattened because they are the
 * evidence for the name: a `suburb` is a named area of a city, a `quarter` is a named part of one, and
 * a `neighbourhood` is the smallest of the three. Salah shows the name either way, and records which
 * kind it was, so nothing has to be inferred later from the string.
 *
 * **None of them is an administrative level.** A locality is not a ward, not a thana and not an
 * upazila, and Salah never presents one as such: the district a locality belongs to is carried
 * separately, verified against the administrative boundaries (see
 * [io.github.towkirahmedrony.salah.core.domain.location.LocalityResolver]).
 */
enum class BangladeshLocalityKind {
    SUBURB,
    QUARTER,
    NEIGHBOURHOOD,
    ;

    companion object {
        /**
         * The kind named by the dataset's column, or `null` when the value is not one this build knows.
         *
         * A value from a later dataset is refused rather than guessed at, so an unrecognised kind
         * rejects the row instead of attaching a plausible-looking label to it.
         */
        fun ofDatasetValue(value: String): BangladeshLocalityKind? = when (value.trim().uppercase()) {
            "SUBURB" -> SUBURB
            "QUARTER" -> QUARTER
            "NEIGHBOURHOOD" -> NEIGHBOURHOOD
            else -> null
        }
    }
}

/**
 * A named urban locality: the place people actually say they are in — Mirpur, Uttara, Dhanmondi.
 *
 * **Why this exists beside [BangladeshArea].** The administrative dataset's finest unit inside the
 * twelve city corporations is the corporation itself, so a device fix anywhere in Dhaka can only be
 * named at that level — half a city, which is a correct answer and a useless one. Localities are the
 * finer layer; they are a *different kind of fact*, which is why they are a different type rather than
 * a fourth [BangladeshAreaLevel].
 *
 * **What the coordinate is.** The locality's own point as the mapper published it — the centre of the
 * name, not the user's position and not a boundary. [districtId] is the district that contains that
 * point, verified by point-in-polygon against the administrative boundaries when the dataset was
 * generated, never by proximity to a district centre. The app uses that verified containment to refuse
 * naming a locality in a district other than the one the user's own position resolved to.
 */
data class BangladeshLocality(
    /** The district this locality's point is inside, as verified by the dataset generator. */
    val districtId: String,
    val kind: BangladeshLocalityKind,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * A locality a stored location was matched to, without its coordinate.
 *
 * The coordinate is deliberately **not** kept: a stored location's coordinates are the user's own
 * position, while a locality's point is where its name sits. Keeping the second alongside the first
 * would put two different positions in one record, and the whole reason a locality is stored at all is
 * to say *which named place the user's position is in* — a name, the district that verified it, and the
 * kind of place it is. The user's coordinates remain the only position the record carries, and they are
 * what prayer times and the Qibla bearing are calculated from.
 */
data class BangladeshLocalityRef(
    val name: String,
    val districtId: String,
    val kind: BangladeshLocalityKind,
) {
    companion object {
        /** The reference a matched locality is stored as. */
        fun of(locality: BangladeshLocality): BangladeshLocalityRef = BangladeshLocalityRef(
            name = locality.name,
            districtId = locality.districtId,
            kind = locality.kind,
        )
    }
}
