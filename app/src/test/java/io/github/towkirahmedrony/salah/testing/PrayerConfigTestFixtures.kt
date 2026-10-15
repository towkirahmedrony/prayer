package io.github.towkirahmedrony.salah.testing

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.RemoteFetch
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.ActivePrayerConfig
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.ActivePrayerConfigStore
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerAreaOverride
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigActivationResult
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationChecksum
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationScope
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationSnapshot
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.PrayerConfigurationWriter
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.remote.RemotePrayerConfigSource
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSyncState
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSyncStateStore
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName

/**
 * Test doubles and canonical fixtures for the prayer-configuration subsystem.
 *
 * The fixtures are built through [PrayerConfigurationWriter] so a snapshot and the payload text a check
 * is taken over are, by construction, the same bytes the real publisher produces — a test that asserted a
 * checksum over a hand-written JSON string would be asserting a string, not the contract. A fake always
 * behaves like the real collaborator in the way the test depends on: the store actually replaces its active
 * value, and the sync state store actually remembers what was written.
 */

/** A remote source a test drives: one scripted manifest and one scripted payload. */
class FakeRemotePrayerConfigSource(
    var manifest: RemoteFetch<String> = RemoteFetch.Success("[]"),
    var content: RemoteFetch<String> = RemoteFetch.Success(""),
) : RemotePrayerConfigSource {

    var manifestCalls = 0
    var contentCalls = 0
    var lastRevisionId: String? = null

    override suspend fun fetchManifest(): RemoteFetch<String> {
        manifestCalls++
        return manifest
    }

    override suspend fun fetchContent(revisionId: String): RemoteFetch<String> {
        contentCalls++
        lastRevisionId = revisionId
        return content
    }
}

/** An in-memory store that replaces its active value exactly as the file store does. */
class FakeActivePrayerConfigStore(
    var current: ActivePrayerConfig? = null,
) : ActivePrayerConfigStore {

    var activations = 0
    var lastSnapshot: PrayerConfigurationSnapshot? = null

    override suspend fun active(): ActivePrayerConfig? = current

    override suspend fun activate(snapshot: PrayerConfigurationSnapshot): PrayerConfigActivationResult {
        activations++
        lastSnapshot = snapshot
        val active = ActivePrayerConfig(
            snapshot = snapshot,
            checksum = PrayerConfigurationChecksum.of(PrayerConfigurationWriter.toJson(snapshot)),
        )
        current = active
        return PrayerConfigActivationResult.Activated(active)
    }
}

/** An in-memory sync-state store. */
class InMemoryPrayerConfigSyncStateStore(
    var state: PrayerConfigSyncState = PrayerConfigSyncState(),
) : PrayerConfigSyncStateStore {
    override suspend fun read(): PrayerConfigSyncState = state
    override suspend fun write(state: PrayerConfigSyncState) {
        this.state = state
    }
}

/** A calculation profile with the given inputs, defaulting to the bundled convention. */
fun sampleProfile(
    method: PrayerCalculationMethod = PrayerCalculationMethod.KARACHI,
    asrMethod: AsrCalculationMethod = AsrCalculationMethod.HANAFI,
    highLatitudeRule: HighLatitudeRule = HighLatitudeRule.AUTOMATIC,
    adjustments: Map<PrayerName, Int> = emptyMap(),
): PrayerCalculationProfile =
    PrayerCalculationProfile(method, asrMethod, highLatitudeRule, adjustments)

/** A snapshot with the given values; its canonical payload is [payloadOf]. */
fun sampleSnapshot(
    configurationId: String = PrayerConfigurationSnapshot.DEFAULT_CONFIGURATION_ID,
    revisionId: String = "rev-0002",
    revisionNumber: Long = 2L,
    method: PrayerCalculationMethod = PrayerCalculationMethod.KARACHI,
    asrMethod: AsrCalculationMethod = AsrCalculationMethod.HANAFI,
    highLatitudeRule: HighLatitudeRule = HighLatitudeRule.AUTOMATIC,
    adjustments: Map<PrayerName, Int> = emptyMap(),
    areaOverrides: List<PrayerAreaOverride> = emptyList(),
): PrayerConfigurationSnapshot = PrayerConfigurationSnapshot(
    configurationId = configurationId,
    revisionId = revisionId,
    revisionNumber = revisionNumber,
    schemaVersion = 1,
    globalProfile = sampleProfile(method, asrMethod, highLatitudeRule, adjustments),
    areaOverrides = areaOverrides.associateBy { it.areaId },
)

/** The canonical payload text for [snapshot]. */
fun payloadOf(snapshot: PrayerConfigurationSnapshot): String = PrayerConfigurationWriter.toJson(snapshot)

/** The checksum a publisher would record for [payload]. */
fun checksumOf(payload: String): String = PrayerConfigurationChecksum.of(payload)

/** A manifest JSON array body, with the checksum taken over [snapshot]'s canonical payload by default. */
fun manifestJson(
    snapshot: PrayerConfigurationSnapshot = sampleSnapshot(),
    configurationId: String = snapshot.configurationId,
    revisionId: String = snapshot.revisionId,
    revisionNumber: Long = snapshot.revisionNumber,
    schema: Int = snapshot.schemaVersion,
    status: String = "published",
    checksum: String = checksumOf(payloadOf(snapshot)),
): String =
    """[{"configuration_id":"$configurationId","revision_id":"$revisionId",""" +
        """"revision_number":$revisionNumber,"schema_version":$schema,"status":"$status",""" +
        """"checksum":"$checksum","published_at":"2026-10-15T00:00:00Z"}]"""

/** An area reference for the synthetic dataset below. */
fun sampleAreaRef(
    divisionId: String = "BD10",
    districtId: String = "BD1001",
    upazilaId: String? = "BD1001001",
): BangladeshAreaRef = BangladeshAreaRef(
    divisionId = divisionId,
    divisionName = "Khulna",
    districtId = districtId,
    districtName = "Kushtia",
    upazilaId = upazilaId,
    upazilaName = upazilaId?.let { "Kumarkhali" },
)

/** A tiny but structurally valid dataset, so area references can be resolved in tests. */
fun prayerConfigDataset(): BangladeshLocationDataset = BangladeshLocationDataset.parse(
    version = "test-v1",
    areasText = "DIVISION\tBD10\tBD\tKhulna\tখুলনা\t22.8\t89.5\n" +
        "DISTRICT\tBD1001\tBD10\tKushtia\tকুষ্টিয়া\t23.9\t89.1\n" +
        "UPAZILA\tBD1001001\tBD1001\tKumarkhali\tকুমারখালী\t23.9\t89.2",
    boundaryText = "0\t22.0\t88.0\n0\t25.0\t88.0\n0\t25.0\t91.0",
)

/** An area override for [payloadOf]'s `areas` array. */
fun areaOverride(
    areaId: String,
    scope: PrayerConfigurationScope,
    method: PrayerCalculationMethod = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE,
    asrMethod: AsrCalculationMethod = AsrCalculationMethod.HANAFI,
    highLatitudeRule: HighLatitudeRule = HighLatitudeRule.AUTOMATIC,
    adjustments: Map<PrayerName, Int> = emptyMap(),
): PrayerAreaOverride = PrayerAreaOverride(
    areaId = areaId,
    scope = scope,
    profile = sampleProfile(method, asrMethod, highLatitudeRule, adjustments),
)
