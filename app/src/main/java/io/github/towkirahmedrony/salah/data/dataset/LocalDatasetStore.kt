package io.github.towkirahmedrony.salah.data.dataset

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.dataset.ActiveDataset
import io.github.towkirahmedrony.salah.core.domain.dataset.ActiveDatasetStore
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetActivationResult
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetPayload
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetSource
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetValidationContext
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetValidator
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetVersion
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.location.DatasetIdentity
import io.github.towkirahmedrony.salah.core.domain.repository.BangladeshAreaCatalog
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Clock

/**
 * Salah's local dataset foundation: which dataset the app is actually running on, and how a validated
 * one safely replaces it.
 *
 * **The model.** `bundled → active`. The dataset packaged in the APK's assets is always present and is
 * the recovery floor; an *applied* dataset is one that passed validation and now sits in the
 * application's own storage. [dataset] returns whichever is in force, so every existing caller —
 * the setup gate, the picker, the area resolver, the search — reads the active dataset without
 * knowing that the concept exists.
 *
 * **The storage layout, and why.** Versioned payload directories plus one small metadata record:
 *
 * ```text
 * <root>/
 *   active.tsv                     the metadata record: which payload is active, and the one before it
 *   payloads/
 *     <checksum>/                  one directory per applied payload, named by its own checksum
 *       dataset.version            the payload describes itself
 *       bd_areas.tsv
 *       bd_boundary.tsv
 *       bd_localities.tsv
 *     .staging-<n>/                an in-flight write; never referenced by the record
 * ```
 *
 * The payload files are the same three tab-separated texts the bundled assets hold, parsed by the same
 * parser. A payload directory is named by the checksum of its own contents, so the directory name is a
 * verifiable claim rather than a label, and the metadata record cannot point at the wrong payload
 * without the mismatch being caught on read.
 *
 * **The activation strategy, and the one operation it depends on.** The task here is not to make a
 * multi-step write atomic — that is impossible — but to arrange the steps so that **only one of them
 * needs to be**, and so that every interruption before it leaves the previous dataset intact:
 *
 *  1. write the payload into a fresh `.staging-<n>` directory and flush it to storage;
 *  2. rename that directory to `payloads/<checksum>` — a single directory rename. Until step 3 the
 *     directory is unreferenced, so publishing it early is harmless;
 *  3. rewrite `active.tsv` by writing a temporary file and renaming it over the old one. **This single
 *     rename is the commit point.** Before it the old record is in force; after it the new one is.
 *     There is no third state, because a rename either happened or it did not.
 *  4. delete payload directories that the new record does not name. Purely housekeeping — a crash here
 *     leaves unreferenced directories, not a broken dataset.
 *
 * Step 1 is the only place a partial dataset can exist, and it is never referenced. That is what makes
 * process death survivable rather than merely unlikely: the record is the only pointer, and the pointer
 * is only ever moved in one atomic step, after the thing it points at is complete.
 *
 * **fsync, and what is actually being defended against.** Payload and record writes are flushed before
 * their rename. This defends against process death without qualification; against power loss it is
 * best-effort, because a directory rename is only durable once the *directory* is synced and not every
 * platform permits opening one. The commit point is the rename, not the flush — the flush narrows the
 * window, it does not define the guarantee. No test in this repository claims to prove power-loss
 * durability.
 *
 * **Recovery.** Reading never trusts the record. Each named payload is re-read, its checksum recomputed
 * from the bytes on disk and compared to the directory name, its version cross-checked, and the result
 * parsed and checked for structural defects. A record naming a missing, truncated or corrupt payload is
 * therefore a *detected* condition, not a corrupted dataset: [`loadApplied`] reports it, the previous
 * payload is tried next, and the bundled assets are the final fallback. The app cannot be left without a
 * dataset by a bad update.
 */
