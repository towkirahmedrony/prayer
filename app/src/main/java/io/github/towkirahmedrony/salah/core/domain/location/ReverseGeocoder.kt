package io.github.towkirahmedrony.salah.core.domain.location

/** Where a reverse-geocoding result came from. */
interface ReverseGeocoder {

    /**
     * Resolves the address of [point], or reports why it could not.
     *
     * Implementations must not throw: a timeout, a rate limit, an HTTP error and a malformed body are
     * all [ReverseGeocodeResult.Unavailable], because address resolution is a convenience here and never
     * a prerequisite for choosing a location. They must also honour coroutine cancellation — the caller
     * cancels the previous lookup when the selected point changes, so an obsolete request has no reason
     * to finish.
     */
    suspend fun reverse(point: GeoPoint): ReverseGeocodeResult
}

/**
 * The outcome of a reverse-geocoding lookup.
 *
 * [Found] carries whatever the provider actually returned, with every field optional: an address in a
 * rural union may have no neighbourhood, no road and no locality, and Salah then shows the coordinates
 * (and the administrative area it resolved offline) rather than inventing the missing parts.
 */
sealed interface ReverseGeocodeResult {

    /** The provider returned an address. */
    data class Found(val address: GeocodedAddress) : ReverseGeocodeResult

    /**
     * The provider answered, but with no usable address for this point — an empty result set, or a
     * response with no address components at all. Distinct from [Unavailable] because it is a fact about
     * the place, not about the network, and the UI says so differently.
     */
    data class NotFound(val reason: String) : ReverseGeocodeResult

    /**
     * The lookup could not complete. [reason] is a stable short code — `network`, `timeout`,
     * `rate_limited`, `http_<code>`, `malformed` — so a diagnostic can distinguish a missing connection
     * from a provider that refused the request without logging anything about the user's position.
     */
    data class Unavailable(val reason: String) : ReverseGeocodeResult
}

/**
 * The address components a reverse-geocoding provider returned for a selected point.
 *
 * **Everything is optional, on purpose.** A provider may know a road but no neighbourhood, or a
 * district but no place name, and Salah must be able to display "the parts that are actually known"
 * rather than a sentence with blanks filled in by guesswork. The fields are the informational address
 * only: they never become the coordinates Salah calculates from, and a missing field is never
 * substituted from another source.
 *
 * **What is deliberately *not* here.** An `upazila`/`thana`. OpenStreetMap has no consistent
 * administrative key for a Bangladeshi upazila, so the picker takes the upazila from the *offline*
 * administrative resolution (`AreaResolver`, against the bundled COD-AB dataset) instead of inventing
 * one from a generic provider key. See `docs/MAP_PICKER.md`.
 */
data class GeocodedAddress(
    /** The provider's own formatted address, e.g. `12, Mirpur Road, Dhaka, Dhaka Division, Bangladesh`. */
    val displayName: String? = null,
    /** The named place, when the provider gives one. */
    val placeName: String? = null,
    /** The neighbourhood, suburb, quarter or village the point is in, when one is published. */
    val locality: String? = null,
    /** The road, when the point is on a named one. */
    val road: String? = null,
    /** The district-level unit, when the provider publishes a comparable key. */
    val district: String? = null,
    /** The division-level unit, when the provider publishes a comparable key. */
    val division: String? = null,
    /** The country name. */
    val country: String? = null,
    /** ISO 3166-1 alpha-2 country code, lower-cased by the provider and normalised here to upper case. */
    val countryCode: String? = null,
) {
    /** True when the provider gave at least one non-blank component. */
    val isUsable: Boolean
        get() = listOfNotNull(displayName, placeName, locality, road, district, division, country)
            .any { it.isNotBlank() }

    /**
     * The deepest name available for a one-line display, in the order the address narrows: the place
     * name, then the locality, then the road, then the provider's formatted address.
     *
     * Used as the stored location's display label when it is not blank, so the name keeps the detail the
     * user actually chose rather than being replaced by an administrative area.
     */
    val primaryName: String?
        get() = listOfNotNull(placeName, locality, road, displayName)
            .firstOrNull { it.isNotBlank() }
}
