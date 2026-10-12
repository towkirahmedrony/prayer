package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.testing.BundledDataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the **real** bundled dataset, loaded from `app/src/main/assets/location`.
 *
 * This is the strict validation gate that keeps a malformed shipped file from reaching a release: CI
 * runs the unit tests, and this test reads the actual bytes the app packages rather than a convenient
 * miniature. Runtime parsing stays lenient (a bad line is recorded, not thrown) so a partial asset does
 * not crash a first launch; the strictness lives here, where it fails the build instead.
 *
 * A single [integrityProblems] pass produces every structural failure at once, so a broken dataset is
 * reported in one run instead of one row at a time.
 */
class BundledDatasetIntegrityTest {

    private val directory: File = requireNotNull(BundledDataset.directory()) {
        "the bundled location directory could not be found"
    }
    private val areasText: String = File(directory, BundledDataset.AREAS_FILE).readText()
    private val boundaryText: String = File(directory, BundledDataset.BOUNDARY_FILE).readText()
    private val dataset: BangladeshLocationDataset = BundledDataset.load(version = "test-bundled")

    @Test
    fun `the bundled dataset has the documented coverage`() {
        assertEquals(8, dataset.divisions.size)
        assertEquals(64, dataset.districts.size)
        assertEquals(507, dataset.upazilas.size)
        assertTrue("the dataset must be usable", dataset.isUsable)
        assertEquals("test-bundled", dataset.version)
    }

    @Test
    fun `no line is rejected and no row disappears between the file and the parsed dataset`() {
        assertTrue(
            "the parser refused bundled lines: ${dataset.rejectedLines}",
            dataset.rejectedLines.isEmpty(),
        )

        // Counting non-comment data lines and comparing to the parsed areas catches a row that vanished
        // for a reason the parser does not record, such as a duplicate id collapsed by `distinctBy`.
        val areaDataLines = dataLineCount(areasText)
        assertEquals("area file has $areaDataLines data lines", 579, areaDataLines)
        assertEquals(areaDataLines, dataset.areas.size)
    }

    @Test
    fun `every id is unique and every parent resolves at the correct level`() {
        val ids = dataset.areas.map { it.id }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)

        val divisionIds = dataset.divisions.map { it.id }.toSet()
        val districtIds = dataset.districts.map { it.id }.toSet()

