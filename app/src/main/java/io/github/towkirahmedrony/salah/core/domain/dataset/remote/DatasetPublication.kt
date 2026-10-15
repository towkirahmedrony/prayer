package io.github.towkirahmedrony.salah.core.domain.dataset.remote

import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetVersion

/**
 * The lifecycle state of a dataset publication.
 *
 * Only [PUBLISHED] may be served to an app client. The other two exist so an operator can stage a
 * candidate ([DRAFT]) or withdraw one ([ARCHIVED]) without deleting its row — and, crucially, without
 * the row ever becoming the current publication. The app checks this status itself rather than trusting
 * that the database pointer only ever names a published row: the pointer is an operator-facing field
 * and the check is cheap, so both ends enforce the same rule.
 */
enum class PublicationStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED,
    ;

    companion object {
        /** The status named by [raw], case-insensitively, or `null` when it is not a known status. */
        fun of(raw: String): PublicationStatus? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * A remote publication of a dataset, as described by the publication manifest.
 *
 * **This is the contract.** It is small, flat and explicit because it is what a publisher and the app
 * have to agree on, and every field is either an identity, an integrity value or a completeness
 * assertion — nothing here is presentation. It mirrors one row of the Supabase view
 * `dataset_current_publication`; see `docs/DATABASE-SCHEMA.md` and
 * `supabase/migrations/0001_dataset_publications.sql`.
 *
 * **The two identifiers, and why both exist.** [datasetId] names the *slot* (`bd_locations`) — the slot
 * a future published dataset type would reuse without disturbing this one. [publicationId] names one
 * immutable *release* inside that slot. The publication id is the value the app compares to decide
 * whether the remote publication differs from what it already holds, and it is opaque: it is never
 * parsed, ordered or interpreted. [version] is the human-readable dataset version (the same string the
 * bundled assets carry) and is *not* used to decide which release is newer — see
 * [io.github.towkirahmedrony.salah.core.domain.dataset.DatasetVersion].
 *
 * **The checksum is over the logical payload, not the transport bytes.** [checksum] is the
 * `DatasetChecksum` of the payload's parts (its version file and the three TSV tables), computed by the
 * same code the local activation path uses. The app therefore verifies a downloaded payload against a
 * value the local store independently agrees with, instead of trusting a hash of the archive beside it.
 */
data class DatasetPublication(
    /** The dataset slot this publication belongs to, e.g. `bd_locations`. */
    val datasetId: String,

    /** The immutable identifier of this release. Opaque; compared for equality only. */
    val publicationId: String,

    /** The dataset version string, mirrored into the applied payload's own version file. */
    val version: DatasetVersion,

    /** The payload layout version. The app supports a fixed set; see [DatasetPublicationParser]. */
    val payloadSchemaVersion: Int,

    /** The lifecycle state. Only [PublicationStatus.PUBLISHED] may be applied. */
    val status: PublicationStatus,

    /** The SHA-256 of the payload's logical parts, lower-case hex. */
    val checksum: String,

    /** The storage object path of the immutable payload archive, relative to the dataset bucket. */
    val payloadPath: String,

    /** The payload archive's size in bytes, when the publisher recorded one. */
    val payloadSizeBytes: Long? = null,

    /** How many administrative areas the payload should contain, when asserted. */
    val areaCount: Int? = null,

    /** How many localities the payload should contain, when asserted. */
    val localityCount: Int? = null,

    /** How many boundary rings the payload should contain, when asserted. */
    val boundaryRingCount: Int? = null,

    /** When the publication was made, as an ISO-8601 instant, when recorded. */
    val publishedAt: String? = null,

    /** Free-text operator notes, when present. Never displayed to the user. */
    val notes: String? = null,
)

/** One reason a publication manifest is not usable. */
sealed interface PublicationProblem {

    /** A short, stable code suitable for diagnostics and tests. */
    val code: String

    /** A human-readable explanation. Never contains a credential. */
    val detail: String

    /** The manifest describes a payload layout this build cannot read. */
    data class UnsupportedSchemaVersion(
        val found: Int,
        val supported: Set<Int>,
        override val detail: String,
    ) : PublicationProblem {
        override val code: String get() = "unsupported_schema_version"
    }

    /** The publication is not published, so it must not be served to an app client. */
    data class NotPublished(
        val status: PublicationStatus,
        override val detail: String,
    ) : PublicationProblem {
        override val code: String get() = "publication_not_published"
    }

    /** The published checksum is not a SHA-256 hex string, so it could not be verified. */
    data class InvalidChecksum(val checksum: String, override val detail: String) : PublicationProblem {
        override val code: String get() = "invalid_checksum"
    }

    /** The manifest describes a different dataset slot than the one being synchronised. */
    data class DatasetIdMismatch(
        val expected: String,
        val found: String,
        override val detail: String,
    ) : PublicationProblem {
        override val code: String get() = "dataset_id_mismatch"
    }
}

/** The outcome of reading a manifest response body. */
sealed interface ManifestParseResult {

    /** A well-formed manifest was read. Whether it is acceptable is a separate question. */
    data class Parsed(val publication: DatasetPublication) : ManifestParseResult

    /** The response is a valid, empty result — the slot has no current publication. */
    data object NoPublication : ManifestParseResult

    /** The response could not be read as a manifest at all. */
    data class Malformed(val detail: String) : ManifestParseResult
}

/**
 * Reads a manifest from a Supabase REST response.
 *
 * The response is the array PostgREST returns for a filtered query, so the reader accepts an array and
 * takes its single element, and also accepts a bare object for callers that fetch a single row
 * differently. Required fields are checked here; *semantic* acceptance (schema version, status, checksum
 * shape) is [DatasetPublicationValidator]'s job, so a manifest that is well-formed but unusable is
 * reported as a specific problem rather than as "malformed".
 *
 * `docs/DATABASE-SCHEMA.md` documents the column names this reads.
 */
object DatasetPublicationParser {

    /**
     * The payload layout version this build reads.
     *
     * Version 1 is the three tab-separated tables plus the version file, zipped — the same data the
     * bundled assets hold. A future layout that changes the payload's shape must bump this and add a
     * second decoder; until then, a publication that claims another version is left alone rather than
     * being guessed at.
     */
    const val SUPPORTED_PAYLOAD_SCHEMA_VERSION: Int = 1

    /** Every payload layout version this build supports. */
    val SUPPORTED_PAYLOAD_SCHEMA_VERSIONS: Set<Int> = setOf(SUPPORTED_PAYLOAD_SCHEMA_VERSION)

    /** Reads [body], the raw response from the current-publication endpoint. */
    fun parseRestResponse(body: String): ManifestParseResult {
        val root = JsonReader.parse(body)
            ?: return ManifestParseResult.Malformed("The publication manifest is not valid JSON.")
        val value = when (root) {
            is JsonValue.ArrayValue -> root.elements.firstOrNull() ?: return ManifestParseResult.NoPublication
            is JsonValue.ObjectValue -> root
            else -> return ManifestParseResult.Malformed(
                "The publication manifest is neither a JSON object nor an array of objects.",
            )
        }
        return parseObject(value)
    }

    /** Reads a manifest from an already-parsed JSON object. */
    fun parseObject(value: JsonValue): ManifestParseResult {
        val datasetId = value.stringOrNull(FIELD_DATASET_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(FIELD_DATASET_ID)
        val publicationId = value.stringOrNull(FIELD_PUBLICATION_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(FIELD_PUBLICATION_ID)
        val version = value.stringOrNull(FIELD_VERSION)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(FIELD_VERSION)
        val schemaVersion = value.intOrNull(FIELD_PAYLOAD_SCHEMA_VERSION) ?: return missing(FIELD_PAYLOAD_SCHEMA_VERSION)
        val statusRaw = value.stringOrNull(FIELD_STATUS)?.takeIf { it.isNotBlank() } ?: return missing(FIELD_STATUS)
        val status = PublicationStatus.of(statusRaw) ?: return ManifestParseResult.Malformed(
            "The publication manifest names an unknown status '$statusRaw'.",
        )
        val checksum = value.stringOrNull(FIELD_CHECKSUM)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(FIELD_CHECKSUM)
        val payloadPath = value.stringOrNull(FIELD_PAYLOAD_PATH)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return missing(FIELD_PAYLOAD_PATH)

        return ManifestParseResult.Parsed(
            DatasetPublication(
                datasetId = datasetId,
                publicationId = publicationId,
                version = DatasetVersion.of(version),
                payloadSchemaVersion = schemaVersion,
                status = status,
                checksum = checksum,
                payloadPath = payloadPath,
                payloadSizeBytes = value.longOrNull(FIELD_PAYLOAD_SIZE_BYTES)?.takeIf { it >= 0 },
                areaCount = value.intOrNull(FIELD_AREA_COUNT)?.takeIf { it >= 0 },
                localityCount = value.intOrNull(FIELD_LOCALITY_COUNT)?.takeIf { it >= 0 },
                boundaryRingCount = value.intOrNull(FIELD_BOUNDARY_RING_COUNT)?.takeIf { it >= 0 },
                publishedAt = value.stringOrNull(FIELD_PUBLISHED_AT),
                notes = value.stringOrNull(FIELD_NOTES),
            ),
        )
    }

    private fun missing(field: String): ManifestParseResult =
        ManifestParseResult.Malformed("The publication manifest is missing the required field '$field'.")

    /** The column names the reader understands, kept in one place so the DB contract has one mirror. */
    const val FIELD_DATASET_ID: String = "dataset_id"
    const val FIELD_PUBLICATION_ID: String = "publication_id"
    const val FIELD_VERSION: String = "version"
    const val FIELD_PAYLOAD_SCHEMA_VERSION: String = "payload_schema_version"
    const val FIELD_STATUS: String = "status"
    const val FIELD_CHECKSUM: String = "checksum"
    const val FIELD_PAYLOAD_PATH: String = "payload_path"
    const val FIELD_PAYLOAD_SIZE_BYTES: String = "payload_size_bytes"
    const val FIELD_AREA_COUNT: String = "area_count"
    const val FIELD_LOCALITY_COUNT: String = "locality_count"
    const val FIELD_BOUNDARY_RING_COUNT: String = "boundary_ring_count"
    const val FIELD_PUBLISHED_AT: String = "published_at"
    const val FIELD_NOTES: String = "notes"
}

/**
 * Decides whether a well-formed manifest may be applied.
 *
 * Separate from parsing because the two answer different questions: parsing asks "is this a manifest?",
 * this asks "may this manifest be trusted to replace the app's data?". Keeping them apart is what lets a
 * test assert that an unpublished publication is *rejected with a reason* rather than reported as
 * unreadable, and what keeps the "only published data is served" rule enforceable at the point of use.
 */
object DatasetPublicationValidator {

    /** The minimum length of a SHA-256 hex digest, used only to reject obvious nonsense. */
    private const val SHA256_HEX_LENGTH = 64

    /**
     * Validates [publication] against the slot the caller is synchronising and the schemas this build
     * supports.
     */
    fun validate(
        publication: DatasetPublication,
        expectedDatasetId: String,
        supportedSchemas: Set<Int> = DatasetPublicationParser.SUPPORTED_PAYLOAD_SCHEMA_VERSIONS,
    ): List<PublicationProblem> {
        val problems = mutableListOf<PublicationProblem>()

        if (publication.datasetId != expectedDatasetId) {
            problems += PublicationProblem.DatasetIdMismatch(
                expected = expectedDatasetId,
                found = publication.datasetId,
                detail = "The publication manifest describes dataset '${publication.datasetId}' but " +
                    "'$expectedDatasetId' was requested.",
            )
        }

        if (publication.payloadSchemaVersion !in supportedSchemas) {
            problems += PublicationProblem.UnsupportedSchemaVersion(
                found = publication.payloadSchemaVersion,
                supported = supportedSchemas,
                detail = "The publication manifest describes payload schema version " +
                    "${publication.payloadSchemaVersion}, which this build cannot read " +
                    "(supported: ${supportedSchemas.sorted().joinToString(", ")}).",
            )
        }

        if (publication.status != PublicationStatus.PUBLISHED) {
            problems += PublicationProblem.NotPublished(
                status = publication.status,
                detail = "The publication manifest describes a ${publication.status.name.lowercase()} " +
                    "publication, which must not be served to an app client.",
            )
        }

        if (!isSha256Hex(publication.checksum)) {
            problems += PublicationProblem.InvalidChecksum(
                checksum = publication.checksum,
                detail = "The publication manifest's checksum is not a 64-character hexadecimal " +
                    "SHA-256 digest.",
            )
        }

        return problems.sortedBy { it.code }
    }

    /** True when [value] looks like a lower- or upper-case SHA-256 hex digest. */
    fun isSha256Hex(value: String): Boolean =
        value.length == SHA256_HEX_LENGTH && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}
