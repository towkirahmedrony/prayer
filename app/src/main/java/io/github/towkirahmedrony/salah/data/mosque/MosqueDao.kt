package io.github.towkirahmedrony.salah.data.mosque

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * The mosque cache's database surface.
 *
 * **Why a bounding box and not a radius in SQL.** Room is not the place to reimplement spherical
 * geometry. The query narrows to the smallest latitude/longitude rectangle that contains the search
 * circle, and [RoomMosqueCache] applies the exact haversine radius and distance sort afterwards, so
 * a row from a wider earlier search cannot leak into a narrower current one. The rectangle is
 * deliberately a little larger than the circle near the poles rather than being exact.
 *
 * All methods are `suspend`: Room runs them on its own executor, and the repository calls them from
 * a coroutine that is cancelled when the screen goes away.
 */
@Dao
interface MosqueDao {

    /** Every stored mosque whose coordinate lies inside the supplied rectangle. */
    @Query(
        """
        SELECT * FROM cached_mosques
        WHERE latitude BETWEEN :minLatitude AND :maxLatitude
          AND longitude BETWEEN :minLongitude AND :maxLongitude
        """,
    )
    suspend fun nearby(
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double,
    ): List<CachedMosqueEntity>

    /**
     * Inserts or replaces each mosque by [CachedMosqueEntity.placeId].
     *
     * `@Upsert` rather than `@Insert(onConflict = REPLACE)`: both update in place, but `@Upsert`
     * is the API Room documents for exactly this, and it does not tempt a future reader into
     * thinking the row's other columns may be discarded.
     */
    @Upsert
    suspend fun upsertAll(mosques: List<CachedMosqueEntity>)

    /** Removes rows fetched before [cutoffEpochMillis]. */
    @Query("DELETE FROM cached_mosques WHERE fetchedAtEpochMillis < :cutoffEpochMillis")
    suspend fun pruneBefore(cutoffEpochMillis: Long): Int

    /** The newest fetch time stored, or `null` when the cache is empty. */
    @Query("SELECT MAX(fetchedAtEpochMillis) FROM cached_mosques")
    suspend fun newestFetchTime(): Long?
}
