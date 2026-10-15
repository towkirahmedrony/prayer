package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.domain.dataset.remote.JsonReader
import io.github.towkirahmedrony.salah.core.domain.dataset.remote.JsonValue
import io.github.towkirahmedrony.salah.core.model.AsrCalculationMethod
import io.github.towkirahmedrony.salah.core.model.BangladeshAreaLevel
import io.github.towkirahmedrony.salah.core.model.HighLatitudeRule
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationMethod
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerSettings
import java.security.MessageDigest

/**
 * The administrative scope a prayer-configuration revision applies to.
 *
 * The order is the resolution order, deepest last: a configuration published for an upazila/thana beats
 * one published for its district, which beats one published for its division, which beats the global
 * default. See [PrayerConfigResolver] for the one place that order is applied.
 */
enum class PrayerConfigurationScope {
    /** The country-wide default for Bangladesh. */
    GLOBAL,

    DIVISION,

    DISTRICT,

    /** An upazila, or a city thana. Salah's deepest administrative level. */
    UPAZILA,
    ;

    companion object {
        /** The scope named by [raw], case-insensitively, or `null` when it is not a known scope. */
        fun of(raw: String): PrayerConfigurationScope? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

        /** The scope an area at [level] is addressed at. */
        fun forLevel(level: BangladeshAreaLevel): PrayerConfigurationScope = when (level) {
            BangladeshAreaLevel.DIVISION -> DIVISION
            BangladeshAreaLevel.DISTRICT -> DISTRICT
            BangladeshAreaLevel.UPAZILA -> UPAZILA
        }
    }
}

/**
 * The lifecycle state of a prayer-configuration publication.
 *
 * Mirrors [io.github.towkirahmedrony.salah.core.domain.dataset.remote.PublicationStatus] deliberately:
 * the two are separate publication systems with the same three states, so neither can be changed for
 * the other's benefit. Only [PUBLISHED] may be applied.
 */
