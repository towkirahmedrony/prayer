package io.github.towkirahmedrony.salah.data.mosque

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import java.util.Locale

/**
 * One cached mosque.
 *
 * The primary key is the provider's `place_id` (or the parser's coordinate-derived identity), so a
 * refresh from a slightly different centre upserts the same rows instead of accumulating copies of
 * the same mosque. The entity is deliberately flat: a separate categories table would add joins and
 * a second consistency problem to answer a question no screen asks — the categories are only kept
 * to re-run the mosque filter after a cache read.
 *
 * [fetchedAtEpochMillis] is stored on every row rather than in a separate metadata table. The cache
 * is pruned by age, so one timestamp per row is enough to bound the file, and the newest row is the
 * timestamp the UI shows.
 *
 * [categories] is newline-separated because Geoapify's category tokens are dot-separated identifiers
 * that never contain a newline, and a newline needs no escaping or JSON parser inside a database
 * column.
 */
@Entity(
    tableName = "cached_mosques",
    indices = [Index(value = ["fetchedAtEpochMillis"])],
)
data class CachedMosqueEntity(
    @PrimaryKey val placeId: String,
    val name: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val categories: String,
    val phone: String?,
    val website: String?,
    val fetchedAtEpochMillis: Long,
)

/** Maps a domain mosque into the row the cache stores. */
fun Mosque.toCachedEntity(fetchedAtEpochMillis: Long): CachedMosqueEntity = CachedMosqueEntity(
    placeId = placeId,
    name = name,
    address = address,
    latitude = location.latitude,
    longitude = location.longitude,
    categories = categories.joinToString(separator = CATEGORY_SEPARATOR),
    phone = phone,
    website = website,
    fetchedAtEpochMillis = fetchedAtEpochMillis,
)

/**
 * Maps a stored row back to a mosque, or `null` when the row cannot describe one.
 *
 * A row written by this app always maps; the nullable result is for the case the database is read
 * after a future schema change, a partial write or manual corruption. Returning `null` lets the
 * cache drop the bad row from the result rather than crashing the screen or showing a mosque with no
 * position.
 */
fun CachedMosqueEntity.toMosqueOrNull(): Mosque? {
    val location = GeoPoint(latitude = latitude, longitude = longitude)
    val cleanName = name.trim()
    if (placeId.isBlank() || cleanName.isEmpty() || !location.isFinite) return null

    return Mosque(
        placeId = placeId,
        name = cleanName,
        address = address?.trim()?.takeIf { it.isNotEmpty() },
        location = location,
        categories = categories
            .split(CATEGORY_SEPARATOR)
            .map { category -> category.trim() }
            .filter { category -> category.isNotEmpty() },
        phone = phone?.trim()?.takeIf { it.isNotEmpty() },
        website = website?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/** The separator between category tokens inside the stored column. */
internal const val CATEGORY_SEPARATOR = "\n"

/**
 * A display-normalised identity, used by tests and diagnostics only.
 *
 * It is not the cache key — [CachedMosqueEntity.placeId] is — and it is not used to deduplicate
 * results, which is [io.github.towkirahmedrony.salah.core.domain.mosque.MosqueGeo.normalize]'s job.
 * The function exists so a future migration can compare rows written with different spelling of the
 * same provider identifier without inventing a second rule in the migration itself.
 */
internal fun normalisedPlaceId(placeId: String): String = placeId.trim().lowercase(Locale.ROOT)
