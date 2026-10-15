package io.github.towkirahmedrony.salah.core.domain.dataset.remote

import io.github.towkirahmedrony.salah.core.domain.dataset.DatasetVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The publication manifest contract: reading it, and deciding whether it may be applied.
 *
 * Parsing and semantic acceptance are deliberately separate concerns with separate tests, because they
 * answer different questions ("is this a manifest?" versus "may this manifest replace the app's data?")
 * and a failure that conflated them would report a draft publication as *unreadable* rather than as
 * *unpublished* — hiding the one diagnosis an operator needs.
 *
 * Every JSON string here is written with ordinary escaped quotes. An earlier version used triple-quoted
 * literals, and a quote at a literal's edge is subtle enough that one numeric field silently emitted
 * `1234"`; the builder below is the version that cannot do that.
 */
class DatasetPublicationTest {

    private val checksum = "a".repeat(64)

    private fun jsonObject(fields: List<String>): String = "{${fields.joinToString(",")}}"

    private fun manifestBody(
        datasetId: String = "bd_locations",
        publicationId: String = "pub-2",
        version: String = "v2",
        schema: Int = 1,
        status: String = "published",
        checksum: String? = this.checksum,
        payloadPath: String? = "bd_locations/pub-2/payload.zip",
        payloadSizeBytes: Long? = null,
        areaCount: Int? = null,
        localityCount: Int? = null,
        boundaryRingCount: Int? = null,
        notes: String? = null,
    ): String {
        val fields = mutableListOf<String>()
        fun quoted(name: String, value: String) {
            fields += "\"$name\":\"$value\""
        }

        fun number(name: String, value: Any) {
            fields += "\"$name\":$value"
        }

        quoted("dataset_id", datasetId)
        quoted("publication_id", publicationId)
        quoted("version", version)
        number("payload_schema_version", schema)
        quoted("status", status)
        if (checksum != null) quoted("checksum", checksum)
        if (payloadPath != null) quoted("payload_path", payloadPath)
        if (payloadSizeBytes != null) number("payload_size_bytes", payloadSizeBytes)
        if (areaCount != null) number("area_count", areaCount)
        if (localityCount != null) number("locality_count", localityCount)
        if (boundaryRingCount != null) number("boundary_ring_count", boundaryRingCount)
        if (notes != null) quoted("notes", notes)
        return jsonObject(fields)
    }

    private fun parsed(body: String): DatasetPublication {
        val result = DatasetPublicationParser.parseRestResponse(body)
        if (result !is ManifestParseResult.Parsed) {
            throw AssertionError("expected a parsed manifest, got $result for body: $body")
        }
        return result.publication
    }

    // Reading ------------------------------------------------------------------------------------

    @Test
    fun `a full manifest is read from the PostgREST array form`() {
        val publication = parsed("[${manifestBody()}]")

        assertEquals("bd_locations", publication.datasetId)
        assertEquals("pub-2", publication.publicationId)
        assertEquals(DatasetVersion.of("v2"), publication.version)
        assertEquals(1, publication.payloadSchemaVersion)
        assertEquals(PublicationStatus.PUBLISHED, publication.status)
        assertEquals(checksum, publication.checksum)
        assertEquals("bd_locations/pub-2/payload.zip", publication.payloadPath)
    }

    @Test
    fun `a bare object is read as well as the array form`() {
        assertEquals("pub-2", parsed(manifestBody()).publicationId)
    }

    @Test
    fun `an empty result means the slot has no current publication`() {
        assertEquals(ManifestParseResult.NoPublication, DatasetPublicationParser.parseRestResponse("[]"))
    }