enum class PrayerConfigurationStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED,
    ;

    companion object {
        fun of(raw: String): PrayerConfigurationStatus? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * One area-specific calculation override inside a published snapshot.
 *
 * The profile is a [PrayerCalculationProfile] — the exact value the existing calculation engine consumes
 * — so a remote configuration can only ever supply inputs the engine already understands.
 */
data class PrayerAreaOverride(
    val areaId: String,
    val scope: PrayerConfigurationScope,
    val profile: PrayerCalculationProfile,
)

/**
 * A complete, self-contained configuration snapshot: the global default plus any area overrides.
 *
 * **Why one snapshot rather than one row per area.** A device resolves exactly one effective profile for
 * its location, and it must be able to do so from one downloaded document with no follow-up request. The
 * snapshot is therefore the unit of publication and the unit of activation: it is either adopted whole or
 * not at all, so a partially updated configuration can never exist.
 *
 * Area overrides are keyed by the **stable area id** the verified location dataset publishes. Editable
 * area names are never used as identity, and coordinates are never a key.
 */
data class PrayerConfigurationSnapshot(
    val configurationId: String,
    val revisionId: String,

    /** The monotonic revision number. Higher wins; an older one may never replace a newer accepted one. */
    val revisionNumber: Long,
    val schemaVersion: Int,
    val globalProfile: PrayerCalculationProfile,
    val areaOverrides: Map<String, PrayerAreaOverride>,
) {
    /** The override published for [areaId], or `null` when that area has none. */
    fun overrideFor(areaId: String?): PrayerAreaOverride? = areaId?.let { areaOverrides[it] }

    companion object {
        /** The single slot this phase publishes. */
        const val DEFAULT_CONFIGURATION_ID: String = "bd_prayer"
    }
}

/**
 * The current-publication manifest for the prayer-configuration slot.
 *
 * Small and flat, exactly like the dataset manifest: it is what a publisher and the app agree on, and
 * every field is either an identity, an integrity value or a completeness assertion. It mirrors one row
 * of the Supabase view `prayer_config_current_publication`; the payload itself is fetched separately, and
 * only when [revisionId] differs from the revision already accepted.
 */
data class PrayerConfigurationManifest(
    val configurationId: String,

    /** The immutable identifier of this revision. Opaque; compared for equality only. */
    val revisionId: String,

    /** The monotonic revision number, used to reject a stale publication. */
    val revisionNumber: Long,

    /** The payload layout version. The app supports a fixed set; see the parser. */
    val schemaVersion: Int,

    val status: PrayerConfigurationStatus,

    /** The SHA-256 (lower-case hex) of the payload text, exactly as [PrayerConfigurationChecksum] computes it. */
    val checksum: String,

    val publishedAt: String? = null,
    val notes: String? = null,
)

/** One reason a manifest or snapshot is not usable. A value, never an exception. */
sealed interface PrayerConfigProblem {

    /** A short, stable code suitable for diagnostics and tests. */
    val code: String

    /** A human-readable explanation. Never contains a credential or user text. */
    val detail: String

    /** The manifest describes a payload layout this build cannot read. */
    data class UnsupportedSchemaVersion(
        val found: Int,
        val supported: Set<Int>,
        override val detail: String,
    ) : PrayerConfigProblem {
        override val code: String get() = "unsupported_schema_version"
    }

    /** The revision is not published, so it must not be served to an app client. */
    data class NotPublished(
        val status: PrayerConfigurationStatus,
        override val detail: String,
    ) : PrayerConfigProblem {
        override val code: String get() = "publication_not_published"
    }

    /** The manifest describes a different configuration slot than the one being synchronised. */
    data class ConfigurationIdMismatch(
        val expected: String,
        val found: String,
        override val detail: String,
    ) : PrayerConfigProblem {
        override val code: String get() = "configuration_id_mismatch"
    }

    /** The published checksum is not a SHA-256 hex string, so it could not be verified. */
    data class InvalidChecksum(val checksum: String, override val detail: String) : PrayerConfigProblem {
        override val code: String get() = "invalid_checksum"
    }

    /** The downloaded payload does not hash to the checksum the manifest published for it. */
    data class ChecksumMismatch(
        val expected: String,
        val actual: String,
        override val detail: String,
    ) : PrayerConfigProblem {
        override val code: String get() = "checksum_mismatch"
    }

    /** A required manifest field is absent or blank. */
    data class MissingField(val field: String, override val detail: String) : PrayerConfigProblem {
        override val code: String get() = "missing_field"
    }

    /** A named value — method, Asr convention, high-latitude rule, scope or prayer — is not supported. */
    data class UnsupportedValue(val field: String, val value: String, override val detail: String) :
        PrayerConfigProblem {
        override val code: String get() = "unsupported_value"
    }

    /** A manual adjustment is outside the range the engine and the local model accept. */
    data class AdjustmentOutOfRange(
        val field: String,
        val minutes: Int,
        val limit: Int,
        override val detail: String,
    ) : PrayerConfigProblem {
        override val code: String get() = "adjustment_out_of_range"
    }

    /** An area assignment names an area the verified location dataset does not contain, or the wrong level. */
    data class UnknownAreaReference(
        val areaId: String,
        val scope: PrayerConfigurationScope,
        override val detail: String,
    ) : PrayerConfigProblem {
        override val code: String get() = "unknown_area_reference"
    }

    /**
     * The revision id is already accepted but the payload differs from what was accepted under it.
     *
     * A revision identifies one immutable release, so two different payloads cannot share a revision id.
     * Accepting this would make the revision meaningless as a record of what is active.
     */
    data class RevisionReusedWithDifferentContent(
        val revisionId: String,
        val activeChecksum: String,
        val candidateChecksum: String,
        override val detail: String,
    ) : PrayerConfigProblem {
        override val code: String get() = "revision_reused_with_different_content"
    }

    /** The candidate revision is not newer than the one already accepted, so adopting it would go backwards. */
    data class StaleRevision(
        val activeRevisionNumber: Long,
        val candidateRevisionNumber: Long,
        override val detail: String,
    ) : PrayerConfigProblem {
        override val code: String get() = "stale_revision"
    }
}

/** The outcome of reading a manifest response body. */
sealed interface ManifestParseResult {

    data class Parsed(val manifest: PrayerConfigurationManifest) : ManifestParseResult

    /** The response is a valid, empty result — the slot has no current publication. */
    data object NoPublication : ManifestParseResult

    data class Malformed(val detail: String) : ManifestParseResult
}

/** The outcome of reading a snapshot payload. */
sealed interface SnapshotParseResult {

    data class Parsed(val snapshot: PrayerConfigurationSnapshot) : SnapshotParseResult

    /** The payload is valid JSON but names values this build does not support. */
    data class Invalid(val problems: List<PrayerConfigProblem>) : SnapshotParseResult

    /** The payload could not be read as a snapshot at all. */
    data class Malformed(val detail: String) : SnapshotParseResult
}

/**
 * The result of validating a parsed snapshot against the manifest and the active dataset.
 *
 * [problems] are fatal — any of them rejects the whole snapshot. [droppedAssignments] are *not*: an area
 * override that names an unknown area is removed from [snapshot] and reported here, so the rest of a valid
 * configuration still applies and the assignment falls back to the next applicable scope rather than
 * silently changing the intended times of a known area. See `docs/PRAYER_CONFIGURATION.md`.
 */
data class PrayerConfigValidation(
    val problems: List<PrayerConfigProblem>,
    val droppedAssignments: List<PrayerConfigProblem>,
    val snapshot: PrayerConfigurationSnapshot?,
) {
    /** True when no fatal problem was found and a usable snapshot came back. */
    val accepted: Boolean get() = problems.isEmpty() && snapshot != null
}

/**
 * Reads a manifest and a snapshot from Supabase REST responses.
 *
 * Parsing is separated from acceptance (see [PrayerConfigurationValidator]) for the same reason it is in
 * the dataset path: "is this a manifest?" and "may this manifest replace what the app holds?" are
 * different questions, and a test must be able to assert that an unpublished revision is *rejected with a
 * reason* rather than reported as unreadable.
 *
 * Every named value is mapped explicitly. An unknown calculation method, Asr convention, high-latitude
 * rule, scope or prayer is reported as an [PrayerConfigProblem] rather than dropped, because dropping it
 * would silently change the prayer times the publisher intended.
 */
object PrayerConfigurationParser {

    /**
     * The payload layout version this build reads.
     *
     * Version 1 is the flat JSON snapshot documented in `docs/PRAYER_CONFIGURATION.md`. A future layout
     * that changes the payload's shape must bump this and add a second decoder; until then a publication
     * that claims another version is left alone rather than being guessed at.
     */
    const val SUPPORTED_SCHEMA_VERSION: Int = 1

    val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(SUPPORTED_SCHEMA_VERSION)

    /** Reads the manifest from the raw JSON body of the current-publication endpoint. */
    fun parseManifestRestResponse(body: String): ManifestParseResult {
        val root = JsonReader.parse(body)
            ?: return ManifestParseResult.Malformed("The prayer-configuration manifest is not valid JSON.")
        val value = when (root) {
            is JsonValue.ArrayValue -> root.elements.firstOrNull() ?: return ManifestParseResult.NoPublication
            is JsonValue.ObjectValue -> root
            else -> return ManifestParseResult.Malformed(
                "The prayer-configuration manifest is neither a JSON object nor an array of objects.",
            )
        }
        return parseManifestObject(value)
    }

    /** Reads a manifest from an already-parsed JSON object. */
    fun parseManifestObject(value: JsonValue): ManifestParseResult {
        val configurationId = value.stringOrNull(FIELD_CONFIGURATION_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(FIELD_CONFIGURATION_ID)
        val revisionId = value.stringOrNull(FIELD_REVISION_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(FIELD_REVISION_ID)
        val revisionNumber = value.longOrNull(FIELD_REVISION_NUMBER)?.takeIf { it >= 0 }
            ?: return missing(FIELD_REVISION_NUMBER)
        val schemaVersion = value.intOrNull(FIELD_SCHEMA_VERSION) ?: return missing(FIELD_SCHEMA_VERSION)
        val statusRaw = value.stringOrNull(FIELD_STATUS)?.takeIf { it.isNotBlank() }
            ?: return missing(FIELD_STATUS)
        val status = PrayerConfigurationStatus.of(statusRaw) ?: return ManifestParseResult.Malformed(
            "The prayer-configuration manifest names an unknown status '$statusRaw'.",
        )
        val checksum = value.stringOrNull(FIELD_CHECKSUM)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(FIELD_CHECKSUM)

        return ManifestParseResult.Parsed(
            PrayerConfigurationManifest(
                configurationId = configurationId,
                revisionId = revisionId,
                revisionNumber = revisionNumber,
                schemaVersion = schemaVersion,
                status = status,
                checksum = checksum,
                publishedAt = value.stringOrNull(FIELD_PUBLISHED_AT),
                notes = value.stringOrNull(FIELD_NOTES),
            ),
        )
    }

    /** Reads a snapshot from its payload text. */
    fun parseSnapshot(text: String): SnapshotParseResult {
        val root = JsonReader.parse(text)
            ?: return SnapshotParseResult.Malformed("The prayer-configuration payload is not valid JSON.")
        val value = root as? JsonValue.ObjectValue
            ?: return SnapshotParseResult.Malformed("The prayer-configuration payload is not a JSON object.")

        val problems = mutableListOf<PrayerConfigProblem>()
        val configurationId = value.stringOrNull(FIELD_CONFIGURATION_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return SnapshotParseResult.Malformed("The payload is missing the field '$FIELD_CONFIGURATION_ID'.")
        val revisionId = value.stringOrNull(FIELD_REVISION_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return SnapshotParseResult.Malformed("The payload is missing the field '$FIELD_REVISION_ID'.")
        val revisionNumber = value.longOrNull(FIELD_REVISION_NUMBER)?.takeIf { it >= 0 }
            ?: return SnapshotParseResult.Malformed("The payload is missing the field '$FIELD_REVISION_NUMBER'.")
        val schemaVersion = value.intOrNull(FIELD_SCHEMA_VERSION)
            ?: return SnapshotParseResult.Malformed("The payload is missing the field '$FIELD_SCHEMA_VERSION'.")

        val globalValue = value.child(FIELD_GLOBAL)
            ?: return SnapshotParseResult.Malformed("The payload is missing the field '$FIELD_GLOBAL'.")
        val globalProfile = profileFrom(globalValue, "global", problems)
            ?: return SnapshotParseResult.Invalid(problems.toList())

        val overrides = mutableMapOf<String, PrayerAreaOverride>()
        val areasValue = value.child(FIELD_AREAS)
        if (areasValue != null) {
            val areas = areasValue as? JsonValue.ArrayValue
                ?: return SnapshotParseResult.Malformed("The payload's '$FIELD_AREAS' is not a JSON array.")
            for (element in areas.elements) {
                val areaId = element.stringOrNull(FIELD_AREA_ID)?.trim()?.takeIf { it.isNotEmpty() }
                if (areaId == null) {
                    problems += PrayerConfigProblem.MissingField(
                        field = FIELD_AREA_ID,
                        detail = "An area override is missing the field '$FIELD_AREA_ID'.",
                    )
                    continue
                }
                val scopeRaw = element.stringOrNull(FIELD_SCOPE)?.trim()?.takeIf { it.isNotEmpty() }
                val scope = scopeRaw?.let { PrayerConfigurationScope.of(it) }
                if (scope == null) {
                    problems += PrayerConfigProblem.UnsupportedValue(
                        field = FIELD_SCOPE,
                        value = scopeRaw ?: "",
                        detail = "Area '$areaId' names an unsupported scope '$scopeRaw'.",
                    )
                    continue
                }
                val profile = profileFrom(element, "area:$areaId", problems) ?: continue
                overrides[areaId] = PrayerAreaOverride(areaId = areaId, scope = scope, profile = profile)
            }
        }

        if (problems.isNotEmpty()) return SnapshotParseResult.Invalid(problems)

        return SnapshotParseResult.Parsed(
            PrayerConfigurationSnapshot(
                configurationId = configurationId,
                revisionId = revisionId,
                revisionNumber = revisionNumber,
                schemaVersion = schemaVersion,
                globalProfile = globalProfile,
                areaOverrides = overrides,
            ),
        )
    }

    /**
     * Reads a calculation profile from [value], appending any problem to [problems].
     *
     * Returns `null` when the profile cannot be built, which the caller turns into an
     * [SnapshotParseResult.Invalid] rather than a partial snapshot.
     */
    private fun profileFrom(
        value: JsonValue,
        context: String,
        problems: MutableList<PrayerConfigProblem>,
    ): PrayerCalculationProfile? {
        val methodRaw = value.stringOrNull(FIELD_METHOD)?.trim()?.takeIf { it.isNotEmpty() }
        val method = methodRaw?.let { name -> PrayerCalculationMethod.entries.firstOrNull { it.name == name } }
        if (method == null) {
            problems += PrayerConfigProblem.UnsupportedValue(
                field = FIELD_METHOD,
                value = methodRaw ?: "",
                detail = "The $context profile names an unsupported calculation method '$methodRaw'.",
            )
            return null
        }

        val asrRaw = value.stringOrNull(FIELD_ASR_METHOD)?.trim()?.takeIf { it.isNotEmpty() }
        val asr = asrRaw?.let { name -> AsrCalculationMethod.entries.firstOrNull { it.name == name } }
        if (asr == null) {
            problems += PrayerConfigProblem.UnsupportedValue(
                field = FIELD_ASR_METHOD,
                value = asrRaw ?: "",
                detail = "The $context profile names an unsupported Asr convention '$asrRaw'.",
            )
            return null
        }

        val ruleRaw = value.stringOrNull(FIELD_HIGH_LATITUDE_RULE)?.trim()?.takeIf { it.isNotEmpty() }
        val rule = ruleRaw?.let { name -> HighLatitudeRule.entries.firstOrNull { it.name == name } }
        if (rule == null) {
            problems += PrayerConfigProblem.UnsupportedValue(
                field = FIELD_HIGH_LATITUDE_RULE,
                value = ruleRaw ?: "",
                detail = "The $context profile names an unsupported high-latitude rule '$ruleRaw'.",
            )
            return null
        }

        val adjustments = mutableMapOf<PrayerName, Int>()
        val adjustmentsValue = value.child(FIELD_ADJUSTMENTS)
        if (adjustmentsValue != null) {
            val objectValue = adjustmentsValue as? JsonValue.ObjectValue
                ?: return run {
                    problems += PrayerConfigProblem.UnsupportedValue(
                        field = FIELD_ADJUSTMENTS,
                        value = context,
                        detail = "The $context profile's '$FIELD_ADJUSTMENTS' is not a JSON object.",
                    )
                    null
                }
            for ((prayerName, minutesValue) in objectValue.entries) {
                val prayer = PrayerName.entries.firstOrNull { it.name == prayerName }
                if (prayer == null) {
                    problems += PrayerConfigProblem.UnsupportedValue(
                        field = FIELD_ADJUSTMENTS,
                        value = prayerName,
                        detail = "The $context profile names an unsupported prayer '$prayerName'.",
                    )
                    return null
                }
                val minutes = (minutesValue as? JsonValue.NumberValue)?.value?.toInt()
                if (minutes == null) {
                    problems += PrayerConfigProblem.UnsupportedValue(
                        field = FIELD_ADJUSTMENTS,
                        value = prayerName,
                        detail = "The $context profile's adjustment for $prayerName is not a number.",
                    )
                    return null
                }
                if (minutes < -PrayerSettings.MAX_ADJUSTMENT_MINUTES || minutes > PrayerSettings.MAX_ADJUSTMENT_MINUTES) {
                    problems += PrayerConfigProblem.AdjustmentOutOfRange(
                        field = "$FIELD_ADJUSTMENTS.$prayerName",
                        minutes = minutes,
                        limit = PrayerSettings.MAX_ADJUSTMENT_MINUTES,
                        detail = "The $context profile's adjustment for $prayerName ($minutes) is outside " +
                            "±${PrayerSettings.MAX_ADJUSTMENT_MINUTES} minutes.",
                    )
                    return null
                }
                if (minutes != 0) adjustments[prayer] = minutes
            }
        }

        return PrayerCalculationProfile(
            method = method,
            asrMethod = asr,
            highLatitudeRule = rule,
            adjustmentsMinutes = adjustments,
        )
    }

    private fun missing(field: String): ManifestParseResult =
        ManifestParseResult.Malformed("The prayer-configuration manifest is missing the required field '$field'.")

    const val FIELD_CONFIGURATION_ID: String = "configuration_id"
    const val FIELD_REVISION_ID: String = "revision_id"
    const val FIELD_REVISION_NUMBER: String = "revision_number"
    const val FIELD_SCHEMA_VERSION: String = "schema_version"
    const val FIELD_STATUS: String = "status"
    const val FIELD_CHECKSUM: String = "checksum"
    const val FIELD_PUBLISHED_AT: String = "published_at"
    const val FIELD_NOTES: String = "notes"
    const val FIELD_GLOBAL: String = "global"
    const val FIELD_AREAS: String = "areas"
    const val FIELD_AREA_ID: String = "area_id"
    const val FIELD_SCOPE: String = "scope"
    const val FIELD_METHOD: String = "method"
    const val FIELD_ASR_METHOD: String = "asr_method"
    const val FIELD_HIGH_LATITUDE_RULE: String = "high_latitude_rule"
    const val FIELD_ADJUSTMENTS: String = "adjustments"
}

/**
 * Decides whether a well-formed manifest and its snapshot may replace what the app holds.
 *
 * Pure and synchronous: it takes the manifest, the parsed snapshot, the slot being synchronised and a
 * lookup from area id to administrative level, and returns problems. The area lookup is a function rather
 * than the whole dataset so the validator does not depend on the location subsystem and every rejection
 * path stays directly testable.
 */
object PrayerConfigurationValidator {

    private const val SHA256_HEX_LENGTH = 64

    /** Validates a manifest against the slot the caller is synchronising and the schemas this build supports. */
    fun validateManifest(
        manifest: PrayerConfigurationManifest,
        expectedConfigurationId: String,
        supportedSchemas: Set<Int> = PrayerConfigurationParser.SUPPORTED_SCHEMA_VERSIONS,
    ): List<PrayerConfigProblem> {
        val problems = mutableListOf<PrayerConfigProblem>()

        if (manifest.configurationId != expectedConfigurationId) {
            problems += PrayerConfigProblem.ConfigurationIdMismatch(
                expected = expectedConfigurationId,
                found = manifest.configurationId,
                detail = "The manifest describes configuration '${manifest.configurationId}' but " +
                    "'$expectedConfigurationId' was requested.",
            )
        }

        if (manifest.schemaVersion !in supportedSchemas) {
            problems += PrayerConfigProblem.UnsupportedSchemaVersion(
                found = manifest.schemaVersion,
                supported = supportedSchemas,
                detail = "The manifest describes schema version ${manifest.schemaVersion}, which this build " +
                    "cannot read (supported: ${supportedSchemas.sorted().joinToString(", ")}).",
            )
        }

        if (manifest.status != PrayerConfigurationStatus.PUBLISHED) {
            problems += PrayerConfigProblem.NotPublished(
                status = manifest.status,
                detail = "The manifest describes a ${manifest.status.name.lowercase()} revision, which must " +
                    "not be served to an app client.",
            )
        }

        if (!isSha256Hex(manifest.checksum)) {
            problems += PrayerConfigProblem.InvalidChecksum(
                checksum = manifest.checksum,
                detail = "The manifest's checksum is not a 64-character hexadecimal SHA-256 digest.",
            )
        }

        return problems.sortedWith(compareBy({ it.code }, { it.detail }))
    }

    /**
     * Validates a parsed snapshot against its manifest and the verified location dataset.
     *
     * The manifest and snapshot must agree on identity: a payload whose `revision_id` or
     * `configuration_id` differs from the manifest's is rejected, because otherwise a mismatched payload
     * could be recorded under the wrong revision.
     */
    fun validateSnapshot(
        snapshot: PrayerConfigurationSnapshot,
        manifest: PrayerConfigurationManifest,
        areaLevel: (String) -> BangladeshAreaLevel?,
        supportedSchemas: Set<Int> = PrayerConfigurationParser.SUPPORTED_SCHEMA_VERSIONS,
    ): PrayerConfigValidation {
        val problems = mutableListOf<PrayerConfigProblem>()

        if (snapshot.schemaVersion !in supportedSchemas) {
            problems += PrayerConfigProblem.UnsupportedSchemaVersion(
                found = snapshot.schemaVersion,
                supported = supportedSchemas,
                detail = "The payload describes schema version ${snapshot.schemaVersion}, which this build " +
                    "cannot read (supported: ${supportedSchemas.sorted().joinToString(", ")}).",
            )
        }

        if (snapshot.configurationId != manifest.configurationId) {
            problems += PrayerConfigProblem.ConfigurationIdMismatch(
                expected = manifest.configurationId,
                found = snapshot.configurationId,
                detail = "The payload describes configuration '${snapshot.configurationId}' but the manifest " +
                    "describes '${manifest.configurationId}'.",
            )
        }

        if (snapshot.revisionId != manifest.revisionId) {
            problems += PrayerConfigProblem.MissingField(
                field = PrayerConfigurationParser.FIELD_REVISION_ID,
                detail = "The payload names revision '${snapshot.revisionId}' but the manifest names " +
                    "'${manifest.revisionId}'.",
            )
        }

        if (snapshot.revisionNumber != manifest.revisionNumber) {
            problems += PrayerConfigProblem.MissingField(
                field = PrayerConfigurationParser.FIELD_REVISION_NUMBER,
                detail = "The payload names revision number ${snapshot.revisionNumber} but the manifest " +
                    "names ${manifest.revisionNumber}.",
            )
        }

        if (problems.isNotEmpty()) {
            return PrayerConfigValidation(problems.sortedBy { it.code }, emptyList(), null)
        }

        val dropped = mutableListOf<PrayerConfigProblem>()
        val kept = snapshot.areaOverrides.values.filter { override ->
            val level = areaLevel(override.areaId)
            val ok = level != null && PrayerConfigurationScope.forLevel(level) == override.scope
            if (!ok) {
                dropped += PrayerConfigProblem.UnknownAreaReference(
                    areaId = override.areaId,
                    scope = override.scope,
                    detail = if (level == null) {
                        "The configuration assigns a ${override.scope.name.lowercase()} override to area " +
                            "'${override.areaId}', which the verified dataset does not contain."
                    } else {
                        "The configuration assigns a ${override.scope.name.lowercase()} override to area " +
                            "'${override.areaId}', which is a ${level.name.lowercase()}."
                    },
                )
            }
            ok
        }

        val sanitized = snapshot.copy(areaOverrides = kept.associateBy { it.areaId })
        return PrayerConfigValidation(
            problems = emptyList(),
            droppedAssignments = dropped.sortedBy { it.code + (it as PrayerConfigProblem.UnknownAreaReference).areaId },
            snapshot = sanitized,
        )
    }

    /** True when [value] looks like a lower- or upper-case SHA-256 hex digest. */
    fun isSha256Hex(value: String): Boolean =
        value.length == SHA256_HEX_LENGTH && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}

/**
 * The integrity value of a prayer-configuration payload.
 *
 * SHA-256 over the payload text's UTF-8 bytes, lower-case hex — the same construction the dataset
 * publisher uses, so an operator computes it with the same tooling. It detects accidental corruption: a
 * truncated download, a half-written file, a payload that does not match the checksum beside it. It does
 * **not** establish authenticity: anyone who can alter the payload can alter the checksum with it, and
 * Salah makes no signature claim in this phase.
 */
object PrayerConfigurationChecksum {

    const val ALGORITHM: String = "SHA-256"

    fun of(payload: String): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        digest.update(payload.toByteArray(Charsets.UTF_8))
        return digest.digest().toLowercaseHex()
    }

    private fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
        for (byte in this@toLowercaseHex) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"
}
