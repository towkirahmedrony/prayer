package io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync

import io.github.towkirahmedrony.salah.core.domain.SalahDiagnosticEvents
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.NetworkFailureKind
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.network.NetworkStatusProvider
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.ActivePrayerConfig
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigProblem
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationScope
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.remote.PrayerConfigRemoteConfig
import io.github.towkirahmedrony.salah.testing.FakeActivePrayerConfigStore
import io.github.towkirahmedrony.salah.testing.FakeRemotePrayerConfigSource
import io.github.towkirahmedrony.salah.testing.InMemoryPrayerConfigSyncStateStore
import io.github.towkirahmedrony.salah.testing.RecordingLogger
import io.github.towkirahmedrony.salah.testing.areaOverride
import io.github.towkirahmedrony.salah.testing.checksumOf
import io.github.towkirahmedrony.salah.testing.manifestJson
import io.github.towkirahmedrony.salah.testing.payloadOf
import io.github.towkirahmedrony.salah.testing.prayerConfigDataset
import io.github.towkirahmedrony.salah.testing.sampleSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The synchronisation sequence, against fakes — no server, no socket, no file.
 *
 * Every test asserts the same overriding property from a different angle: **a failed or refused
 * synchronisation leaves the active configuration exactly as it was, and an unchanged revision downloads
 * nothing.** The two properties most expensive to get wrong — the "no download when unchanged" rule and the
 * "a corrupt or invalid payload never reaches activation" rule — are asserted directly on the call counts
 * and the fake store's state.
 */
class PrayerConfigSynchronizerTest {

    private val logger = RecordingLogger()
    private val stateStore = InMemoryPrayerConfigSyncStateStore()
    private lateinit var store: FakeActivePrayerConfigStore

    private val config = PrayerConfigRemoteConfig(
        baseUrl = "https://project.supabase.co",
        anonKey = "publishable-anon-key-not-a-secret",
        configurationId = "bd_prayer",
    )

    @Before
    fun setUp() {
        store = FakeActivePrayerConfigStore()
        stateStore.state = PrayerConfigSyncState()
    }

    @Test
    fun `with no configuration nothing is attempted and nothing crashes`() = runTest {
        val source = FakeRemotePrayerConfigSource()

        val outcome = synchronizer(source, config = PrayerConfigRemoteConfig())
            .synchronize(PrayerConfigSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is PrayerConfigSyncOutcome.NotConfigured)
        assertEquals(0, source.manifestCalls)
        assertTrue(logger.has(SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_NOT_CONFIGURED))
    }

    @Test
    fun `the publishable key is never written to the log`() = runTest {
        val snapshot = sampleSnapshot()
        val source = FakeRemotePrayerConfigSource(
            manifest = RemoteFetch.Success(manifestJson(snapshot)),
            content = RemoteFetch.Success(payloadOf(snapshot)),
        )

        synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START, forced = true)

