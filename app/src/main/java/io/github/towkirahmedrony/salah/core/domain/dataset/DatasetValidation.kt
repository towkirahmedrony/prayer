package io.github.towkirahmedrony.salah.core.domain.dataset

import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import io.github.towkirahmedrony.salah.core.domain.location.DatasetIdentity
import io.github.towkirahmedrony.salah.core.domain.location.IdentityChange
import io.github.towkirahmedrony.salah.core.domain.location.IdentityDefect
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef

/**
 * One reason a candidate payload is not acceptable.
 *
 * Each is a *value* rather than an exception, matching the rest of Salah: a dataset that cannot be
 * used is a reported state, and the caller decides what to do about it. Rejection is always total —
 * a candidate with any problem at all is not activated, so there is no "mostly good" dataset to reason
 * about later.
 */
sealed interface DatasetProblem {

    /** A short, stable code suitable for diagnostics and tests. */
    val code: String

    /** A human-readable explanation, with ids and levels but never a coordinate or a user's text. */
    val detail: String

    /** The payload names no version, so nothing could record which dataset is active. */
    data class BlankVersion(override val detail: String) : DatasetProblem {
        override val code: String get() = "blank_version"
    }

    /** The payload parsed but does not carry what a usable dataset needs. */
    data class NotUsable(override val detail: String) : DatasetProblem {
        override val code: String get() = "not_usable"
    }

    /** The payload contained lines the parser refused. */
    data class RejectedLines(
        val lines: List<String>,
        val total: Int,
        override val detail: String,
    ) : DatasetProblem {
        override val code: String get() = "rejected_lines"
    }

    /**
     * A part of the payload lost rows between the file and the parsed dataset.
     *
     * The parser is deliberately forgiving — it refuses a bad line rather than throwing — but it also
     * *deduplicates* by id, and a collapsed duplicate is not something [RejectedLines] or
     * [DuplicateId] can see: the line parsed perfectly well and then quietly replaced another one. So
     * a duplicated id, or any other cause of a row disappearing without a rejection, would otherwise
     * be invisible. Counting the data lines and comparing them to what came out is what makes it
     * visible.
     */
    data class RowsDropped(
        val part: String,
        val dataLines: Int,
        val kept: Int,
        override val detail: String,
    ) : DatasetProblem {
        override val code: String get() = "rows_dropped"
    }

    /** The payload's integrity value is not the one that was published for it. */
    data class ChecksumMismatch(
        val expected: String,
        val actual: String,
        override val detail: String,
    ) : DatasetProblem {
        override val code: String get() = "checksum_mismatch"
    }

    /**
     * The payload claims the version that is already in use but does not have the same contents.
     *
     * A version identifies a dataset, so two different payloads cannot both be `v03`. Accepting this
     * would make the version meaningless as a record of what is active, and would let an unnoticed
     * re-publication change a user's data while every log line and every screen still reported the old
     * version. It is therefore a rejection, not an update.
     *
     * Produced by the activation decision rather than by the payload checks themselves; see
     * `LocalDatasetStore.activate`.
     */
    data class VersionReusedWithDifferentContent(
        val version: String,
        val activeChecksum: String,
        val candidateChecksum: String,
        override val detail: String,
    ) : DatasetProblem {
        override val code: String get() = "version_reused_with_different_content"
    }

    /** Same id twice. */
    data class DuplicateId(
        val id: String,
        val occurrences: Int,
        override val detail: String,
    ) : DatasetProblem {
        override val code: String get() = "duplicate_id"
    }

    /** A parent id the payload does not define. */
    data class UnknownParent(
        val id: String,
        val parentId: String,
        override val detail: String,
    ) : DatasetProblem {
        override val code: String get() = "unknown_parent"
    }

    /** A parent that exists at the wrong level. */
    data class WrongLevelParent(
        val id: String,
        val parentId: String,
        override val detail: String,
    ) : DatasetProblem {
        override val code: String get() = "wrong_level_parent"
    }

