package io.github.towkirahmedrony.salah.data.mosque

import android.content.Context
import io.github.towkirahmedrony.salah.core.domain.mosque.MapBounds
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapException
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapFailure
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapProgress
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapRegion
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapRequest
import io.github.towkirahmedrony.salah.core.domain.mosque.OfflineMapStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline map regions backed by MapLibre Native's own tile database.
 *
 * **This is genuine offline data, not a screenshot.** `createOfflineRegion` asks the engine to fetch
 * and store the region's style document, fonts, sprites and every vector tile between the requested
 * zooms. The engine writes them to a persistent database under the app's files directory, and later
 * serves those URLs from that database with no connection. Nothing here paints part of a map itself;
 * the map still renders through the same `MapView`.
 *
 * **Licensing.** The tiles come from Geoapify, whose open tile policy permits caching, storing and
 * redistributing its generated tiles. The attribution map rule still applies and is drawn over the
 * map: © Geoapify, © OpenStreetMap contributors, © OpenMapTiles. Nothing is ever fetched from
 * `tile.openstreetmap.org`.
 *
 * **Regions are bound to a style URL.** A downloaded region belongs to the style it was created with
 * (light or dark). [OfflineMapRegion.styleUrl] is kept so the screen can draw that stored style when
 * offline instead of requesting one that was never downloaded.
 *
 * **Failure is not a thrown platform exception.** Engine callbacks are translated into
 * [OfflineMapException] with a [OfflineMapFailure], so the view model can distinguish a dropped
 * connection from a full disk. A cancelled download stops and deletes its partial region, so a
 * cancellation cannot leave storage consumed by an unusable area.
 *
 * **A corrupt region database is "no regions".** Listing is a read of a cache the app can rebuild;
 * returning an empty list keeps the mosque list and the rest of the screen usable instead of failing
 * the screen on a database the user cannot repair.
 */