    @Test
    fun `optional fields are read when present`() {
        val body = "[${manifestBody(
            payloadSizeBytes = 1234,
            areaCount = 579,
            localityCount = 1216,
            boundaryRingCount = 13,
            notes = "hello",
        )}]"

        val publication = parsed(body)

        assertEquals(1234L, publication.payloadSizeBytes)
        assertEquals(579, publication.areaCount)
        assertEquals(1216, publication.localityCount)
        assertEquals(13, publication.boundaryRingCount)
        assertEquals("hello", publication.notes)
    }

    @Test
    fun `a manifest missing a required field is malformed and names the field`() {
        val bodies = listOf(
            "[" + manifestBody().replace("\"dataset_id\":\"bd_locations\",", "") + "]",
            "[" + manifestBody().replace("\"publication_id\":\"pub-2\",", "") + "]",
            "[" + manifestBody().replace("\"version\":\"v2\",", "") + "]",
            "[" + manifestBody().replace("\"payload_schema_version\":1,", "") + "]",
            "[" + manifestBody().replace("\"status\":\"published\",", "") + "]",
            "[${manifestBody(checksum = null)}]",
            "[${manifestBody(payloadPath = null)}]",
        )

        for (body in bodies) {
            val result = DatasetPublicationParser.parseRestResponse(body)
            assertTrue("expected malformed for $body", result is ManifestParseResult.Malformed)
            assertTrue(
                "expected a missing-field message for $body",
                (result as ManifestParseResult.Malformed).detail.contains("required field"),
            )
        }
    }

    @Test
    fun `an unknown status is malformed`() {
        val result = DatasetPublicationParser.parseRestResponse("[${manifestBody(status = "banana")}]")

        assertTrue(result is ManifestParseResult.Malformed)
    }

    @Test
    fun `invalid JSON is malformed`() {
        assertTrue(DatasetPublicationParser.parseRestResponse("not json") is ManifestParseResult.Malformed)
    }

    @Test
    fun `a JSON scalar is malformed`() {
        assertTrue(DatasetPublicationParser.parseRestResponse("42") is ManifestParseResult.Malformed)
    }

    // Validating ---------------------------------------------------------------------------------

    @Test
    fun `a well-formed published manifest is accepted`() {
        val problems = DatasetPublicationValidator.validate(parsed("[${manifestBody()}]"), "bd_locations")

        assertTrue(problems.toString(), problems.isEmpty())
    }

    @Test
    fun `an unsupported payload schema version is refused`() {
        val problems = DatasetPublicationValidator.validate(
            parsed("[${manifestBody(schema = 2)}]"),
            "bd_locations",
        )

        assertEquals(listOf("unsupported_schema_version"), problems.map { it.code })
    }

    @Test
    fun `an unpublished publication is refused`() {
        for (status in listOf("draft", "archived")) {
            val problems = DatasetPublicationValidator.validate(
                parsed("[${manifestBody(status = status)}]"),
                "bd_locations",
            )

            assertEquals(listOf("publication_not_published"), problems.map { it.code })
        }
    }

    @Test
    fun `a checksum that is not a sha-256 digest is refused`() {
        val problems = DatasetPublicationValidator.validate(
            parsed("[${manifestBody(checksum = "nope")}]"),
            "bd_locations",
        )

        assertEquals(listOf("invalid_checksum"), problems.map { it.code })
    }

    @Test
    fun `a manifest for another dataset slot is refused`() {
        val problems = DatasetPublicationValidator.validate(
            parsed("[${manifestBody(datasetId = "other_slot")}]"),
            "bd_locations",
        )

        assertEquals(listOf("dataset_id_mismatch"), problems.map { it.code })
    }

    @Test
    fun `multiple problems are all reported and ordered`() {
        val problems = DatasetPublicationValidator.validate(
            parsed("[${manifestBody(schema = 9, status = "draft", checksum = "x")}]"),
            "bd_locations",
        )

        val codes = problems.map { it.code }
        assertEquals(codes.sorted(), codes)
        assertTrue(codes.contains("unsupported_schema_version"))
        assertTrue(codes.contains("publication_not_published"))
        assertTrue(codes.contains("invalid_checksum"))
    }

    @Test
    fun `checksum shape is accepted in either case`() {
        assertTrue(DatasetPublicationValidator.isSha256Hex("a".repeat(64)))
        assertTrue(DatasetPublicationValidator.isSha256Hex("A1".repeat(32)))
        assertTrue(!DatasetPublicationValidator.isSha256Hex("a".repeat(63)))
        assertTrue(!DatasetPublicationValidator.isSha256Hex("g".repeat(64)))
    }
}
