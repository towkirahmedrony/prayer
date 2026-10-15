package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stable-identity contract, as rules rather than as an aspiration.
 *
 * The property being protected is narrow and worth stating exactly: **an id keeps meaning the same
 * place.** A corrected spelling, a fixed coordinate or a reordered file must not change identity; a
 * removed id, a changed level or a changed parent must. Every test here is one half of that sentence.
 */
class DatasetIdentityTest {

    private fun identity(vararg areas: AreaIdentity): DatasetIdentity = DatasetIdentity.ofAreas(areas.toList())

    private fun area(
        id: String,
        level: BangladeshAreaLevel,
        parentId: String,
    ): AreaIdentity = AreaIdentity(id = id, parentId = parentId, level = level)

    // What must NOT count as a change -------------------------------------------------------------

    @Test
    fun `a renamed area keeps its identity`() {
        val before = dataset(areaLine("DISTRICT", "BD1001", "BD10", "Kushtia"))
        val after = dataset(areaLine("DISTRICT", "BD1001", "BD10", "Kushtia (corrected spelling)"))

        assertEquals(
            "a display-name correction must not be an identity change",
            DatasetIdentity.of(before),
            DatasetIdentity.of(after),
        )
        assertTrue(DatasetIdentity.of(after).changesAgainst(DatasetIdentity.of(before)).isEmpty())
    }

    @Test
    fun `a corrected coordinate keeps its identity`() {
        val before = dataset(areaLine("UPAZILA", "BD1001001", "BD1001", "Kumarkhali", 23.86, 89.24))
        val after = dataset(areaLine("UPAZILA", "BD1001001", "BD1001", "Kumarkhali", 23.90, 89.30))

        assertEquals(DatasetIdentity.of(before), DatasetIdentity.of(after))
    }

    @Test
    fun `a changed Bangla name keeps its identity`() {
        val before = dataset(areaLine("DISTRICT", "BD1001", "BD10", "Kushtia", bangla = "কুষ্টিয়া"))
        val after = dataset(areaLine("DISTRICT", "BD1001", "BD10", "Kushtia", bangla = "কুষ্টিয়া জেলা"))

        assertEquals(DatasetIdentity.of(before), DatasetIdentity.of(after))
    }

    @Test
    fun `reordering the areas keeps every identity`() {
        val ordered = dataset(
            areaLine("DIVISION", "BD10", "BD", "Khulna"),
            areaLine("DISTRICT", "BD1001", "BD10", "Kushtia"),
        )
        val reversed = dataset(
            areaLine("DISTRICT", "BD1001", "BD10", "Kushtia"),
            areaLine("DIVISION", "BD10", "BD", "Khulna"),
        )

        assertEquals(DatasetIdentity.of(ordered), DatasetIdentity.of(reversed))
    }

    // What MUST count as a change -----------------------------------------------------------------

    @Test
    fun `a removed id is a breaking change`() {
        val baseline = identity(area("BD10", BangladeshAreaLevel.DIVISION, "BD"))
        val candidate = identity()

        val changes = candidate.changesAgainst(baseline)

        assertEquals(1, changes.size)
        val change = changes.single()
        assertTrue(change is IdentityChange.Removed)
        assertTrue("removing an id breaks a stored reference", change.breaksExistingIdentity)
        assertEquals("BD10", change.id)

        val removal = change as IdentityChange.Removed
        assertEquals(BangladeshAreaLevel.DIVISION, removal.level)
        assertEquals("BD", removal.parentId)
    }

    @Test
    fun `a changed level is a breaking change`() {
        val baseline = identity(area("BD1001", BangladeshAreaLevel.DISTRICT, "BD10"))
        val candidate = identity(area("BD1001", BangladeshAreaLevel.UPAZILA, "BD10"))

        val change = candidate.changesAgainst(baseline).single()

        assertTrue(change is IdentityChange.LevelChanged)
        assertTrue(change.breaksExistingIdentity)

        val levelChange = change as IdentityChange.LevelChanged
        assertEquals(BangladeshAreaLevel.DISTRICT, levelChange.was)
        assertEquals(BangladeshAreaLevel.UPAZILA, levelChange.now)
    }

