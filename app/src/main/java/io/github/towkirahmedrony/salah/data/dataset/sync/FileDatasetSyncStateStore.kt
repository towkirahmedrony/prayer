package io.github.towkirahmedrony.salah.data.dataset.sync

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.dataset.sync.DatasetSyncState
import io.github.towkirahmedrony.salah.core.domain.dataset.sync.DatasetSyncStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The synchronisation state kept in a small file, beside the applied dataset but not inside it.
 *
 * **Why a file and not DataStore.** DataStore holds user preferences and the saved location, and the
 * backup rules capture it on purpose. This state is neither: it is a cache of "when did a check last run"
 * and "which publication did we last evaluate", reconstructible from nothing but harmless to lose, and it
 * lives under `files/datasets/` — the directory the backup rules deliberately exclude. Writing it to
 * DataStore would sweep machine state into the user's backup for no benefit.
 *
 * **The writer is atomic for the same reason the dataset record is.** A half-written state file would at
 * worst cause one redundant check, so strictly this could be a plain write; it uses the sibling-temp-then-
 * rename pattern anyway so there is exactly one write idiom in this feature, and a reader never sees a
 * truncated value that would otherwise be parsed as a real timestamp. Reads are total: any malformed or
 * missing file yields the default state rather than an exception, because a lost state file must never be
 * able to stop the app from opening.
 */
class FileDatasetSyncStateStore(
    /** The state file, e.g. `<filesDir>/datasets/bd_locations.sync`. */
    private val file: File,
    private val logger: DiagnosticsLogger,
) : DatasetSyncStateStore {

    private val mutex = Mutex()

    override suspend fun read(): DatasetSyncState = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext DatasetSyncState()
            val text = runCatching { file.readText() }.getOrElse { error ->
                logger.warn(
                    SalahDiagnosticEvents.DATASET_SYNC_FAILED,
                    mapOf("reason" to "storage_failed", "phase" to "read", "detail" to error.message),
                )
                return@withContext DatasetSyncState()
            }

            var lastAttempt: Long? = null
            var publicationId: String? = null
            var failureCode: String? = null
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val fields = line.split('\t')
                if (fields.size < 2) continue
                when (fields[0].trim()) {
                    KEY_LAST_ATTEMPT -> lastAttempt = fields[1].trim().toLongOrNull()
                    KEY_PUBLICATION -> publicationId = fields[1].trim().takeIf { it.isNotEmpty() }
                    KEY_FAILURE -> failureCode = fields[1].trim().takeIf { it.isNotEmpty() }
                }
            }
            DatasetSyncState(
                lastAttemptAtEpochMillis = lastAttempt,
                lastAttemptedPublicationId = publicationId,
                lastFailureCode = failureCode,
            )
        }
    }

    override suspend fun write(state: DatasetSyncState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val text = buildString {
                appendLine(HEADER)
                state.lastAttemptAtEpochMillis?.let { appendLine("$KEY_LAST_ATTEMPT\t$it") }
                state.lastAttemptedPublicationId?.let { appendLine("$KEY_PUBLICATION\t$it") }
                state.lastFailureCode?.let { appendLine("$KEY_FAILURE\t$it") }
            }
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temporary).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (!temporary.renameTo(file)) {
                temporary.delete()
                throw IllegalStateException("Could not replace ${file.name}.")
            }
        }
    }

    private companion object {
        const val KEY_LAST_ATTEMPT = "last_attempt_at_epoch_millis"
        const val KEY_PUBLICATION = "last_attempted_publication_id"
        const val KEY_FAILURE = "last_failure_code"
        const val HEADER =
            "# Salah dataset sync state. Machine state only; not user data, and not backed up."
    }
}
