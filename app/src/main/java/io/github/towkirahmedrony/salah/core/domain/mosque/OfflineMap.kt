package io.github.towkirahmedrony.salah.core.domain.mosque

import io.github.towkirahmedrony.salah.core.domain.location.GeoMath
import io.github.towkirahmedrony.salah.core.domain.location.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * An axis-aligned latitude/longitude rectangle, in degrees.
 *
 * This is the geographic area an offline map region covers. It is deliberately a rectangle rather
 * than a circle: map tile pyramids are addressed by tile rectangles, and a region definition takes
 * bounds, so a circle would be converted back into a rectangle anyway.
 */
data class MapBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
) {
    /** True when the four values describe a real, non-empty rectangle. */
    val isValid: Boolean
        get() = north.isFinite() &&
            south.isFinite() &&
            east.isFinite() &&
            west.isFinite() &&
            north >= south &&
            north <= 90.0 &&
            south >= -90.0 &&
            east >= west &&
            west >= -180.0 &&
            east <= 180.0

    /** True when [point] lies inside the rectangle. The edges count as inside. */
    fun contains(point: GeoPoint): Boolean =
        point.isFinite &&
            point.latitude in south..north &&
            point.longitude in west..east

    companion object {
        /**
         * The smallest rectangle containing a circle of [radiusMetres] around [center].
         *
         * The latitude span uses one degree ≈ [GeoMath.EARTH_RADIUS_METRES] × π / 180 metres; the
         * longitude span shortens with latitude, hence the cosine. Two cases are widened to the whole
         * longitude range instead of wrapping: a circle that would cross the antimeridian (whose
         * rectangle cannot be expressed as `west..east` in degrees) and a near-polar circle whose
         * longitude span approaches the globe. Widening is safe — the rectangle is only a download
         * area, and [OfflineMapArea] refuses an area whose tile count exceeds the bound.
         *
         * Returns `null` for a non-finite centre or a non-positive radius rather than inventing a box.
         */
        fun around(center: GeoPoint, radiusMetres: Int): MapBounds? {
            if (!center.isFinite || radiusMetres <= 0) return null

            val metresPerDegreeLatitude = GeoMath.EARTH_RADIUS_METRES * PI / 180.0
            val latitudeDelta = radiusMetres / metresPerDegreeLatitude
            val cosine = abs(cos(Math.toRadians(center.latitude))).coerceAtLeast(MINIMUM_COSINE)
            val longitudeDelta = radiusMetres / (metresPerDegreeLatitude * cosine)

            val north = (center.latitude + latitudeDelta).coerceAtMost(90.0)
            val south = (center.latitude - latitudeDelta).coerceAtLeast(-90.0)

            val crossesAntimeridian =
                longitudeDelta >= 180.0 ||
                    center.longitude - longitudeDelta < -180.0 ||
                    center.longitude + longitudeDelta > 180.0

            return if (crossesAntimeridian) {
                MapBounds(north = north, south = south, east = 180.0, west = -180.0)
            } else {
                MapBounds(
                    north = north,
                    south = south,
                    east = center.longitude + longitudeDelta,
                    west = center.longitude - longitudeDelta,
                )
            }
        }

        /** A near-pole cosine guard: small enough to widen the longitude span to the globe. */
        private const val MINIMUM_COSINE = 1e-6
    }
}

/**
 * One downloaded map area, as stored by the map engine.
 *
 * [styleUrl] is kept because an offline region is bound to the style it was downloaded with: the
 * engine stores the style document, its fonts, its sprites and the tiles it references under that
 * URL. Reusing the region therefore means drawing that style, not the one the current theme happens
 * to prefer.
 */
data class OfflineMapRegion(
    val id: Long,
    val label: String,
    val styleUrl: String,
    val bounds: MapBounds,
    val minZoom: Double,
    val maxZoom: Double,
    val downloadedBytes: Long,
    val isComplete: Boolean,
    val createdAtEpochMillis: Long,
) {
    /** True when this region contains [point]. */
    fun covers(point: GeoPoint?): Boolean = point != null && bounds.contains(point)
}

