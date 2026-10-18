package io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync

import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger
import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.network.AlwaysOnlineNetworkStatusProvider
import io.github.towkirahmedrony.salah.core.domain.network.NetworkStatusProvider
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.ActivePrayerConfig
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.ActivePrayerConfigStore
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.ManifestParseResult
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigActivationResult
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigProblem
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationChecksum
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationManifest
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationParser
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationValidator
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.SnapshotParseResult
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.remote.PrayerConfigRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.remote.RemotePrayerConfigSource
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * The one place a remote prayer configuration can become the app's active configuration.
 *
 * **The sequence, and the promise each step keeps.**
 *
 * ```text
 * 1  fetch the current-publication manifest          (cheap; no payload)
 * 2  validate it                                     (identity, schema, published, checksum-shaped)
 * 3  apply the revision rules                        (unchanged → download nothing; reused or stale → reject)
 * 4  fetch the payload for the named revision        (only when the revision changed)
 * 5  verify the payload checksum against the manifest's
 * 6  parse the snapshot                              (explicit supported-value mapping)
 * 7  validate it against the manifest and the dataset (drop unknown area assignments, reject the rest)
 * 8  activate it atomically through the local store
 * 9  hand the accepted configuration to the applier  (which recalculates the effective settings)
 * ```
 *
 * **Every failure leaves the active configuration exactly as it was.** There is no branch that writes user
 * preferences, clears the location or removes stored data: the only mutation it can cause is
 * `store.activate`, which itself writes nothing unless the whole candidate passes. A failed
 * synchronisation can therefore only fail to improve the app, never make it worse.
 *
 * **It downloads only when it has to.** Step 3 answers "is the remote revision the one already accepted?"
 * from the manifest alone, so an unchanged publication costs one small request and no payload. A revision
 * that was already attempted and *terminally* declined is also not re-fetched, so a bad publication is not
 * downloaded on every launch.
 *
 * `docs/PRAYER_CONFIGURATION.md` documents this sequence and its offline guarantees.
 */
