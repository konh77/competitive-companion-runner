package companion.model

/**
 * Minimal strict JSON parser (RFC 8259) used for untrusted ingress bodies.
 *
 * Rejects: duplicate object keys, comments, trailing content, NaN / Infinity,
 * leading zeros, control characters inside strings, lone surrogates produced by
 * escapes, and nesting deeper than [maxDepth].
 */
sealed class JsonValue {
    data class JObject(val fields: Map<String, JsonValue>) : JsonValue()
    data class JArray(val items: List<JsonValue>) : JsonValue()
    data class JString(val value: String) : JsonValue()
    /** Number kept as its literal text so callers can check integrality / range exactly. */
    data class JNumber(val literal: String) : JsonValue()
    data class JBool(val value: Boolean) : JsonValue()
    object JNull : JsonValue() { override fun toString() = "JNull" }
}

class JsonSyntaxException(message: String, val offset: Int) : Exception("$message (at offset $offset)")

class StrictJsonParser(private val text: String, private val maxDepth: Int = 32) {
    private var pos = 0
    private var depth = 0

    companion object {
        fun parse(text: String, maxDepth: Int = 32): JsonValue = StrictJsonParser(text, maxDepth).parseDocument()
    }

    fun parseDocument(): JsonValue {
        skipWs()
        val v = parseValue()
        skipWs()
        if (pos != text.length) fail("Trailing content after JSON value")
        return v
    }

    private fun fail(msg: String): Nothing = throw JsonSyntaxException(msg, pos)

    private fun peek(): Char = if (pos < text.length) text[pos] else Char.MIN_VALUE

    private fun skipWs() {
        while (pos < text.length) {
            when (text[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }

    private fun expect(c: Char) {
        if (peek() != c) fail("Expected '$c'")
        pos++
    }

    private fun parseValue(): JsonValue {
        if (pos >= text.length) fail("Unexpected end of input")
        return when (val c = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.JString(parseString())
            't' -> { literal("true"); JsonValue.JBool(true) }
            'f' -> { literal("false"); JsonValue.JBool(false) }
            'n' -> { literal("null"); JsonValue.JNull }
            '-', in '0'..'9' -> parseNumber()
            else -> fail("Unexpected character '$c'")
        }
    }

    private fun literal(word: String) {
        if (!text.startsWith(word, pos)) fail("Invalid literal")
        pos += word.length
    }

    private fun enter() {
        depth++
        if (depth > maxDepth) fail("Nesting deeper than $maxDepth")
    }

    private fun leave() { depth-- }

    private fun parseObject(): JsonValue.JObject {
        expect('{')
        enter()
        val map = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (peek() == '}') { pos++; leave(); return JsonValue.JObject(map) }
        while (true) {
            skipWs()
            if (peek() != '"') fail("Expected string key")
            val key = parseString()
            skipWs()
            expect(':')
            skipWs()
            val value = parseValue()
            if (map.containsKey(key)) fail("Duplicate key \"$key\"")
            map[key] = value
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> { pos++; leave(); return JsonValue.JObject(map) }
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): JsonValue.JArray {
        expect('[')
        enter()
        val list = ArrayList<JsonValue>()
        skipWs()
        if (peek() == ']') { pos++; leave(); return JsonValue.JArray(list) }
        while (true) {
            skipWs()
            list.add(parseValue())
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> { pos++; leave(); return JsonValue.JArray(list) }
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun parseNumber(): JsonValue.JNumber {
        val start = pos
        if (peek() == '-') pos++
        if (peek() == '0') {
            pos++
        } else if (peek() in '1'..'9') {
            while (peek() in '0'..'9') pos++
        } else fail("Invalid number")
        if (peek() == '.') {
            pos++
            if (peek() !in '0'..'9') fail("Invalid fraction")
            while (peek() in '0'..'9') pos++
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++
            if (peek() == '+' || peek() == '-') pos++
            if (peek() !in '0'..'9') fail("Invalid exponent")
            while (peek() in '0'..'9') pos++
        }
        return JsonValue.JNumber(text.substring(start, pos))
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("Unterminated string")
            val c = text[pos++]
            when {
                c == '"' -> break
                c == '\\' -> {
                    if (pos >= text.length) fail("Unterminated escape")
                    when (val e = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append(8.toChar())
                        'f' -> sb.append(12.toChar())
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hi = readHex4()
                            if (Character.isHighSurrogate(hi)) {
                                if (!text.startsWith("\\u", pos)) fail("Lone high surrogate")
                                pos += 2
                                val lo = readHex4()
                                if (!Character.isLowSurrogate(lo)) fail("Invalid surrogate pair")
                                sb.append(hi).append(lo)
                            } else if (Character.isLowSurrogate(hi)) {
                                fail("Lone low surrogate")
                            } else sb.append(hi)
                        }
                        else -> fail("Invalid escape '\\$e'")
                    }
                }
                c < ' ' -> fail("Control character in string")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun readHex4(): Char {
        if (pos + 4 > text.length) fail("Truncated \\u escape")
        var v = 0
        repeat(4) {
            val d = Character.digit(text[pos++], 16)
            if (d < 0) fail("Invalid hex digit in \\u escape")
            v = v * 16 + d
        }
        return v.toChar()
    }
}
