package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled-format parser: what it accepts, what it refuses, and what it does with a refusal.
 *
 * The parser is deliberately lenient at runtime — a bad line is recorded rather than thrown — so
 * `BangladeshLocationDatasetTest` pins exactly which lines are refused, and the separate
 * `BundledDatasetIntegrityTest` pins that the real shipped files contain none.
 */
class BangladeshLocationDatasetTest {

    @Test
    fun `a well-formed file parses into the three levels with their parents`() {
        val dataset = parse(areas = SAMPLE_AREAS, boundary = SQUARE_BOUNDARY)

        assertEquals("test", dataset.version)
        assertEquals(1, dataset.divisions.size)
        assertEquals(1, dataset.districts.size)
        assertEquals(2, dataset.upazilas.size)
        assertTrue(dataset.isUsable)
        assertTrue(dataset.rejectedLines.isEmpty())

        val kushtia = requireNotNull(dataset.area("BD1001"))
        assertEquals(BangladeshAreaLevel.DISTRICT, kushtia.level)
        assertEquals("BD10", kushtia.parentId)
        assertEquals("Kushtia", kushtia.name)
        assertEquals("কুষ্টিয়া", kushtia.banglaName)

        assertEquals(listOf("BD1001"), dataset.districtsOf("BD10").map { it.id })
        assertEquals(listOf("BD1001002", "BD1001001"), dataset.upazilasOf("BD1001").map { it.id })
        assertEquals(listOf("BD1001"), dataset.childrenOf("BD10").map { it.id })
    }

    @Test
    fun `a blank Bangla name is kept as null rather than invented`() {
        val dataset = parse(areas = SAMPLE_AREAS, boundary = SQUARE_BOUNDARY)

        val bheramara = requireNotNull(dataset.area("BD1001002"))
        assertNull(bheramara.banglaName)
        assertEquals("Bheramara", bheramara.labelledName)
    }

    @Test
    fun `a row with the wrong number of columns is rejected and recorded`() {
        // The real defect this format once shipped with: an extra tab producing eight fields.
        val dataset = parse(
            areas = row("DIVISION", "BD10", "BD", "Khulna", "খুলনা", "22.80", "89.30") + "\n" +
                "DISTRICT\tBD1001\tBD10\tKushtia\tকুষ্টিয়া\t\t23.90\t89.12",
            boundary = SQUARE_BOUNDARY,
        )

        assertNull(dataset.area("BD1001"))
        assertEquals(1, dataset.rejectedLines.size)
        assertTrue(dataset.rejectedLines.single().contains("BD1001"))
        assertEquals(1, dataset.divisions.size)
    }

    @Test
    fun `a row with a non-numeric coordinate is rejected`() {
        val dataset = parse(
            areas = row("DIVISION", "BD10", "BD", "Khulna", "", "not-a-number", "89.30"),
            boundary = SQUARE_BOUNDARY,
        )

        assertTrue(dataset.divisions.isEmpty())
        assertEquals(1, dataset.rejectedLines.size)
    }

    @Test
    fun `a row with an out-of-range coordinate is rejected`() {
        val dataset = parse(
            areas = row("DIVISION", "BD10", "BD", "Khulna", "", "91.0", "89.30"),
            boundary = SQUARE_BOUNDARY,
        )

        assertTrue(dataset.divisions.isEmpty())
        assertEquals(1, dataset.rejectedLines.size)
    }

    @Test
    fun `a row with an unknown level is rejected`() {
        val dataset = parse(
            areas = row("WARD", "BD10", "BD", "Something", "", "22.80", "89.30"),
            boundary = SQUARE_BOUNDARY,
        )

        assertTrue(dataset.areas.isEmpty())
        assertEquals(1, dataset.rejectedLines.size)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val dataset = parse(areas = "# a comment\n\n" + SAMPLE_AREAS, boundary = SQUARE_BOUNDARY)

        assertEquals(4, dataset.areas.size)
        assertTrue(dataset.rejectedLines.isEmpty())
    }

    @Test
    fun `duplicate ids are collapsed, not silently multiplied`() {
        val dataset = parse(
            areas = SAMPLE_AREAS + "\n" + row("UPAZILA", "BD1001001", "BD1001", "Kumarkhali again", "", "23.86", "89.24"),
            boundary = SQUARE_BOUNDARY,
        )

        assertEquals(1, dataset.upazilas.count { it.id == "BD1001001" })
        assertEquals(4, dataset.areas.size)
    }

    @Test
    fun `an empty area file yields an unusable dataset rather than an empty picker`() {
        val dataset = parse(areas = "", boundary = SQUARE_BOUNDARY)

        assertTrue(dataset.areas.isEmpty())
        assertFalse(dataset.isUsable)
    }

