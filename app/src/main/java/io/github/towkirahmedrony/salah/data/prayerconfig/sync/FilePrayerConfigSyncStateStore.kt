package io.github.towkirahmedrony.salah.data.prayerconfig.sync

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSyncState
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSyncStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The synchronisation state kept in a small file, beside the stored configuration but not inside it.
 *
 * **Why a file and not DataStore.** DataStore holds user preferences and the saved location, and the
 * backup rules capture it on purpose. This state is neither: it is a cache of "when did a check last run"
 * and "which revision did we last evaluate", reconstructible from nothing but harmless to lose, and it
 * lives under `filesDir` where the backup rules deliberately exclude the machine state. Mirroring
 * [io.github.towkirahmedrony.salah.data.dataset.sync.FileDatasetSyncStateStore] keeps one write idiom in
 * the codebase.
 *
 * Reads are total: any malformed or missing file yields the default state rather than an exception, because
 * a lost state file must never be able to stop the app from opening.
 */
class FilePrayerConfigSyncStateStore(
    /** The state file, e.g. `<filesDir>/prayer_config.sync`. */
    private val file: File,
    private val logger: DiagnosticsLogger,
) : PrayerConfigSyncStateStore {

    private val mutex = Mutex()

    override suspend fun read(): PrayerConfigSyncState = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext PrayerConfigSyncState()
            val text = runCatching { file.readText() }.getOrElse { error ->
                logger.warn(
                    SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_FAILED,
                    mapOf("reason" to "storage_failed", "phase" to "read", "detail" to error.message),
                )
                return@withContext PrayerConfigSyncState()
            }

            var lastAttempt: Long? = null
            var revisionId: String? = null
            var failureCode: String? = null
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val fields = line.split('\t')
                if (fields.size < 2) continue
                when (fields[0].trim()) {
                    KEY_LAST_ATTEMPT -> lastAttempt = fields[1].trim().toLongOrNull()
                    KEY_REVISION -> revisionId = fields[1].trim().takeIf { it.isNotEmpty() }
                    KEY_FAILURE -> failureCode = fields[1].trim().takeIf { it.isNotEmpty() }
                }
            }
            PrayerConfigSyncState(
                lastAttemptAtEpochMillis = lastAttempt,
                lastAttemptedRevisionId = revisionId,
                lastFailureCode = failureCode,
            )
        }
    }

    override suspend fun write(state: PrayerConfigSyncState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val text = buildString {
                appendLine(HEADER)
                state.lastAttemptAtEpochMillis?.let { appendLine("$KEY_LAST_ATTEMPT\t$it") }
                state.lastAttemptedRevisionId?.let { appendLine("$KEY_REVISION\t$it") }
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
        const val KEY_REVISION = "last_attempted_revision_id"
        const val KEY_FAILURE = "last_failure_code"
        const val HEADER =
            "# Salah prayer-config sync state. Machine state only; not user data, and not backed up."
    }
}
