package io.github.towkirahmedrony.salah.core.domain.dataset.sync

import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.dataset.ActiveDataset
import io.github.towkirahmedrony.salah.core.domain.dataset.ActiveDatasetStore
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetActivationResult
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetPayload
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetProblem
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetSource
import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetVersion
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.DatasetRemoteConfig
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteDatasetSource
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The synchronisation sequence, against fakes — no server, no socket, no file.
 *
 * Every test asserts the same overriding property from a different angle: **a failed or refused
 * synchronisation leaves the active dataset exactly as it was.** The fakes make each failure path
 * reachable in isolation, which is the only way "HTTP 500 does not corrupt local data" can be a checked
 * claim rather than a hope. The two properties that would be most expensive to get wrong — that an
 * unchanged publication downloads nothing, and that a corrupted payload never reaches activation — are
 * asserted directly on the call counts and the fake store's state.
 *
 * The real HTTP client and the real filesystem store have their own tests; here the source and the
 * dataset store are both fakes, so a failure means the *synchroniser* is wrong, not the transport.
 */
class DatasetSynchronizerTest {

    private val logger = RecordingLogger()
    private val stateStore = InMemorySyncStateStore()
    private lateinit var store: FakeActiveDatasetStore
    private var storedArea: BangladeshAreaRef? = null

    private val config = DatasetRemoteConfig(
        baseUrl = "https://project.supabase.co",
        anonKey = "publishable-anon-key-not-a-secret",
        bucket = "datasets",
        datasetId = "bd_locations",
    )

    private val areas =
        "DIVISION\tBD10\tBD\tKhulna\n" +
            "DISTRICT\tBD1001\tBD10\tKushtia\n" +
            "UPAZILA\tBD1001001\tBD1001\tKumarkhali"

    private val boundary = "0\t22.0\t88.0\n0\t25.0\t88.0\n0\t25.0\t91.0"

    @Before
    fun setUp() {
        store = FakeActiveDatasetStore(
            active = ActiveDataset(DatasetVersion.of("bundled-v1"), DatasetSource.BUNDLED, null),
        )
        storedArea = null
        stateStore.state = DatasetSyncState()
    }

    // Configuration ------------------------------------------------------------------------------

    @Test
    fun `with no configuration nothing is attempted and nothing crashes`() = runTest {
        val source = FakeRemoteDatasetSource()

        val outcome = synchronizer(source, config = DatasetRemoteConfig())
            .synchronize(DatasetSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is DatasetSyncOutcome.NotConfigured)
        assertEquals(0, source.manifestCalls)
        assertEquals("bundled-v1", store.active?.version?.value)
        assertTrue(logger.has(SalahDiagnosticEvents.DATASET_SYNC_NOT_CONFIGURED))
    }