    /** A top-level area parented to another area. */
    data class ParentIsAnArea(
        val id: String,
        val parentId: String,
        override val detail: String,
    ) : DatasetProblem {
        override val code: String get() = "parent_is_an_area"
    }

    /**
     * The payload changes an identity that the currently active dataset already had.
     *
     * Only *breaking* changes are reported. An area the payload adds is not a problem, because it
     * extends the dataset without changing what any existing id means.
     */
    data class IdentityBreach(val change: IdentityChange, override val detail: String) : DatasetProblem {
        override val code: String get() = "identity_breach"
    }

    /**
     * Activating the payload would leave the user's stored location unresolvable.
     *
     * The user's data is the reason this check exists: a payload that is internally valid can still
     * remove the one area a saved location depends on, and the cost of accepting it is a user who
     * opens the app to a setup screen they already completed.
     */
    data class StoredLocationWouldBreak(
        val divisionId: String,
        val districtId: String,
        val upazilaId: String?,
        override val detail: String,
    ) : DatasetProblem {
        override val code: String get() = "stored_location_would_break"
    }
}

/**
 * Everything a candidate payload is judged against, other than the payload itself.
 *
 * A single object rather than five positional parameters: the checks are a *policy* assembled from the
 * active dataset, the user's stored location and the publisher's expectations, and a named context
 * makes it hard to pass the wrong thing silently — the failure mode this whole area exists to prevent.
 */
data class DatasetValidationContext(
    /** The identity set of the dataset currently in use, or `null` when there is nothing to compare. */
    val activeIdentity: DatasetIdentity? = null,
    /** The version of the dataset currently in use, or `null` when the bundled one is in force. */
    val activeVersion: DatasetVersion? = null,
    /** The integrity value of the dataset currently in use, or `null` for the bundled dataset. */
    val activeChecksum: String? = null,
    /**
     * The area of the user's saved location, or `null` when none is stored.
     *
     * The only input here that comes from user data.
     */
    val storedArea: BangladeshAreaRef? = null,
    /** The checksum published for the candidate, or `null` to skip the integrity check. */
    val expectedChecksum: String? = null,
    /** How many refused lines are tolerated. See [DatasetValidator.DEFAULT_MAX_REJECTED_LINES]. */
    val maxRejectedLines: Int = DatasetValidator.DEFAULT_MAX_REJECTED_LINES,
)

/**
 * Decides whether a candidate payload may be activated.
 *
 * Pure and synchronous: it takes the payload, the identities and reference material to compare against,
 * and returns problems. No file, no clock, no coroutine, no platform call — so every rejection path is
 * directly testable, which matters because these are the paths that must never silently pass.
 *
 * **Order is deliberate.** Cheap answers come first (a blank version, a checksum, a reused version) and
 * the expensive structural pass runs once. More importantly, the checks are ordered so the *reason*
 * reported is the most fundamental one: a payload whose integrity failed is rejected for that alone
 * rather than as a set of identity breaches caused by it being corrupt.
 *
 * `docs/ARCHITECTURE.md` §12 documents how this is used.
 */
object DatasetValidator {

    /**
     * How many refused lines a payload may carry before it is rejected.
     *
     * Zero by default, and that is the intended setting: the bundled dataset is asserted to reject
     * nothing, and a published payload that does not parse cleanly is far more likely to be truncated
     * or mis-generated than to contain a line worth forgiving. A caller that deliberately wants to
     * tolerate a known-bad line raises this explicitly rather than relying on a lax default.
     */
    const val DEFAULT_MAX_REJECTED_LINES: Int = 0

    /** True when [problems] is empty, i.e. the payload may be activated. */
    fun accepts(problems: List<DatasetProblem>): Boolean = problems.isEmpty()