    @Test
    fun `a dataset without a boundary is not usable`() {
        val dataset = parse(areas = SAMPLE_AREAS, boundary = "")

        assertTrue(dataset.divisions.isNotEmpty())
        assertTrue(dataset.boundaryRings.isEmpty())
        assertFalse("a dataset that cannot enforce the country policy is unusable", dataset.isUsable)
    }

    @Test
    fun `boundary rings are grouped by index and a ring too small to enclose is dropped`() {
        val dataset = parse(
            areas = SAMPLE_AREAS,
            boundary = listOf(
                "0\t22.0\t88.5",
                "0\t22.0\t95.0",
                "0\t26.0\t95.0",
                "0\t26.0\t88.5",
                "1\t24.0\t89.0",
                "1\t24.1\t89.1",
                "2\t10.0\t10.0",
                "2\t10.0\t11.0",
                "2\t11.0\t11.0",
            ).joinToString("\n"),
        )

        assertEquals(2, dataset.boundaryRings.size)
        assertEquals(4, dataset.boundaryRings[0].size)
        assertEquals(3, dataset.boundaryRings[1].size)
    }

    @Test
    fun `a malformed boundary line is rejected and recorded`() {
        val dataset = parse(
            areas = SAMPLE_AREAS,
            boundary = "0\t22.0\t88.5\n0\tnot-a-number\t90.0\n0\t22.0\t90.0\n0\t25.0\t90.0\n0\t25.0\t88.5",
        )

        assertEquals(1, dataset.rejectedLines.size)
        // The four well-formed points are still a ring, so the boundary survives a single bad line.
        assertEquals(1, dataset.boundaryRings.size)
        assertEquals(4, dataset.boundaryRings.single().size)
    }

    @Test
    fun `referenceOf builds the full chain from the ids`() {
        val dataset = parse(areas = SAMPLE_AREAS, boundary = SQUARE_BOUNDARY)

        val reference = requireNotNull(dataset.referenceOf("BD1001001"))
        assertEquals("BD10", reference.divisionId)
        assertEquals("Khulna", reference.divisionName)
        assertEquals("BD1001", reference.districtId)
        assertEquals("Kushtia", reference.districtName)
        assertEquals("BD1001001", reference.upazilaId)
        assertEquals("Kumarkhali", reference.upazilaName)

        assertNull("an unknown id has no reference", dataset.referenceOf("missing"))
    }

    @Test
    fun `referenceOf returns null when a level is missing from the chain`() {
        val dataset = parse(
            areas = row("UPAZILA", "BD1001001", "BD1001", "Orphan", "", "23.86", "89.24"),
            boundary = SQUARE_BOUNDARY,
        )

        assertNull(dataset.referenceOf("BD1001001"))
    }

    @Test
    fun `recognises checks the level of every id`() {
        val dataset = parse(areas = SAMPLE_AREAS, boundary = SQUARE_BOUNDARY)

        val valid = BangladeshAreaRef(
            divisionId = "BD10",
            divisionName = "Khulna",
            districtId = "BD1001",
            districtName = "Kushtia",
            upazilaId = "BD1001001",
            upazilaName = "Kumarkhali",
        )
        assertTrue(dataset.recognises(valid))

        // A district id used as a division: present, but at the wrong level.
        val wrongLevel = valid.copy(divisionId = "BD1001", upazilaId = null)
        assertFalse(dataset.recognises(wrongLevel))

        val absentUpazila = valid.copy(upazilaId = "BD9999999")
        assertFalse(dataset.recognises(absentUpazila))

        // An area resolved only as far as a district is still recognised.
        assertTrue(dataset.recognises(valid.copy(upazilaId = null, upazilaName = null)))
    }

    @Test
    fun `an area that is present but at the wrong level is not returned by a level-specific lookup`() {
        val dataset = parse(areas = SAMPLE_AREAS, boundary = SQUARE_BOUNDARY)

        assertNotNull(dataset.area("BD1001"))
        assertTrue(dataset.upazilasOf("BD10").isEmpty())
        assertTrue(dataset.districtsOf("BD1001").isEmpty())
    }

    // --- The locality layer -----------------------------------------------------

