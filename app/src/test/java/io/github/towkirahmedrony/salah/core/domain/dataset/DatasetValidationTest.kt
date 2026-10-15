package io.github.towkirahmedrony.salah.core.domain.dataset

import io.github.towkirahmedrony.salah.core.domain.location.DatasetIdentity
import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The candidate validation seam.
 *
 * Every rejection path here is one that must never silently pass, so each is asserted directly rather
 * than assumed from the happy path. The two properties that matter most:
 *
 *  - a payload broken *on its own terms* (bad integrity, unusable, structurally wrong) is rejected
 *    without needing to know anything about the active dataset; and
 *  - a payload that is internally fine but would harm *this user* — by breaking an identity the active
 *    dataset has, or by removing the area their saved location depends on — is rejected too.
 */
class DatasetValidationTest {

    // A payload that passes everything, used as the control. Changing one thing at a time from here is
    // what makes each failing assertion mean something specific.
    private val validAreas = listOf(
        areaLine("DIVISION", "BD10", "BD", "Khulna"),
        areaLine("DISTRICT", "BD1001", "BD10", "Kushtia"),
        areaLine("UPAZILA", "BD1001001", "BD1001", "Kumarkhali"),
    ).joinToString("\n")

    private val validBoundary = listOf(
        "0\t22.0\t88.0",
        "0\t25.0\t88.0",
        "0\t25.0\t91.0",
        "0\t22.0\t91.0",
    ).joinToString("\n")

    private fun payload(
        version: String = "v2",
        areas: String = validAreas,
        boundary: String = validBoundary,
        localities: String = "",
    ): DatasetPayload = DatasetPayload(
        version = DatasetVersion.of(version),
        areasText = areas,
        boundaryText = boundary,
        localitiesText = localities,
    )

    private val activeDataset: BangladeshLocationDataset = payload().toDataset()
    private val activeIdentity: DatasetIdentity = DatasetIdentity.of(activeDataset)

    private val storedArea = BangladeshAreaRef(
        divisionId = "BD10",
        divisionName = "Khulna",
        districtId = "BD1001",
        districtName = "Kushtia",
        upazilaId = "BD1001001",
        upazilaName = "Kumarkhali",
    )

    private fun codes(problems: List<DatasetProblem>): List<String> = problems.map { it.code }

    // The control ------------------------------------------------------------------------------

    @Test
    fun `a well-formed payload is accepted`() {
        val problems = DatasetValidator.validate(payload())

        assertTrue("expected no problems, got ${codes(problems)}", DatasetValidator.accepts(problems))
    }

    @Test
    fun `a well-formed payload is accepted even against an active dataset and a stored location`() {
        val problems = DatasetValidator.validate(
            payload = payload(),
            context = DatasetValidationContext(
                activeIdentity = activeIdentity,
                storedArea = storedArea,
            ),
        )

        assertTrue("expected no problems, got ${codes(problems)}", problems.isEmpty())
    }

    // Broken on its own terms -------------------------------------------------------------------

    @Test
    fun `a payload that names no version is rejected before anything else`() {
        val problems = DatasetValidator.validate(payload(version = "   "))

        assertEquals(listOf("blank_version"), codes(problems))
    }

    @Test
    fun `a checksum mismatch is the only problem reported`() {
        val problems = DatasetValidator.validate(
            payload = payload(),
            context = DatasetValidationContext(expectedChecksum = "not-the-real-checksum"),
        )

        // Integrity failed, so the contents cannot be trusted to mean anything and must not be
        // interpreted — reporting a second, derived problem would only be reporting on corruption.
        assertEquals(listOf("checksum_mismatch"), codes(problems))
    }

    @Test
    fun `a payload whose checksum matches is accepted`() {
        val candidate = payload()

        val problems = DatasetValidator.validate(
            payload = candidate,
            context = DatasetValidationContext(expectedChecksum = candidate.checksum()),
        )

        assertTrue(codes(problems).toString(), problems.isEmpty())
    }

    @Test
    fun `a payload with no areas is rejected as unusable`() {
        val problems = DatasetValidator.validate(
            payload = payload(areas = "DIVISION\tBD10\tBD\tKhulna\t\t22.8\t89.3"),
        )

        assertEquals(listOf("not_usable"), codes(problems))
    }

