package io.github.towkirahmedrony.salah.core.domain.dataset.sync

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.dataset.ActiveDatasetStore
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetActivationResult
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetPayloadArchive
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetSource
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.DatasetPublication
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.DatasetPublicationParser
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.DatasetPublicationValidator
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.DatasetRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.ManifestParseResult
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.PublicationProblem
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteDatasetSource
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.network.AlwaysOnlineNetworkStatusProvider
import io.github.towkirahmedrony.salah.core.domain.network.NetworkStatusProvider
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * The one place a remote dataset publication can become the app's active dataset.
 *
 * **The sequence, and the promise each step keeps.**
 *
 * ```text
 * 1  fetch the current-publication manifest                 (cheap; no payload)
 * 2  validate it                                            (well-formed, supported, published, checksum-shaped)
 * 3  compare with the active dataset                        (equal → download nothing)
 * 4  fetch the immutable payload under a size ceiling
 * 5  decode the archive into a payload
 * 6  verify the payload checksum against the published one
 * 7  verify the asserted record counts
 * 8  hand the payload to the existing local activation path
 * ```
 *
 * **Every failure leaves the active dataset exactly as it was.** There is no branch in this class that
 * writes user data, clears the stored location, or removes a dataset: the only mutation it can cause is
 * `store.activate`, which itself writes nothing unless the whole candidate passes validation. A failed
 * synchronisation therefore cannot make the app unavailable — it can only fail to improve it.
 *
 * **It downloads only when it has to.** Step 3 answers "is the remote publication the one already in
 * use?" from the manifest alone, so an unchanged publication costs one small request and no payload. A
 * publication that was already attempted and *terminally* declined is also not re-downloaded, so a bad
 * publication is not re-fetched on every launch.
 *
 * **The order of checks is deliberate.** Integrity (step 6) is verified before the payload is interpreted
 * or handed on, and the record-count assertions (step 7) are checked before activation, so a payload that
 * corrupts in transit is reported as corruption rather than as a set of validation failures it would
 * otherwise appear to cause.
 *
 * `docs/ARCHITECTURE.md` §13 documents this sequence and its offline guarantees; `docs/DATABASE-SCHEMA.md`
 * documents the manifest this reads.
 */
