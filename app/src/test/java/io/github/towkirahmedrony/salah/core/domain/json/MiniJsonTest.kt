package io.github.towkirahmedrony.salah.core.domain.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON reader the reverse-geocoding path depends on.
 *
 * A parser is only as good as its refusals, so most of these cases are malformed input: the point of a
 * hand-written reader is that a broken or truncated response is reported as unavailable rather than
 * half-read into a location. The accepted cases mirror the payloads a Nominatim-style response actually
 * uses — objects of optional strings, with numbers and arrays alongside them.
 */
class MiniJsonTest {

    @Test
    fun `parses an object of strings`() {
        val value = MiniJson.parse("""{"a":"one","b":"two"}""")
        assertTrue(value is JsonValue.ObjectValue)
        assertEquals("one", value?.stringOrNull("a"))
        assertEquals("two", value?.stringOrNull("b"))
    }

    @Test
    fun `reads a nested address object`() {
        val value = MiniJson.parse("""{"display_name":"X","address":{"country":"Bangladesh"}}""")
        assertEquals("X", value?.stringOrNull("display_name"))
        assertEquals("Bangladesh", value?.objectOrNull("address")?.stringOrNull("country"))
    }

    @Test
    fun `blank and absent strings are treated the same`() {
        val value = MiniJson.parse("""{"empty":"   ","present":"ok"}""")
        assertNull(value?.nonBlankStringOrNull("empty"))
        assertNull(value?.nonBlankStringOrNull("missing"))
        assertEquals("ok", value?.nonBlankStringOrNull("present"))
    }

    @Test
    fun `decodes every standard escape`() {
        val value = MiniJson.parse("""{"s":"a\"b\\c\/d\ne\tf\u0041"}""")
        assertEquals("a\"b\\c/d\ne\tfA", value?.stringOrNull("s"))
    }

    @Test
    fun `parses numbers booleans nulls and arrays`() {
        val value = MiniJson.parse("""{"n":-12.5,"e":1e3,"t":true,"z":null,"a":[1,2,3]}""")
        val entries = (value as JsonValue.ObjectValue).entries
        assertEquals(-12.5, (entries["n"] as JsonValue.NumberValue).value, 1e-9)
        assertEquals(1000.0, (entries["e"] as JsonValue.NumberValue).value, 1e-9)
        assertEquals(JsonValue.BooleanValue(true), entries["t"])
        assertEquals(JsonValue.NullValue, entries["z"])
        assertEquals(3, (entries["a"] as JsonValue.ArrayValue).items.size)
    }

    @Test
    fun `keeps the last value for a repeated key`() {
        val value = MiniJson.parse("""{"k":"first","k":"second"}""")
        assertEquals("second", value?.stringOrNull("k"))
    }

    @Test
    fun `refuses malformed input instead of guessing`() {
        assertNull(MiniJson.parse(""))
        assertNull(MiniJson.parse("{"))
        assertNull(MiniJson.parse("""{"a":}"""))
        assertNull(MiniJson.parse("""{"a":"unterminated}"""))
        assertNull(MiniJson.parse("""{"a":1,}"""))
        assertNull(MiniJson.parse("""[1,2"""))
        assertNull(MiniJson.parse("""{"a":tru}"""))
        assertNull(MiniJson.parse("""{"a":01x}"""))
        assertNull(MiniJson.parse("not json at all"))
    }

    @Test
    fun `refuses trailing content after the top-level value`() {
        // A response body followed by another document is not something this app should half-read.
        assertNull(MiniJson.parse("""{"a":1}{"b":2}"""))
    }

    @Test
    fun `refuses a raw control character inside a string`() {
        assertNull(MiniJson.parse("{\"a\":\"line\nbreak\"}"))
    }

    @Test
    fun `reads a realistic nested payload`() {
        val body = """
            {
              "place_id": 123,
              "lat": "23.8103",
              "display_name": "Mirpur, Dhaka, Dhaka Division, Bangladesh",
              "address": {
                "suburb": "Mirpur",
                "state_district": "Dhaka",
                "country_code": "bd"
              }
            }
        """.trimIndent()
        val address = MiniJson.parse(body)?.objectOrNull("address")
        assertEquals("Mirpur", address?.stringOrNull("suburb"))
        assertEquals("Dhaka", address?.stringOrNull("state_district"))
    }
}
