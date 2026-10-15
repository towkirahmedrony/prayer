package io.github.towkirahmedrony.salah.core.domain.dataset

import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset
import java.security.MessageDigest

/**
 * One named file of a dataset payload.
 *
 * The name is part of the checksum, so two payloads cannot be made to collide by moving content
 * between a division table and a boundary table.
 */
data class DatasetPart(val name: String, val content: String)

/**
 * A complete dataset payload: the three texts a dataset is made of, plus the version they describe.
 *
 * **The same three texts the bundled assets hold, in the same tab-separated format.** That is
 * deliberate: a payload is parsed by the same [BangladeshLocationDataset.parse] the bundled assets go
 * through, so there is exactly one format, one parser and one set of invariants to keep correct. A
 * payload is therefore not a new kind of data — it is the same data, movable.
 *
 * A payload is inert. It has no file, no directory and no notion of being active: it is what a *candidate*
 * is made of and what a checksum is taken over, and nothing reads it until it has passed validation.
 */
data class DatasetPayload(
    val version: DatasetVersion,
    val areasText: String,
    val boundaryText: String,
    val localitiesText: String = "",
) {

    /**
     * The payload's parts, in the fixed order the checksum is taken over.
     *
     * The version travels as a part rather than only in the metadata beside the payload. That is what
     * makes a stored payload self-describing: its integrity value covers the version as well as the
     * three tables, so a metadata record that named the wrong version for a payload could not match
     * the payload's own checksum, and the record is therefore not trusted to be correct — it is
     * checked.
     */
    val parts: List<DatasetPart>
        get() = listOf(
            DatasetPart(VERSION_PART, version.value),
            DatasetPart(AREAS_PART, areasText),
            DatasetPart(BOUNDARY_PART, boundaryText),
            DatasetPart(LOCALITIES_PART, localitiesText),
        )

    /**
     * Parses the payload into a dataset, using the same parser the bundled assets use.
     *
     * Parsing is lenient by contract — a bad line is recorded in
     * [BangladeshLocationDataset.rejectedLines] rather than thrown — so this never fails. Whether the
     * result is *acceptable* is a separate question, answered by [DatasetValidator].
     */
    fun toDataset(): BangladeshLocationDataset = BangladeshLocationDataset.parse(
        version = version.value,
        areasText = areasText,
        boundaryText = boundaryText,
        localitiesText = localitiesText,
    )

    /** The integrity value of this payload. */
    fun checksum(): String = DatasetChecksum.of(parts)

    companion object {
        /** The file holding the payload's own version, so a stored payload describes itself. */
        const val VERSION_PART: String = "dataset.version"

        /** The hierarchy file's name, matching the bundled asset. */
        const val AREAS_PART: String = "bd_areas.tsv"

        /** The boundary file's name, matching the bundled asset. */
        const val BOUNDARY_PART: String = "bd_boundary.tsv"

        /** The locality file's name, matching the bundled asset. */
        const val LOCALITIES_PART: String = "bd_localities.tsv"

        /** Every part name, in checksum order. */
        val PART_NAMES: List<String> = listOf(VERSION_PART, AREAS_PART, BOUNDARY_PART, LOCALITIES_PART)

        /**
         * Reads a payload from parts keyed by name, ignoring the order they are supplied in.
         *
         * Requires the version, the hierarchy and the boundary; the locality layer is optional and
         * absent means an empty one, matching the bundled parser's contract.
         */
        fun ofParts(parts: Map<String, String>): DatasetPayload? {
            val version = parts[VERSION_PART]?.takeIf { it.isNotBlank() } ?: return null
            val areas = parts[AREAS_PART] ?: return null
            val boundary = parts[BOUNDARY_PART] ?: return null
            return DatasetPayload(
                version = DatasetVersion.of(version),
                areasText = areas,
                boundaryText = boundary,
                localitiesText = parts[LOCALITIES_PART].orEmpty(),
            )
        }
    }
}

/**
 * The payload integrity value.
 *
 * **What a checksum does and does not establish.** It detects accidental corruption — a truncated
 * download, a half-written file, a payload that does not match what was published — and it is what
 * stops a partially written candidate from ever being read as if it were complete. It does **not**
 * establish authenticity: anyone who can alter the payload can alter the checksum beside it. Salah has
 * no key-management design in this phase and therefore makes no authenticity claim, which is why no
 * signature is pretended here. When published datasets arrive, integrity and authenticity are separate
 * decisions and this type only answers the first.
 *
 * The construction is length-prefixed and part-named so the encoding is unambiguous: content that
 * happened to contain the separators cannot produce the same digest as a different split of the same
 * bytes.
 */
object DatasetChecksum {

    /** The digest algorithm. Named so a stored checksum can be interpreted later. */
    const val ALGORITHM: String = "SHA-256"

    /** The checksum of [parts], in the order supplied. */
    fun of(parts: List<DatasetPart>): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        for (part in parts) {
            digest.updatePart(part.name)
            digest.update(SEPARATOR_FIELD)
            digest.updatePart(part.content)
            digest.update(SEPARATOR_PART)
        }
        return digest.digest().toLowercaseHex()
    }

    private const val SEPARATOR_FIELD: Byte = ':'.code.toByte()
    private const val SEPARATOR_PART: Byte = '|'.code.toByte()

    private fun MessageDigest.updatePart(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        update(SEPARATOR_FIELD)
        update(bytes)
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