/**
 * Download progress reported by the map engine.
 *
 * [requiredResources] counts tiles, style documents, fonts and sprites. It is the engine's estimate
 * and may grow while the download runs, which is why a fraction is only a fraction and not a promise.
 */
data class OfflineMapProgress(
    val completedResources: Long,
    val requiredResources: Long,
    val downloadedBytes: Long,
    val isComplete: Boolean,
) {
    /** Completed share in `0..1`, or `null` while the engine has not yet counted the work. */
    val fraction: Float?
        get() = when {
            isComplete -> 1f
            requiredResources <= 0 -> null
            else -> (completedResources.toFloat() / requiredResources).coerceIn(0f, 1f)
        }

    /** [fraction] as a whole percentage, or `null` while it is unknown. */
    val percent: Int?
        get() = fraction?.let { value -> (value * 100).roundToInt().coerceIn(0, 100) }
}

/** Why an offline map download could not finish. */
enum class OfflineMapFailure {
    /** The connection failed or dropped. Nothing was kept. */
    NETWORK,

    /** The tile database could not be written — usually no free space. */
    STORAGE,

    /** The engine reported a failure this app cannot classify. */
    UNKNOWN,
}

/** One offline map download request, in the terms a map engine needs. */
data class OfflineMapRequest(
    val styleUrl: String,
    val bounds: MapBounds,
    val minZoom: Double = OfflineMapArea.MIN_ZOOM,
    val maxZoom: Double = OfflineMapArea.MAX_ZOOM,
)

/** A failure from the map engine, carrying the distinction the screen explains. */
class OfflineMapException(
    val reason: OfflineMapFailure = OfflineMapFailure.UNKNOWN,
) : Exception(reason.name)

/**
 * Persistent offline map storage.
 *
 * A real region download is not a byte cache the app manages: the engine owns a tile database,
 * writes the style, fonts, sprites and tiles it needs, and later serves them by URL without a
 * connection. This port exposes only what the screen can honestly report — which regions exist,
 * whether they cover a point, how large they are, progress, and deletion.
 *
 * Implementations must:
 *
 *  * persist regions across process death (that is the entire point);
 *  * stop and discard a partially downloaded region when the coroutine is cancelled, so a cancel
 *    never leaves storage consumed by an unusable area;
 *  * treat an unreadable region database as "no regions" rather than throwing, because a corrupt
 *    cache must not make the screen unusable.
 */
interface OfflineMapStore {

    /** Every stored region, with its current size and completeness. */
    suspend fun regions(): List<OfflineMapRegion>

    /**
     * Downloads [request] and returns the finished region.
     *
     * [onProgress] is called while the download runs. [label] is persisted with the region so a
     * later listing can describe it without a second lookup.
     */
    suspend fun download(
        request: OfflineMapRequest,
        label: String,
        onProgress: (OfflineMapProgress) -> Unit,
    ): OfflineMapRegion

    /** Deletes the region with [regionId]; a missing region is a no-op. */
    suspend fun delete(regionId: Long)
}

/**
 * The pure arithmetic of "how big would this area be".
 *
 * **A plan, not a download.** The screen shows [Plan.estimatedBytes] before anything is fetched, so a
 * user chooses to spend the data rather than discovering it afterwards.
 *
 * **A bound, not a promise.** The estimate multiplies a tile count by a typical vector-tile size. Real
 * tiles vary with what they contain, so the number is labelled an estimate in the interface and the
 * engine's own progress replaces it while downloading.
 */
object OfflineMapArea {

    /**
     * The lowest zoom downloaded. Below this a mosque search area is a handful of country-scale
     * tiles, and there is nothing useful to see.
     */
    const val MIN_ZOOM: Double = 10.0