    /**
     * Validates [payload] against [context].
     *
     * The checks, in the order they run:
     *
     *  1. the payload names a non-blank version;
     *  2. its integrity value matches the published one, when one was supplied;
     *  3. it parses into a usable dataset with no more refused lines than allowed;
     *  4. its hierarchy is structurally sound (unique ids, resolvable parents, a root);
     *  5. it does not break an identity the active dataset already had;
     *  6. it does not claim the active version while carrying different contents;
     *  7. it still contains the area of the user's saved location.
     *
     * Checks 3 and 4 are answered by the payload alone — they are defects whatever it is compared to.
     * Checks 5 to 7 are answered against the context.
     */
    fun validate(
        payload: DatasetPayload,
        context: DatasetValidationContext = DatasetValidationContext(),
    ): List<DatasetProblem> {
        val activeIdentity = context.activeIdentity
        val storedArea = context.storedArea
        val expectedChecksum = context.expectedChecksum
        val maxRejectedLines = context.maxRejectedLines

        val problems = mutableListOf<DatasetProblem>()

        if (payload.version.isBlank) {
            return listOf(
                DatasetProblem.BlankVersion("The candidate payload names no dataset version."),
            )
        }

        if (expectedChecksum != null) {
            val actual = payload.checksum()
            if (!actual.equals(expectedChecksum, ignoreCase = true)) {
                problems += DatasetProblem.ChecksumMismatch(
                    expected = expectedChecksum,
                    actual = actual,
                    detail = "The candidate payload does not match the checksum published for it.",
                )
                // Integrity failed: the contents cannot be trusted to mean anything, so stop before
                // interpreting them. Every further check would be reporting on corrupted input.
                return problems
            }
        }

        val dataset = payload.toDataset()
        if (!dataset.isUsable) {
            return problems + DatasetProblem.NotUsable(
                detail = "The candidate payload is not a usable dataset: " +
                    "divisions=${dataset.divisions.size}, districts=${dataset.districts.size}, " +
                    "upazilas=${dataset.upazilas.size}, boundaryRings=${dataset.boundaryRings.size}.",
            )
        }

        if (dataset.rejectedLines.size > maxRejectedLines) {
            problems += DatasetProblem.RejectedLines(
                lines = dataset.rejectedLines,
                total = dataset.rejectedLines.size,
                detail = "The candidate payload contains ${dataset.rejectedLines.size} line(s) the " +
                    "parser refused, more than the $maxRejectedLines allowed.",
            )
        }

        // Rows that parsed but did not survive. See RowsDropped for why the other checks cannot see
        // this: the parser deduplicates by id, and a collapsed duplicate parses perfectly, so it is
        // invisible both to the rejected-lines check and to a duplicate check on the parsed result.
        val namedAreas = distinctIdsIn(payload.areasText, BangladeshLocationDataset.AREA_COLUMNS, idIndex = 1)
        if (namedAreas.size != dataset.areas.size) {
            val repeated = namedAreas.size - namedAreas.distinct().size
            problems += DatasetProblem.RowsDropped(
                part = DatasetPayload.AREAS_PART,
                dataLines = namedAreas.size,
                kept = dataset.areas.size,
                detail = "The candidate payload's hierarchy part names ${namedAreas.size} area id(s) " +
                    "but produces ${dataset.areas.size} area(s)" +
                    if (repeated > 0) ", so $repeated id(s) are defined more than once." else ".",
            )
        }

        val namedLocalities = distinctLocalitiesIn(payload.localitiesText)
        if (namedLocalities.size != dataset.localities.size) {
            val repeated = namedLocalities.size - namedLocalities.distinct().size
            problems += DatasetProblem.RowsDropped(
                part = DatasetPayload.LOCALITIES_PART,
                dataLines = namedLocalities.size,
                kept = dataset.localities.size,
                detail = "The candidate payload's locality part names ${namedLocalities.size} " +
                    "localit(ies) but produces ${dataset.localities.size}" +
                    if (repeated > 0) ", so $repeated is defined more than once." else ".",
            )
        }

        val identity = DatasetIdentity.of(dataset)
        problems += identity.defects().map { it.toProblem() }

        if (activeIdentity != null) {
            identity.changesAgainst(activeIdentity)
                .filter { it.breaksExistingIdentity }
                .forEach { change ->
                    problems += DatasetProblem.IdentityBreach(
                        change = change,
                        detail = change.explanation(),
                    )
                }
        }

        // A version identifies a dataset, so the same version with different contents is a publishing
        // error rather than an update: accepting it would let the reported version stop describing what
        // is actually active.
        val activeVersion = context.activeVersion
        val activeChecksum = context.activeChecksum
        if (activeVersion != null && activeChecksum != null &&
            payload.version.isSameAs(activeVersion) &&
            !payload.checksum().equals(activeChecksum, ignoreCase = true)
        ) {
            problems += DatasetProblem.VersionReusedWithDifferentContent(
                version = payload.version.value,
                activeChecksum = activeChecksum,
                candidateChecksum = payload.checksum(),
                detail = "The candidate payload claims version '${payload.version.value}', which is " +
                    "already in use, but its contents differ from the active dataset.",
            )
        }

        if (storedArea != null && !identity.recognises(storedArea)) {
            problems += DatasetProblem.StoredLocationWouldBreak(
                divisionId = storedArea.divisionId,
                districtId = storedArea.districtId,
                upazilaId = storedArea.upazilaId,
                detail = "The candidate payload does not contain the area of the location already " +
                    "stored on this device, so activating it would leave that location unresolvable.",
            )
        }

        return problems.sortedWith(compareBy({ it.code }, { it.detail }))
    }