    @Test
    fun `the publishable key is never written to the log`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.Success(manifest()))

        synchronizer(source).synchronize(DatasetSyncTrigger.APP_START, forced = true)

        assertFalse(
            "the anon key must never be logged",
            logger.renderAll().contains(config.anonKey),
        )
    }

    // No publication, no change ----------------------------------------------------------------

    @Test
    fun `a missing manifest leaves the active dataset unchanged`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.NotFound)

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is DatasetSyncOutcome.UpToDate)
        assertEquals(0, source.payloadCalls)
        assertEquals("bundled-v1", store.active?.version?.value)
    }

    @Test
    fun `an empty publication slot leaves the active dataset unchanged`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.Success("[]"))

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is DatasetSyncOutcome.UpToDate)
        assertEquals(0, source.payloadCalls)
    }

    @Test
    fun `an unchanged publication does not download the payload`() = runTest {
        store.active = ActiveDataset(DatasetVersion.of("v2"), DatasetSource.APPLIED, checksumOf("v2"))
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.Success(manifest()))

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is DatasetSyncOutcome.UpToDate)
        assertEquals("an unchanged publication must not download anything", 0, source.payloadCalls)
    }

    // A real update ----------------------------------------------------------------------------

    @Test
    fun `a changed publication is downloaded, verified and activated`() = runTest {
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest()),
            payload = RemoteFetch.Success(payloadBytes()),
        )

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is DatasetSyncOutcome.Activated)
        assertEquals(1, source.payloadCalls)
        assertEquals(1, store.activations)
        assertEquals(checksumOf("v2"), store.lastExpectedChecksum)
        assertEquals(DatasetSource.APPLIED, store.active?.source)
        assertTrue(logger.has(SalahDiagnosticEvents.DATASET_SYNC_ACTIVATED))
    }

    @Test
    fun `the user's saved area is passed to activation as an input`() = runTest {
        val area = BangladeshAreaRef("BD10", "Khulna", "BD1001", "Kushtia", "BD1001001", "Kumarkhali")
        storedArea = area
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest()),
            payload = RemoteFetch.Success(payloadBytes()),
        )

        synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(area, store.lastStoredArea)
    }

    @Test
    fun `repeated checks of an unchanged publication avoid repeated downloads`() = runTest {
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest()),
            payload = RemoteFetch.Success(payloadBytes()),
        )
        val synchroniser = synchronizer(source)

        synchroniser.synchronize(DatasetSyncTrigger.APP_START)
        val second = synchroniser.synchronize(DatasetSyncTrigger.FOREGROUND, forced = true)

        assertTrue(second.toString(), second is DatasetSyncOutcome.UpToDate)
        assertEquals("the payload must be downloaded once, not once per check", 1, source.payloadCalls)
    }

    // Every failure path leaves the dataset alone ---------------------------------------------

    @Test
    fun `a checksum mismatch is refused and nothing is activated`() = runTest {
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest(checksum = "b".repeat(64))),
            payload = RemoteFetch.Success(payloadBytes()),
        )

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.CHECKSUM_MISMATCH, failure(outcome))
        assertEquals(0, store.activations)
        assertEquals(DatasetSource.BUNDLED, store.active?.source)
    }

    @Test
    fun `no network leaves the active dataset unchanged`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.NetworkFailure(NetworkFailureKind.CONNECTION))

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.MANIFEST_UNAVAILABLE, failure(outcome))
        assertEquals("bundled-v1", store.active?.version?.value)
    }

    @Test
    fun `a timeout leaves the active dataset unchanged`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.NetworkFailure(NetworkFailureKind.TIMEOUT))

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.MANIFEST_UNAVAILABLE, failure(outcome))
        assertTrue("a timeout is transient and worth retrying", failure(outcome).transient)
    }

    @Test
    fun `an http error leaves the active dataset unchanged`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.HttpError(503))

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.MANIFEST_UNAVAILABLE, failure(outcome))
        assertEquals("bundled-v1", store.active?.version?.value)
    }

    @Test
    fun `a malformed manifest is refused`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.Success("this is not json"))

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.MANIFEST_MALFORMED, failure(outcome))
        assertEquals(0, source.payloadCalls)
    }

    @Test
    fun `a manifest for another dataset slot is refused`() = runTest {
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest(datasetId = "another_slot")),
        )

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is DatasetSyncOutcome.Failed)
        assertEquals(0, source.payloadCalls)
    }

    @Test
    fun `an unsupported payload schema is refused without downloading`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.Success(manifest(schema = 2)))

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.UNSUPPORTED_SCHEMA, failure(outcome))
        assertEquals(0, source.payloadCalls)
    }

    @Test
    fun `an unpublished publication is refused without downloading`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.Success(manifest(status = "draft")))

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.PUBLICATION_NOT_PUBLISHED, failure(outcome))
        assertEquals(0, source.payloadCalls)
    }

    @Test
    fun `a payload that is not an archive is refused`() = runTest {
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest()),
            payload = RemoteFetch.Success("not a zip".toByteArray()),
        )

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.PAYLOAD_MALFORMED, failure(outcome))
        assertEquals(0, store.activations)
    }

    @Test
    fun `a payload past the transport ceiling is refused`() = runTest {
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest()),
            payload = RemoteFetch.TooLarge(8L * 1024 * 1024),
        )

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.PAYLOAD_TOO_LARGE, failure(outcome))
        assertEquals(0, store.activations)
    }

    @Test
    fun `a publication asserting an oversized payload is refused before downloading`() = runTest {
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest(payloadSize = 999_999_999L)),
        )

        val outcome = synchronizer(source, maxPayloadBytes = 1024).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.PAYLOAD_TOO_LARGE, failure(outcome))
        assertEquals(0, source.payloadCalls)
    }

    @Test
    fun `a record count the payload does not match is refused`() = runTest {
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest(areaCount = 99)),
            payload = RemoteFetch.Success(payloadBytes()),
        )

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.RECORD_COUNT_MISMATCH, failure(outcome))
        assertEquals(0, store.activations)
    }

    @Test
    fun `an activation rejection keeps the last known-good dataset available`() = runTest {
        val problems = listOf(
            DatasetProblem.StoredLocationWouldBreak(
                divisionId = "BD10",
                districtId = "BD1001",
                upazilaId = "BD1001001",
                detail = "the saved location would no longer resolve",
            ),
        )
        store.nextActivation = DatasetActivationResult.Rejected(problems)
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest()),
            payload = RemoteFetch.Success(payloadBytes()),
        )

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is DatasetSyncOutcome.Rejected)
        assertEquals(problems, (outcome as DatasetSyncOutcome.Rejected).problems)
        assertEquals("the last known-good dataset must still be in force", "bundled-v1", store.active?.version?.value)
    }

    @Test
    fun `a local activation failure leaves the last known-good dataset available`() = runTest {
        store.nextActivation = DatasetActivationResult.Failed("the storage could not be written")
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest()),
            payload = RemoteFetch.Success(payloadBytes()),
        )

        val outcome = synchronizer(source).synchronize(DatasetSyncTrigger.APP_START)

        assertEquals(SyncFailureReason.ACTIVATION_FAILED, failure(outcome))
        assertEquals("bundled-v1", store.active?.version?.value)
    }

    // Retry, throttle, and not re-downloading a declined publication --------------------------

    @Test
    fun `a retry recovers from a transient failure`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.NetworkFailure(NetworkFailureKind.CONNECTION))
        val synchroniser = synchronizer(source)

        val first = synchroniser.synchronize(DatasetSyncTrigger.APP_START)
        assertTrue(first is DatasetSyncOutcome.Failed)

        source.manifest = RemoteFetch.Success(manifest())
        source.payload = RemoteFetch.Success(payloadBytes())

        val second = synchroniser.synchronize(DatasetSyncTrigger.FOREGROUND, forced = true)

        assertTrue(second.toString(), second is DatasetSyncOutcome.Activated)
        assertEquals(1, store.activations)
    }

    @Test
    fun `a terminally declined publication is not re-downloaded`() = runTest {
        val source = FakeRemoteDatasetSource(
            manifest = RemoteFetch.Success(manifest(checksum = "b".repeat(64))),
            payload = RemoteFetch.Success(payloadBytes()),
        )
        val synchroniser = synchronizer(source)

        val first = synchroniser.synchronize(DatasetSyncTrigger.APP_START)
        assertEquals(SyncFailureReason.CHECKSUM_MISMATCH, failure(first))
        assertEquals(1, source.payloadCalls)

        val second = synchroniser.synchronize(DatasetSyncTrigger.FOREGROUND, forced = true)

        assertTrue(second.toString(), second is DatasetSyncOutcome.UpToDate)
        assertEquals("a terminally declined publication must not be fetched again", 1, source.payloadCalls)
    }

    @Test
    fun `a second immediate check is throttled`() = runTest {
        val source = FakeRemoteDatasetSource(manifest = RemoteFetch.NotFound)
        val synchroniser = synchronizer(source, throttle = SyncThrottlePolicy())

        synchroniser.synchronize(DatasetSyncTrigger.APP_START)
        val second = synchroniser.synchronize(DatasetSyncTrigger.FOREGROUND)

        assertTrue(second.toString(), second is DatasetSyncOutcome.Throttled)
        assertEquals("the second check must not reach the network", 1, source.manifestCalls)
    }

    // Helpers ----------------------------------------------------------------------------------

    private fun synchronizer(
        source: RemoteDatasetSource,
        throttle: SyncThrottlePolicy = SyncThrottlePolicy(successIntervalMillis = 0, failureIntervalMillis = 0),
        config: DatasetRemoteConfig = this.config,
        maxPayloadBytes: Long = 8L * 1024 * 1024,
    ): DatasetSynchronizer = DatasetSynchronizer(
        config = config,
        source = source,
        store = store,
        storedArea = { storedArea },
        stateStore = stateStore,
        logger = logger,
        clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC),
        throttle = throttle,
        maxPayloadBytes = maxPayloadBytes,
    )

    private fun failure(outcome: DatasetSyncOutcome): SyncFailureReason {
        assertTrue("expected a failure, got $outcome", outcome is DatasetSyncOutcome.Failed)
        return (outcome as DatasetSyncOutcome.Failed).reason
    }

    private fun checksumOf(version: String): String = payload(version).checksum()

    private fun payload(version: String = "v2"): DatasetPayload =
        DatasetPayload(DatasetVersion.of(version), areas, boundary, "")

    private fun payloadBytes(version: String = "v2"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { archive ->
            for ((name, content) in listOf(
                DatasetPayload.VERSION_PART to version,
                DatasetPayload.AREAS_PART to areas,
                DatasetPayload.BOUNDARY_PART to boundary,
            )) {
                archive.putNextEntry(ZipEntry(name))
                archive.write(content.toByteArray(Charsets.UTF_8))
                archive.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(
        datasetId: String = "bd_locations",
        publicationId: String = "pub-2",
        version: String = "v2",
        schema: Int = 1,
        status: String = "published",
        checksum: String = checksumOf("v2"),
        areaCount: Int? = null,
        payloadSize: Long? = null,
    ): String {
        val optional = buildList {
            if (areaCount != null) add(""""area_count":$areaCount""")
            if (payloadSize != null) add(""""payload_size_bytes":$payloadSize""")
        }.joinToString(",", prefix = if (areaCount != null || payloadSize != null) "," else "")
        return """[{"dataset_id":"$datasetId","publication_id":"$publicationId",""" +
            """"version":"$version","payload_schema_version":$schema,"status":"$status",""" +
            """"checksum":"$checksum","payload_path":"$datasetId/$publicationId/payload.zip"$optional}]"""
    }

    private class FakeRemoteDatasetSource(
        var manifest: RemoteFetch<String> = RemoteFetch.Success("[]"),
        var payload: RemoteFetch<ByteArray> = RemoteFetch.Success(ByteArray(0)),
    ) : RemoteDatasetSource {

        var manifestCalls = 0
        var payloadCalls = 0
        var lastPayloadPath: String? = null
        var lastMaxBytes: Long? = null

        override suspend fun fetchManifest(datasetId: String): RemoteFetch<String> {
            manifestCalls++
            return manifest
        }

        override suspend fun fetchPayload(objectPath: String, maxBytes: Long): RemoteFetch<ByteArray> {
            payloadCalls++
            lastPayloadPath = objectPath
            lastMaxBytes = maxBytes
            return payload
        }
    }

    private class FakeActiveDatasetStore(
        var active: ActiveDataset? = null,
        var nextActivation: DatasetActivationResult? = null,
    ) : ActiveDatasetStore {

        var activations = 0
        var lastPayload: DatasetPayload? = null
        var lastStoredArea: BangladeshAreaRef? = null
        var lastExpectedChecksum: String? = null

        override suspend fun activeDataset(): ActiveDataset? = active

        override suspend fun activate(
            payload: DatasetPayload,
            storedArea: BangladeshAreaRef?,
            expectedChecksum: String?,
        ): DatasetActivationResult {
            activations++
            lastPayload = payload
            lastStoredArea = storedArea
            lastExpectedChecksum = expectedChecksum
            val result = nextActivation ?: DatasetActivationResult.Activated(
                ActiveDataset(payload.version, DatasetSource.APPLIED, payload.checksum()),
            )
            if (result is DatasetActivationResult.Activated) active = result.active
            return result
        }
    }

    private class InMemorySyncStateStore(
        var state: DatasetSyncState = DatasetSyncState(),
    ) : DatasetSyncStateStore {
        override suspend fun read(): DatasetSyncState = state
        override suspend fun write(state: DatasetSyncState) {
            this.state = state
        }
    }
}
