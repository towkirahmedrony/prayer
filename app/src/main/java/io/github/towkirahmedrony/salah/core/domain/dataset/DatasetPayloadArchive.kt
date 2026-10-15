package io.github.towkirahmedrony.salah.core.domain.dataset

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * The transport form of a [DatasetPayload]: one immutable ZIP archive holding the payload's parts.
 *
 * **Why an archive and not three separate downloads.** A payload is three files that must arrive
 * together — a hierarchy, a boundary and a locality layer that are only meaningful with each other — and
 * activated together or not at all. Fetching them separately would triple the round trips and introduce
 * a state where two of the three are present, which is exactly the partial dataset the Phase 1
 * activation design exists to make impossible. One object with one checksum keeps the unit of transfer
 * and the unit of validation the same size.
 *
 * **The archive is not the identity.** The checksum that is published and verified is the
 * [DatasetPayload.checksum] over the *parts*, computed after this decoder unpacks them, not a hash of
 * the ZIP bytes. Compression framing (entry order, timestamps, compression level) is therefore not part
 * of what the app trusts: the publisher can rebuild the archive and the app still verifies the same
 * value, against the same local code the activation path uses.
 *
 * **Bounds are part of the decoder, not the caller.** The archive is remote input, so it is read under a
 * hard uncompressed-size ceiling and its entry names are checked against the payload's known parts. An
 * entry the payload does not define is refused rather than ignored, so a publication cannot smuggle an
 * extra file past validation by naming it something unexpected.
 */
object DatasetPayloadArchive {

    /**
     * The largest total uncompressed payload accepted, in bytes.
     *
     * The bundled dataset is about 141 KB uncompressed. This ceiling is generous by two orders of
     * magnitude — enough for a substantially larger legitimate publication — while still bounding what a
     * hostile or corrupt archive can expand to in memory.
     */
    const val MAX_UNCOMPRESSED_BYTES: Long = 16L * 1024 * 1024

    /** What reading an archive produced. */
    sealed interface Result {

        /** The archive decoded into an assembled payload. */
        data class Decoded(val payload: DatasetPayload) : Result

        /** The archive could not be decoded into a payload. Nothing has been written. */
        data class Malformed(val detail: String) : Result
    }

    /**
     * Decodes [bytes] into a [DatasetPayload], or reports why it could not.
     *
     * @param maxUncompressedBytes the total uncompressed ceiling; entries beyond it fail the read.
     */
    fun read(bytes: ByteArray, maxUncompressedBytes: Long = MAX_UNCOMPRESSED_BYTES): Result {
        val parts = LinkedHashMap<String, String>()
        var total = 0L

        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) {
                        archive.closeEntry()
                        continue
                    }
                    if (name !in DatasetPayload.PART_NAMES) {
                        archive.closeEntry()
                        return Result.Malformed(
                            "The payload archive contains an unexpected entry '$name'.",
                        )
                    }
                    if (parts.containsKey(name)) {
                        archive.closeEntry()
                        return Result.Malformed("The payload archive contains '$name' more than once.")
                    }
                    val content = readEntryBounded(archive, maxUncompressedBytes - total)
                        ?: return Result.Malformed(
                            "The payload archive's content is larger than the " +
                                "$maxUncompressedBytes byte limit.",
                        )
                    total += content.toByteArray(Charsets.UTF_8).size.toLong()
                    parts[name] = content
                    archive.closeEntry()
                }
            }
        } catch (error: Exception) {
            return Result.Malformed(
                "The payload archive could not be read: ${error.message ?: error::class.simpleName}",
            )
        }

        val payload = DatasetPayload.ofParts(parts)
            ?: return Result.Malformed(
                "The payload archive is missing a required part " +
                    "(expected ${DatasetPayload.PART_NAMES.joinToString(", ")}).",
            )
        return Result.Decoded(payload)
    }

    /** Reads one entry into text, or `null` when it exceeds [remaining] bytes. */
    private fun readEntryBounded(archive: ZipInputStream, remaining: Long): String? {
        if (remaining <= 0) return null
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var read = 0L
        while (true) {
            val count = archive.read(chunk)
            if (count < 0) break
            read += count
            if (read > remaining) return null
            buffer.write(chunk, 0, count)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private const val READ_CHUNK_BYTES = 16 * 1024
}