    @Test
    fun `a locality row is parsed with the district that contains it and the kind of place it is`() {
        val dataset = parse(
            areas = SAMPLE_AREAS,
            boundary = SQUARE_BOUNDARY,
            localities = LOCALITIES,
        )

        assertTrue(dataset.rejectedLines.isEmpty())
        assertEquals(2, dataset.localities.size)
        assertTrue(dataset.hasLocalities)

        val centre = requireNotNull(dataset.localities.firstOrNull { it.name == "Centre Bazar" })
        assertEquals("BD1001", centre.districtId)
        assertEquals(BangladeshLocalityKind.QUARTER, centre.kind)
        assertEquals(23.86, centre.latitude, 1e-9)
        assertEquals(89.24, centre.longitude, 1e-9)

        assertEquals(listOf("Centre Bazar", "Nearby Para"), dataset.localitiesOf("BD1001").map { it.name })
        assertTrue("another district has no rows", dataset.localitiesOf("BD1002").isEmpty())
        assertTrue("an unknown district has no rows", dataset.localitiesOf("BD9999").isEmpty())
        assertTrue("a missing district has no rows", dataset.localitiesOf(null).isEmpty())
    }

    @Test
    fun `a locality row naming a district the dataset does not define is rejected`() {
        // It could never be verified against a resolved position, so it is refused rather than kept as an
        // entry no lookup can reach.
        val dataset = parse(
            areas = SAMPLE_AREAS,
            boundary = SQUARE_BOUNDARY,
            localities = row("SUBURB", "BD9999", "Elsewhere", "23.86", "89.24"),
        )

        assertTrue(dataset.localities.isEmpty())
        assertEquals(1, dataset.rejectedLines.size)
    }

    @Test
    fun `a locality row with an unknown kind, a blank name or a bad coordinate is rejected`() {
        val dataset = parse(
            areas = SAMPLE_AREAS,
            boundary = SQUARE_BOUNDARY,
            localities = listOf(
                row("WARD", "BD1001", "Unknown Kind", "23.86", "89.24"),
                row("QUARTER", "BD1001", "", "23.86", "89.24"),
                row("QUARTER", "BD1001", "Bad Coordinate", "north", "89.24"),
                row("QUARTER", "BD1001", "Out Of Range", "91.0", "89.24"),
                row("QUARTER", "BD1001", "Too Few Columns", "23.86"),
            ).joinToString("\n"),
        )

        assertTrue(dataset.localities.isEmpty())
        assertEquals(5, dataset.rejectedLines.size)
    }

    @Test
    fun `a locality repeated for the same district and kind is collapsed`() {
        val dataset = parse(
            areas = SAMPLE_AREAS,
            boundary = SQUARE_BOUNDARY,
            localities = listOf(
                row("QUARTER", "BD1001", "Centre Bazar", "23.86", "89.24"),
                row("QUARTER", "BD1001", "Centre Bazar", "23.861", "89.241"),
            ).joinToString("\n"),
        )

        assertEquals(1, dataset.localities.size)
        assertTrue("the duplicate is a collapse, not a refusal", dataset.rejectedLines.isEmpty())
    }

    @Test
    fun `a locality file with only comments yields no rows and a still-usable dataset`() {
        val dataset = parse(
            areas = SAMPLE_AREAS,
            boundary = SQUARE_BOUNDARY,
            localities = "# the locality layer was not generated for this build\n\n",
        )

        assertTrue(dataset.localities.isEmpty())
        assertTrue("the locality layer is optional, so the dataset stays usable", dataset.isUsable)
        assertTrue(dataset.rejectedLines.isEmpty())
    }

    private fun parse(
        areas: String,
        boundary: String,
        localities: String = "",
    ): BangladeshLocationDataset = BangladeshLocationDataset.parse(
        version = "test",
        areasText = areas,
        boundaryText = boundary,
        localitiesText = localities,
    )

    private fun row(vararg fields: String): String = fields.joinToString("\t")

    private companion object {
        val SQUARE_BOUNDARY: String = listOf(
            "0\t22.0\t88.5",
            "0\t22.0\t90.5",
            "0\t26.0\t90.5",
            "0\t26.0\t88.5",
        ).joinToString("\n")

        /** Two named places in Kushtia, in the bundled locality format. */
        val LOCALITIES: String = listOf(
            "QUARTER\tBD1001\tCentre Bazar\t23.860\t89.240",
            "NEIGHBOURHOOD\tBD1001\tNearby Para\t23.868\t89.240",
        ).joinToString("\n")

        val SAMPLE_AREAS: String = listOf(
            "DIVISION\tBD10\tBD\tKhulna\tখুলনা\t22.80\t89.30",
            "DISTRICT\tBD1001\tBD10\tKushtia\tকুষ্টিয়া\t23.90\t89.12",
            "UPAZILA\tBD1001001\tBD1001\tKumarkhali\tকুমারখালী\t23.86\t89.24",
            "UPAZILA\tBD1001002\tBD1001\tBheramara\t\t23.99\t88.97",
        ).joinToString("\n")
    }
}