    /**
     * Every id a hierarchy part names, in file order, including repeats.
     *
     * Deliberately a *census* rather than a parse: it looks only at lines that carry the documented
     * column count and a non-blank id, so a line the parser would refuse is not counted here (that is
     * [DatasetProblem.RejectedLines]) and a line that parses into an area is counted exactly once even
     * if it is a duplicate. The gap between the count and the distinct count is precisely the collapse
     * the parser performs silently.
     */
    private fun distinctIdsIn(text: String, columns: Int, idIndex: Int): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.split('\t') }
        .filter { it.size == columns }
        .map { it[idIndex].trim() }
        .filter { it.isNotEmpty() }
        .toList()

    /** Every locality key a locality part names, in file order, including repeats. */
    private fun distinctLocalitiesIn(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.split('\t') }
        .filter { it.size == BangladeshLocationDataset.LOCALITY_COLUMNS }
        // The parser's de-duplication key, mirrored here: a locality is identified by the district it
        // was verified against, its kind and its name.
        .map { "${it[1].trim()}\u0000${it[0].trim()}\u0000${it[2].trim()}" }
        .filter { key -> key.split('\u0000').none { it.isEmpty() } }
        .toList()

    private fun IdentityDefect.toProblem(): DatasetProblem = when (this) {
        is IdentityDefect.DuplicateId -> DatasetProblem.DuplicateId(
            id = id,
            occurrences = occurrences,
            detail = "The candidate payload defines $id $occurrences times.",
        )

        is IdentityDefect.UnknownParent -> DatasetProblem.UnknownParent(
            id = id,
            parentId = parentId,
            detail = "$id is parented to $parentId, which the payload does not define.",
        )

        is IdentityDefect.WrongLevelParent -> DatasetProblem.WrongLevelParent(
            id = id,
            parentId = parentId,
            detail = "$id is parented to $parentId, which is a ${parentLevel.name.lowercase()} " +
                "rather than a ${expected.name.lowercase()}.",
        )

        is IdentityDefect.ParentIsAnArea -> DatasetProblem.ParentIsAnArea(
            id = id,
            parentId = parentId,
            detail = "$id is a ${BangladeshAreaLevel.DIVISION.name.lowercase()} but claims the area " +
                "$parentId as its parent, so the hierarchy has no root.",
        )
    }

    private fun IdentityChange.explanation(): String = when (this) {
        is IdentityChange.Removed ->
            "The candidate payload no longer contains $id (a ${level.name.lowercase()})."

        is IdentityChange.LevelChanged ->
            "The candidate payload moves $id from ${was.name.lowercase()} to ${now.name.lowercase()}."

        is IdentityChange.ParentChanged ->
            "The candidate payload moves $id from parent $was to parent $now."

        is IdentityChange.Added ->
            "The candidate payload adds $id (a ${level.name.lowercase()})."
    }
}
