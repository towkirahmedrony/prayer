package io.github.towkirahmedrony.salah.core.domain.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
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
    fun `a duplicated entry is refused`() {
        val result = DatasetPayloadArchive.read(
            zip(
                DatasetPayload.VERSION_PART to "v2",
                DatasetPayload.AREAS_PART to areas,
                DatasetPayload.BOUNDARY_PART to boundary,
                DatasetPayload.AREAS_PART to areas,
            ),
        )

        assertTrue(result is DatasetPayloadArchive.Result.Malformed)
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
}