class DatasetSynchronizer(
    private val config: DatasetRemoteConfig,
    private val source: RemoteDatasetSource,
    private val store: ActiveDatasetStore,
    /** The area of the user's saved location, read at activation time; never written. */
    private val storedArea: suspend () -> BangladeshAreaRef?,
    private val stateStore: DatasetSyncStateStore,
    private val logger: DiagnosticsLogger,
    private val clock: Clock,
    private val throttle: SyncThrottlePolicy = SyncThrottlePolicy(),
    private val maxPayloadBytes: Long = MAX_PAYLOAD_BYTES,
    /**
     * Whether the device has a network. Consulted before any request so a plainly-offline device is not
     * made to attempt one. Defaulted to "always online" so an existing caller keeps the previous
     * behaviour and no test is accidentally suppressed.
     */
    private val networkStatus: NetworkStatusProvider = AlwaysOnlineNetworkStatusProvider,
) {

    private val mutex = Mutex()

    /**
     * Runs one synchronisation attempt.
     *
     * Serialised by a mutex so two triggers at once (start-up and foreground, say) cannot both download
     * and attempt to activate; the second observes the first's state and does nothing.
     *
     * @param forced skips the throttle — used for a user-initiated check.
     */
    suspend fun synchronize(
        trigger: DatasetSyncTrigger,
        forced: Boolean = false,
    ): DatasetSyncOutcome = mutex.withLock { attempt(trigger, forced) }

    private suspend fun attempt(trigger: DatasetSyncTrigger, forced: Boolean): DatasetSyncOutcome {
        if (!config.isConfigured) {
            logger.warn(
                SalahDiagnosticEvents.DATASET_SYNC_NOT_CONFIGURED,
                mapOf("trigger" to trigger.name),
            )
            return DatasetSyncOutcome.NotConfigured
        }

        val now = clock.millis()
        val state = readState()
        if (!throttle.shouldCheck(state, now, forced)) {
            logger.info(
                SalahDiagnosticEvents.DATASET_SYNC_THROTTLED,
                mapOf("trigger" to trigger.name, "lastAttemptAt" to state.lastAttemptAtEpochMillis),
            )
            return DatasetSyncOutcome.Throttled
        }

        // A plainly-offline device is not made to attempt a request it cannot complete. This cheap, local
        // check sits *after* the throttle (a suppressed check should not consult connectivity at all) and
        // *before* the attempt is recorded: an offline skip is not a failure, it leaves the last-success
        // timestamp untouched, and returning to connectivity must not have to wait out a backoff window.
        if (!networkStatus.isOnline()) {
            logger.info(
                SalahDiagnosticEvents.DATASET_SYNC_OFFLINE,
                mapOf("trigger" to trigger.name),
            )
            return DatasetSyncOutcome.Offline
        }

        logger.info(
            SalahDiagnosticEvents.DATASET_SYNC_STARTED,
            mapOf("trigger" to trigger.name, "datasetId" to config.datasetId),
        )

        val active = runCatching { store.activeDataset() }.getOrNull()

        // 1. The manifest.
        val body = when (val fetch = source.fetchManifest(config.datasetId)) {
            is RemoteFetch.Success -> fetch.value
            RemoteFetch.NotFound -> return finish(
                outcome = DatasetSyncOutcome.UpToDate(active),
                now = now,
                publicationId = null,
                previous = state,
            )
            is RemoteFetch.HttpError -> return finish(
                outcome = DatasetSyncOutcome.Failed(
                    SyncFailureReason.MANIFEST_UNAVAILABLE,
                    "HTTP ${fetch.status}",
                ),
                now = now,
                publicationId = null,
                previous = state,
            )
            is RemoteFetch.NetworkFailure -> return finish(
                outcome = DatasetSyncOutcome.Failed(
                    SyncFailureReason.MANIFEST_UNAVAILABLE,
                    fetch.kind.name,
                ),
                now = now,
                publicationId = null,
                previous = state,
            )
            is RemoteFetch.TooLarge -> return finish(
                outcome = DatasetSyncOutcome.Failed(
                    SyncFailureReason.MANIFEST_MALFORMED,
                    "the manifest exceeded ${fetch.limitBytes} bytes",
                ),
                now = now,
                publicationId = null,
                previous = state,
            )
        }

        // 2. Parsing and semantic acceptance.
        val publication = when (val parsed = DatasetPublicationParser.parseRestResponse(body)) {
            is ManifestParseResult.Parsed -> parsed.publication
            ManifestParseResult.NoPublication -> return finish(
                outcome = DatasetSyncOutcome.UpToDate(active),
                now = now,
                publicationId = null,
                previous = state,
            )
            is ManifestParseResult.Malformed -> return finish(
                outcome = DatasetSyncOutcome.Failed(SyncFailureReason.MANIFEST_MALFORMED, parsed.detail),
                now = now,
                publicationId = null,
                previous = state,
            )
        }

        val problems = DatasetPublicationValidator.validate(publication, config.datasetId)
        if (problems.isNotEmpty()) {
            logger.warn(
                SalahDiagnosticEvents.DATASET_SYNC_REJECTED,
                mapOf("publicationId" to publication.publicationId, "codes" to problems.map { it.code }),
            )
            return finish(
                outcome = DatasetSyncOutcome.Failed(
                    reason = problems.first().toFailureReason(),
                    detail = problems.joinToString("; ") { it.code },
                ),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
        }

        // 3. Nothing to do when the remote publication is the one already active.
        if (active != null && active.source == DatasetSource.APPLIED &&
            active.checksum != null && active.checksum.equals(publication.checksum, ignoreCase = true)
        ) {
            return finish(
                outcome = DatasetSyncOutcome.UpToDate(active),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
        }

        // 3b. Nor when it is a publication that was already attempted and terminally declined.
        if (state.lastAttemptedPublicationId == publication.publicationId &&
            state.lastFailureCode != null && isTerminalCode(state.lastFailureCode)
        ) {
            return finish(
                outcome = DatasetSyncOutcome.UpToDate(active),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
        }

        val assertedSize = publication.payloadSizeBytes
        if (assertedSize != null && assertedSize > maxPayloadBytes) {
            return finish(
                outcome = DatasetSyncOutcome.Failed(
                    SyncFailureReason.PAYLOAD_TOO_LARGE,
                    "the publication asserts $assertedSize bytes, over the $maxPayloadBytes limit",
                ),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
        }

        // 4. The payload.
        val bytes = when (val fetch = source.fetchPayload(publication.payloadPath, maxPayloadBytes)) {
            is RemoteFetch.Success -> fetch.value
            RemoteFetch.NotFound -> return finish(
                outcome = DatasetSyncOutcome.Failed(SyncFailureReason.PAYLOAD_UNAVAILABLE, "not found"),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
            is RemoteFetch.HttpError -> return finish(
                outcome = DatasetSyncOutcome.Failed(
                    SyncFailureReason.PAYLOAD_UNAVAILABLE,
                    "HTTP ${fetch.status}",
                ),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
            is RemoteFetch.NetworkFailure -> return finish(
                outcome = DatasetSyncOutcome.Failed(
                    SyncFailureReason.PAYLOAD_UNAVAILABLE,
                    fetch.kind.name,
                ),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
            is RemoteFetch.TooLarge -> return finish(
                outcome = DatasetSyncOutcome.Failed(
                    SyncFailureReason.PAYLOAD_TOO_LARGE,
                    "over ${fetch.limitBytes} bytes",
                ),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
        }

        // 5. Decoding.
        val payload = when (val decoded = DatasetPayloadArchive.read(bytes)) {
            is DatasetPayloadArchive.Result.Decoded -> decoded.payload
            is DatasetPayloadArchive.Result.Malformed -> return finish(
                outcome = DatasetSyncOutcome.Failed(SyncFailureReason.PAYLOAD_MALFORMED, decoded.detail),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
        }

        // 6. Integrity, before the payload is interpreted.
        val actualChecksum = payload.checksum()
        if (!actualChecksum.equals(publication.checksum, ignoreCase = true)) {
            return finish(
                outcome = DatasetSyncOutcome.Failed(
                    SyncFailureReason.CHECKSUM_MISMATCH,
                    "expected ${publication.checksum.take(12)}…, computed ${actualChecksum.take(12)}…",
                ),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
        }

        // 7. Completeness, when the publication asserted counts.
        val dataset = payload.toDataset()
        publication.countMismatch(dataset)?.let { detail ->
            return finish(
                outcome = DatasetSyncOutcome.Failed(SyncFailureReason.RECORD_COUNT_MISMATCH, detail),
                now = now,
                publicationId = publication.publicationId,
                previous = state,
            )
        }

        // 8. Activation through the existing Phase 1 path.
        val area = runCatching { storedArea() }.getOrNull()
        return when (val result = store.activate(payload, area, publication.checksum)) {
            is DatasetActivationResult.Activated ->
                finish(
                    outcome = DatasetSyncOutcome.Activated(result.active),
                    now = now,
                    publicationId = publication.publicationId,
                    previous = state,
                )
            is DatasetActivationResult.AlreadyActive ->
                finish(
                    outcome = DatasetSyncOutcome.UpToDate(result.active),
                    now = now,
                    publicationId = publication.publicationId,
                    previous = state,
                )
            is DatasetActivationResult.Rejected -> {
                logger.warn(
                    SalahDiagnosticEvents.DATASET_SYNC_REJECTED,
                    mapOf(
                        "publicationId" to publication.publicationId,
                        "codes" to result.problems.map { it.code }.distinct(),
                    ),
                )
                finish(
                    outcome = DatasetSyncOutcome.Rejected(result.problems),
                    now = now,
                    publicationId = publication.publicationId,
                    previous = state,
                )
            }
            is DatasetActivationResult.Failed ->
                finish(
                    outcome = DatasetSyncOutcome.Failed(SyncFailureReason.ACTIVATION_FAILED, result.detail),
                    now = now,
                    publicationId = publication.publicationId,
                    previous = state,
                )
        }
    }

    /** Records the attempt, logs its outcome, and returns it. State recording is best-effort. */
    private suspend fun finish(
        outcome: DatasetSyncOutcome,
        now: Long,
        publicationId: String?,
        previous: DatasetSyncState,
    ): DatasetSyncOutcome {
        val failureCode = when (outcome) {
            is DatasetSyncOutcome.Failed -> outcome.reason.code
            is DatasetSyncOutcome.Rejected -> REJECTED_CODE
            else -> null
        }
        // Only a *transient* failure grows the backoff: a terminal one is not retried for the same
        // publication anyway, so letting it inflate the failure count would only delay a legitimate new
        // publication. A success resets it to zero.
        val transientFailure = outcome is DatasetSyncOutcome.Failed && outcome.reason.transient
        val consecutiveFailures = when {
            outcome.isSuccess -> 0
            transientFailure -> previous.consecutiveFailures + 1
            else -> previous.consecutiveFailures
        }
        writeState(
            DatasetSyncState(
                lastAttemptAtEpochMillis = now,
                lastAttemptedPublicationId = publicationId ?: previous.lastAttemptedPublicationId,
                lastFailureCode = failureCode,
                lastSuccessAtEpochMillis = if (outcome.isSuccess) now else previous.lastSuccessAtEpochMillis,
                consecutiveFailures = consecutiveFailures,
            ),
        )

        when (outcome) {
            is DatasetSyncOutcome.Activated -> logger.info(
                SalahDiagnosticEvents.DATASET_SYNC_ACTIVATED,
                mapOf(
                    "version" to outcome.active.version.value,
                    "source" to outcome.active.source.name.lowercase(),
                ),
            )
            is DatasetSyncOutcome.UpToDate -> logger.info(SalahDiagnosticEvents.DATASET_SYNC_UP_TO_DATE)
            is DatasetSyncOutcome.Rejected -> logger.warn(
                SalahDiagnosticEvents.DATASET_SYNC_REJECTED,
                mapOf("codes" to outcome.problems.map { it.code }.distinct().sorted()),
            )
            is DatasetSyncOutcome.Failed -> {
                logger.warn(
                    SalahDiagnosticEvents.DATASET_SYNC_FAILED,
                    mapOf("reason" to outcome.reason.code, "detail" to outcome.detail),
                )
                if (outcome.reason.transient) {
                    logger.info(
                        SalahDiagnosticEvents.DATASET_SYNC_RETRY_SCHEDULED,
                        mapOf(
                            "reason" to outcome.reason.code,
                            "consecutiveFailures" to consecutiveFailures,
                        ),
                    )
                }
            }
            DatasetSyncOutcome.NotConfigured,
            DatasetSyncOutcome.Throttled,
            DatasetSyncOutcome.Offline,
            -> Unit
        }

        return outcome
    }

    private suspend fun readState(): DatasetSyncState =
        runCatching { stateStore.read() }.getOrElse { error ->
            logger.warn(
                SalahDiagnosticEvents.DATASET_SYNC_FAILED,
                mapOf("reason" to SyncFailureReason.STORAGE_FAILED.code, "phase" to "read"),
            )
            DatasetSyncState()
        }

    private suspend fun writeState(state: DatasetSyncState) {
        runCatching { stateStore.write(state) }.onFailure {
            logger.warn(
                SalahDiagnosticEvents.DATASET_SYNC_FAILED,
                mapOf("reason" to SyncFailureReason.STORAGE_FAILED.code, "phase" to "write"),
            )
        }
    }

    private companion object {
        /** Eight mebibytes: far above a real location payload, below anything worth expanding. */
        const val MAX_PAYLOAD_BYTES: Long = 8L * 1024 * 1024

        /** The failure code recorded when an activation candidate was rejected. */
        const val REJECTED_CODE = "rejected"
    }
}

/** True when [code] names a failure that repeating the same fetch could not fix. */
private fun isTerminalCode(code: String): Boolean {
    if (code == "rejected") return true
    return SyncFailureReason.entries.firstOrNull { it.code == code }?.isTerminal() ?: false
}

/** Maps the problems that reject a manifest to the single failure reason the outcome reports. */
private fun PublicationProblem.toFailureReason(): SyncFailureReason =
    when (code) {
        "unsupported_schema_version" -> SyncFailureReason.UNSUPPORTED_SCHEMA
        "publication_not_published" -> SyncFailureReason.PUBLICATION_NOT_PUBLISHED
        "dataset_id_mismatch", "invalid_checksum" -> SyncFailureReason.MANIFEST_MALFORMED
        else -> SyncFailureReason.MANIFEST_MALFORMED
    }

/** True when the payload's own record counts differ from the ones the publication asserted. */
private fun DatasetPublication.countMismatch(dataset: BangladeshLocationDataset): String? {
    areaCount?.let { asserted ->
        if (dataset.areas.size != asserted) {
            return "the publication asserts $asserted areas but the payload contains ${dataset.areas.size}"
        }
    }
    localityCount?.let { asserted ->
        if (dataset.localities.size != asserted) {
            return "the publication asserts $asserted localities but the payload contains ${dataset.localities.size}"
        }
    }
    boundaryRingCount?.let { asserted ->
        if (dataset.boundaryRings.size != asserted) {
            return "the publication asserts $asserted boundary rings but the payload contains " +
                "${dataset.boundaryRings.size}"
        }
    }
    return null
}
