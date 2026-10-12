package io.github.towkirahmedrony.salah.data.location

import android.content.Context
import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads the bundled Bangladesh geography out of the APK's assets.
 *
 * **Why assets and not `res/raw` or a generated Kotlin file.** These are data files whose format is
 * documented in `tools/generate_bd_location_dataset.py`, not UI resources: nothing should be able to
 * address a division by a resource id, the content is versioned by the generator, and the parser
 * already reports malformed lines. Assets are also the one packaging path where a 45 KB text file is
 * guaranteed to arrive byte-identical.
 *
 * **Read once.** The two files are about 83 KB in total and parsing takes a few milliseconds, but the
 * result is cached: first launch reads it during the setup gate, and every later screen (the picker,
 * the location screen, an area resolution for a GPS fix) would otherwise re-read and re-parse it.
 *
 * **Failure is a value.** A missing or unreadable asset returns `null` after logging, and a file that
 * parses into something unusable is returned as it is — `BangladeshLocationDataset.isUsable` and
 * `LocationSetup` then decide what to tell the user. Nothing throws into the UI, and nothing falls back
 * to a hand-written list of places.
 */
class AssetBangladeshAreaCatalog(
    private val context: Context,
    private val logger: DiagnosticsLogger,
) : BangladeshAreaCatalog {

    private val mutex = Mutex()
    private var cached: BangladeshLocationDataset? = null
    private var cacheInitialised = false

    override suspend fun dataset(): BangladeshLocationDataset? = mutex.withLock {
        if (cacheInitialised) return cached
        val loaded = withContext(Dispatchers.IO) { read() }
        cached = loaded
        cacheInitialised = true
        loaded
    }

    private fun read(): BangladeshLocationDataset? {
        val areas = readAsset(AREAS_ASSET)
        val boundary = readAsset(BOUNDARY_ASSET)
        if (areas == null || boundary == null) {
            logger.error(
                SalahDiagnosticEvents.LOCATION_DATASET_UNAVAILABLE,
                null,
                mapOf("areas" to (areas != null), "boundary" to (boundary != null)),
            )
            return null
        }

        val dataset = BangladeshLocationDataset.parse(
            version = VERSION,
            areasText = areas,
            boundaryText = boundary,
        )
        logger.info(
            SalahDiagnosticEvents.LOCATION_DATASET_LOADED,
            mapOf(
                "areas" to dataset.areas.size,
                "divisions" to dataset.divisions.size,
                "districts" to dataset.districts.size,
                "upazilas" to dataset.upazilas.size,
                "rings" to dataset.boundaryRings.size,
                "rejected" to dataset.rejectedLines.size,
                "usable" to dataset.isUsable,
            ),
        )
        return dataset
    }

    private fun readAsset(name: String): String? = try {
        context.assets.open(name).bufferedReader().use { it.readText() }
    } catch (error: Exception) {
        logger.error(
            SalahDiagnosticEvents.LOCATION_DATASET_UNAVAILABLE,
            error,
            mapOf("asset" to name),
        )
        null
    }

    companion object {
        /** Directory inside `app/src/main/assets`. */
        const val ASSET_DIRECTORY: String = "location"

        /** The generated hierarchy file. */
        const val AREAS_ASSET: String = "$ASSET_DIRECTORY/bd_areas.tsv"

        /** The generated national boundary file. */
        const val BOUNDARY_ASSET: String = "$ASSET_DIRECTORY/bd_boundary.tsv"

        /**
         * The dataset version reported to the app and stored with a selected area.
         *
         * Not read from the file header: a value the app reads out of its own data could differ between
         * devices if a file were edited, and the point of the version is that it identifies the build's
         * dataset. It names the same release as the generator's `COD_VERSION_NOTE`
         * (`COD-AB BGD v03 (valid_on 2023-05-21)`), written for display rather than for a file header.
         */
        const val VERSION: String = "BGD-COD-AB v03 (2023-05-21)"
    }
}