class PrayerConfigSynchronizer(
    private val config: PrayerConfigRemoteConfig,
    private val source: RemotePrayerConfigSource,
    private val store: ActivePrayerConfigStore,
    /** The active location dataset, read once per attempt to validate area references; never written. */
    private val areaDataset: suspend () -> BangladeshLocationDataset?,
    private val stateStore: PrayerConfigSyncStateStore,
    private val logger: DiagnosticsLogger,
    private val clock: Clock,
    private val throttle: PrayerConfigThrottlePolicy = PrayerConfigThrottlePolicy(),
    /** Invoked after a validated configuration becomes active, so the app can reapply effective settings. */
    private val onActivated: suspend (ActivePrayerConfig) -> Unit = {},
    /**
     * Whether the device has a network. Consulted before any request so a plainly-offline device is not
     * made to attempt one. Defaulted to "always online" for the same reason as the dataset synchroniser.
     */
    private val networkStatus: NetworkStatusProvider = AlwaysOnlineNetworkStatusProvider,
) {

    private val mutex = Mutex()

    /** Serialised so two triggers at once cannot both download and attempt to activate. */
    suspend fun synchronize(
        trigger: PrayerConfigSyncTrigger,
        forced: Boolean = false,
    ): PrayerConfigSyncOutcome = mutex.withLock { attempt(trigger, forced) }

    private suspend fun attempt(trigger: PrayerConfigSyncTrigger, forced: Boolean): PrayerConfigSyncOutcome {
        if (!config.isConfigured) {
            logger.warn(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_NOT_CONFIGURED,
                mapOf("trigger" to trigger.name),
            )
            return PrayerConfigSyncOutcome.NotConfigured
        }

        val now = clock.millis()
        val state = readState()
        if (!throttle.shouldCheck(state, now, forced)) {
            logger.info(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_THROTTLED,
                mapOf("trigger" to trigger.name, "lastAttemptAt" to state.lastAttemptAtEpochMillis),
            )
            return PrayerConfigSyncOutcome.Throttled
        }

        // See the dataset synchroniser: a clearly-offline device skips the request, the skip is not an
        // attempt, and the last-success timestamp is left alone.
        if (!networkStatus.isOnline()) {
            logger.info(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_OFFLINE,
                mapOf("trigger" to trigger.name),
            )
            return PrayerConfigSyncOutcome.Offline
        }

        logger.info(
            SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_STARTED,
            mapOf("trigger" to trigger.name, "configurationId" to config.configurationId),
        )

        val active = runCatching { store.active() }.getOrNull()

        // 1. The manifest.
        val body = when (val fetch = source.fetchManifest()) {
            is RemoteFetch.Success -> fetch.value
            // 404 is a missing view/grant, not an empty slot: an empty slot is `200 []`, handled by the
            // parser below. Reporting a missing endpoint as "up to date" would claim a check succeeded.
            RemoteFetch.NotFound -> return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    PrayerConfigFailureReason.ENDPOINT_NOT_FOUND,
                    "the prayer-configuration endpoint is not available",
                ),
                now = now,
                revisionId = null,
                previous = state,
            )
            is RemoteFetch.HttpError -> return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    if (fetch.status == HTTP_UNAUTHORIZED || fetch.status == HTTP_FORBIDDEN) {
                        PrayerConfigFailureReason.ACCESS_DENIED
                    } else {
                        PrayerConfigFailureReason.MANIFEST_UNAVAILABLE
                    },
                    "manifest HTTP ${fetch.status}",
                ),
                now = now,
                revisionId = null,
                previous = state,
            )
            is RemoteFetch.NetworkFailure -> return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    if (fetch.kind == NetworkFailureKind.TIMEOUT) {
                        PrayerConfigFailureReason.TIMEOUT
                    } else {
                        PrayerConfigFailureReason.MANIFEST_UNAVAILABLE
                    },
                    "manifest ${fetch.kind.name.lowercase()}",
                ),
                now = now,
                revisionId = null,
                previous = state,
            )
            is RemoteFetch.TooLarge -> return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    PrayerConfigFailureReason.MANIFEST_MALFORMED,
                    "the manifest exceeded ${fetch.limitBytes} bytes",
                ),
                now = now,
                revisionId = null,
                previous = state,
            )
        }

        // 2. Parsing and manifest acceptance.
        val manifest: PrayerConfigurationManifest = when (
            val parsed = PrayerConfigurationParser.parseManifestRestResponse(body)
        ) {
            is ManifestParseResult.Parsed -> parsed.manifest
            ManifestParseResult.NoPublication -> return finish(
                outcome = PrayerConfigSyncOutcome.UpToDate(active),
                now = now,
                revisionId = null,
                previous = state,
            )
            is ManifestParseResult.Malformed -> return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    PrayerConfigFailureReason.MANIFEST_MALFORMED,
                    parsed.detail,
                ),
                now = now,
                revisionId = null,
                previous = state,
            )
        }

        val manifestProblems = PrayerConfigurationValidator.validateManifest(manifest, config.configurationId)
        if (manifestProblems.isNotEmpty()) {
            logger.warn(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_REJECTED,
                mapOf("revisionId" to manifest.revisionId, "codes" to manifestProblems.map { it.code }),
            )
            return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    reason = manifestProblems.first().toFailureReason(),
                    detail = manifestProblems.joinToString("; ") { it.code },
                ),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
        }

        // 3. Revision rules, answered from the manifest alone.
        if (active != null && active.revisionId == manifest.revisionId) {
            if (active.checksum.equals(manifest.checksum, ignoreCase = true)) {
                return finish(
                    outcome = PrayerConfigSyncOutcome.UpToDate(active),
                    now = now,
                    revisionId = manifest.revisionId,
                    previous = state,
                )
            }
            val problem = PrayerConfigProblem.RevisionReusedWithDifferentContent(
                revisionId = manifest.revisionId,
                activeChecksum = active.checksum,
                candidateChecksum = manifest.checksum,
                detail = "The manifest reuses revision '${manifest.revisionId}' but its checksum differs " +
                    "from the revision already accepted.",
            )
            return finish(
                outcome = PrayerConfigSyncOutcome.Rejected(listOf(problem)),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
        }

        if (active != null && manifest.revisionNumber <= active.revisionNumber) {
            val problem = PrayerConfigProblem.StaleRevision(
                activeRevisionNumber = active.revisionNumber,
                candidateRevisionNumber = manifest.revisionNumber,
                detail = "The manifest names revision number ${manifest.revisionNumber}, which is not newer " +
                    "than the accepted revision number ${active.revisionNumber}.",
            )
            return finish(
                outcome = PrayerConfigSyncOutcome.Rejected(listOf(problem)),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
        }

        // 3b. A publication already attempted and terminally declined is not fetched again.
        if (state.lastAttemptedRevisionId == manifest.revisionId &&
            state.lastFailureCode != null && isTerminalCode(state.lastFailureCode)
        ) {
            return finish(
                outcome = PrayerConfigSyncOutcome.UpToDate(active),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
        }

        // 4. The payload.
        val payloadText = when (val fetch = source.fetchContent(manifest.revisionId)) {
            is RemoteFetch.Success -> fetch.value
            RemoteFetch.NotFound -> return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    PrayerConfigFailureReason.CONTENT_NOT_FOUND,
                    "the revision's content is not served",
                ),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
            is RemoteFetch.HttpError -> return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    if (fetch.status == HTTP_UNAUTHORIZED || fetch.status == HTTP_FORBIDDEN) {
                        PrayerConfigFailureReason.ACCESS_DENIED
                    } else {
                        PrayerConfigFailureReason.CONTENT_UNAVAILABLE
                    },
                    "content HTTP ${fetch.status}",
                ),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
            is RemoteFetch.NetworkFailure -> return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    if (fetch.kind == NetworkFailureKind.TIMEOUT) {
                        PrayerConfigFailureReason.TIMEOUT
                    } else {
                        PrayerConfigFailureReason.CONTENT_UNAVAILABLE
                    },
                    "content ${fetch.kind.name.lowercase()}",
                ),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
            is RemoteFetch.TooLarge -> return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    PrayerConfigFailureReason.CONTENT_UNAVAILABLE,
                    "over ${fetch.limitBytes} bytes",
                ),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
        }

        // 5. Integrity, before the payload is interpreted.
        val actualChecksum = PrayerConfigurationChecksum.of(payloadText)
        if (!actualChecksum.equals(manifest.checksum, ignoreCase = true)) {
            return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    PrayerConfigFailureReason.CHECKSUM_MISMATCH,
                    "expected ${manifest.checksum.take(12)}…, computed ${actualChecksum.take(12)}…",
                ),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
        }

        // 6. Parsing.
        val snapshot = when (val parsed = PrayerConfigurationParser.parseSnapshot(payloadText)) {
            is SnapshotParseResult.Parsed -> parsed.snapshot
            is SnapshotParseResult.Invalid -> {
                logger.warn(
                    SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_REJECTED,
                    mapOf("revisionId" to manifest.revisionId, "codes" to parsed.problems.map { it.code }),
                )
                return finish(
                    outcome = PrayerConfigSyncOutcome.Failed(
                        reason = PrayerConfigFailureReason.INVALID_CONFIGURATION,
                        detail = parsed.problems.joinToString("; ") { it.code },
                    ),
                    now = now,
                    revisionId = manifest.revisionId,
                    previous = state,
                )
            }
            is SnapshotParseResult.Malformed -> return finish(
                outcome = PrayerConfigSyncOutcome.Failed(
                    PrayerConfigFailureReason.CONTENT_MALFORMED,
                    parsed.detail,
                ),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
        }

        // 7. Contextual validation against the manifest and the active dataset.
        val areaLevel = areaLevelLookup()
        val validation = PrayerConfigurationValidator.validateSnapshot(
            snapshot = snapshot,
            manifest = manifest,
            areaLevel = areaLevel,
        )
        if (!validation.accepted) {
            logger.warn(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_REJECTED,
                mapOf("revisionId" to manifest.revisionId, "codes" to validation.problems.map { it.code }),
            )
            return finish(
                outcome = PrayerConfigSyncOutcome.Rejected(validation.problems),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
        }
        if (validation.droppedAssignments.isNotEmpty()) {
            logger.warn(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_REJECTED,
                mapOf(
                    "revisionId" to manifest.revisionId,
                    "codes" to validation.droppedAssignments.map { it.code }.distinct(),
                    "dropped" to validation.droppedAssignments.size,
                ),
            )
        }

        val acceptedSnapshot = validation.snapshot ?: snapshot

        // 8. Activation through the local store.
        return when (val result = store.activate(acceptedSnapshot)) {
            is PrayerConfigActivationResult.Activated -> {
                runCatching { onActivated(result.active) }
                finish(
                    outcome = PrayerConfigSyncOutcome.Activated(result.active, validation.droppedAssignments),
                    now = now,
                    revisionId = manifest.revisionId,
                    previous = state,
                )
            }
            is PrayerConfigActivationResult.AlreadyActive -> finish(
                outcome = PrayerConfigSyncOutcome.UpToDate(result.active),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
            is PrayerConfigActivationResult.Rejected -> {
                logger.warn(
                    SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_REJECTED,
                    mapOf("revisionId" to manifest.revisionId, "codes" to result.problems.map { it.code }.distinct()),
                )
                finish(
                    outcome = PrayerConfigSyncOutcome.Rejected(result.problems),
                    now = now,
                    revisionId = manifest.revisionId,
                    previous = state,
                )
            }
            is PrayerConfigActivationResult.Failed -> finish(
                outcome = PrayerConfigSyncOutcome.Failed(PrayerConfigFailureReason.ACTIVATION_FAILED, result.detail),
                now = now,
                revisionId = manifest.revisionId,
                previous = state,
            )
        }
    }

    /** Reads the dataset's area levels once, or an empty mapping when no dataset can be read. */
    private suspend fun areaLevelLookup(): (String) -> BangladeshAreaLevel? {
        val levels = runCatching { areaDataset() }
            .getOrNull()
            ?.areas
            ?.associate { it.id to it.level }
            .orEmpty()
        return { id -> levels[id] }
    }

    /** Records the attempt, logs its outcome and returns it. State recording is best-effort. */
    private suspend fun finish(
        outcome: PrayerConfigSyncOutcome,
        now: Long,
        revisionId: String?,
        previous: PrayerConfigSyncState,
    ): PrayerConfigSyncOutcome {
        val failureCode = when (outcome) {
            is PrayerConfigSyncOutcome.Failed -> outcome.reason.code
            is PrayerConfigSyncOutcome.Rejected -> outcome.problems.firstOrNull()?.code ?: REJECTED_CODE
            else -> null
        }
        val transientFailure = outcome is PrayerConfigSyncOutcome.Failed && outcome.reason.transient
        val consecutiveFailures = when {
            outcome.isSuccess -> 0
            transientFailure -> previous.consecutiveFailures + 1
            else -> previous.consecutiveFailures
        }
        runCatching {
            stateStore.write(
                PrayerConfigSyncState(
                    lastAttemptAtEpochMillis = now,
                    lastAttemptedRevisionId = revisionId ?: previous.lastAttemptedRevisionId,
                    lastFailureCode = failureCode,
                    lastSuccessAtEpochMillis = if (outcome.isSuccess) now else previous.lastSuccessAtEpochMillis,
                    consecutiveFailures = consecutiveFailures,
                ),
            )
        }.onFailure {
            logger.warn(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_FAILED,
                mapOf("reason" to PrayerConfigFailureReason.STORAGE_FAILED.code, "phase" to "write"),
            )
        }

        // What remains in force after a failed check: the accepted revision, or none (bundled defaults).
        // An explicit event so "the configuration is unchanged" is observable rather than inferred.
        if (outcome.isFailure) {
            val cached = runCatching { store.active() }.getOrNull()
            logger.info(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_CACHED_DATA_USED,
                mapOf(
                    "reason" to failureCode,
                    "revisionId" to (cached?.revisionId ?: "none"),
                ),
            )
        }

        when (outcome) {
            is PrayerConfigSyncOutcome.Activated -> logger.info(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_ACTIVATED,
                mapOf(
                    "revisionId" to outcome.active.revisionId,
                    "revisionNumber" to outcome.active.revisionNumber,
                    "overrides" to outcome.active.snapshot.areaOverrides.size,
                ),
            )
            is PrayerConfigSyncOutcome.UpToDate -> logger.info(SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_UP_TO_DATE)
            is PrayerConfigSyncOutcome.Rejected -> logger.warn(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_REJECTED,
                mapOf("codes" to outcome.problems.map { it.code }.distinct().sorted()),
            )
            is PrayerConfigSyncOutcome.Failed -> {
                logger.warn(
                    SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_FAILED,
                    mapOf("reason" to outcome.reason.code, "detail" to outcome.detail),
                )
                if (outcome.reason.transient) {
                    logger.info(
                        SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_RETRY_SCHEDULED,
                        mapOf(
                            "reason" to outcome.reason.code,
                            "consecutiveFailures" to consecutiveFailures,
                        ),
                    )
                }
            }
            PrayerConfigSyncOutcome.NotConfigured,
            PrayerConfigSyncOutcome.Throttled,
            PrayerConfigSyncOutcome.Offline,
            -> Unit
        }

        return outcome
    }

    private suspend fun readState(): PrayerConfigSyncState =
        runCatching { stateStore.read() }.getOrElse {
            logger.warn(
                SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_FAILED,
                mapOf("reason" to PrayerConfigFailureReason.STORAGE_FAILED.code, "phase" to "read"),
            )
            PrayerConfigSyncState()
        }

    private companion object {
        const val REJECTED_CODE = "rejected"

        /** The two statuses that mean "the project refused this read", not "the server broke". */
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/** True when [code] names a failure that repeating the same fetch could not fix. */
private fun isTerminalCode(code: String): Boolean {
    if (code == "rejected") return true
    return PrayerConfigFailureReason.entries.firstOrNull { it.code == code }?.isTerminal() ?: false
}

/** Maps the problems that reject a manifest to the single failure reason the outcome reports. */
private fun PrayerConfigProblem.toFailureReason(): PrayerConfigFailureReason = when (code) {
    "unsupported_schema_version" -> PrayerConfigFailureReason.UNSUPPORTED_SCHEMA
    "publication_not_published" -> PrayerConfigFailureReason.PUBLICATION_NOT_PUBLISHED
    "configuration_id_mismatch" -> PrayerConfigFailureReason.CONFIGURATION_ID_MISMATCH
    "invalid_checksum" -> PrayerConfigFailureReason.INVALID_CHECKSUM
    else -> PrayerConfigFailureReason.MANIFEST_MALFORMED
}
