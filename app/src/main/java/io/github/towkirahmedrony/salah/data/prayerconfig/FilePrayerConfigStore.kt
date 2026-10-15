package io.github.towkirahmedrony.salah.data.prayerconfig

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.ActivePrayerConfig
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.ActivePrayerConfigStore
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigActivationResult
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigProblem
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationChecksum
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationParser
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationSnapshot
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationWriter
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.SnapshotParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The local prayer-configuration store: which revision the app is actually running on, and how a validated
 * one safely replaces it.
 *
 * **The storage layout.**
 *
 * ```text
 * <root>/
 *   payload.json     the accepted snapshot, in the canonical format the parser reads
 *   active.tsv       small metadata record: revision id, revision number, checksum
 *   payload.json.tmp a sibling temporary file, used to make the swap atomic
 * ```
 *
 * **The commit point is one rename.** The payload is written and flushed to a sibling temporary file, then
 * renamed over `payload.json`. Before the rename the previous payload is in force; after it the new one is.
 * A crash at any earlier moment leaves the previous configuration intact, never a half-written one. The
 * record is rewritten afterwards and only ever *describes* the payload: if it disagrees with the payload's
 * own checksum on the next read, the payload is treated as unreadable and the bundled defaults apply.
 *
 * **Reading never trusts the record.** The stored payload's checksum is recomputed from the bytes on disk
 * and compared with the record; the payload is re-parsed with the same parser a download uses; and its
 * revision id and number are cross-checked against the record. A record naming a missing, truncated or
 * corrupt payload is therefore a *detected* condition, not a corrupted configuration — the app simply has
 * no remote configuration and falls back to bundled defaults and the user's explicit choices.
 */
