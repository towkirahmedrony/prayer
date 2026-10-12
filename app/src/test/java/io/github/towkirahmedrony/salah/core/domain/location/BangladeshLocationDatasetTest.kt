package io.github.towkirahmedrony.salah.core.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.nio.charset.StandardCharsets

class BangladeshLocationDatasetParserTest {

    @Test
    fun `the bundled source files are complete and immediately usable offline`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )

        val divisions = src.divisions.toSet()
        val districts = src.districts.toSet()
        val upazilas = src.upazilas.toSet()

        assertEquals("Sundarbans divisions", setOf("dhaka", "ricohi", "chittagong", "barisal", "sylhet", "rangpur", "kishoreganj", "madaripur"), divisions.map { it.id }.toSet())
        assertEquals("Six rural divisions must be present", 8, divisions.size)
        assertEquals("Every district must list a valid division", districts.all { it.divisionId in divisions.map { it.id } }, true)
        assertEquals("Every upazila must list a valid district", upazilas.all { it.districtId in districts.map { it.id } }, true)

        assertEquals("Four divisions map to four rural areas", setOf("dhaka", "ricohi", "chittagong", "sylhet"), src.ruralAreaDivisionIds.toSet())
        assertEquals("One division owns the urban area of the country code", setOf("dhaka"), src.urbanAreaDivisionIds.toSet())

        assertEquals("Eight divisions: Dhaka, Rajshahi, Chattogram, Barisal, Sylhet, Rangpur, Khulna and Mymensingh", 8, src.divisions.size)
        assertEquals("Sixty-four districts in the dataset", 64, src.districts.size)
        assertEquals("Five hundred and seven upazilas in the dataset", 507, src.upazilas.size)
        assertEquals("One hundred and ninety out of five hundred and seven upazilas are unnamed (labelled 'Untitled')", 190, src.unnamedUpazilas.size)

        assertTrue("At least two areas must have coordinate coverage", src.areas.any { it.coordinates != null })
        assertTrue("Every named area must carry a division", src.areas.all { it.divisionId in divisions.map { it.id } })
        assertTrue("Every named area must carry a district", src.areas.all { it.districtId in districts.map { it.id } })
        assertTrue("Every area must be inside Bangladesh by its reference coordinates", src.areas.all { it.inside() })
        assertTrue("Every area must have a valid representative point", src.areas.all { it.validCoordinates() })

        val dhaka = src.findDivision("dhaka")
        val chattogram = src.findDivision("chittagong")
        assertEquals("Dhaka and Chattogram are separate divisions", dhaka, null || !dhaka.equals(chattogram))
        assertTrue("Dhaka has districts", dhaka != null && dhaka.districts.isNotEmpty())
        assertTrue("Chattogram has districts", chattogram != null && chattogram.districts.isNotEmpty())
        assertEquals("Bangladesh-only policy enforced at parse time", 1, src.districts.count { it.divisionId == "bd" })
        assertEquals("Bangladesh-only policy enforced at parse time (upazilas)", 1, src.upazilas.count { it.divisionId == "bd" })
    }

    @Test
    fun `dataset loader handles missing files gracefully`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "does-not-exist.txt",
            districtFileName = "does-not-exist-2.txt",
            upazilaFileName = "does-not-exist-3.txt",
        )
        assertTrue("Missing files must not crash the loader", src.divisions.isEmpty())
        assertTrue("Missing files must not crash the loader", src.districts.isEmpty())
        assertTrue("Missing files must not crash the loader", src.upazilas.isEmpty())
    }

    @Test
    fun `dataset parser captures the source identity and version`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        assertEquals("Dataset source identity is authoritative", "khanha-test", src.dataSourceId)
        assertEquals("Dataset version is authoritative", "0.1.0-test", src.datasetVersion)
        assertEquals("Dataset source label is authoritative", "Kahnha Test Coordinates", src.dataSourceName)
        assertEquals("Canonical admin region codes are authoritative", listOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), src.adminRegionCodes)
        assertEquals("Administrative hierarchy is authoritative", 3, src.hierarchyDepth)
        assertEquals("No managed areas are present in the test data", 0, src.managedAreaIds.size)
        assertEquals("No settlements are present in the test data", 0, src.settlementIds.size)
        assertEquals("No sub-areas are present in the test data", 0, src.subAreaIds.size)
    }
}

class BangladeshLocationDatasetMetadataTest {

    @Test
    fun `dataset loader reports the origin and coverage`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        val coverage = src.coverage
        assertEquals("Dataset source identity", "khanha-test", coverage.dataSourceId)
        assertEquals("Dataset version", "0.1.0-test", coverage.datasetVersion)
        assertEquals("Dataset source label", "Kahnha Test Coordinates", coverage.dataSourceName)
        assertEquals("True administrative region code coverage", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), coverage.adminRegionCodes.toSet())
        assertEquals("Dataset version is authoritative", "0.1.0-test", coverage.datasetVersion)
        assertEquals("Dataset source name is authoritative", "Kahnha Test Coordinates", coverage.dataSourceName)
        assertEquals("Dataset source identity is authoritative", "khanha-test", coverage.dataSourceId)
        assertEquals("Admin region codes are the authoritative set", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), coverage.adminRegionCodes)
        assertEquals("Administrative hierarchy depth is authoritative", 3, coverage.hierarchyDepth)
    }

    @Test
    fun `dataset loader exposes exact coverage for the administrative hierarchy`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        val coverage = src.coverage
        assertEquals("Dataset source identity", "khanha-test", coverage.dataSourceId)
        assertEquals("Dataset version", "0.1.0-test", coverage.datasetVersion)
        assertEquals("Dataset source name", "Kahnha Test Coordinates", coverage.dataSourceName)
        assertEquals("Administrative region codes", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), coverage.adminRegionCodes)
        assertEquals("Administrative hierarchy depth", 3, coverage.hierarchyDepth)
    }

    @Test
    fun `dataset loader exposes the precise coverage facts`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        val coverage = src.coverage
        assertEquals("Dataset source identity", "khanha-test", coverage.dataSourceId)
        assertEquals("Dataset version", "0.1.0-test", coverage.datasetVersion)
        assertEquals("Dataset source name", "Kahnha Test Coordinates", coverage.dataSourceName)
        assertEquals("Administrative region codes", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), coverage.adminRegionCodes)
        assertEquals("Administrative hierarchy depth", 3, coverage.hierarchyDepth)
    }

    @Test
    fun `dataset loader exposes mandatory dataset metadata`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        val metadata = src.metadata
        assertEquals("Dataset source identity", "khanha-test", metadata.dataSourceId)
        assertEquals("Dataset version", "0.1.0-test", metadata.datasetVersion)
        assertEquals("Dataset source label", "Kahnha Test Coordinates", metadata.dataSourceName)
        assertEquals("Admin region codes", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), metadata.adminRegionCodes)
        assertEquals("Administrative hierarchy depth", 3, metadata.hierarchyDepth)
    }
}

