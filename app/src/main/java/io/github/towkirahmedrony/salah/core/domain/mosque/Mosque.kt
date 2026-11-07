package io.github.towkirahmedrony.salah.core.domain.mosque

import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint

/**
 * A mosque returned by the places service.
 *
 * The model holds only what the service actually returned. Nothing here is enriched by a second
 * request: `place_id` is kept because it is the stable identity of a result, but no Place Details call
 * is made, so phone, website and address are present only when the search response carried them.
 *
 * [location] is a [GeoPoint] so the same haversine implementation the rest of Salah uses decides the
 * distance. The service's own `distance` property is deliberately not trusted or stored: it is
 * computed against the request's bias point, while the screen needs the distance from whichever
 * position the user is actually searching from.
 */
data class Mosque(
    /** The service's stable identifier, or a coordinate-derived identity when it did not return one. */
    val placeId: String,
    val name: String,
    val address: String?,
    val location: GeoPoint,
    val categories: List<String> = emptyList(),
    val phone: String? = null,
    val website: String? = null,
) {
    companion object {
        /**
         * The provider's mosque-specific category.
         *
         * Geoapify documents this under `tourism.sights.place_of_worship.mosque`. In practice many
         * ordinary neighbourhood mosques are published only under the religion hierarchy below, so the
         * search requests both and [MosqueGeo] applies a name-based mosque filter to the latter.
         */
        const val CATEGORY_MOSQUE_SPECIFIC: String = "tourism.sights.place_of_worship.mosque"

        /**
         * The provider's Islamic place-of-worship category.
         *
         * This is broader than a mosque: it also contains madrasas, shrines and Eidgah grounds. It is
         * requested because it is where the provider actually publishes most mosques, and every result
         * from it must pass [MosqueGeo.isMosque] before it is shown.
         */
        const val CATEGORY_ISLAMIC_WORSHIP: String = "religion.place_of_worship.islam"
    }
}

/** A mosque paired with its locally calculated great-circle distance from the search position. */
data class MosqueWithDistance(
    val mosque: Mosque,
    val distanceMetres: Double,
)

/** Where the list currently on screen came from. */
enum class MosqueDataOrigin {
    /** A successful request to the places service. */
    NETWORK,

    /** A previously stored result, shown because the service could not be reached. */
    CACHE,
}
