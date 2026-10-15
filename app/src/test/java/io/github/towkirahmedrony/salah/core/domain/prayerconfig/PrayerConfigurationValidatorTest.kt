package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.testing.areaOverride
import io.github.towkirahmedrony.salah.testing.checksumOf
import io.github.towkirahmedrony.salah.testing.payloadOf
import io.github.towkirahmedrony.salah.testing.prayerConfigDataset
import io.github.towkirahmedrony.salah.testing.sampleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance of a manifest and its snapshot.
 *
 * Every rejection is asserted to carry the *specific* problem that caused it, because "the app refused it"
 * without a reason is not a diagnosable outcome, and because a broad acceptance would be the failure mode
 * that actually changes a user's prayer times.
 */
class PrayerConfigurationValidatorTest {

    private val areaLevel: (String) -> BangladeshAreaLevel? =
        prayerConfigDataset().areas.associate { it.id to it.level }::get

    private fun manifestFor(snapshot: PrayerConfigurationSnapshot) = PrayerConfigurationManifest(
        configurationId = snapshot.configurationId,
        revisionId = snapshot.revisionId,
        revisionNumber = snapshot.revisionNumber,
        schemaVersion = snapshot.schemaVersion,
        status = PrayerConfigurationStatus.PUBLISHED,
        checksum = checksumOf(payloadOf(snapshot)),
    )

    // Manifest ---------------------------------------------------------------------------------

    @Test
    fun `a published manifest with a supported schema is accepted`() {
        val problems = PrayerConfigurationValidator.validateManifest(manifestFor(sampleSnapshot()), "bd_prayer")
        assertTrue(problems.toString(), problems.isEmpty())
    }

    @Test
    fun `an unsupported schema version is refused`() {
        val manifest = manifestFor(sampleSnapshot()).copy(schemaVersion = 2)
        val problems = PrayerConfigurationValidator.validateManifest(manifest, "bd_prayer")
        assertTrue(problems.any { it is PrayerConfigProblem.UnsupportedSchemaVersion })
    }

    @Test
    fun `an unpublished revision is refused`() {
        val manifest = manifestFor(sampleSnapshot()).copy(status = PrayerConfigurationStatus.DRAFT)
        val problems = PrayerConfigurationValidator.validateManifest(manifest, "bd_prayer")
        assertTrue(problems.any { it is PrayerConfigProblem.NotPublished })
    }

    @Test
    fun `a manifest for another slot is refused`() {
        val problems = PrayerConfigurationValidator.validateManifest(manifestFor(sampleSnapshot()), "another_slot")
        assertTrue(problems.any { it is PrayerConfigProblem.ConfigurationIdMismatch })
    }

    @Test
    fun `a checksum that is not a SHA-256 digest is refused`() {
        val manifest = manifestFor(sampleSnapshot()).copy(checksum = "not-a-checksum")
        val problems = PrayerConfigurationValidator.validateManifest(manifest, "bd_prayer")
        assertTrue(problems.any { it is PrayerConfigProblem.InvalidChecksum })
    }

    // Snapshot ---------------------------------------------------------------------------------

    @Test
    fun `a snapshot that agrees with its manifest is accepted`() {
        val snapshot = sampleSnapshot(
            areaOverrides = listOf(areaOverride("BD10", PrayerConfigurationScope.DIVISION)),
        )
        val validation = PrayerConfigurationValidator.validateSnapshot(snapshot, manifestFor(snapshot), areaLevel)

        assertTrue(validation.problems.toString(), validation.accepted)
        assertTrue(validation.droppedAssignments.isEmpty())
        assertEquals(snapshot, validation.snapshot)
    }

    @Test
    fun `an area reference the dataset does not contain is dropped and falls back`() {
        val snapshot = sampleSnapshot(
            areaOverrides = listOf(
                areaOverride("BD9999", PrayerConfigurationScope.DIVISION),
                areaOverride("BD10", PrayerConfigurationScope.DIVISION),
            ),
        )

        val validation = PrayerConfigurationValidator.validateSnapshot(snapshot, manifestFor(snapshot), areaLevel)

        assertTrue("the rest of a valid configuration still applies", validation.accepted)
        assertEquals(1, validation.droppedAssignments.size)
        assertTrue(validation.droppedAssignments.first() is PrayerConfigProblem.UnknownAreaReference)
        assertFalse(
            "the unknown assignment must not survive",
            validation.snapshot!!.areaOverrides.containsKey("BD9999"),
        )
        assertTrue(validation.snapshot!!.areaOverrides.containsKey("BD10"))
    }

    @Test
    fun `an area reference at the wrong level is dropped`() {
        // BD1001 is a district; a DIVISION override for it is not applicable to a district and must be
        // dropped rather than applied as if the level were right.
        val snapshot = sampleSnapshot(
            areaOverrides = listOf(areaOverride("BD1001", PrayerConfigurationScope.DIVISION)),
        )

        val validation = PrayerConfigurationValidator.validateSnapshot(snapshot, manifestFor(snapshot), areaLevel)

        assertEquals(1, validation.droppedAssignments.size)
        assertTrue(validation.snapshot!!.areaOverrides.isEmpty())
    }

    @Test
    fun `a payload whose revision differs from the manifest is refused`() {
        val snapshot = sampleSnapshot(revisionId = "rev-0003")
        val manifest = manifestFor(snapshot).copy(revisionId = "rev-0002", revisionNumber = 2)

        val validation = PrayerConfigurationValidator.validateSnapshot(snapshot, manifest, areaLevel)

        assertFalse(validation.accepted)
        assertTrue(validation.problems.isNotEmpty())
    }
}