    @Test
    fun `a changed parent is a breaking change`() {
        val baseline = identity(area("BD1001001", BangladeshAreaLevel.UPAZILA, "BD1001"))
        val candidate = identity(area("BD1001001", BangladeshAreaLevel.UPAZILA, "BD1002"))

        val change = candidate.changesAgainst(baseline).single()

        assertTrue(change is IdentityChange.ParentChanged)
        assertTrue(change.breaksExistingIdentity)

        val parentChange = change as IdentityChange.ParentChanged
        assertEquals("BD1001", parentChange.was)
        assertEquals("BD1002", parentChange.now)
    }

    @Test
    fun `an added id is reported but is not a breaking change`() {
        val baseline = identity(area("BD10", BangladeshAreaLevel.DIVISION, "BD"))
        val candidate = identity(
            area("BD10", BangladeshAreaLevel.DIVISION, "BD"),
            area("BD20", BangladeshAreaLevel.DIVISION, "BD"),
        )

        val change = candidate.changesAgainst(baseline).single()

        assertTrue(change is IdentityChange.Added)
        assertFalse("adding a place invalidates nothing", change.breaksExistingIdentity)
        assertEquals("BD20", change.id)
    }

    @Test
    fun `reusing a retired id for a different place is reported as a change of the same id`() {
        // The id survives, so it is not "removed" — but it is no longer the same place, and only the
        // level/parent can reveal that. This is exactly the case a name-based check would miss.
        val baseline = identity(area("BD1001001", BangladeshAreaLevel.UPAZILA, "BD1001"))
        val candidate = identity(area("BD1001001", BangladeshAreaLevel.UPAZILA, "BD2001"))

        val change = candidate.changesAgainst(baseline).single()

        assertTrue(change is IdentityChange.ParentChanged)
        assertTrue(change.breaksExistingIdentity)
    }

    // Structural defects, answered without a baseline ----------------------------------------------

    @Test
    fun `a duplicate id is a defect`() {
        val defects = identity(
            area("BD10", BangladeshAreaLevel.DIVISION, "BD"),
            area("BD10", BangladeshAreaLevel.DIVISION, "BD"),
        ).defects()

        val duplicate = defects.single()
        assertTrue(duplicate is IdentityDefect.DuplicateId)
        assertEquals("BD10", duplicate.id)
        assertEquals(2, (duplicate as IdentityDefect.DuplicateId).occurrences)
    }

    @Test
    fun `an unresolvable parent is a defect`() {
        val defects = identity(area("BD1001", BangladeshAreaLevel.DISTRICT, "BD99")).defects()

        val unknown = defects.single()
        assertTrue(unknown is IdentityDefect.UnknownParent)
        assertEquals("BD99", (unknown as IdentityDefect.UnknownParent).parentId)
    }

    @Test
    fun `a parent at the wrong level is a defect`() {
        val defects = identity(
            area("BD10", BangladeshAreaLevel.DIVISION, "BD"),
            area("BD1001", BangladeshAreaLevel.DISTRICT, "BD10"),
            // An upazila whose parent is a division: the hierarchy would be one level short.
            area("BD1001001", BangladeshAreaLevel.UPAZILA, "BD10"),
        ).defects()

        val defect = defects.single()
        assertTrue(defect is IdentityDefect.WrongLevelParent)

        val wrongLevel = defect as IdentityDefect.WrongLevelParent
        assertEquals(BangladeshAreaLevel.DIVISION, wrongLevel.parentLevel)
        assertEquals(BangladeshAreaLevel.DISTRICT, wrongLevel.expected)
    }

    @Test
    fun `a top-level area parented to another area is a defect`() {
        val defects = identity(
            area("BD10", BangladeshAreaLevel.DIVISION, "BD"),
            area("BD20", BangladeshAreaLevel.DIVISION, "BD10"),
        ).defects()

        val wrong = defects.single()
        assertTrue(wrong is IdentityDefect.ParentIsAnArea)
        assertEquals("BD20", wrong.id)
    }

    @Test
    fun `the root is not required to be a particular id`() {
        // A later dataset with a different root must not be reported as broken for that reason alone.
        assertTrue(identity(area("BD10", BangladeshAreaLevel.DIVISION, "BGD")).defects().isEmpty())
    }

