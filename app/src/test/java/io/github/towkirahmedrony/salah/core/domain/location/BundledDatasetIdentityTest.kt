package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.testing.BundledDataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The stable-identity gate for the **shipped** dataset.
 *
 * [DatasetIdentityTest] proves the rules; this proves the bundled data obeys them, and keeps obeying
 * them. It compares the identity of `app/src/main/assets/location/bd_areas.tsv` against the baseline
 * checked in at `app/src/test/resources/location/bd_identity.baseline.tsv`.
 *
 * **Why a checked-in baseline rather than a hash.** A hash would fail on any change and say nothing
 * about what changed. A baseline file makes the *diff* the review: an intentional edit — a corrected
 * id, a re-parented upazila, a newly added area — shows up as a small, readable change to a file whose
 * only content is identity, and a reviewer reads it on its own terms. An unintentional one is caught
 * before it ships instead of after.
 *
 * **Refreshing it.** When a data change is intended, regenerate the baseline from the dataset (the
 * projection is `level<TAB>id<TAB>parentId`, ordered by id) and commit it alongside the change. The
 * failure messages below print the differences, so the edit is mechanical. Changing this file *is* the
 * acknowledgement that the identity of a shipped place changed.
 *
 * **What it deliberately does not freeze.** Names, Bangla names and coordinates. Those are checked
 * elsewhere for being *well formed* (`BundledDatasetIntegrityTest`), never for being *unchanged* —
 * correcting a spelling must not fail a build.
 */
class BundledDatasetIdentityTest {

    private val bundled: BangladeshLocationDataset = BundledDataset.load(version = "test-bundled")

    private val baselineText: String = requireNotNull(
        baselineFile(),
    ) {
        "The identity baseline was not found. Searched upwards from ${File(".").absolutePath} for " +
            RELATIVE_CANDIDATES.joinToString() + "."
    }.readText()

    private val baseline: DatasetIdentity = DatasetIdentity.parse(baselineText)
    private val bundledIdentity: DatasetIdentity = DatasetIdentity.of(bundled)

    @Test
    fun `the baseline is well formed`() {
        assertTrue(
            "the baseline has lines that are not 'level<TAB>id<TAB>parentId': " +
                "${baseline.malformedLines}",
            baseline.malformedLines.isEmpty(),
        )
        assertFalse("the baseline is empty", baseline.isEmpty)

        val duplicates = baseline.defects().filterIsInstance<IdentityDefect.DuplicateId>()
        assertTrue("the baseline repeats an id: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `the baseline carries no display names and no coordinates`() {
        // The file must stay an identity record. A name or a coordinate creeping in would turn the
        // next legitimate label or position correction into a build failure, which is the brittleness
        // this whole split exists to avoid.
        for (line in baselineText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val fields = trimmed.split('\t')
            assertEquals(
                "every baseline line must carry exactly level, id and parent id: '$trimmed'",
                DatasetIdentity.BASELINE_COLUMNS,
                fields.size,
            )
            assertTrue(
                "a baseline id must not contain a space, which would suggest a name: '${fields[1]}'",
                !fields[1].contains(' '),
            )
        }
    }

    @Test
    fun `no id, level or parent recorded in the baseline has changed in the bundled dataset`() {
        val breaking = bundledIdentity.changesAgainst(baseline)
            .filter { it.breaksExistingIdentity }

        assertTrue(
            "the bundled dataset changed the identity of ${breaking.size} place(s):\n" +
                breaking.joinToString("\n") { "  ${describe(it)}" } +
                "\n\nEvery one of these can invalidate a location a user has already saved. If the " +
                "change is intended, it is a deliberate break and needs a migration story; if it is " +
                "not, fix the data. The baseline is only to be regenerated once the change is " +
                "understood:\n  ${RELATIVE_CANDIDATES.first()}",
            breaking.isEmpty(),
        )
    }

    @Test
    fun `every area in the bundled dataset is recorded in the baseline`() {
        val additions = bundledIdentity.changesAgainst(baseline)
            .filterIsInstance<IdentityChange.Added>()

        val message = buildString {
            append("the bundled dataset has ${additions.size} area(s) the baseline does not record:\n")
            additions.take(MAX_LISTED).forEach { append("  + ${it.id} (${it.level.name})\n") }
            if (additions.size > MAX_LISTED) append("  … and ${additions.size - MAX_LISTED} more\n")
            append("\nAn added area is not a break, but the baseline is out of date. Regenerate it so ")
            append("the next change is reviewed against the current data:\n  ")
            append(RELATIVE_CANDIDATES.first())
        }

        assertTrue(message, additions.isEmpty())
    }

    @Test
    fun `the baseline records the level counts the shipped dataset is documented to have`() {
        fun count(level: BangladeshAreaLevel): Int = baseline.areas.count { it.level == level }

        assertEquals("divisions", 8, count(BangladeshAreaLevel.DIVISION))
        assertEquals("districts", 64, count(BangladeshAreaLevel.DISTRICT))
        assertEquals("upazilas", 507, count(BangladeshAreaLevel.UPAZILA))
        assertEquals("total areas", 579, baseline.areas.size)
    }

    @Test
    fun `the bundled dataset has no structural identity defect`() {
        val defects = bundledIdentity.defects()
        assertTrue(
            "the bundled dataset has identity defects: $defects",
            defects.isEmpty(),
        )
    }

    @Test
    fun `the bundled dataset recognises the areas the baseline describes`() {
        // Spot-checks the identity contract against ids that are pinned elsewhere in the suite too, so
        // a wholesale file swap that happened to keep the counts would still be caught.
        for (id in listOf("BD10", "BD2013", "BD20130095")) {
            val area = requireNotNull(bundledIdentity.area(id)) { "$id is missing from the bundled dataset" }
            assertTrue("$id must be recognised by its own dataset", id in bundledIdentity.ids)
            assertTrue("$id must have a parent", area.parentId.isNotBlank())
        }
    }

    private fun describe(change: IdentityChange): String = when (change) {
        is IdentityChange.Removed -> "- ${change.id} removed (was a ${change.level.name})"
        is IdentityChange.LevelChanged -> "- ${change.id} moved from ${change.was.name} to ${change.now.name}"
        is IdentityChange.ParentChanged -> "- ${change.id} moved from parent ${change.was} to ${change.now}"
        is IdentityChange.Added -> "+ ${change.id} (${change.level.name})"
    }

    /**
     * Finds the baseline by walking up from the working directory.
     *
     * The same technique [BundledDataset] uses for the assets, and for the same reason: a Gradle unit
     * test runs with the module directory as its working directory, but the same tests are also run
     * from the repository root. Reading the real file rather than a classpath copy also means the
     * failure message can name a path the reader can open.
     */
    private fun baselineFile(): File? {
        var base: File? = File(".").absoluteFile
        repeat(SEARCH_DEPTH) {
            val current = base ?: return null
            RELATIVE_CANDIDATES
                .map { relative -> File(current, relative) }
                .firstOrNull { candidate -> candidate.isFile }
                ?.let { return it }
            base = current.parentFile
        }
        return null
    }

    private companion object {
        val RELATIVE_CANDIDATES = listOf(
            "app/src/test/resources/location/bd_identity.baseline.tsv",
            "src/test/resources/location/bd_identity.baseline.tsv",
            "location/bd_identity.baseline.tsv",
        )
        const val SEARCH_DEPTH = 5

        /** How many additions the failure message lists before summarising the rest. */
        const val MAX_LISTED = 20
    }
}
