package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.domain.json.JsonValue
import io.github.towkirahmedrony.salah.core.domain.json.MiniJson
import io.github.towkirahmedrony.salah.core.domain.json.nonBlankStringOrNull
import io.github.towkirahmedrony.salah.core.domain.json.objectOrNull

/**
 * Reads a Nominatim reverse-geocoding response into a [GeocodedAddress].
 *
 * **Why a parser of its own.** The mapping from a provider's JSON to Salah's model is a decision — which
 * provider keys mean "locality", which mean "district", and which are dropped rather than mapped — and
 * keeping it here rather than inside the HTTP client makes that decision testable on the JVM from a real
 * captured payload, with no device and no network. See `NominatimAddressParserTest`.
 *
 * **Only keys that exist.** The list below uses Nominatim's documented `address` keys
 * (`suburb`, `neighbourhood`, `quarter`, `village`, `hamlet`, `city`, `town`, `municipality`, `road`,
 * `state_district`, `county`, `city_district`, `state`, `country`, `country_code`). It maps them only
 * where the meaning is unambiguous and leaves the rest `null`:
 *
 *  - a **locality** is the narrowest inhabited place key present;
 *  - a **district** is taken from `state_district`, `county` or `city_district`, whichever is present;
 *  - a **division** is `state`;
 *  - an **upazila** is never read from here (OpenStreetMap has no consistent key for one), and the
 *    picker uses the offline administrative resolution instead.
 *
 * A response that is not well-formed JSON is [ReverseGeocodeResult.Unavailable] with `malformed`; a
 * well-formed response with no usable components is [ReverseGeocodeResult.NotFound]. Neither case
 * prevents the user from saving the coordinates they chose.
 */
object NominatimAddressParser {

    /** A stable reason code for a response that could not be parsed. */
    const val REASON_MALFORMED: String = "malformed"

    /** A stable reason code for a response with no usable address components. */
    const val REASON_NO_ADDRESS: String = "no_address"

    /** Parses [body], the raw response text. */
    fun parse(body: String): ReverseGeocodeResult {
        val root = MiniJson.parse(body) ?: return ReverseGeocodeResult.Unavailable(REASON_MALFORMED)
        if (root !is JsonValue.ObjectValue) return ReverseGeocodeResult.Unavailable(REASON_MALFORMED)

        val address = root.objectOrNull("address")
        val geocoded = GeocodedAddress(
            displayName = root.nonBlankStringOrNull("display_name"),
            placeName = root.nonBlankStringOrNull("name")
                ?: address.firstOf("city", "town", "municipality", "village"),
            locality = address.firstOf("suburb", "neighbourhood", "quarter", "village", "hamlet"),
            road = address.firstOf("road", "pedestrian", "footway"),
            district = address.firstOf("state_district", "county", "city_district"),
            division = address.firstOf("state", "region"),
            country = address.firstOf("country"),
            countryCode = address?.nonBlankStringOrNull("country_code")?.uppercase(),
        )
        return if (geocoded.isUsable) {
            ReverseGeocodeResult.Found(geocoded)
        } else {
            ReverseGeocodeResult.NotFound(REASON_NO_ADDRESS)
        }
    }

    /** The first of [keys] whose value is a non-blank string, or `null`. */
    private fun JsonValue.ObjectValue?.firstOf(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this?.nonBlankStringOrNull(key) }
}