    // Serialisation ------------------------------------------------------------------------------

    @Test
    fun `the baseline text round-trips`() {
        val original = identity(
            area("BD1001", BangladeshAreaLevel.DISTRICT, "BD10"),
            area("BD10", BangladeshAreaLevel.DIVISION, "BD"),
            area("BD1001001", BangladeshAreaLevel.UPAZILA, "BD1001"),
        )

        val reparsed = DatasetIdentity.parse(original.toText())

        assertTrue("a clean baseline must parse without complaint", reparsed.malformedLines.isEmpty())
        assertEquals(original, reparsed)
        assertTrue(reparsed.changesAgainst(original).isEmpty())
    }

    @Test
    fun `the baseline text carries no names and no coordinates`() {
        val text = dataset(areaLine("DISTRICT", "BD1001", "BD10", "Kushtia", 23.90, 89.12)).let {
            DatasetIdentity.of(it).toText()
        }

        assertFalse("a name in the baseline would freeze a label", text.contains("Kushtia"))
        assertFalse("a coordinate in the baseline would freeze a position", text.contains("23.9"))
        assertTrue(text.contains("BD1001"))
    }

    @Test
    fun `a malformed baseline line is recorded rather than thrown`() {
        val parsed = DatasetIdentity.parse(
            """
            # a comment
            DIVISION	BD10	BD

            NOT_A_LEVEL	BD20	BD
            DISTRICT	BD1001
            DISTRICT	BD1002	BD10
            """.trimIndent(),
        )

        assertEquals(2, parsed.areas.size)
        assertEquals(2, parsed.malformedLines.size)
    }

    // Recognition --------------------------------------------------------------------------------

    @Test
    fun `recognises requires the id to be present at the expected level`() {
        val identity = identity(
            area("BD10", BangladeshAreaLevel.DIVISION, "BD"),
            area("BD1001", BangladeshAreaLevel.DISTRICT, "BD10"),
            area("BD1001001", BangladeshAreaLevel.UPAZILA, "BD1001"),
        )

        assertTrue(
            identity.recognises(
                BangladeshAreaRef(
                    divisionId = "BD10",
                    divisionName = "Khulna",
                    districtId = "BD1001",
                    districtName = "Kushtia",
                    upazilaId = "BD1001001",
                    upazilaName = "Kumarkhali",
                ),
            ),
        )

        // A district-level reference needs no upazila.
        assertTrue(
            identity.recognises(
                BangladeshAreaRef("BD10", "Khulna", "BD1001", "Kushtia"),
            ),
        )

        // The id exists but at the wrong level, so it is not the place the reference means.
        assertFalse(
            identity.recognises(
                BangladeshAreaRef("BD10", "Khulna", "BD1001001", "Kumarkhali"),
            ),
        )

        assertFalse(
            identity.recognises(
                BangladeshAreaRef("BD10", "Khulna", "BD1001", "Kushtia", "BD9999999", "Ghost"),
            ),
        )
    }

    @Test
    fun `an area lookup is null for an unknown or absent id`() {
        val identity = identity(area("BD10", BangladeshAreaLevel.DIVISION, "BD"))

        assertNotNull(identity.area("BD10"))
        assertNull(identity.area("BD9999"))
        assertNull(identity.area(null))
    }

    /** Parses a small dataset from `bd_areas.tsv` lines, with the minimum boundary to be usable. */
    private fun dataset(vararg areaLines: String): BangladeshLocationDataset =
        BangladeshLocationDataset.parse(
            version = "test",
            areasText = areaLines.joinToString("\n"),
            // A three-point ring, so the dataset parses; these tests are about identity, not geography.
            boundaryText = "0\t23.0\t89.0\n0\t23.0\t90.0\n0\t24.0\t89.0",
        )

    private fun areaLine(
        level: String,
        id: String,
        parentId: String,
        name: String,
        latitude: Double = 23.5,
        longitude: Double = 89.5,
        bangla: String = "",
    ): String = listOf(
        level,
        id,
        parentId,
        name,
        bangla,
        latitude.toString(),
        longitude.toString(),
    ).joinToString("\t")
}
