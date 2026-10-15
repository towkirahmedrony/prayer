package io.github.towkirahmedrony.salah.core.domain.dataset.remote

/**
 * A parsed JSON value, and the tiny reader that produces one.
 *
 * **Why hand-written rather than a library.** Salah's remote publication manifest is a small, flat
 * object read over Supabase's REST interface, which answers in JSON. The two obvious alternatives are
 * `org.json` and kotlinx.serialization: `org.json` is part of the Android platform and is a *stub*
 * under a JVM unit test, so every manifest test would either need Robolectric or would silently read
 * defaults; kotlinx.serialization would add a compiler plugin, a dependency and a code-generation step
 * to a project whose only structured formats so far are two tab-separated files parsed by hand. This
 * reader is about a hundred lines, has no dependency, and — the part that matters — runs identically on
 * a JVM unit test and on the device, so the manifest contract is tested directly rather than emulated.
 *
 * **What it deliberately does not do.** It is a reader, not a validating parser: a value that does not
 * parse is reported as `null` and the caller decides what that means. It does not preserve object key
 * order as significant, it does not distinguish integer from floating point (the manifest's numbers are
 * counts and byte sizes, which are read back through the integer accessors), and it does not enforce
 * duplicate-key rejection beyond last-one-wins, which is the JSON-object rule every other reader uses.
 */
sealed interface JsonValue {

    /** A JSON object: an unordered set of named values. */
    data class ObjectValue(val entries: Map<String, JsonValue>) : JsonValue

    /** A JSON array, in document order. */
    data class ArrayValue(val elements: List<JsonValue>) : JsonValue

    /** A JSON string, with escapes already resolved. */
    data class StringValue(val value: String) : JsonValue

    /** A JSON number. Held as a [Double]; the manifest's numbers are all integral in range. */
    data class NumberValue(val value: Double) : JsonValue

    /** `true` or `false`. */
    data class BooleanValue(val value: Boolean) : JsonValue

    /** The JSON literal `null`. */
    data object NullValue : JsonValue

    /** The value named [name] when this is an object, or `null` otherwise. */
    fun child(name: String): JsonValue? = (this as? ObjectValue)?.entries?.get(name)

    /** The string named [name], or `null` when it is absent or of another kind. */
    fun stringOrNull(name: String): String? = (child(name) as? StringValue)?.value

    /** The number named [name] as a [Long], or `null` when it is absent or of another kind. */
    fun longOrNull(name: String): Long? = (child(name) as? NumberValue)?.value?.toLong()

    /** The number named [name] as an [Int], or `null` when it is absent or of another kind. */
    fun intOrNull(name: String): Int? = (child(name) as? NumberValue)?.value?.toInt()

    /** The boolean named [name], or `null` when it is absent or of another kind. */
    fun booleanOrNull(name: String): Boolean? = (child(name) as? BooleanValue)?.value
}

/**
 * Reads JSON text into a [JsonValue], or reports `null` when the text is not valid JSON.
 *
 * The reader is total in the sense that it never throws: any malformed input — a truncated document, a
 * trailing token, an unterminated string, a bad escape — returns `null`. That matters here because the
 * input arrives over the network from a service this app does not control, and "the manifest is not
 * valid JSON" must be a reported outcome rather than a crash inside a background coroutine.
 */
object JsonReader {

    /** [text] as a [JsonValue], or `null` when it is not exactly one JSON document. */
    fun parse(text: String): JsonValue? {
        val parser = Parser(text)
        val value = parser.readValue() ?: return null
        parser.skipWhitespace()
        // Trailing content means this is not one document; refusing it keeps a concatenated or
        // log-wrapped response from being read as a valid manifest.
        return if (parser.atEnd()) value else null
    }

    private class Parser(private val text: String) {

        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): JsonValue? {
            skipWhitespace()
            if (atEnd()) return null
            return when (val character = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()?.let { JsonValue.StringValue(it) }
                't' -> if (consume("true")) JsonValue.BooleanValue(true) else null
                'f' -> if (consume("false")) JsonValue.BooleanValue(false) else null
                'n' -> if (consume("null")) JsonValue.NullValue else null
                else -> if (character == '-' || character.isDigit()) {
                    readNumber()?.let { JsonValue.NumberValue(it) }
                } else {
                    null
                }
            }
        }

        private fun readObject(): JsonValue? {
            index++ // '{'
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.ObjectValue(entries)
            }
            while (true) {
                skipWhitespace()
                val key = readString() ?: return null
                skipWhitespace()
                if (peek() != ':') return null
                index++
                val value = readValue() ?: return null
                entries[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.ObjectValue(entries)
                    }
                    else -> return null
                }
            }
        }

        private fun readArray(): JsonValue? {
            index++ // '['
            val elements = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.ArrayValue(elements)
            }
            while (true) {
                val element = readValue() ?: return null
                elements += element
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.ArrayValue(elements)
                    }
                    else -> return null
                }
            }
        }

        private fun readString(): String? {
            if (peek() != '"') return null
            index++
            val builder = StringBuilder()
            while (index < text.length) {
                val character = text[index++]
                when {
                    character == '"' -> return builder.toString()
                    character == '\\' -> {
                        if (index >= text.length) return null
                        when (val escape = text[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) return null
                                val code = text.substring(index, index + 4).toIntOrNull(16) ?: return null
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> return null
                        }
                    }
                    else -> builder.append(character)
                }
            }
            // An unterminated string is not a string.
            return null
        }

        private fun readNumber(): Double? {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && text[index].isNumberCharacter()) index++
            if (index == start) return null
            return text.substring(start, index).toDoubleOrNull()
        }

        private fun Char.isNumberCharacter(): Boolean =
            isDigit() || this == '.' || this == 'e' || this == 'E' || this == '+' || this == '-'

        private fun consume(literal: String): Boolean {
            if (!text.startsWith(literal, index)) return false
            index += literal.length
            return true
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null
    }
}
