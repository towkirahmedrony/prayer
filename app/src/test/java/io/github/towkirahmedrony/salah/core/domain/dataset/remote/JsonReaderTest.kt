package io.github.towkirahmedrony.salah.core.domain.dataset.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON reader, against the shapes a Supabase response actually takes.
 *
 * The reader is the one place the manifest contract can silently go wrong — a mis-parsed field reads as
 * "missing" rather than as an error — so both the success and the failure paths are asserted directly.
 * The malformed cases matter most: they arrive from a service this app does not control, and "the
 * manifest is not valid JSON" must be a reported outcome rather than a crash in a background coroutine.
 */
class JsonReaderTest {

    @Test
    fun `a flat object is read`() {
        val value = JsonReader.parse("""{"dataset_id":"bd_locations","payload_schema_version":1}""")

        assertTrue(value is JsonValue.ObjectValue)
        assertEquals("bd_locations", value!!.stringOrNull("dataset_id"))
        assertEquals(1, value.intOrNull("payload_schema_version"))
    }

    @Test
    fun `an array of objects is read`() {
        val value = JsonReader.parse("""[{"a":"1"}]""")

        val array = value as JsonValue.ArrayValue
        assertEquals(1, array.elements.size)
        assertEquals("1", array.elements.first().stringOrNull("a"))
    }

    @Test
    fun `an empty array is an empty array`() {
        val value = JsonReader.parse("[]")

        assertEquals(JsonValue.ArrayValue(emptyList()), value)
    }

    @Test
    fun `null is null and not a missing value`() {
        val value = JsonReader.parse("null")

        assertEquals(JsonValue.NullValue, value)
    }

    @Test
    fun `numbers of every numeric shape are read`() {
        val value = JsonReader.parse("""{"negative":-3,"decimal":1.5,"exponent":2e3}""")!!

        assertEquals(-3L, value.longOrNull("negative"))
        assertEquals(1.5, (value.child("decimal") as JsonValue.NumberValue).value, 0.0)
        assertEquals(2000L, value.longOrNull("exponent"))
    }

    @Test
    fun `booleans are read`() {
        val value = JsonReader.parse("""{"yes":true,"no":false}""")!!

        assertEquals(true, value.booleanOrNull("yes"))
        assertEquals(false, value.booleanOrNull("no"))
    }

    @Test
    fun `string escapes are resolved`() {
        val value = JsonReader.parse("""{"s":"a\"b\\c\nd\u0041"}""")!!

        assertEquals("a\"b\\c\ndA", value.stringOrNull("s"))
    }

    @Test
    fun `missing fields read as absent rather than as an error`() {
        val value = JsonReader.parse("""{"a":"1"}""")!!

        assertNull(value.stringOrNull("b"))
        assertNull(value.intOrNull("b"))
        assertNull(value.booleanOrNull("b"))
        assertNull(value.child("b"))
    }

    @Test
    fun `a field of the wrong kind reads as absent from the typed accessor`() {
        val value = JsonReader.parse("""{"a":"not a number"}""")!!

        assertNull(value.longOrNull("a"))
    }

    @Test
    fun `malformed input returns null rather than throwing`() {
        val malformed = listOf(
            "",
            "   ",
            "{",
            "[",
            """{"a":}""",
            """{"a":1,}""",
            """{"a" 1}""",
            """{"a":"unterminated}""",
            """{"a":"bad\qescape"}""",
            """{"a":"\u00"}""",
            "tru",
            "nul",
            "-",
        )

        for (text in malformed) {
            assertNull("expected '$text' to be refused", JsonReader.parse(text))
        }
    }

    @Test
    fun `trailing content after the document is refused`() {
        // A log line or a concatenated response must not be read as a valid manifest.
        assertNull(JsonReader.parse("""{"a":1}{"b":2}"""))
        assertNull(JsonReader.parse("""{"a":1} trailing"""))
    }

    @Test
    fun `whitespace around the document is allowed`() {
        assertEquals(
            "1",
            JsonReader.parse("  \n\t [ { \"a\" : \"1\" } ]  ")!!.let { (it as JsonValue.ArrayValue).elements.first() }
                .stringOrNull("a"),
        )
    }

    @Test
    fun `nested objects are read`() {
        val value = JsonReader.parse("""{"outer":{"inner":{"deep":"x"}}}""")!!

        assertEquals("x", value.child("outer")?.child("inner")?.stringOrNull("deep"))
    }
}
