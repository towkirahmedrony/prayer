package io.github.towkirahmedrony.salah.core.domain.json

/**
 * A JSON value, as far as Salah needs one.
 *
 * **Why this exists instead of a JSON library.** Salah's one network call is a reverse-geocoding
 * lookup, and the only thing it has to read is a small object of optional string fields. The
 * alternatives were both worse for this repository:
 *
 *  - a JSON dependency (`org.json` is not testable on the JVM — it is a stub in the Android jar, so a
 *    unit test cannot assert on a parse — and `kotlinx-serialization`/Moshi would add a compiler plugin
 *    or an annotation processor to a build that deliberately keeps KSP out of the toolchain);
 *  - reading the response with regular expressions, which is how a parser becomes a source of silent
 *    wrong answers on an escaped or reordered payload.
 *
 * So the reader is a small recursive-descent parser, held to the subset of JSON these responses use,
 * and covered by `MiniJsonTest` against malformed input. It never throws: a failure is `null`, which
 * the caller reports as an unavailable address rather than as a crash.
 *
 * The classes are named `…Value` rather than `Json.Array`/`Json.Object` because `Object` and `Array`
 * are the names of the Kotlin types they would sit beside in the same file.
 */
sealed interface JsonValue {

    /** A JSON object. Duplicate keys keep the last value, as every JSON reader does. */
    data class ObjectValue(val entries: Map<String, JsonValue>) : JsonValue

    /** A JSON array. */
    data class ArrayValue(val items: List<JsonValue>) : JsonValue

    /** A JSON string. */
    data class StringValue(val value: String) : JsonValue

    /** A JSON number. Held as a `Double`; the addresses Salah reads are all strings in practice. */
    data class NumberValue(val value: Double) : JsonValue

    /** A JSON boolean. */
    data class BooleanValue(val value: Boolean) : JsonValue

    /** JSON `null`. */
    data object NullValue : JsonValue
}

/** The string at [key], or `null` when the value is absent or is not a string. */
fun JsonValue.stringOrNull(key: String): String? {
    val obj = this as? JsonValue.ObjectValue ?: return null
    return (obj.entries[key] as? JsonValue.StringValue)?.value
}

/** The string at [key], trimmed, or `null` when it is absent, not a string, or blank. */
fun JsonValue.nonBlankStringOrNull(key: String): String? =
    stringOrNull(key)?.trim()?.takeIf { it.isNotEmpty() }

/** The object at [key], or `null` when the value is absent or is not an object. */
fun JsonValue.objectOrNull(key: String): JsonValue.ObjectValue? =
    (this as? JsonValue.ObjectValue)?.entries?.get(key) as? JsonValue.ObjectValue

/**
 * A minimal JSON reader.
 *
 * It accepts exactly RFC 8259 JSON — objects, arrays, strings with the standard escapes (including
 * `\uXXXX` surrogate pairs), numbers, `true`/`false`/`null` — and rejects anything else by returning
 * `null`. Trailing content after the top-level value is a failure, because a truncated or concatenated
 * response is not a document this app should half-read.
 */
object MiniJson {

    /** Parses [text], or returns `null` when it is not well-formed JSON. */
    fun parse(text: String): JsonValue? {
        val parser = Parser(text)
        return try {
            parser.parseDocument()
        } catch (ignored: InvalidJson) {
            null
        }
    }

    private class InvalidJson : RuntimeException()

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = readValue()
            skipWhitespace()
            if (index != text.length) throw InvalidJson()
            return value
        }

        private fun readValue(): JsonValue {
            skipWhitespace()
            if (index >= text.length) throw InvalidJson()
            return when (val character = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.StringValue(readString())
                't' -> readLiteral("true", JsonValue.BooleanValue(true))
                'f' -> readLiteral("false", JsonValue.BooleanValue(false))
                'n' -> readLiteral("null", JsonValue.NullValue)
                else ->
                    if (character == '-' || character.isDigit()) readNumber()
                    else throw InvalidJson()
            }
        }

        private fun readLiteral(expected: String, value: JsonValue): JsonValue {
            if (index + expected.length > text.length) throw InvalidJson()
            if (text.regionMatches(index, expected, 0, expected.length).not()) throw InvalidJson()
            index += expected.length
            return value
        }

        private fun readObject(): JsonValue.ObjectValue {
            expect('{')
            val entries = linkedMapOf<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.ObjectValue(entries)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw InvalidJson()
                val key = readString()
                skipWhitespace()
                expect(':')
                entries[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.ObjectValue(entries)
                    }

                    else -> throw InvalidJson()
                }
            }
        }

        private fun readArray(): JsonValue.ArrayValue {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.ArrayValue(items)
            }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.ArrayValue(items)
                    }

                    else -> throw InvalidJson()
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) throw InvalidJson()
                when (val character = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(readEscape())
                    // A raw control character is not legal inside a JSON string, but accepting it would
                    // let a broken payload through as a well-formed one, so it is refused too.
                    else -> if (character < ' ') throw InvalidJson() else builder.append(character)
                }
            }
        }

        private fun readEscape(): Char {
            if (index >= text.length) throw InvalidJson()
            return when (text[index++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> throw InvalidJson()
            }
        }

        private fun readUnicodeEscape(): Char {
            if (index + 4 > text.length) throw InvalidJson()
            val hex = text.substring(index, index + 4)
            index += 4
            val code = hex.toIntOrNull(16) ?: throw InvalidJson()
            return code.toChar()
        }

        private fun readNumber(): JsonValue.NumberValue {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            if (peek() == '.') {
                index++
                if (index >= text.length || !text[index].isDigit()) throw InvalidJson()
                while (index < text.length && text[index].isDigit()) index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                if (index >= text.length || !text[index].isDigit()) throw InvalidJson()
                while (index < text.length && text[index].isDigit()) index++
            }
            val literal = text.substring(start, index)
            val value = literal.toDoubleOrNull() ?: throw InvalidJson()
            return JsonValue.NumberValue(value)
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun expect(character: Char) {
            if (peek() != character) throw InvalidJson()
            index++
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}