        assertFalse(logger.renderAll().contains(config.anonKey))
    }

    @Test
    fun `an empty slot leaves the app on its bundled defaults`() = runTest {
        val source = FakeRemotePrayerConfigSource(manifest = RemoteFetch.Success("[]"))

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is PrayerConfigSyncOutcome.UpToDate)
        assertEquals(0, source.contentCalls)
        assertNull(store.current)
    }

    @Test
    fun `a new revision is downloaded, validated and activated`() = runTest {
        val snapshot = sampleSnapshot(revisionId = "rev-0002", revisionNumber = 2)
        val source = FakeRemotePrayerConfigSource(
            manifest = RemoteFetch.Success(manifestJson(snapshot)),
            content = RemoteFetch.Success(payloadOf(snapshot)),
        )

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is PrayerConfigSyncOutcome.Activated)
        assertEquals(1, source.contentCalls)
        assertEquals(1, store.activations)
        assertEquals("rev-0002", store.current?.revisionId)
        assertTrue(logger.has(SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_ACTIVATED))
    }

    @Test
    fun `an unchanged revision downloads no payload`() = runTest {
        val snapshot = sampleSnapshot(revisionId = "rev-0002", revisionNumber = 2)
        store.current = ActivePrayerConfig(snapshot, checksumOf(payloadOf(snapshot)))
        val source = FakeRemotePrayerConfigSource(manifest = RemoteFetch.Success(manifestJson(snapshot)))

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is PrayerConfigSyncOutcome.UpToDate)
        assertEquals("an unchanged revision must not download anything", 0, source.contentCalls)
    }

    @Test
    fun `reusing a revision id with different content is refused`() = runTest {
        store.current = ActivePrayerConfig(
            sampleSnapshot(revisionId = "rev-0002", revisionNumber = 2),
            checksumOf("something else entirely"),
        )
        val incoming = sampleSnapshot(revisionId = "rev-0002", revisionNumber = 2)
        val source = FakeRemotePrayerConfigSource(manifest = RemoteFetch.Success(manifestJson(incoming)))

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is PrayerConfigSyncOutcome.Rejected)
        assertTrue(
            (outcome as PrayerConfigSyncOutcome.Rejected).problems.any {
                it is PrayerConfigProblem.RevisionReusedWithDifferentContent
            },
        )
        assertEquals(0, source.contentCalls)
        assertEquals(0, store.activations)
    }

    @Test
    fun `an older revision may not replace a newer accepted one`() = runTest {
        store.current = ActivePrayerConfig(
            sampleSnapshot(revisionId = "rev-0009", revisionNumber = 9),
            checksumOf("active"),
        )
        val older = sampleSnapshot(revisionId = "rev-0002", revisionNumber = 2)
        val source = FakeRemotePrayerConfigSource(manifest = RemoteFetch.Success(manifestJson(older)))

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is PrayerConfigSyncOutcome.Rejected)
        assertTrue(
            (outcome as PrayerConfigSyncOutcome.Rejected).problems.any {
                it is PrayerConfigProblem.StaleRevision
            },
        )
        assertEquals("a stale revision must not be downloaded", 0, source.contentCalls)
        assertEquals("rev-0009", store.current?.revisionId)
    }

    @Test
    fun `a network failure leaves the active configuration unchanged`() = runTest {
        store.current = ActivePrayerConfig(sampleSnapshot(revisionId = "rev-0001", revisionNumber = 1), "sum")
        val source = FakeRemotePrayerConfigSource(manifest = RemoteFetch.NetworkFailure(NetworkFailureKind.CONNECTION))

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertEquals(PrayerConfigFailureReason.MANIFEST_UNAVAILABLE, failure(outcome))
        assertEquals("rev-0001", store.current?.revisionId)
    }

    @Test
    fun `an unpublished revision is refused without downloading`() = runTest {
        val snapshot = sampleSnapshot()
        val source = FakeRemotePrayerConfigSource(
            manifest = RemoteFetch.Success(manifestJson(snapshot, status = "draft")),
        )

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertEquals(PrayerConfigFailureReason.PUBLICATION_NOT_PUBLISHED, failure(outcome))
        assertEquals(0, source.contentCalls)
    }

    @Test
    fun `a checksum mismatch is refused and nothing is activated`() = runTest {
        val snapshot = sampleSnapshot()
        val source = FakeRemotePrayerConfigSource(
            manifest = RemoteFetch.Success(manifestJson(snapshot, checksum = "b".repeat(64))),
            content = RemoteFetch.Success(payloadOf(snapshot)),
        )

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertEquals(PrayerConfigFailureReason.CHECKSUM_MISMATCH, failure(outcome))
        assertEquals(0, store.activations)
    }

    @Test
    fun `an invalid remote configuration cannot replace a valid one`() = runTest {
        store.current = ActivePrayerConfig(
            sampleSnapshot(revisionId = "rev-0001", revisionNumber = 1),
            checksumOf("active"),
        )
        val badPayload = """{"configuration_id":"bd_prayer","revision_id":"rev-0002","revision_number":2,""" +
            """"schema_version":1,"global":{"method":"MARS","asr_method":"HANAFI",""" +
            """"high_latitude_rule":"AUTOMATIC","adjustments":{}}}"""
        val source = FakeRemotePrayerConfigSource(
            manifest = RemoteFetch.Success(manifestJson(checksum = checksumOf(badPayload))),
            content = RemoteFetch.Success(badPayload),
        )

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertEquals(PrayerConfigFailureReason.INVALID_CONFIGURATION, failure(outcome))
        assertEquals(0, store.activations)
        assertEquals("rev-0001", store.current?.revisionId)
    }

    @Test
    fun `an unknown area assignment is dropped and the rest is applied`() = runTest {
        val snapshot = sampleSnapshot(
            areaOverrides = listOf(
                areaOverride("BD9999", PrayerConfigurationScope.DIVISION),
                areaOverride("BD10", PrayerConfigurationScope.DIVISION),
            ),
        )
        val source = FakeRemotePrayerConfigSource(
            manifest = RemoteFetch.Success(manifestJson(snapshot)),
            content = RemoteFetch.Success(payloadOf(snapshot)),
        )

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is PrayerConfigSyncOutcome.Activated)
        assertEquals(1, (outcome as PrayerConfigSyncOutcome.Activated).droppedAssignments.size)
        assertEquals(1, store.current?.snapshot?.areaOverrides?.size)
    }

    @Test
    fun `the applier callback runs after a successful activation`() = runTest {
        val snapshot = sampleSnapshot()
        var applied = 0
        val source = FakeRemotePrayerConfigSource(
            manifest = RemoteFetch.Success(manifestJson(snapshot)),
            content = RemoteFetch.Success(payloadOf(snapshot)),
        )

        synchronizer(source, onActivated = { applied++ }).synchronize(PrayerConfigSyncTrigger.APP_START)

        assertEquals(1, applied)
    }

    @Test
    fun `a second immediate check is throttled`() = runTest {
        val source = FakeRemotePrayerConfigSource(manifest = RemoteFetch.Success("[]"))
        val synchronizer = synchronizer(source, throttle = PrayerConfigThrottlePolicy())

        synchronizer.synchronize(PrayerConfigSyncTrigger.APP_START)
        val second = synchronizer.synchronize(PrayerConfigSyncTrigger.FOREGROUND)

        assertTrue(second.toString(), second is PrayerConfigSyncOutcome.Throttled)
        assertEquals(1, source.manifestCalls)
    }

    @Test
    fun `the trigger does not change what a check does`() = runTest {
        val snapshot = sampleSnapshot(revisionId = "rev-0002", revisionNumber = 2)
        val source = FakeRemotePrayerConfigSource(
            manifest = RemoteFetch.Success(manifestJson(snapshot)),
            content = RemoteFetch.Success(payloadOf(snapshot)),
        )

        val outcome = synchronizer(source).synchronize(PrayerConfigSyncTrigger.MANUAL, forced = true)

        assertTrue(outcome.toString(), outcome is PrayerConfigSyncOutcome.Activated)
    }

    // Offline, freshness and backoff --------------------------------------------

    @Test
    fun `an offline device does not attempt a request and records no attempt`() = runTest {
        val source = FakeRemotePrayerConfigSource(manifest = RemoteFetch.Success("[]"))
        val synchronizer = synchronizer(source, networkStatus = FakeNetworkStatusProvider(online = false))

        val outcome = synchronizer.synchronize(PrayerConfigSyncTrigger.APP_START)

        assertTrue(outcome.toString(), outcome is PrayerConfigSyncOutcome.Offline)
        assertEquals(0, source.manifestCalls)
        assertNull("an offline skip is not an attempt", stateStore.state.lastAttemptAtEpochMillis)
        assertTrue(logger.has(SalahDiagnosticEvents.PRAYER_CONFIG_SYNC_OFFLINE))
    }

    @Test
    fun `a successful check records the last success and clears the failure count`() = runTest {
        stateStore.state = PrayerConfigSyncState(
            lastFailureCode = "content_unavailable",
            consecutiveFailures = 3,
        )
        val snapshot = sampleSnapshot(revisionId = "rev-0002", revisionNumber = 2)
        val source = FakeRemotePrayerConfigSource(
            manifest = RemoteFetch.Success(manifestJson(snapshot)),
            content = RemoteFetch.Success(payloadOf(snapshot)),
        )

        synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START, forced = true)

        assertEquals(
            Instant.parse("2026-10-15T00:00:00Z").toEpochMilli(),
            stateStore.state.lastSuccessAtEpochMillis,
        )
        assertEquals(0, stateStore.state.consecutiveFailures)
        assertNull(stateStore.state.lastFailureCode)
    }

    @Test
    fun `a failed check keeps the previous last success and counts the failure`() = runTest {
        stateStore.state = PrayerConfigSyncState(lastSuccessAtEpochMillis = 42L)
        val source = FakeRemotePrayerConfigSource(manifest = RemoteFetch.NetworkFailure(NetworkFailureKind.CONNECTION))

        synchronizer(source).synchronize(PrayerConfigSyncTrigger.APP_START, forced = true)

        assertEquals("a failed request is not a fresh success", 42L, stateStore.state.lastSuccessAtEpochMillis)
        assertEquals(1, stateStore.state.consecutiveFailures)
    }

    // Helpers ----------------------------------------------------------------------------------

    private fun synchronizer(
        source: FakeRemotePrayerConfigSource,
        config: PrayerConfigRemoteConfig = this.config,
        throttle: PrayerConfigThrottlePolicy = PrayerConfigThrottlePolicy(
            successIntervalMillis = 0,
            failureIntervalMillis = 0,
        ),
        onActivated: suspend (ActivePrayerConfig) -> Unit = {},
        networkStatus: NetworkStatusProvider = FakeNetworkStatusProvider(online = true),
    ): PrayerConfigSynchronizer = PrayerConfigSynchronizer(
        config = config,
        source = source,
        store = store,
        areaDataset = { prayerConfigDataset() },
        stateStore = stateStore,
        logger = logger,
        clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC),
        throttle = throttle,
        onActivated = onActivated,
        networkStatus = networkStatus,
    )

    private fun failure(outcome: PrayerConfigSyncOutcome): PrayerConfigFailureReason {
        assertTrue("expected a failure, got $outcome", outcome is PrayerConfigSyncOutcome.Failed)
        return (outcome as PrayerConfigSyncOutcome.Failed).reason
    }

    private class FakeNetworkStatusProvider(var online: Boolean) : NetworkStatusProvider {
        override suspend fun isOnline(): Boolean = online
    }
}
