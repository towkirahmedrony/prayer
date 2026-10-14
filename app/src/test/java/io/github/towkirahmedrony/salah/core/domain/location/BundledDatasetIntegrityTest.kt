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
    private val localitiesText: String = File(directory, BundledDataset.LOCALITIES_FILE).readText()
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
    fun `the bundled locality layer is complete, joinable and inside the country`() {
        val dataLines = dataLineCount(localitiesText)
        assertEquals("locality file has $dataLines data lines", 1_216, dataLines)
        assertEquals(
            "a locality vanished between the file and the parsed layer",
            dataLines,
            dataset.localities.size,
        )
        assertTrue("the locality layer must be present", dataset.hasLocalities)

        // Every row joins to a district this build actually has: the file's district column is the
        // verified containment, and an unknown id there would make the row unreachable at runtime — the
        // parser refuses it, which is why the rejected-lines assertion above is the first line of defence.
        val districtIds = dataset.districts.map { it.id }.toSet()
        val orphaned = dataset.localities.filter { it.districtId !in districtIds }
        assertTrue("localities naming an unknown district: $orphaned", orphaned.isEmpty())

        // The names and coordinates are usable: no blanks, no two rows describing the same place in the
        // same district as the same kind, and every point inside the country the areas are checked against.
        val blankNames = dataset.localities.filter { it.name.isBlank() }
        assertTrue("localities with no name: $blankNames", blankNames.isEmpty())

        val keys = dataset.localities.map { Triple(it.districtId, it.kind, it.name) }
        assertEquals("duplicate localities", keys.size, keys.toSet().size)

        val implausible = dataset.localities.filter { locality ->
            val point = GeoPoint(locality.latitude, locality.longitude)
            !point.isFinite ||
                !BangladeshLocationPolicy.isWithinCoarseBounds(point) ||
                !GeoMath.isInsideAny(point, dataset.boundaryRings)
        }
        assertTrue("localities outside the country: $implausible", implausible.isEmpty())
    }

    @Test
    fun `the bundled locality layer covers the cities it exists for`() {
        // Coverage is urban by construction — the layer is the named places inside the twelve city
        // corporations and other towns — so this asserts the shape it must have, not an exact count.
        val byDistrict = dataset.localities.groupBy { it.districtId }
        val dhaka = requireNotNull(byDistrict["BD3026"]) { "the Dhaka district has no locality layer" }
        assertTrue("Dhaka must carry a real neighbourhood layer, had ${dhaka.size}", dhaka.size >= 500)
        assertTrue(
            "at least 25 districts carry localities, had ${byDistrict.size}",
            byDistrict.size >= 25,
        )

        // The names the whole feature exists for, present in the shipped file.
        val dhakaNames = dhaka.map { it.name }.toSet()
        for (expected in listOf("Mirpur", "Uttara", "Dhanmondi", "Gulshan", "Farmgate")) {
            assertTrue("$expected is missing from the Dhaka layer", expected in dhakaNames)
        }
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

        val localityDataLines = dataLineCount(localitiesText)
        if (localityDataLines != dataset.localities.size) {
            problems +=
                "locality data lines ($localityDataLines) != parsed localities (${dataset.localities.size})"
        }
        if (!dataset.hasLocalities) problems += "the bundled locality layer is empty"

        val localityIds = dataset.localities.map { Triple(it.districtId, it.kind, it.name) }
        if (localityIds.size != localityIds.toSet().size) problems += "duplicate localities"
        for (locality in dataset.localities) {
            if (locality.districtId !in districtIds) {
                problems += "locality ${locality.name} names an unknown district ${locality.districtId}"
            }
            if (locality.name.isBlank()) problems += "a locality in ${locality.districtId} has no name"
            val localityPoint = GeoPoint(locality.latitude, locality.longitude)
            if (!localityPoint.isFinite || !BangladeshLocationPolicy.isWithinCoarseBounds(localityPoint)) {
                problems += "implausible coordinate for locality ${locality.name}"
            }
            if (!GeoMath.isInsideAny(localityPoint, dataset.boundaryRings)) {
                problems += "locality ${locality.name} is outside the boundary"
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