class MapLibreOfflineMapStore(
    private val context: Context,
) : OfflineMapStore {

    /** Serialises region mutations: the engine's database is not safe for concurrent region writes. */
    private val mutex = Mutex()

    override suspend fun regions(): List<OfflineMapRegion> = mutex.withLock {
        withContext(Dispatchers.Main) {
            val manager = offlineManager() ?: return@withContext emptyList()
            listRegions(manager).mapNotNull { region -> region.toSummaryOrNull() }
        }
    }

    override suspend fun download(
        request: OfflineMapRequest,
        label: String,
        onProgress: (OfflineMapProgress) -> Unit,
    ): OfflineMapRegion {
        require(request.styleUrl.isNotBlank()) { "an offline region needs a style URL" }
        require(request.bounds.isValid) { "an offline region needs valid bounds" }
        require(request.minZoom <= request.maxZoom) { "the zoom range is inverted" }

        return mutex.withLock {
            withContext(Dispatchers.Main) {
                val manager = offlineManager()
                    ?: throw OfflineMapException(OfflineMapFailure.STORAGE)
                val region = createRegion(manager, request, label)

                try {
                    val status = awaitDownload(region, onProgress)
                    val definition = region.definition as OfflineTilePyramidRegionDefinition
                    val styleUrl = definition.styleURL
                        ?: throw OfflineMapException(OfflineMapFailure.STORAGE)
                    val bounds = definition.toMapBounds()
                        ?: throw OfflineMapException(OfflineMapFailure.STORAGE)
                    OfflineMapRegion(
                        id = region.id,
                        label = label,
                        styleUrl = styleUrl,
                        bounds = bounds,
                        minZoom = definition.minZoom,
                        maxZoom = definition.maxZoom,
                        downloadedBytes = status.completedResourceSize,
                        isComplete = status.isComplete,
                        createdAtEpochMillis = System.currentTimeMillis(),
                    )
                } catch (cancellation: CancellationException) {
                    // A partial region would otherwise stay on disk, counted as an area the user
                    // cannot use. Stopping and deleting it is the honest outcome of a cancel.
                    withContext(NonCancellable) { stopAndDelete(region) }
                    throw cancellation
                } catch (error: OfflineMapException) {
                    withContext(NonCancellable) { stopAndDelete(region) }
                    throw error
                } catch (error: Exception) {
                    withContext(NonCancellable) { stopAndDelete(region) }
                    throw OfflineMapException(OfflineMapFailure.UNKNOWN)
                }
            }
        }
    }

    override suspend fun delete(regionId: Long) {
        mutex.withLock {
            withContext(Dispatchers.Main) {
                val manager = offlineManager() ?: return@withContext
                val region = listRegions(manager).firstOrNull { it.id == regionId }
                    ?: return@withContext
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                deleteRegion(region)
            }
        }
    }

    /**
     * The engine's singleton, initialized on first use.
     *
     * `MapLibre.getInstance` must run before `OfflineManager.getInstance`; calling it here means the
     * store works even if the map composable has never been opened in this process. A failure is
     * reported as storage unavailable rather than crashing the screen.
     */
    private fun offlineManager(): OfflineManager? = try {
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context)
    } catch (_: Exception) {
        null
    }

    private suspend fun listRegions(manager: OfflineManager): List<OfflineRegion> =
        suspendCancellableCoroutine { continuation ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    if (continuation.isActive) {
                        continuation.resume(offlineRegions?.toList().orEmpty())
                    }
                }

                override fun onError(error: String) {
                    // A region database that cannot be read is treated as empty; see the class note.
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            })
        }

    private suspend fun createRegion(
        manager: OfflineManager,
        request: OfflineMapRequest,
        label: String,
    ): OfflineRegion = suspendCancellableCoroutine<OfflineRegion> { continuation ->
        val definition = OfflineTilePyramidRegionDefinition(
            request.styleUrl,
            LatLngBounds.Builder()
                .include(LatLng(request.bounds.south, request.bounds.west))
                .include(LatLng(request.bounds.north, request.bounds.east))
                .build(),
            request.minZoom,
            request.maxZoom,
            context.resources.displayMetrics.density,
        )
        val metadata = JSONObject()
            .put(FIELD_LABEL, label)
            .put(FIELD_CREATED_AT, System.currentTimeMillis())
            .toString()
            .toByteArray(Charsets.UTF_8)

        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    if (continuation.isActive) {
                        continuation.resume(region)
                    } else {
                        // The collector went away while the engine was creating the region.
                        region.delete(NoOpDeleteCallback)
                    }
                }

                override fun onError(error: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(OfflineMapException(classify(error)))
                    }
                }
            },
        )
    }

    /**
     * Starts the download and suspends until the engine says the region is complete.
     *
     * Progress is forwarded as it arrives. A resume is guarded by a flag because the engine may report
     * several final statuses, and resuming a continuation twice would throw inside a platform
     * callback where nothing could handle it.
     */
    private suspend fun awaitDownload(
        region: OfflineRegion,
        onProgress: (OfflineMapProgress) -> Unit,
    ): OfflineRegionStatus = suspendCancellableCoroutine<OfflineRegionStatus> { continuation ->
        var finished = false

        val observer = object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                onProgress(
                    OfflineMapProgress(
                        completedResources = status.completedResourceCount,
                        requiredResources = status.requiredResourceCount,
                        downloadedBytes = status.completedResourceSize,
                        isComplete = status.isComplete,
                    ),
                )
                if (status.isComplete && !finished && continuation.isActive) {
                    finished = true
                    continuation.resume(status)
                }
            }

            override fun onError(error: OfflineRegionError) {
                if (finished || !continuation.isActive) return
                finished = true
                continuation.resumeWithException(OfflineMapException(classify(error.reason)))
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                if (finished || !continuation.isActive) return
                finished = true
                continuation.resumeWithException(OfflineMapException(OfflineMapFailure.STORAGE))
            }
        }

        region.setObserver(observer)
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private suspend fun statusOf(region: OfflineRegion): OfflineRegionStatus? =
        suspendCancellableCoroutine<OfflineRegionStatus?> { continuation ->
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (continuation.isActive) continuation.resume(status)
                }

                override fun onError(error: String?) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }

    private suspend fun deleteRegion(region: OfflineRegion) {
        suspendCancellableCoroutine { continuation ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(error: String) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            })
        }
    }

    private suspend fun stopAndDelete(region: OfflineRegion) {
        try {
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        } catch (_: Exception) {
            // The region may already be gone; deletion below is the part that matters.
        }
        try {
            deleteRegion(region)
        } catch (_: Exception) {
            // Best effort: a region that cannot be deleted is still better than a crash on cancel.
        }
    }

    private suspend fun OfflineRegion.toSummaryOrNull(): OfflineMapRegion? {
        val status = statusOf(this) ?: return null
        val definition = definition as? OfflineTilePyramidRegionDefinition ?: return null
        val metadata = metadataJson()
        val styleUrl = definition.styleURL ?: return null
        val bounds = definition.toMapBounds() ?: return null
        return OfflineMapRegion(
            id = id,
            label = metadata.optString(FIELD_LABEL),
            styleUrl = styleUrl,
            bounds = bounds,
            minZoom = definition.minZoom,
            maxZoom = definition.maxZoom,
            downloadedBytes = status.completedResourceSize,
            isComplete = status.isComplete,
            createdAtEpochMillis = metadata.optLong(FIELD_CREATED_AT, 0L),
        )
    }

    private fun OfflineTilePyramidRegionDefinition.toMapBounds(): MapBounds? {
        val box = bounds ?: return null
        return MapBounds(
            north = box.latNorth,
            south = box.latSouth,
            east = box.lonEast,
            west = box.lonWest,
        )
    }

    /** Metadata written by this app, or an empty object for a region it did not write. */
    private fun OfflineRegion.metadataJson(): JSONObject =
        this.metadata?.toString(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
            ?.let { text -> runCatching { JSONObject(text) }.getOrNull() }
            ?: JSONObject()

    private fun classify(reason: String?): OfflineMapFailure = when (reason) {
        OfflineRegionError.REASON_CONNECTION -> OfflineMapFailure.NETWORK
        OfflineRegionError.REASON_SERVER -> OfflineMapFailure.NETWORK
        OfflineRegionError.REASON_NOT_FOUND -> OfflineMapFailure.STORAGE
        else -> OfflineMapFailure.UNKNOWN
    }

    private object NoOpDeleteCallback : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = Unit
        override fun onError(error: String) = Unit
    }

    private companion object {
        const val FIELD_LABEL = "label"
        const val FIELD_CREATED_AT = "createdAtEpochMillis"
    }
}
