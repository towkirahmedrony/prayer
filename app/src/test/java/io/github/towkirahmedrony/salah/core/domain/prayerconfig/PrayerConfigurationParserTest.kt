package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.testing.areaOverride
import io.github.towkirahmedrony.salah.testing.manifestJson
import io.github.towkirahmedrony.salah.testing.payloadOf
import io.github.towkirahmedrony.salah.testing.sampleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest and payload readers, against the exact text shape the app talks to.
 *
 * The point of these tests is that a manifest which is well-formed but *unusable* is reported as such
 * rather than as unreadable, and that every unsupported named value is refused explicitly instead of being
 * dropped — a silently dropped method would change the prayer times the publisher intended.
 */
class PrayerConfigurationParserTest {

    @Test
    fun `reads a well-formed manifest`() {
        val snapshot = sampleSnapshot(revisionId = "rev-0007", revisionNumber = 7)

        val result = PrayerConfigurationParser.parseManifestRestResponse(manifestJson(snapshot))

        assertTrue(result.toString(), result is ManifestParseResult.Parsed)
        val manifest = (result as ManifestParseResult.Parsed).manifest
        assertEquals("bd_prayer", manifest.configurationId)
        assertEquals("rev-0007", manifest.revisionId)
        assertEquals(7L, manifest.revisionNumber)
        assertEquals(1, manifest.schemaVersion)
        assertEquals(PrayerConfigurationStatus.PUBLISHED, manifest.status)
    }

    @Test
    fun `an empty publication slot is a valid empty result`() {
        val result = PrayerConfigurationParser.parseManifestRestResponse("[]")
        assertTrue(result.toString(), result is ManifestParseResult.NoPublication)
    }

    @Test
    fun `a body that is not json is malformed`() {
        val result = PrayerConfigurationParser.parseManifestRestResponse("<html>nope</html>")
        assertTrue(result.toString(), result is ManifestParseResult.Malformed)
    }

    @Test
    fun `an unknown status is malformed`() {
        val result = PrayerConfigurationParser.parseManifestRestResponse(
            manifestJson(status = "whenever"),
        )
        assertTrue(result.toString(), result is ManifestParseResult.Malformed)
    }

    @Test
    fun `a manifest missing a required field is malformed`() {
        val body = """[{"configuration_id":"bd_prayer","revision_id":"r","revision_number":1,"schema_version":1,"status":"published"}]"""
        val result = PrayerConfigurationParser.parseManifestRestResponse(body)
        assertTrue(result.toString(), result is ManifestParseResult.Malformed)
    }

    @Test
    fun `reads a well-formed payload`() {
        val snapshot = sampleSnapshot(
            method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE,
            asrMethod = AsrCalculationMethod.STANDARD,
            adjustments = mapOf(PrayerName.FAJR to 3),
            areaOverrides = listOf(areaOverride("BD10", PrayerConfigurationScope.DIVISION)),
        )

        val result = PrayerConfigurationParser.parseSnapshot(payloadOf(snapshot))

        assertTrue(result.toString(), result is SnapshotParseResult.Parsed)
        assertEquals(snapshot, (result as SnapshotParseResult.Parsed).snapshot)
    }

    @Test
    fun `an unsupported method is refused, not silently dropped`() {
        val raw = """{"configuration_id":"bd_prayer","revision_id":"r1","revision_number":1,""" +
            """"schema_version":1,"global":{"method":"MARS","asr_method":"HANAFI",""" +
            """"high_latitude_rule":"AUTOMATIC","adjustments":{}}}"""

        val result = PrayerConfigurationParser.parseSnapshot(raw)

        assertTrue(result.toString(), result is SnapshotParseResult.Invalid)
        val problems = (result as SnapshotParseResult.Invalid).problems
        assertTrue(problems.any { it is PrayerConfigProblem.UnsupportedValue })
    }

    @Test
    fun `an unsupported Asr convention is refused`() {
        val raw = """{"configuration_id":"bd_prayer","revision_id":"r1","revision_number":1,""" +
            """"schema_version":1,"global":{"method":"KARACHI","asr_method":"WHATEVER",""" +
            """"high_latitude_rule":"AUTOMATIC","adjustments":{}}}"""

        val result = PrayerConfigurationParser.parseSnapshot(raw)
        assertTrue(result.toString(), result is SnapshotParseResult.Invalid)
    }

    @Test
    fun `an adjustment outside the supported range is refused`() {
        val raw = """{"configuration_id":"bd_prayer","revision_id":"r1","revision_number":1,""" +
            """"schema_version":1,"global":{"method":"KARACHI","asr_method":"HANAFI",""" +
            """"high_latitude_rule":"AUTOMATIC","adjustments":{"FAJR":999}}}"""

        val result = PrayerConfigurationParser.parseSnapshot(raw)

        assertTrue(result.toString(), result is SnapshotParseResult.Invalid)
        assertTrue(
            (result as SnapshotParseResult.Invalid).problems.any {
                it is PrayerConfigProblem.AdjustmentOutOfRange
            },
        )
    }

    @Test
    fun `a payload missing the global default is malformed`() {
        val raw = """{"configuration_id":"bd_prayer","revision_id":"r1","revision_number":1,"schema_version":1}"""
        val result = PrayerConfigurationParser.parseSnapshot(raw)
        assertTrue(result.toString(), result is SnapshotParseResult.Malformed)
    }

    @Test
    fun `a payload that is not json is malformed`() {
        val result = PrayerConfigurationParser.parseSnapshot("not json at all")
        assertTrue(result.toString(), result is SnapshotParseResult.Malformed)
    }
}
