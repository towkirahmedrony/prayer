package io.github.towkirahmedrony.salah.data.mosque

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.JsonReader
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.JsonValue
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import java.util.Locale

/**
 * One readable Geoapify Places response.
 *
 * [featureCount] is the number of features the service returned, not the number this parser could
 * map. Pagination uses it to decide whether a full page implies another page, so a page whose
 * features were all skipped still advances correctly.
 */
data class GeoapifyMosquePage(
    val mosques: List<Mosque>,
    val featureCount: Int,
)

/**
 * Reads the Geoapify Places `v2/places` GeoJSON response into domain mosques.
 *
 * **The response shape.** Geoapify answers with a GeoJSON `FeatureCollection`:
 *
 * ```
 * { "type": "FeatureCollection",
 *   "features": [
 *     { "type": "Feature",
 *       "properties": { "name": "...", "formatted": "...", "categories": ["..."],
 *                       "place_id": "...", "datasource": { "raw": { "phone": "..." } } },
 *       "geometry": { "type": "Point", "coordinates": [longitude, latitude] } } ] }
 * ```
 *
 * Coordinates are **longitude first**, which is the opposite order of [GeoPoint] and of every
 * other latitude/longitude value in Salah. The swap happens here, once, so nothing downstream has
 * to remember it.
 *
 * **What is skipped rather than guessed.** A feature with no name, no finite point, or no
 * coordinates at all cannot be shown as a mosque, so it is dropped instead of being padded with a
 * placeholder. A malformed *feature* does not invalidate its siblings; only a root that is not a
 * FeatureCollection with a `features` array is reported as malformed.
 *
 * **No Place Details call.** `place_id` is carried because it is the stable identity used by the
 * cache, not because it will be exchanged for a second request. Phone and website are read only if
 * the search response already contains them (under `datasource.raw`), and are left absent otherwise.
 */
object GeoapifyMosqueParser {

    /** The parsed page, or `null` when the body is not a readable FeatureCollection. */
    fun parse(body: ByteArray): GeoapifyMosquePage? {
        if (body.isEmpty()) return null

        val text = runCatching { body.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val root = JsonReader.parse(text) as? JsonValue.ObjectValue ?: return null
        val features = root.child("features") as? JsonValue.ArrayValue ?: return null

        val mosques = ArrayList<Mosque>(features.elements.size)
        for (feature in features.elements) {
            val mosque = feature.toMosque() ?: continue
            mosques += mosque
        }
        return GeoapifyMosquePage(mosques = mosques, featureCount = features.elements.size)
    }

    private fun JsonValue.toMosque(): Mosque? {
        val properties = child("properties") as? JsonValue.ObjectValue ?: return null
        val name = properties.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val coordinates = child("geometry")?.child("coordinates") as? JsonValue.ArrayValue ?: return null
        val longitude = coordinates.elements.getOrNull(0)?.asNumberOrNull() ?: return null
        val latitude = coordinates.elements.getOrNull(1)?.asNumberOrNull() ?: return null
        val location = GeoPoint(latitude = latitude, longitude = longitude)
        if (!location.isFinite) return null

        val placeId = properties.stringOrNull("place_id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: coordinateIdentity(location, name)

        val formatted = properties.stringOrNull("formatted")?.trim()?.takeIf { it.isNotEmpty() }
        val address = formatted
            ?: listOfNotNull(
                properties.stringOrNull("address_line1")?.trim()?.takeIf { it.isNotEmpty() },
                properties.stringOrNull("address_line2")?.trim()?.takeIf { it.isNotEmpty() },
            ).joinToString(", ").takeIf { it.isNotEmpty() }

        return Mosque(
            placeId = placeId,
            name = name,
            address = address,
            location = location,
            categories = properties.stringList("categories"),
            phone = properties.rawContact("phone"),
            website = properties.rawContact("website"),
        )
    }

    private fun JsonValue.asNumberOrNull(): Double? =
        (this as? JsonValue.NumberValue)?.value?.takeIf { it.isFinite() }

    private fun JsonValue.stringList(name: String): List<String> {
        val array = child(name) as? JsonValue.ArrayValue ?: return emptyList()
        return array.elements.mapNotNull { element ->
            (element as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * A contact field from `datasource.raw`, falling back to a top-level property.
     *
     * Geoapify's documented shape nests the upstream OpenStreetMap tags under `datasource.raw`; the
     * top-level fallback keeps a response that flattens them readable rather than silently losing a
     * field this feature can display.
     */
    private fun JsonValue.rawContact(name: String): String? =
        child("datasource")
            ?.child("raw")
            ?.stringOrNull(name)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: stringOrNull(name)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A stable identity for a feature the service did not tag with a `place_id`.
     *
     * Deliberately derived from the coordinate and the name rather than from the response's order:
     * pagination and cache updates can see the same feature in a different position, and an identity
     * that moved with position would duplicate it. A coordinate alone is not enough because two
     * buildings at one point are two places.
     */
    private fun coordinateIdentity(location: GeoPoint, name: String): String {
        val normalisedName = name.lowercase(Locale.ROOT).trim().replace(WHITESPACE, " ")
        return "geo:%.6f:%.6f:%s".format(Locale.ROOT, location.latitude, location.longitude, normalisedName)
    }

    private val WHITESPACE = Regex("\\s+")
}