class FilePrayerConfigStore(
    /** The configuration root. In the app this is a directory under `filesDir`. */
    private val root: File,
    private val logger: DiagnosticsLogger,
) : ActivePrayerConfigStore {

    private val mutex = Mutex()

    private var cached: ActivePrayerConfig? = null
    private var cacheReady = false

    override suspend fun active(): ActivePrayerConfig? = mutex.withLock { resolveLocked() }

    override suspend fun activate(snapshot: PrayerConfigurationSnapshot): PrayerConfigActivationResult =
        mutex.withLock {
            val existing = resolveLocked()
            val json = PrayerConfigurationWriter.toJson(snapshot)
            val checksum = PrayerConfigurationChecksum.of(json)

            if (existing != null) {
                // Same revision, same contents: answered before any write so an unchanged revision costs
                // nothing.
                if (existing.revisionId == snapshot.revisionId) {
                    return@withLock if (existing.checksum.equals(checksum, ignoreCase = true)) {
                        PrayerConfigActivationResult.AlreadyActive(existing)
                    } else {
                        PrayerConfigActivationResult.Rejected(
                            listOf(
                                PrayerConfigProblem.RevisionReusedWithDifferentContent(
                                    revisionId = snapshot.revisionId,
                                    activeChecksum = existing.checksum,
                                    candidateChecksum = checksum,
                                    detail = "Revision '${snapshot.revisionId}' is already stored with " +
                                        "different contents, so it will not be replaced.",
                                ),
                            ),
                        )
                    }
                }

                // A revision that is not newer must not silently replace the accepted one.
                if (snapshot.revisionNumber <= existing.revisionNumber) {
                    return@withLock PrayerConfigActivationResult.Rejected(
                        listOf(
                            PrayerConfigProblem.StaleRevision(
                                activeRevisionNumber = existing.revisionNumber,
                                candidateRevisionNumber = snapshot.revisionNumber,
                                detail = "Revision number ${snapshot.revisionNumber} is not newer than the " +
                                    "stored revision number ${existing.revisionNumber}.",
                            ),
                        ),
                    )
                }
            }

            try {
                withContext(Dispatchers.IO) { write(snapshot, json, checksum) }
            } catch (error: Exception) {
                logger.error(
                    SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_FAILED,
                    error,
                    mapOf("stage" to "write", "revisionId" to snapshot.revisionId),
                )
                return@withLock PrayerConfigActivationResult.Failed(
                    "The configuration could not be written: ${error.message ?: error::class.simpleName}",
                )
            }

            val active = ActivePrayerConfig(snapshot = snapshot, checksum = checksum)
            cached = active
            cacheReady = true
            logger.info(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_ACTIVATED,
                mapOf(
                    "revisionId" to snapshot.revisionId,
                    "revisionNumber" to snapshot.revisionNumber,
                    "overrides" to snapshot.areaOverrides.size,
                ),
            )
            return@withLock PrayerConfigActivationResult.Activated(active)
        }

    private suspend fun resolveLocked(): ActivePrayerConfig? {
        if (cacheReady) return cached
        val value = withContext(Dispatchers.IO) { read() }
        cached = value
        cacheReady = true
        return value
    }

    /** Reads and verifies the stored configuration, or `null` when none can be trusted. */
    private fun read(): ActivePrayerConfig? {
        val payloadFile = File(root, PAYLOAD_FILE)
        if (!payloadFile.isFile) return null

        val text = runCatching { payloadFile.readText() }.getOrElse { error ->
            logger.warn(
                SalahDiagnosticEvents.PRAYER_CONFIG_STORED_UNAVAILABLE,
                mapOf("reason" to "unreadable", "detail" to error.message),
            )
            return null
        }
        val record = readRecord() ?: return null

        val checksum = PrayerConfigurationChecksum.of(text)
        if (!checksum.equals(record.checksum, ignoreCase = true)) {
            logger.warn(
                SalahDiagnosticEvents.PRAYER_CONFIG_STORED_UNAVAILABLE,
                mapOf("reason" to "checksum_mismatch"),
            )
            return null
        }

        val snapshot = when (val parsed = PrayerConfigurationParser.parseSnapshot(text)) {
            is SnapshotParseResult.Parsed -> parsed.snapshot
            is SnapshotParseResult.Invalid -> {
                logger.warn(
                    SalahDiagnosticEvents.PRAYER_CONFIG_STORED_UNAVAILABLE,
                    mapOf("reason" to "invalid", "codes" to parsed.problems.map { it.code }.distinct()),
                )
                return null
            }
            is SnapshotParseResult.Malformed -> {
                logger.warn(
                    SalahDiagnosticEvents.PRAYER_CONFIG_STORED_UNAVAILABLE,
                    mapOf("reason" to "malformed"),
                )
                return null
            }
        }

        if (snapshot.revisionId != record.revisionId || snapshot.revisionNumber != record.revisionNumber) {
            logger.warn(
                SalahDiagnosticEvents.PRAYER_CONFIG_STORED_UNAVAILABLE,
                mapOf("reason" to "record_mismatch"),
            )
            return null
        }

        return ActivePrayerConfig(snapshot = snapshot, checksum = checksum)
    }

    // Files -------------------------------------------------------------------------------------

    private fun write(snapshot: PrayerConfigurationSnapshot, json: String, checksum: String) {
        root.mkdirs()
        writeReplacing(File(root, PAYLOAD_FILE), json)
        writeReplacing(
            File(root, RECORD_FILE),
            buildString {
                appendLine(RECORD_HEADER)
                appendLine("$KEY_REVISION_ID\t${snapshot.revisionId}")
                appendLine("$KEY_REVISION_NUMBER\t${snapshot.revisionNumber}")
                appendLine("$KEY_CHECKSUM\t$checksum")
            },
        )
    }

    private fun readRecord(): Record? {
        val file = File(root, RECORD_FILE)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null

        var revisionId: String? = null
        var revisionNumber: Long? = null
        var checksum: String? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = line.split('\t')
            if (fields.size < 2) continue
            when (fields[0].trim()) {
                KEY_REVISION_ID -> revisionId = fields[1].trim().takeIf { it.isNotEmpty() }
                KEY_REVISION_NUMBER -> revisionNumber = fields[1].trim().toLongOrNull()
                KEY_CHECKSUM -> checksum = fields[1].trim().takeIf { it.isNotEmpty() }
            }
        }
        val id = revisionId ?: return null
        val number = revisionNumber ?: return null
        val sum = checksum ?: return null
        return Record(id, number, sum)
    }

    /** Replaces [target] in one atomic step: write a sibling temporary file, flush it, then rename it over. */
    private fun writeReplacing(target: File, content: String) {
        val temporary = File(target.parentFile, "${target.name}$TEMPORARY_SUFFIX")
        FileOutputStream(temporary).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("Could not replace ${target.name}.")
        }
    }

    private data class Record(val revisionId: String, val revisionNumber: Long, val checksum: String)

    private companion object {
        const val PAYLOAD_FILE = "payload.json"
        const val RECORD_FILE = "active.tsv"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val KEY_REVISION_ID = "revision_id"
        const val KEY_REVISION_NUMBER = "revision_number"
        const val KEY_CHECKSUM = "checksum"
        const val RECORD_HEADER =
            "# Salah active prayer configuration. The payload is re-parsed and re-checksummed on read."
    }
}