class LocalDatasetStore(
    /** The dataset root. In the app this is a directory under `filesDir`. */
    private val root: File,
    /** The bundled assets, used as the recovery floor and as the first-launch dataset. */
    private val bundled: BangladeshAreaCatalog,
    private val logger: DiagnosticsLogger,
    private val clock: Clock,
) : BangladeshAreaCatalog, ActiveDatasetStore {

    private val mutex = Mutex()

    private var cached: Resolved? = null
    private var cacheReady = false

    override suspend fun dataset(): BangladeshLocationDataset? = mutex.withLock { resolveLocked() }?.dataset

    /**
     * Which dataset is in force, and where it came from — or `null` when no dataset can be produced at
     * all, which means the bundled assets could not be read either.
     *
     * Resolved rather than remembered: this describes what is genuinely being read.
     */
    override suspend fun activeDataset(): ActiveDataset? = mutex.withLock { resolveLocked() }?.active

    /**
     * Offers [payload] as the dataset to activate.
     *
     * The candidate is validated against the dataset currently in force and against [storedArea], the
     * area of the user's saved location. **Nothing is written unless every check passes**, and in
     * particular the user's stored location is never touched by this class — it is an input to the
     * decision and never an output, so a rejected candidate cannot clear it.
     *
     * @param storedArea the area of the saved location, or `null` when the user has not chosen one. A
     *   candidate that does not contain it is rejected rather than activated, because the alternative
     *   is a user who opens the app to a setup screen they had already completed.
     * @param expectedChecksum the checksum published for this payload, when the caller has one.
     */
    override suspend fun activate(
        payload: DatasetPayload,
        storedArea: BangladeshAreaRef? = null,
        expectedChecksum: String? = null,
    ): DatasetActivationResult = mutex.withLock {
        val current = resolveLocked()
        val applied = current?.active?.takeIf { it.source == DatasetSource.APPLIED }

        // Unchanged: the same version, with the same contents. Answering before validation means an
        // unchanged dataset costs no parsing and no writing at all.
        if (applied != null && applied.checksum != null &&
            payload.version.isSameAs(applied.version) &&
            payload.checksum().equals(applied.checksum, ignoreCase = true)
        ) {
            return@withLock DatasetActivationResult.AlreadyActive(applied)
        }

        val problems = DatasetValidator.validate(
            payload = payload,
            context = DatasetValidationContext(
                activeIdentity = current?.dataset?.let { DatasetIdentity.of(it) },
                activeVersion = applied?.version,
                activeChecksum = applied?.checksum,
                storedArea = storedArea,
                expectedChecksum = expectedChecksum,
            ),
        )

        if (problems.isNotEmpty()) {
            logger.warn(
                SalahDiagnosticEvents.DATASET_ACTIVATION_REJECTED,
                mapOf(
                    "version" to payload.version.value,
                    "problems" to problems.size,
                    "codes" to problems.map { it.code }.distinct().sorted(),
                ),
            )
            return@withLock DatasetActivationResult.Rejected(problems)
        }

        withContext(Dispatchers.IO) { commitLocked(payload) }
    }

    // Resolution ---------------------------------------------------------------------------------

    /** Assumes [mutex] is held. */
    private suspend fun resolveLocked(): Resolved? {
        cached?.let { return it }
        if (cacheReady) return null

        val value = withContext(Dispatchers.IO) {
            discardStaging()
            readApplied() ?: readBundled()
        }
        cached = value
        cacheReady = true
        return value
    }

    /**
     * The applied payload in force, or `null` when there is none that can be read.
     *
     * The active slot is tried first, then the previous one. Falling through to the previous dataset
     * is the whole reason the record keeps a second slot: a payload that was valid when it was
     * activated can still be unreadable now — a partial restore, a cleared directory, storage damage —
     * and the useful answer in that case is the dataset that was in force before it, not a setup screen.
     */
    private fun readApplied(): Resolved? {
        val record = readRecord()

        val activeSlot = record.active
        if (activeSlot != null) {
            val dataset = loadApplied(activeSlot)
            if (dataset != null) {
                return Resolved(
                    dataset = dataset,
                    active = ActiveDataset(
                        version = activeSlot.version,
                        source = DatasetSource.APPLIED,
                        checksum = activeSlot.key,
                    ),
                )
            }
            logger.warn(
                SalahDiagnosticEvents.DATASET_APPLIED_UNAVAILABLE,
                mapOf("slot" to SLOT_ACTIVE, "version" to activeSlot.version.value),
            )
        }

        val previousSlot = record.previous
        if (previousSlot != null) {
            val dataset = loadApplied(previousSlot)
            if (dataset != null) {
                logger.warn(
                    SalahDiagnosticEvents.DATASET_APPLIED_UNAVAILABLE,
                    mapOf(
                        "slot" to SLOT_ACTIVE,
                        "recoveredFrom" to SLOT_PREVIOUS,
                        "version" to previousSlot.version.value,
                    ),
                )
                return Resolved(
                    dataset = dataset,
                    active = ActiveDataset(
                        version = previousSlot.version,
                        source = DatasetSource.APPLIED,
                        checksum = previousSlot.key,
                    ),
                )
            }
            logger.warn(
                SalahDiagnosticEvents.DATASET_APPLIED_UNAVAILABLE,
                mapOf("slot" to SLOT_PREVIOUS, "version" to previousSlot.version.value),
            )
        }

        return null
    }

    /**
     * Reads the payload [slot] names, verifying it rather than trusting it.
     *
     * Every check here is a *detection*: a payload that has been truncated, edited, mislabelled or only
     * partly written is reported and skipped so the caller can fall back, rather than being loaded and
     * failing later somewhere that has no fallback left.
     */
    private fun loadApplied(slot: Slot): BangladeshLocationDataset? {
        val directory = payloadDirectory(slot.key)
        if (!directory.isDirectory) return null

        val parts = DatasetPayload.PART_NAMES.associateWith { name ->
            val file = File(directory, name)
            // The locality layer is optional, exactly as it is in the bundled assets: an absent one
            // degrades a display name, it does not make a dataset unusable.
            if (name == DatasetPayload.LOCALITIES_PART && !file.isFile) "" else {
                runCatching { file.readText() }.getOrNull() ?: return null
            }
        }

        val payload = DatasetPayload.ofParts(parts) ?: return null

        // The directory is named by the checksum of what it contains, so this compares the payload's
        // own integrity value against the name the record pointed at. A mismatch means the record and
        // the bytes disagree, and the bytes are what actually get loaded — so neither is trusted.
        if (!payload.checksum().equals(slot.key, ignoreCase = true)) return null

        // The version in the metadata must be the version the payload carries. Without this a record
        // could name a version the payload does not have, and the app would report a dataset it is not
        // running.
        if (!payload.version.isSameAs(slot.version)) return null

        val dataset = payload.toDataset()
        if (!dataset.isUsable) return null
        if (DatasetIdentity.of(dataset).defects().isNotEmpty()) return null

        return dataset
    }

    /** The bundled dataset, which is always the last resort. */
    private suspend fun readBundled(): Resolved? {
        val dataset = bundled.dataset() ?: return null
        return Resolved(
            dataset = dataset,
            active = ActiveDataset(
                version = DatasetVersion.of(dataset.version),
                source = DatasetSource.BUNDLED,
                checksum = null,
            ),
        )
    }

    // Activation ---------------------------------------------------------------------------------

    /** Assumes [mutex] is held and that the payload has already passed validation. */
    private fun commitLocked(payload: DatasetPayload): DatasetActivationResult {
        val key = payload.checksum()
        val previousRecord = readRecord()
        val target = payloadDirectory(key)

        val staging = File(payloadsDirectory(), "$STAGING_PREFIX${clock.millis()}-${key.take(8)}")

        // 1. Write the payload somewhere nothing references. A failure here — or process death — leaves
        //    the active dataset exactly as it was.
        try {
            if (!staging.isDirectory && !staging.mkdirs()) {
                return DatasetActivationResult.Failed("Could not create the staging directory.")
            }
            for (part in payload.parts) {
                writeFlushed(File(staging, part.name), part.content)
            }
            forceDirectory(staging)
        } catch (error: Exception) {
            staging.deleteRecursively()
            logger.error(
                SalahDiagnosticEvents.DATASET_APPLIED_UNAVAILABLE,
                error,
                mapOf("stage" to "write", "version" to payload.version.value),
            )
            return DatasetActivationResult.Failed(
                "The candidate payload could not be written: ${error.message ?: error::class.simpleName}",
            )
        }

        // 2. Publish the payload directory. Still unreferenced, so this cannot break anything.
        if (!target.isDirectory) {
            if (!staging.renameTo(target)) {
                staging.deleteRecursively()
                return DatasetActivationResult.Failed("The candidate payload could not be published.")
            }
        } else {
            // The same contents are already published under this checksum; the staged copy is redundant.
            staging.deleteRecursively()
        }

        // 3. The commit point: one atomic rename of the metadata record.
        val activatedAt = clock.millis()
        val record = DatasetRecord(
            active = Slot(key = key, version = payload.version, appliedAtEpochMillis = activatedAt),
            previous = previousRecord.active ?: previousRecord.previous,
        )
        try {
            writeRecord(record)
        } catch (error: Exception) {
            logger.error(
                SalahDiagnosticEvents.DATASET_APPLIED_UNAVAILABLE,
                error,
                mapOf("stage" to "record", "version" to payload.version.value),
            )
            return DatasetActivationResult.Failed(
                "The dataset record could not be updated: ${error.message ?: error::class.simpleName}",
            )
        }

        // 4. Housekeeping. Losing this work costs disk space and nothing else.
        prunePayloads(keep = setOfNotNull(record.active?.key, record.previous?.key))

        val dataset = payload.toDataset()
        val activated = ActiveDataset(
            version = payload.version,
            source = DatasetSource.APPLIED,
            checksum = key,
        )
        cached = Resolved(dataset = dataset, active = activated)
        cacheReady = true

        logger.info(
            SalahDiagnosticEvents.DATASET_ACTIVATED,
            mapOf(
                "version" to payload.version.value,
                "areas" to dataset.areas.size,
                "localities" to dataset.localities.size,
            ),
        )

        return DatasetActivationResult.Activated(activated)
    }

    // Files --------------------------------------------------------------------------------------

    private fun payloadsDirectory(): File = File(root, PAYLOADS_DIRECTORY)

    private fun payloadDirectory(key: String): File = File(payloadsDirectory(), key)

    private fun recordFile(): File = File(root, RECORD_FILE)

    private fun readRecord(): DatasetRecord {
        val file = recordFile()
        if (!file.isFile) return DatasetRecord(active = null, previous = null)
        val text = runCatching { file.readText() }.getOrNull()
            ?: return DatasetRecord(active = null, previous = null)

        var active: Slot? = null
        var previous: Slot? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = line.split('\t')
            if (fields.size < RECORD_COLUMNS) continue
            val version = DatasetVersion.of(fields[2])
            val key = fields[1].trim()
            if (key.isEmpty() || version.isBlank) continue
            val slot = Slot(key = key, version = version, appliedAtEpochMillis = fields[3].trim().toLongOrNull())
            when (fields[0].trim().lowercase()) {
                SLOT_ACTIVE -> active = slot
                SLOT_PREVIOUS -> previous = slot
            }
        }
        return DatasetRecord(active = active, previous = previous)
    }

    private fun writeRecord(record: DatasetRecord) {
        val text = buildString {
            appendLine(RECORD_HEADER)
            record.active?.let { append(slotLine(SLOT_ACTIVE, it)) }
            record.previous?.let { append(slotLine(SLOT_PREVIOUS, it)) }
        }
        root.mkdirs()
        writeReplacing(recordFile(), text)
    }

    private fun slotLine(slot: String, value: Slot): String =
        listOf(slot, value.key, value.version.value, value.appliedAtEpochMillis?.toString().orEmpty())
            .joinToString("\t", postfix = "\n")

    /** Removes staging directories. They are never referenced, so this cannot remove live data. */
    private fun discardStaging() {
        val entries = payloadsDirectory().listFiles() ?: return
        for (entry in entries) {
            if (entry.isDirectory && entry.name.startsWith(STAGING_PREFIX)) {
                runCatching { entry.deleteRecursively() }
            }
        }
    }

    /** Removes published payloads the record no longer names, and any stray staging directory. */
    private fun prunePayloads(keep: Set<String>) {
        val entries = payloadsDirectory().listFiles() ?: return
        for (entry in entries) {
            val name = entry.name
            if (!entry.isDirectory) continue
            if (name in keep) continue
            val isPayload = name.length == CHECKSUM_LENGTH && name.all { it in HEX_DIGITS }
            if (isPayload || name.startsWith(STAGING_PREFIX)) {
                runCatching { entry.deleteRecursively() }
            }
        }
    }

    /**
     * Writes [content] to [target] and flushes it, creating parent directories as needed.
     *
     * Used for the payload parts, whose names are fixed and whose directory is fresh, so no rename is
     * needed — the *directory* is what gets atomically renamed later.
     */
    private fun writeFlushed(target: File, content: String) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
    }

    /**
     * Replaces [target] with [content] in one atomic step: write a sibling temporary file, flush it,
     * then rename it over the target.
     *
     * The temporary file is a sibling on purpose — a rename across filesystems is a copy, and a copy is
     * not atomic, which would defeat the entire strategy.
     */
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
        forceDirectory(target.parentFile)
    }

    /**
     * Best-effort directory flush, so a rename is durable across power loss where the platform allows
     * opening a directory as a channel. Failure is ignored: this narrows a window, it is not the
     * guarantee. See the class documentation.
     */
    private fun forceDirectory(directory: File?) {
        if (directory == null) return
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /** The dataset in force, together with the identity it was resolved from. */
    private data class Resolved(val dataset: BangladeshLocationDataset, val active: ActiveDataset)

    /** One payload named by the record. [key] is the payload's checksum and its directory name. */
    private data class Slot(
        val key: String,
        val version: DatasetVersion,
        val appliedAtEpochMillis: Long?,
    )

    /** The metadata record: the payload in force, and the one it replaced. */
    private data class DatasetRecord(val active: Slot?, val previous: Slot?)

    private companion object {
        const val PAYLOADS_DIRECTORY = "payloads"
        const val RECORD_FILE = "active.tsv"
        const val STAGING_PREFIX = ".staging-"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val RECORD_COLUMNS = 4
        const val SLOT_ACTIVE = "active"
        const val SLOT_PREVIOUS = "previous"
        const val CHECKSUM_LENGTH = 64
        const val HEX_DIGITS = "0123456789abcdef"
        const val RECORD_HEADER =
            "# Salah active dataset. Written by LocalDatasetStore; the payload directory is named by " +
                "its own checksum."
    }
}