        val divisionsWithBadParent = dataset.divisions.filter { it.parentId != "BD" }
        assertTrue("divisions must be parented to the country: $divisionsWithBadParent", divisionsWithBadParent.isEmpty())
        assertTrue(
            "districts with an unknown division",
            dataset.districts.all { it.parentId in divisionIds },
        )
        assertTrue(
            "upazilas with an unknown district",
            dataset.upazilas.all { it.parentId in districtIds },
        )
    }

    @Test
    fun `every coordinate is numeric and inside the country's coarse bounds`() {
        val offenders = dataset.areas.filter { area ->
            val point = GeoPoint(area.latitude, area.longitude)
            !point.isFinite || !BangladeshLocationPolicy.isWithinCoarseBounds(point)
        }
        assertTrue("areas with an implausible coordinate: $offenders", offenders.isEmpty())
    }

    @Test
    fun `every representative centre is strictly inside the bundled boundary`() {
        val outside = dataset.areas.filter { area ->
            !GeoMath.isInsideAny(GeoPoint(area.latitude, area.longitude), dataset.boundaryRings)
        }
        assertTrue("centres outside the national boundary: $outside", outside.isEmpty())
    }

    @Test
    fun `the boundary parses into valid polygon rings`() {
        assertTrue("expected at least one boundary ring", dataset.boundaryRings.isNotEmpty())
        assertTrue(
            "every ring must have at least three points",
            dataset.boundaryRings.all { it.size >= 3 },
        )
        assertTrue(
            "every boundary point must be finite",
            dataset.boundaryRings.flatten().all { it.isFinite },
        )

        // Every ring index in the file must survive into the parsed rings; a ring silently dropped for
        // having too few points would otherwise make the boundary quietly smaller than the file.
        val ringIndices = boundaryRingIndices(boundaryText)
        assertEquals(ringIndices.size, dataset.boundaryRings.size)
        assertEquals(dataLineCount(boundaryText), dataset.boundaryRings.sumOf { it.size })
    }

    @Test
    fun `the repaired Shahrasti row keeps its identity, parent and coordinates`() {
        val shahrasti = requireNotNull(dataset.area("BD20130095")) { "the repaired row is missing" }

        assertEquals(BangladeshAreaLevel.UPAZILA, shahrasti.level)
        assertEquals("BD2013", shahrasti.parentId)
        assertEquals("Shahrasti", shahrasti.name)
        assertEquals(23.205879, shahrasti.latitude, 1e-9)
        assertEquals(90.956917, shahrasti.longitude, 1e-9)
        assertNotNull("the repaired row kept its Bangla name", shahrasti.banglaName)

        val parent = requireNotNull(dataset.area(shahrasti.parentId))
        assertEquals(BangladeshAreaLevel.DISTRICT, parent.level)
    }

    @Test
    fun `the strict validation pass reports no problem at all`() {
        val problems = integrityProblems()

        assertTrue("bundled dataset integrity problems:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    /** Every structural problem in the shipped files, so a failure lists them all at once. */
    private fun integrityProblems(): List<String> {
        val problems = mutableListOf<String>()

        if (dataset.rejectedLines.isNotEmpty()) {
            problems += "rejected lines: ${dataset.rejectedLines}"
        }
        val areaDataLines = dataLineCount(areasText)
        if (areaDataLines != dataset.areas.size) {
            problems += "area data lines ($areaDataLines) != parsed areas (${dataset.areas.size})"
        }
        if (dataset.divisions.size != 8) problems += "divisions=${dataset.divisions.size}, expected 8"
        if (dataset.districts.size != 64) problems += "districts=${dataset.districts.size}, expected 64"
        if (dataset.upazilas.size != 507) problems += "upazilas=${dataset.upazilas.size}, expected 507"

        val ids = dataset.areas.map { it.id }
        if (ids.size != ids.toSet().size) problems += "duplicate ids"
        if (dataset.divisions.any { it.parentId != "BD" }) problems += "a division is not parented to BD"
        val divisionIds = dataset.divisions.map { it.id }.toSet()
        val districtIds = dataset.districts.map { it.id }.toSet()
        if (dataset.districts.any { it.parentId !in divisionIds }) problems += "a district has an unknown division"
        if (dataset.upazilas.any { it.parentId !in districtIds }) problems += "an upazila has an unknown district"

        for (area in dataset.areas) {
            val point = GeoPoint(area.latitude, area.longitude)
            if (!point.isFinite || !BangladeshLocationPolicy.isWithinCoarseBounds(point)) {
                problems += "implausible coordinate for ${area.id} (${area.latitude}, ${area.longitude})"
            }
            if (!GeoMath.isInsideAny(point, dataset.boundaryRings)) {
                problems += "centre of ${area.id} is outside the boundary"
            }
        }

        if (dataset.boundaryRings.isEmpty()) problems += "no boundary rings"
        if (dataset.boundaryRings.any { it.size < 3 }) problems += "a boundary ring has fewer than three points"
        if (boundaryRingIndices(boundaryText).size != dataset.boundaryRings.size) {
            problems += "a boundary ring index in the file did not become a ring"
        }
        return problems
    }

    private fun dataLineCount(text: String): Int = text.lineSequence().count { line ->
        val trimmed = line.trim()
        trimmed.isNotEmpty() && !trimmed.startsWith("#")
    }

    private fun boundaryRingIndices(text: String): Set<Int> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { it.split('\t').firstOrNull()?.trim()?.toIntOrNull() }
        .toSet()
}
