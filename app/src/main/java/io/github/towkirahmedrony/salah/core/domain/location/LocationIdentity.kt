package io.github.towkirahmedrony.salah.core.domain.location

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef

/**
 * The part of an area that decides **which place it is**: its id, the id of its parent and its level.
 *
 * Deliberately excludes the name, the Bangla name and the coordinates. Those describe a place; they do
 * not identify it. Splitting them out is what lets Salah tell "a label was corrected" apart from "a
 * different place now sits under this id" — the first is an ordinary data edit, the second silently
 * changes what a user's saved location means.
 *
 * `docs/LOCATION_SYSTEM.md` §8.3 states the identity rules this type exists to enforce.
 */
data class AreaIdentity(
    val id: String,
    val parentId: String,
    val level: BangladeshAreaLevel,
)

/**
 * A change between one dataset's identity set and another's.
 *
 * [breaksExistingIdentity] is the distinction that matters: an added area extends the dataset and
 * invalidates nothing, while a removed id — or a changed level or parent — can invalidate a location a
 * user has already saved.
 */
sealed interface IdentityChange {

    /** The id this change is about. */
    val id: String

    /** True when the change can invalidate a previously stored reference to [id]. */
    val breaksExistingIdentity: Boolean

    /** An id present in the baseline that the candidate no longer contains. */
    data class Removed(
        override val id: String,
        val level: BangladeshAreaLevel,
        val parentId: String,
    ) : IdentityChange {
        override val breaksExistingIdentity: Boolean get() = true
    }

    /** The same id, now at a different administrative level. */
    data class LevelChanged(
        override val id: String,
        val was: BangladeshAreaLevel,
        val now: BangladeshAreaLevel,
    ) : IdentityChange {
        override val breaksExistingIdentity: Boolean get() = true
    }

    /** The same id, now under a different parent. */
    data class ParentChanged(
        override val id: String,
        val was: String,
        val now: String,
    ) : IdentityChange {
        override val breaksExistingIdentity: Boolean get() = true
    }

    /** An id the candidate introduces that the baseline did not have. */
    data class Added(
        override val id: String,
        val level: BangladeshAreaLevel,
    ) : IdentityChange {
        override val breaksExistingIdentity: Boolean get() = false
    }
}

/**
 * A structural defect in **one** dataset's identity set, with no second dataset to compare against.
 *
 * Separate from [IdentityChange] because it can be answered from the candidate alone: a duplicate id,
 * a parent that is not in the dataset at all, or a parent at the wrong level is broken whatever it is
 * being compared to.
 */
sealed interface IdentityDefect {

    /** The id this defect is about. */
    val id: String

    /** The same id appears more than once. */
    data class DuplicateId(override val id: String, val occurrences: Int) : IdentityDefect

    /** The parent id is not an area in this dataset. */
    data class UnknownParent(override val id: String, val parentId: String) : IdentityDefect

    /** The parent exists but sits at the wrong level for this area's level. */
    data class WrongLevelParent(
        override val id: String,
        val parentId: String,
        val parentLevel: BangladeshAreaLevel,
        val expected: BangladeshAreaLevel,
    ) : IdentityDefect

    /** A top-level area claims another area as its parent, so the hierarchy has no root. */
    data class ParentIsAnArea(override val id: String, val parentId: String) : IdentityDefect
}

/**
 * A dataset's identity set: every area's id, parent and level, and nothing else.
 *
 * **What it is for.** Two jobs, both about not silently changing what a saved location means:
 *
 *  - **Validation of a candidate dataset.** Comparing a candidate's identity against the active
 *    dataset's answers "would activating this invalidate a location a user has already saved?"
 *    before anything is written. This is the seam a future published dataset will have to pass, and
 *    it is exercised today by the bundled dataset's own regression gate.
 *  - **A reviewable baseline.** [toText] and [parse] round-trip the identity set through a small
 *    tab-separated file, so the shipped dataset's identities can be checked into the repository and
 *    asserted in CI. A legitimate, intended data change then shows up as an explicit diff to that
 *    file rather than passing unnoticed — which is the point: the change must be *reviewable*, not
 *    impossible.
 *
 * **What it deliberately does not carry.** Names, Bangla names and coordinates. Freezing those in a
 * baseline would make a corrected spelling fail the build, which is exactly the brittleness the
 * identity/description split exists to avoid.
 */
