package io.github.towkirahmedrony.salah.data.mosque

import io.github.towkirahmedrony.salah.core.domain.location.GeoMath
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import io.github.towkirahmedrony.salah.core.domain.mosque.Mosque
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueCache
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueCacheSnapshot
import io.github.towkirahmedrony.salah.core.domain.mosque.MosqueGeo
import kotlin.math.abs
import kotlin.math.cos

/**
 * The Room-backed mosque cache.
 *
 * The DAO answers a rectangle; this class turns that rectangle into a circle and a distance-sorted
 * list using the same [GeoMath] and [MosqueGeo] rules a live response goes through. That is what
 * makes an offline list and a live list comparable: the screen does not have to know which one it
 * is drawing while it sorts them.
 *
 * **Age, not size.** A mosque is real data that changes slowly, so the cache is bounded by how old
 * a row is rather than by how many rows exist. The repository calls [pruneBefore] after a successful
 * write, which keeps the file from growing across months of use while still letting a user return to
 * an area after a long absence.
 *
 * **A failed cache is not a failed search.** The repository treats cache writes as best-effort; a
 * read that cannot produce a mosque returns `null`, and the caller reports the original network
 * failure rather than a database exception.
 */
class RoomMosqueCache(
    private val dao: MosqueDao,
) : MosqueCache {

    override suspend fun read(center: GeoPoint, radiusMetres: Int): MosqueCacheSnapshot? {
        if (!center.isFinite || radiusMetres <= 0) return null

        val bounds = boundsAround(center, radiusMetres)
        val rows = dao.nearby(
            minLatitude = bounds.minLatitude,
            maxLatitude = bounds.maxLatitude,
            minLongitude = bounds.minLongitude,
            maxLongitude = bounds.maxLongitude,
        )
        if (rows.isEmpty()) return null

        val mosques = MosqueGeo.normalize(rows.mapNotNull { row -> row.toMosqueOrNull() })
        if (mosques.isEmpty()) return null

        val nearest = MosqueGeo.nearestFirst(
            center = center,
            mosques = mosques,
            radiusMetres = radiusMetres,
        )
        if (nearest.isEmpty()) return null

        return MosqueCacheSnapshot(
            mosques = nearest,
            cachedAtEpochMillis = rows.maxOf { row -> row.fetchedAtEpochMillis },
        )
    }

    override suspend fun upsert(mosques: Collection<Mosque>, fetchedAtEpochMillis: Long) {
        if (mosques.isEmpty()) return

        // `associateBy` keeps the last occurrence of a duplicated identity, so a single call can
        // never hand Room two rows with the same primary key.
        val rows = mosques
            .filter { mosque -> MosqueGeo.isUsable(mosque) }
            .associateBy { mosque -> mosque.placeId }
            .values
            .map { mosque -> mosque.toCachedEntity(fetchedAtEpochMillis) }
        if (rows.isEmpty()) return
        dao.upsertAll(rows)
    }

    override suspend fun pruneBefore(cutoffEpochMillis: Long) {
        dao.pruneBefore(cutoffEpochMillis)
    }

    /**
     * The smallest rectangle containing a circle of [radiusMetres] around [center].
     *
     * One degree of latitude is about [GeoMath.EARTH_RADIUS_METRES] × π / 180 metres. Longitude
     * shrinks with latitude, hence the cosine. Near a pole the cosine approaches zero and the
     * longitude span is widened to the whole world rather than divided by it; the exact radius
     * filter in [read] then removes anything outside the circle. The result is clamped to the
     * coordinate range so a pole-adjacent point never queries `-181°`.
     */
    internal fun boundsAround(center: GeoPoint, radiusMetres: Int): CacheBounds {
        val metresPerDegreeLatitude = GeoMath.EARTH_RADIUS_METRES * Math.PI / 180.0
        val latitudeDelta = radiusMetres / metresPerDegreeLatitude
        val cosine = abs(cos(Math.toRadians(center.latitude))).coerceAtLeast(MINIMUM_COSINE)
        val longitudeDelta = (radiusMetres / (metresPerDegreeLatitude * cosine))
            .coerceAtMost(MAX_LONGITUDE_DELTA)

        return CacheBounds(
            minLatitude = (center.latitude - latitudeDelta).coerceAtLeast(-90.0),
            maxLatitude = (center.latitude + latitudeDelta).coerceAtMost(90.0),
            minLongitude = (center.longitude - longitudeDelta).coerceAtLeast(-180.0),
            maxLongitude = (center.longitude + longitudeDelta).coerceAtMost(180.0),
        )
    }

    private companion object {
        /** A near-pole cosine guard: small enough to make the longitude span global. */
        const val MINIMUM_COSINE = 1e-6

        /** No longitude span can exceed the coordinate range. */
        const val MAX_LONGITUDE_DELTA = 360.0
    }
}

/** An axis-aligned query rectangle, in degrees. */
internal data class CacheBounds(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
)