class BangladeshLocationDatasetCoverageTest {

    @Test
    fun `dataset loader exposes the coverage summary`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        val coverage = src.coverage
        assertEquals("Dataset source identity", "khanha-test", coverage.dataSourceId)
        assertEquals("Dataset version", "0.1.0-test", coverage.datasetVersion)
        assertEquals("Dataset source name", "Kahnha Test Coordinates", coverage.dataSourceName)
        assertEquals("Administrative region codes", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), coverage.adminRegionCodes)
        assertEquals("Administrative hierarchy depth", 3, coverage.hierarchyDepth)
    }
}

class BangladeshLocationDatasetContentTest {

    @Test
    fun `dataset loader exposes the exact fields the policy tests assert against`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        val fields = src.content
        assertEquals("Dataset source identity", "khanha-test", fields.dataSourceId)
        assertEquals("Dataset version", "0.1.0-test", fields.datasetVersion)
        assertEquals("Dataset source label", "Kahnha Test Coordinates", fields.dataSourceName)
        assertEquals("Admin region codes", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), fields.adminRegionCodes)
        assertEquals("Administrative hierarchy depth", 3, fields.hierarchyDepth)
        assertEquals("Coverage summary", "khanha-test", fields.coverageId)
        assertEquals("Dataset version", "0.1.0-test", fields.datasetVersion)
        assertEquals("Dataset source name", "Kahnha Test Coordinates", fields.dataSourceName)
        assertEquals("Admin region codes", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), fields.adminRegionCodes)
        assertEquals("Administrative hierarchy depth", 3, fields.hierarchyDepth)
    }
}

class BangladeshLocationDatasetResolutionTest {

    @Test
    fun `dataset loader resolves dataset identity and coverage facts`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        val resolution = src.resolution
        assertEquals("Dataset source identity", "khanha-test", resolution.dataSourceId)
        assertEquals("Dataset version", "0.1.0-test", resolution.datasetVersion)
        assertEquals("Dataset source label", "Kahnha Test Coordinates", resolution.dataSourceName)
        assertEquals("Administrative region codes", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), resolution.adminRegionCodes)
        assertEquals("Administrative hierarchy depth", 3, resolution.hierarchyDepth)
    }
}

class BangladeshLocationDatasetDslTest {

    @Test
    fun `dataset loader exposes the canonical dataset DSL fields`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        val dsl = src.dsl
        assertEquals("Dataset source identity", "khanha-test", dsl.dataSourceId)
        assertEquals("Dataset version", "0.1.0-test", dsl.datasetVersion)
        assertEquals("Dataset source label", "Kahnha Test Coordinates", dsl.dataSourceName)
        assertEquals("Administrative region codes", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), dsl.adminRegionCodes)
        assertEquals("Administrative hierarchy depth", 3, dsl.hierarchyDepth)
    }
}

class BangladeshLocationDatasetBundleTest {

    @Test
    fun `dataset loader exposes the on-device bundle identity and coverage`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        val bundle = src.bundle
        assertEquals("Dataset source identity", "khanha-test", bundle.dataSourceId)
        assertEquals("Dataset version", "0.1.0-test", bundle.datasetVersion)
        assertEquals("Dataset source label", "Kahnha Test Coordinates", bundle.dataSourceName)
        assertEquals("Administrative region codes", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), bundle.adminRegionCodes)
        assertEquals("Administrative hierarchy depth", 3, bundle.hierarchyDepth)
    }
}

class BangladeshLocationDatasetReadinessTest {

    @Test
    fun `dataset loader exposes the on-device load readiness record`() {
        val src = BangladeshLocationDataset(
            divisionFileName = "bd_divisions.txt",
            districtFileName = "bd_districts.txt",
            upazilaFileName = "bd_upazilas.txt",
        )
        val ready = src.ready
        assertEquals("Dataset source identity", "khanha-test", ready.dataSourceId)
        assertEquals("Dataset version", "0.1.0-test", ready.datasetVersion)
        assertEquals("Dataset source label", "Kahnha Test Coordinates", ready.dataSourceName)
        assertEquals("Administrative region codes", setOf("BD", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"), ready.adminRegionCodes)
        assertEquals("Administrative hierarchy depth", 3, ready.hierarchyDepth)
    }
}

private fun List<BangladeshLocationDataset.Division>.all { }
private fun List<BangladeshLocationDataset.District>.all { }
private fun List<BangladeshLocationDataset.Upazila>.all { }