class DatasetIdentity private constructor(
    /** The identity of every area, ordered by id so two datasets compare deterministically. */
    val areas: List<AreaIdentity>,
    /** Data lines the parser refused, kept so a malformed baseline is visible rather than silent. */
    val malformedLines: List<String>,
) {

    private val byId: Map<String, AreaIdentity> = areas.associateBy { it.id }

    /** Every id in the dataset. */
    val ids: Set<String> = areas.mapTo(LinkedHashSet()) { it.id }

    /** True when the identity set is empty, which no usable dataset is. */
    val isEmpty: Boolean get() = areas.isEmpty()

    /** The identity of [id], or `null` when the dataset does not contain it. */
    fun area(id: String?): AreaIdentity? = id?.let { byId[it] }

    /**
     * True when every id in [reference] is present **at the level the reference expects**.
     *
     * Level is checked, not just presence: an id that still exists but has moved to a different level
     * no longer describes the same place, and accepting it would attach a stored location to the wrong
     * administrative unit.
     */
    fun recognises(reference: BangladeshAreaRef): Boolean =
        area(reference.divisionId)?.level == BangladeshAreaLevel.DIVISION &&
            area(reference.districtId)?.level == BangladeshAreaLevel.DISTRICT &&
            (reference.upazilaId == null || area(reference.upazilaId)?.level == BangladeshAreaLevel.UPAZILA)

    /**
     * How this identity set differs from [baseline], oldest-first by id for a stable report.
     *
     * Includes additions, because a report that hid them would make a large unintended change harder
     * to read; callers that only care about breakage filter on [IdentityChange.breaksExistingIdentity].
     */
    fun changesAgainst(baseline: DatasetIdentity): List<IdentityChange> {
        val changes = mutableListOf<IdentityChange>()

        for (was in baseline.areas) {
            val now = byId[was.id]
            if (now == null) {
                changes += IdentityChange.Removed(id = was.id, level = was.level, parentId = was.parentId)
                continue
            }
            if (now.level != was.level) {
                changes += IdentityChange.LevelChanged(id = was.id, was = was.level, now = now.level)
            }
            if (now.parentId != was.parentId) {
                changes += IdentityChange.ParentChanged(id = was.id, was = was.parentId, now = now.parentId)
            }
        }

        for (now in areas) {
            if (baseline.byId[now.id] == null) {
                changes += IdentityChange.Added(id = now.id, level = now.level)
            }
        }

        return changes.sortedWith(compareBy({ it.id }, { it.javaClass.name }))
    }

    /**
     * Structural problems in this dataset on its own: duplicate ids, parents that do not exist, parents
     * at the wrong level, and a top-level area that is parented to another area.
     *
     * The root is not named here. A division is required to have a parent that is *not* an area in the
     * dataset, rather than to equal a particular constant, so a later dataset with a different root id
     * is not flagged for a reason that has nothing to do with identity.
     */
    fun defects(): List<IdentityDefect> {
        val defects = mutableListOf<IdentityDefect>()

        areas.groupingBy { it.id }.eachCount()
            .filterValues { it > 1 }
            .forEach { (id, occurrences) -> defects += IdentityDefect.DuplicateId(id, occurrences) }

        for (area in areas) {
            val parent = byId[area.parentId]
            val expectedParentLevel = when (area.level) {
                BangladeshAreaLevel.DIVISION -> null
                BangladeshAreaLevel.DISTRICT -> BangladeshAreaLevel.DIVISION
                BangladeshAreaLevel.UPAZILA -> BangladeshAreaLevel.DISTRICT
            }

            if (expectedParentLevel == null) {
                if (parent != null) {
                    defects += IdentityDefect.ParentIsAnArea(id = area.id, parentId = area.parentId)
                }
                continue
            }

            if (parent == null) {
                defects += IdentityDefect.UnknownParent(id = area.id, parentId = area.parentId)
            } else if (parent.level != expectedParentLevel) {
                defects += IdentityDefect.WrongLevelParent(
                    id = area.id,
                    parentId = area.parentId,
                    parentLevel = parent.level,
                    expected = expectedParentLevel,
                )
            }
        }

        return defects.sortedBy { it.id }
    }

    /**
     * This identity set as tab-separated text, one `level<TAB>id<TAB>parentId` record per area, ordered
     * by id, with a comment header.
     */
    fun toText(): String = buildString {
        appendLine(BASELINE_HEADER)
        for (area in areas) {
            append(area.level.name).append('\t').append(area.id).append('\t').append(area.parentId).append('\n')
        }
    }

    override fun toString(): String = "DatasetIdentity(areas=${areas.size}, malformed=${malformedLines.size})"

    /**
     * Identity equality is equality of the area set.
     *
     * [malformedLines] is deliberately **not** part of it: it is a diagnostic about the input that
     * produced this identity, not a fact about the places. Two identities over the same areas are the
     * same identity whether one of them was read from a file with a bad line in it or not — which is
     * what lets a candidate parsed from a file be compared directly against a baseline.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DatasetIdentity) return false
        return areas == other.areas
    }

    override fun hashCode(): Int = areas.hashCode()

    companion object {

        /** How many refused lines are retained: enough to diagnose, bounded so it cannot grow. */
        private const val MAX_RETAINED_MALFORMED = 50

        /** Number of columns a baseline record carries: level, id, parent id. No names, no coordinates. */
        const val BASELINE_COLUMNS = 3

        private const val BASELINE_HEADER =
            "# Salah location identity baseline - level, id, parent id. " +
                "Names and coordinates are deliberately absent."

        /** The identity set of [dataset]. */
        fun of(dataset: BangladeshLocationDataset): DatasetIdentity = ofAreas(
            dataset.areas.map { area ->
                AreaIdentity(id = area.id, parentId = area.parentId, level = area.level)
            },
        )

        /** The identity set of [areas], ordered by id. */
        fun ofAreas(areas: List<AreaIdentity>): DatasetIdentity =
            DatasetIdentity(areas = areas.sortedBy { it.id }, malformedLines = emptyList())

        /**
         * Parses a baseline produced by [toText].
         *
         * Comment and blank lines are skipped; a data line that does not carry exactly
         * [BASELINE_COLUMNS] usable fields is refused and recorded in [malformedLines] rather than
         * throwing, matching the bundled parser's contract: a bad line is reported, never guessed at.
         */
        fun parse(text: String): DatasetIdentity {
            val malformed = mutableListOf<String>()
            val areas = mutableListOf<AreaIdentity>()

            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parsed = parseLine(line)
                if (parsed == null) {
                    if (malformed.size < MAX_RETAINED_MALFORMED) malformed += line
                } else {
                    areas += parsed
                }
            }

            return DatasetIdentity(areas = areas.sortedBy { it.id }, malformedLines = malformed)
        }

        private fun parseLine(line: String): AreaIdentity? {
            val fields = line.split('\t')
            if (fields.size != BASELINE_COLUMNS) return null

            val level = BangladeshAreaLevel.entries
                .firstOrNull { it.name == fields[0].trim().uppercase() }
                ?: return null
            val id = fields[1].trim().takeIf { it.isNotEmpty() } ?: return null
            val parentId = fields[2].trim().takeIf { it.isNotEmpty() } ?: return null

            return AreaIdentity(id = id, parentId = parentId, level = level)
        }
    }
}