    @Test
    fun `a payload with no boundary is rejected as unusable`() {
        // A dataset without a boundary could not enforce the Bangladesh-only policy, so it is unusable
        // rather than merely incomplete.
        val problems = DatasetValidator.validate(payload(boundary = ""))

        assertEquals(listOf("not_usable"), codes(problems))
    }

    @Test
    fun `a payload with a line the parser refused is rejected`() {
        val problems = DatasetValidator.validate(
            payload = payload(areas = validAreas + "\nDISTRICT\tBD1002\tBD10"),
        )

        assertEquals(listOf("rejected_lines"), codes(problems))
    }

    @Test
    fun `tolerating a refused line is possible but must be asked for explicitly`() {
        val candidate = payload(areas = validAreas + "\nDISTRICT\tBD1002\tBD10")

        val problems = DatasetValidator.validate(
            payload = candidate,
            context = DatasetValidationContext(maxRejectedLines = 1),
        )

        assertTrue(codes(problems).toString(), problems.isEmpty())
    }

    @Test
    fun `a silently collapsed duplicate row is reported as rows dropped`() {
        // The parser deduplicates by id, and a collapsed duplicate parses perfectly — so neither the
        // rejected-lines check nor a duplicate check on the parsed result can see it. Counting the ids
        // the file names against the ids that came out is what catches it.
        val candidate = payload(
            areas = validAreas + "\n" + areaLine("DISTRICT", "BD1001", "BD10", "Kushtia (repeated id)"),
        )

        val problems = DatasetValidator.validate(payload = candidate)

        assertEquals(listOf("rows_dropped"), codes(problems))
        val dropped = problems.single() as DatasetProblem.RowsDropped
        assertEquals(4, dropped.dataLines)
        assertEquals(3, dropped.kept)
        assertEquals(DatasetPayload.AREAS_PART, dropped.part)
    }

    @Test
    fun `an unresolvable parent is reported`() {
        val problems = DatasetValidator.validate(
            payload = payload(areas = validAreas + "\n" + areaLine("DISTRICT", "BD2001", "BD20", "Bogura")),
        )

        assertEquals(listOf("unknown_parent"), codes(problems))
    }

    @Test
    fun `a parent at the wrong level is reported`() {
        val problems = DatasetValidator.validate(
            payload = payload(
                areas = listOf(
                    areaLine("DIVISION", "BD10", "BD", "Khulna"),
                    areaLine("DISTRICT", "BD1001", "BD10", "Kushtia"),
                    // An upazila parented straight to a division: the hierarchy is a level short. The
                    // district above it is present so the payload is usable, and the mis-parenting is
                    // the only thing wrong with it.
                    areaLine("UPAZILA", "BD1001001", "BD10", "Kumarkhali"),
                ).joinToString("\n"),
            ),
        )

        assertEquals(listOf("wrong_level_parent"), codes(problems))
    }

    // Harmful to this user ----------------------------------------------------------------------

    @Test
    fun `removing an id the active dataset has is an identity breach`() {
        val candidate = payload(
            areas = listOf(
                areaLine("DIVISION", "BD10", "BD", "Khulna"),
                areaLine("DISTRICT", "BD1001", "BD10", "Kushtia"),
                // BD1001001, which the active dataset has, is gone — replaced by a different upazila so
                // the payload is still a usable dataset and the identity is the only thing wrong with it.
                areaLine("UPAZILA", "BD1001002", "BD1001", "Bheramara"),
            ).joinToString("\n"),
        )

        val problems = DatasetValidator.validate(
            payload = candidate,
            context = DatasetValidationContext(activeIdentity = activeIdentity),
        )

        assertEquals(listOf("identity_breach"), codes(problems))
        val breach = (problems.single() as DatasetProblem.IdentityBreach).change
        assertEquals("BD1001001", breach.id)
        assertTrue(breach.breaksExistingIdentity)
    }

