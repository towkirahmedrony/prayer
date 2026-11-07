package io.github.towkirahmedrony.salah.core.domain.mosque

import io.github.towkirahmedrony.salah.core.domain.location.GeoMath
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import java.util.Locale

/**
 * The pure rules that decide what a mosque result is and how far away it is.
 *
 * **Why the provider's category alone is not enough.** Geoapify documents a mosque-specific category,
 * `tourism.sights.place_of_worship.mosque`, and an Islamic-worship category,
 * `religion.place_of_worship.islam`. The former is precise but is not where ordinary neighbourhood
 * mosques are published; the latter contains the mosques but also madrasas, shrines, Eidgah grounds and
 * other Islamic institutions. The search therefore asks for both and this object accepts:
 *
 *  * anything carrying the mosque-specific category; or
 *  * anything carrying the Islamic-worship category whose **name** identifies it as a mosque.
 *
 * The name test is deliberately conservative: a building called "Noorani Madrasah" or "Khankah
 * Shirajia" is not a mosque even though it is an Islamic place, and showing it under "mosques nearby"
 * would be the wrong answer to the user's question. Only a name that actually contains a mosque word
 * is accepted; a name that names both — for example "Sayednogor Mosjid Madrasa" — counts, because it
 * names a mosque even when it also names the school attached to it.
 *
 * **What this object never does.** It never invents a name, address or coordinate. A feature with no
 * name, no usable coordinate, or no mosque identity is dropped, and the screen reports a smaller list
 * rather than a list padded with guesses.
 */
object MosqueGeo {

    /**
     * Words that identify a mosque in the names this app receives.
     *
     * Latin transliterations are included because OpenStreetMap contributors spell the same word many
     * ways ("Masjid", "Mosjid", "Mashjid", "Moshjid", "Mosque", "Moque") and because a filter that only
     * recognised the dictionary spelling would drop most of Dhaka's neighbourhood mosques. Bengali is
     * included because the service returns local names for many places even when the request asks for
     * English.
     */
    private val MOSQUE_NAME_MARKERS: List<String> = listOf(
        "mosque",
        "mosqe",
        "moaque",
        "masjid",
        "masjeed",
        "masjed",
        "masgid",
        "mosjid",
        "moshjid",
        "mashjid",
        "musalla",
        "musollah",
        "jame",
        "jaame",
        "jami",
        "jamia",
        "baitul",
        "baytul",
        "মসজিদ",
        "জামে",
    )

    /** Whether [mosque] is a mosque this feature may show. */
    fun isMosque(mosque: Mosque): Boolean {
        if (mosque.categories.any { category -> category == Mosque.CATEGORY_MOSQUE_SPECIFIC }) {
            return true
        }
        if (mosque.categories.none { category -> category == Mosque.CATEGORY_ISLAMIC_WORSHIP }) {
            return false
        }

        // A bare category such as "Islamic place of worship" also matches madrasas, shrines and
        // Eidgah grounds. Without a mosque-specific category, the name is the only signal strong
        // enough to tell a mosque apart from its neighbouring institutions.
        val name = mosque.name.lowercase(Locale.ROOT)
        return MOSQUE_NAME_MARKERS.any { marker -> name.contains(marker) }
    }

    /**
     * Drops unusable and duplicate features.
     *
     * Identity is [Mosque.placeId], so two pages that repeat the same place collapse to one. The first
     * occurrence wins: it already carries the fields the response provided, and preferring a later one
     * would make the result depend on pagination order for no benefit.
     */
    fun normalize(mosques: List<Mosque>): List<Mosque> {
        val accepted = LinkedHashMap<String, Mosque>()
        for (mosque in mosques) {
            if (!isUsable(mosque)) continue
            if (accepted.containsKey(mosque.placeId)) continue
            accepted[mosque.placeId] = mosque
        }
        return accepted.values.toList()
    }

    /** Whether a feature has enough content to be shown. */
    fun isUsable(mosque: Mosque): Boolean =
        mosque.placeId.isNotBlank() &&
            mosque.name.isNotBlank() &&
            mosque.location.isFinite &&
            isMosque(mosque)

    /**
     * Pairs every mosque with its distance from [center], nearest first.
     *
     * When [radiusMetres] is supplied, results outside the circle are dropped. The service already
     * applied a circle filter, but doing it here as well keeps a cached result and a live result subject
     * to the same rule — and means a cache row from a wider earlier search cannot leak into a narrower
     * current one.
     */
    fun nearestFirst(
        center: GeoPoint,
        mosques: List<Mosque>,
        radiusMetres: Int? = null,
    ): List<MosqueWithDistance> {
        val withDistance = mosques.map { mosque ->
            MosqueWithDistance(
                mosque = mosque,
                distanceMetres = GeoMath.distanceMetres(center, mosque.location),
            )
        }
        val withinRadius = if (radiusMetres == null) {
            withDistance
        } else {
            withDistance.filter { result -> result.distanceMetres <= radiusMetres }
        }
        return withinRadius.sortedWith(
            compareBy<MosqueWithDistance> { result -> result.distanceMetres }
                .thenBy { result -> result.mosque.name.lowercase(Locale.ROOT) },
        )
    }
}
