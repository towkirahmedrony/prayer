package io.github.towkirahmedrony.salah.core.domain.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Decoding the published payload archive.
 *
 * The archive is remote input, so every test here is about what the decoder *refuses*: an extra entry, a
 * duplicated entry, a missing part, an archive that is not an archive, and content that expands past the
 * ceiling. A decoder that accepted any of these would let a publication smuggle past validation the very
 * things the local activation path assumes cannot happen.
 */
class DatasetPayloadArchiveTest {

    private val areas = "DIVISION\tBD10\tBD\tKhulna\tখুলনা\t22.8\t89.3"
    private val boundary = "0\t22.0\t88.0\n0\t25.0\t88.0\n0\t25.0\t91.0"
    private val localities = "suburb\tBD1001\tMirpur\t23.8\t90.4"

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { archive ->
            for ((name, content) in entries) {
                archive.putNextEntry(ZipEntry(name))
                archive.write(content.toByteArray(Charsets.UTF_8))
                archive.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun fullZip(): ByteArray = zip(
        DatasetPayload.VERSION_PART to "v2",
        DatasetPayload.AREAS_PART to areas,
        DatasetPayload.BOUNDARY_PART to boundary,
        DatasetPayload.LOCALITIES_PART to localities,
    )

    @Test
    fun `a well-formed archive decodes into the same payload it was built from`() {
        val expected = DatasetPayload(
            version = DatasetVersion.of("v2"),
            areasText = areas,
            boundaryText = boundary,
            localitiesText = localities,
        )

        val result = DatasetPayloadArchive.read(fullZip())

        assertTrue(result.toString(), result is DatasetPayloadArchive.Result.Decoded)
        val payload = (result as DatasetPayloadArchive.Result.Decoded).payload
        assertEquals(expected, payload)
        assertEquals(expected.checksum(), payload.checksum())
    }

    @Test
    fun `the locality layer is optional, matching the bundled asset contract`() {
        val result = DatasetPayloadArchive.read(
            zip(
                DatasetPayload.VERSION_PART to "v2",
                DatasetPayload.AREAS_PART to areas,
                DatasetPayload.BOUNDARY_PART to boundary,
            ),
        )

        assertTrue(result.toString(), result is DatasetPayloadArchive.Result.Decoded)
        assertEquals("", (result as DatasetPayloadArchive.Result.Decoded).payload.localitiesText)
    }

    @Test
    fun `a missing required part is refused`() {
        val result = DatasetPayloadArchive.read(
            zip(
                DatasetPayload.VERSION_PART to "v2",
                DatasetPayload.AREAS_PART to areas,
            ),
        )

        assertTrue(result is DatasetPayloadArchive.Result.Malformed)
    }

    @Test
    fun `a blank version part is refused`() {
        val result = DatasetPayloadArchive.read(
            zip(
                DatasetPayload.VERSION_PART to "   ",
                DatasetPayload.AREAS_PART to areas,
                DatasetPayload.BOUNDARY_PART to boundary,
            ),
        )

        assertTrue(result is DatasetPayloadArchive.Result.Malformed)
    }

    @Test
    fun `an unexpected entry is refused`() {
        val result = DatasetPayloadArchive.read(
            zip(
                DatasetPayload.VERSION_PART to "v2",
                DatasetPayload.AREAS_PART to areas,
                DatasetPayload.BOUNDARY_PART to boundary,
                "evil.sh" to "rm -rf /",
            ),
        )

        assertTrue(result is DatasetPayloadArchive.Result.Malformed)
        assertTrue(
            (result as DatasetPayloadArchive.Result.Malformed).detail.contains("unexpected entry"),
        )
    }

    @Test
    fun `a hand-built stored archive decodes, so the duplicate test below is not vacuous`() {
        val result = DatasetPayloadArchive.read(
            rawStoredZip(
                listOf(
                    DatasetPayload.VERSION_PART to "v2",
                    DatasetPayload.AREAS_PART to areas,
                    DatasetPayload.BOUNDARY_PART to boundary,
                ),
            ),
        )

        assertTrue(result.toString(), result is DatasetPayloadArchive.Result.Decoded)
    }

    @Test
    fun `a duplicated entry is refused`() {
        // Written by hand: java.util.zip.ZipOutputStream refuses to emit a duplicate name, and the
        // decoder's guard exists for archives produced by other tools, so the case cannot be built with
        // the standard writer.
        val result = DatasetPayloadArchive.read(
            rawStoredZip(
                listOf(
                    DatasetPayload.VERSION_PART to "v2",
                    DatasetPayload.AREAS_PART to areas,
                    DatasetPayload.BOUNDARY_PART to boundary,
                    DatasetPayload.AREAS_PART to areas,
                ),
            ),
        )

        assertTrue(result.toString(), result is DatasetPayloadArchive.Result.Malformed)
    }

    @Test
    fun `bytes that are not an archive are refused`() {
        val result = DatasetPayloadArchive.read("this is not a zip".toByteArray())

        assertTrue(result is DatasetPayloadArchive.Result.Malformed)
    }

    @Test
    fun `a truncated archive is refused`() {
        val bytes = fullZip()

        val result = DatasetPayloadArchive.read(bytes.copyOfRange(0, bytes.size / 2))

        assertTrue(result is DatasetPayloadArchive.Result.Malformed)
    }

    @Test
    fun `an archive that expands past the ceiling is refused`() {
        val result = DatasetPayloadArchive.read(
            bytes = zip(
                DatasetPayload.VERSION_PART to "v2",
                DatasetPayload.AREAS_PART to "x".repeat(4096),
                DatasetPayload.BOUNDARY_PART to boundary,
            ),
            maxUncompressedBytes = 1024,
        )

        assertTrue(result is DatasetPayloadArchive.Result.Malformed)
    }

    @Test
    fun `an empty archive is refused`() {
        assertTrue(DatasetPayloadArchive.read(zip()) is DatasetPayloadArchive.Result.Malformed)
    }

    /**
     * A stored (uncompressed) zip written by hand, so duplicate entry names can be produced.
     *
     * Deliberately minimal — local file headers, a central directory and an end-of-directory record —
     * because that is all the decoder needs and all the test needs to control.
     */
    private fun rawStoredZip(entries: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var offset = 0
        for ((name, content) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val data = content.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value.toInt()

            val local = ByteArrayOutputStream().apply {
                writeIntLe(0x04034b50)
                writeShortLe(20)
                writeShortLe(0)
                writeShortLe(0) // stored
                writeShortLe(0)
                writeShortLe(0)
                writeIntLe(crc)
                writeIntLe(data.size)
                writeIntLe(data.size)
                writeShortLe(nameBytes.size)
                writeShortLe(0)
                write(nameBytes)
                write(data)
            }.toByteArray()

            out.write(local)
            central.writeIntLe(0x02014b50)
            central.writeShortLe(20)
            central.writeShortLe(20)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeIntLe(crc)
            central.writeIntLe(data.size)
            central.writeIntLe(data.size)
            central.writeShortLe(nameBytes.size)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeIntLe(0)
            central.writeIntLe(offset)
            central.write(nameBytes)

            offset += local.size
        }

        val centralBytes = central.toByteArray()
        out.write(centralBytes)
        out.writeIntLe(0x06054b50)
        out.writeShortLe(0)
        out.writeShortLe(0)
        out.writeShortLe(entries.size)
        out.writeShortLe(entries.size)
        out.writeIntLe(centralBytes.size)
        out.writeIntLe(offset)
        out.writeShortLe(0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