    @Test
    fun `adding an area is not an identity breach`() {
        val candidate = payload(
            areas = validAreas + "\n" + areaLine("UPAZILA", "BD1001002", "BD1001", "Bheramara"),
        )

        val problems = DatasetValidator.validate(
            payload = candidate,
            context = DatasetValidationContext(activeIdentity = activeIdentity),
        )

        assertTrue(codes(problems).toString(), problems.isEmpty())
    }

    @Test
    fun `a payload that no longer contains the stored area is rejected`() {
        val candidate = payload(
            areas = listOf(
                areaLine("DIVISION", "BD10", "BD", "Khulna"),
                areaLine("DISTRICT", "BD1001", "BD10", "Kushtia"),
                areaLine("UPAZILA", "BD1001999", "BD1001", "Somewhere else"),
            ).joinToString("\n"),
        )

        val problems = DatasetValidator.validate(
            payload = candidate,
            context = DatasetValidationContext(storedArea = storedArea),
        )

        assertEquals(listOf("stored_location_would_break"), codes(problems))
        val breakage = problems.single() as DatasetProblem.StoredLocationWouldBreak
        assertEquals("BD10", breakage.divisionId)
        assertEquals("BD1001", breakage.districtId)
        assertEquals("BD1001001", breakage.upazilaId)
    }

    @Test
    fun `a district-level stored area is satisfied by a dataset that keeps the district`() {
        val districtOnly = BangladeshAreaRef("BD10", "Khulna", "BD1001", "Kushtia")

        val problems = DatasetValidator.validate(
            payload = payload(),
            context = DatasetValidationContext(storedArea = districtOnly),
        )

        assertTrue(codes(problems).toString(), problems.isEmpty())
    }

    @Test
    fun `reusing the active version with different contents is rejected`() {
        val active = payload(version = "v2")
        val candidate = payload(
            version = "v2",
            areas = validAreas + "\n" + areaLine("UPAZILA", "BD1001002", "BD1001", "Bheramara"),
        )

        val problems = DatasetValidator.validate(
            payload = candidate,
            context = DatasetValidationContext(
                activeVersion = active.version,
                activeChecksum = active.checksum(),
            ),
        )

        assertEquals(listOf("version_reused_with_different_content"), codes(problems))
    }

    @Test
    fun `reusing the active version with identical contents is not a problem`() {
        val active = payload(version = "v2")
        val candidate = payload(version = "v2")

        val problems = DatasetValidator.validate(
            payload = candidate,
            context = DatasetValidationContext(
                activeVersion = active.version,
                activeChecksum = active.checksum(),
            ),
        )

        assertTrue(codes(problems).toString(), problems.isEmpty())
    }

    // Reporting ---------------------------------------------------------------------------------

    @Test
    fun `problems are ordered deterministically`() {
        val candidate = payload(
            version = "v2",
            areas = listOf(
                areaLine("DIVISION", "BD10", "BD", "Khulna"),
                areaLine("UPAZILA", "BD1001001", "BD10", "Kumarkhali"),
                areaLine("DISTRICT", "BD2001", "BD20", "Bogura"),
            ).joinToString("\n"),
        )

        val first = DatasetValidator.validate(
            payload = candidate,
            context = DatasetValidationContext(activeIdentity = activeIdentity, storedArea = storedArea),
        )
        val second = DatasetValidator.validate(
            payload = candidate,
            context = DatasetValidationContext(activeIdentity = activeIdentity, storedArea = storedArea),
        )

        assertEquals(codes(first), codes(second))
        assertEquals(codes(first).sorted(), codes(first))
        assertTrue(codes(first).size > 1)
    }

    @Test
    fun `a problem never carries a coordinate`() {
        val problems = DatasetValidator.validate(
            payload = payload(areas = validAreas + "\n" + areaLine("DISTRICT", "BD2001", "BD20", "Bogura")),
        )

        for (problem in problems) {
            assertFalse(
                "a problem detail must describe ids, not positions: ${problem.detail}",
                problem.detail.contains("23.") || problem.detail.contains("89."),
            )
        }
    }

    private fun areaLine(
        level: String,
        id: String,
        parentId: String,
        name: String,
        latitude: Double = 23.5,
        longitude: Double = 89.5,
    ): String = listOf(level, id, parentId, name, "", latitude.toString(), longitude.toString())
        .joinToString("\t")
}