    /**
     * The highest zoom downloaded. At 16 a street is readable, and each step above it multiplies the
     * tile count by four — a 5 km area at zoom 18 would be tens of thousands of tiles for detail this
     * screen does not need.
     */
    const val MAX_ZOOM: Double = 16.0

    /** A typical vector tile, used only for the pre-download estimate. */
    const val BYTES_PER_TILE: Long = 14_000L

    /** The style document, glyphs and sprites every region also stores. */
    const val STYLE_OVERHEAD_BYTES: Long = 250_000L

    /**
     * The most tiles this app will download in one action.
     *
     * About 56 MB at [BYTES_PER_TILE]. The default 5 km search area is far below it; the bound exists
     * so a wider configuration, a polar latitude or a future radius change cannot silently turn one
     * tap into a many-hundred-megabyte download.
     */
    const val MAX_TILES: Long = 4_000L

    /** Web Mercator is only defined inside this latitude; tiles outside it do not exist. */
    const val MERCATOR_LATITUDE_LIMIT: Double = 85.05112878

    /** A downloadable area and what it is expected to cost. */
    data class Plan(
        val bounds: MapBounds,
        val minZoom: Double,
        val maxZoom: Double,
        val tileCount: Long,
        val estimatedBytes: Long,
    ) {
        /** True when the plan is small enough to offer as one download. */
        val isWithinLimit: Boolean
            get() = tileCount in 1..OfflineMapArea.MAX_TILES
    }

    /** The plan for the circle around [center], or `null` when no honest plan exists. */
    fun plan(
        center: GeoPoint,
        radiusMetres: Int,
        minZoom: Double = MIN_ZOOM,
        maxZoom: Double = MAX_ZOOM,
    ): Plan? {
        val bounds = MapBounds.around(center, radiusMetres) ?: return null
        if (!bounds.isValid) return null
        if (minZoom > maxZoom || minZoom < 0.0 || maxZoom > 22.0) return null

        val tiles = tileCount(bounds, minZoom, maxZoom)
        return Plan(
            bounds = bounds,
            minZoom = minZoom,
            maxZoom = maxZoom,
            tileCount = tiles,
            estimatedBytes = tiles * BYTES_PER_TILE + STYLE_OVERHEAD_BYTES,
        )
    }

    /** Every tile in [bounds] across the integer zooms between [minZoom] and [maxZoom]. */
    fun tileCount(bounds: MapBounds, minZoom: Double, maxZoom: Double): Long {
        if (!bounds.isValid) return 0L
        val first = minZoom.toInt().coerceAtLeast(0)
        val last = maxZoom.toInt().coerceAtMost(22)
        if (last < first) return 0L

        var total = 0L
        for (zoom in first..last) {
            total += tilesAtZoom(bounds, zoom)
        }
        return total
    }

    private fun tilesAtZoom(bounds: MapBounds, zoom: Int): Long {
        val tilesPerSide = 1 shl zoom
        val firstColumn = longitudeToTile(bounds.west, tilesPerSide)
        val lastColumn = longitudeToTile(bounds.east, tilesPerSide)
        val firstRow = latitudeToTile(bounds.north, tilesPerSide)
        val lastRow = latitudeToTile(bounds.south, tilesPerSide)

        val columns = (lastColumn - firstColumn + 1).toLong()
        val rows = (lastRow - firstRow + 1).toLong()
        return columns * rows
    }

    private fun longitudeToTile(longitude: Double, tilesPerSide: Int): Int {
        val x = ((longitude + 180.0) / 360.0 * tilesPerSide).toInt()
        return x.coerceIn(0, tilesPerSide - 1)
    }

    private fun latitudeToTile(latitude: Double, tilesPerSide: Int): Int {
        val clamped = latitude.coerceIn(-MERCATOR_LATITUDE_LIMIT, MERCATOR_LATITUDE_LIMIT)
        val radians = Math.toRadians(clamped)
        val y = ((1.0 - kotlin.math.ln(kotlin.math.tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 *
            tilesPerSide).toInt()
        return y.coerceIn(0, tilesPerSide - 1)
    }
}
