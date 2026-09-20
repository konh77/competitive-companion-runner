package companion.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

sealed class ValidationResult {
    data class Ok(val task: ReceivedTask) : ValidationResult()
    data class Rejected(val code: String, val field: String?, val reason: String) : ValidationResult()
    data class Unsupported(val reason: String) : ValidationResult()
}

/** Strict UTF-8 decode, strict JSON, schema validation, normalization, URL/ID validation. */
object TaskValidator {
    const val MAX_TESTS = 100
    const val MAX_TEST_BYTES = 1 shl 20
    private val NAME_SPLIT = Regex("^([A-Za-z0-9]{1,4})\\s*-\\s*(.+)$")
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val WS_RUN = Regex("\\s+")

    fun decodeUtf8(bytes: ByteArray): String? {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    fun validate(body: ByteArray, receiptSequence: Long, receivedAt: Instant): ValidationResult {
        val text = decodeUtf8(body) ?: return ValidationResult.Rejected("UTF8", null, "body is not valid UTF-8")
        val root = try {
            StrictJsonParser.parse(text)
        } catch (e: JsonSyntaxException) {
            return ValidationResult.Rejected("JSON", null, e.message ?: "syntax error")
        }
        return validate(root, receiptSequence, receivedAt)
    }

    fun validate(root: JsonValue, receiptSequence: Long, receivedAt: Instant): ValidationResult {
        val obj = (root as? JsonValue.JObject)?.fields ?: return rej("SCHEMA", null, "root must be an object")
        val notes = ArrayList<String>()

        val nameNode = obj["name"] ?: return rej("SCHEMA", "name", "missing")
        val rawName = (nameNode as? JsonValue.JString)?.value ?: return rej("SCHEMA", "name", "not a string")
        val name = normalizeText(rawName)
        if (hasBadControl(name)) return rej("SCHEMA", "name", "control characters")
        val nameLen = name.codePointCount(0, name.length)
        if (nameLen !in 1..300) return rej("SCHEMA", "name", "length $nameLen not in 1..300")

        val urlNode = obj["url"] ?: return rej("SCHEMA", "url", "missing")
        val rawUrl = (urlNode as? JsonValue.JString)?.value ?: return rej("SCHEMA", "url", "not a string")
        val url = when (val r = AtCoderUrl.parse(rawUrl)) {
            is AtCoderUrl.ParseResult.Ok -> r.url
            is AtCoderUrl.ParseResult.Unsupported -> return ValidationResult.Unsupported(r.reason)
        }

        val testsNode = obj["tests"] ?: return rej("SCHEMA", "tests", "missing")
        val testsArr = (testsNode as? JsonValue.JArray)?.items ?: return rej("SCHEMA", "tests", "not an array")
        if (testsArr.size > MAX_TESTS) return rej("SCHEMA", "tests", "more than $MAX_TESTS tests")
        val tests = ArrayList<ReceivedTest>(testsArr.size)
        for ((i, t) in testsArr.withIndex()) {
            val to = (t as? JsonValue.JObject)?.fields ?: return rej("SCHEMA", "tests[$i]", "not an object")
            val input = (to["input"] as? JsonValue.JString)?.value
                ?: return rej("SCHEMA", "tests[$i].input", "missing or not a string")
            val output = (to["output"] as? JsonValue.JString)?.value
                ?: return rej("SCHEMA", "tests[$i].output", "missing or not a string")
            val ni = normalizeTestData(input)
            val no = normalizeTestData(output)
            if (utf8Len(input) > MAX_TEST_BYTES || utf8Len(ni) > MAX_TEST_BYTES) {
                return rej("SCHEMA", "tests[$i].input", "larger than 1 MiB")
            }
            if (utf8Len(output) > MAX_TEST_BYTES || utf8Len(no) > MAX_TEST_BYTES) {
                return rej("SCHEMA", "tests[$i].output", "larger than 1 MiB")
            }
            tests.add(ReceivedTest(ni, no))
        }

        val timeLimitMs: Int = when (val v = obj["timeLimit"]) {
            null -> { notes.add("timeLimit defaulted to 2000"); 2000 }
            is JsonValue.JNumber -> {
                val d = v.literal.toBigDecimalOrNull() ?: return rej("SCHEMA", "timeLimit", "not a finite number")
                if (d < BigDecimal.ONE || d > BigDecimal(600000)) return rej("SCHEMA", "timeLimit", "out of range 1..600000")
                d.setScale(0, RoundingMode.FLOOR).intValueExact()
            }
            else -> return rej("SCHEMA", "timeLimit", "not a number")
        }

        val memoryLimitMb: Int = when (val v = obj["memoryLimit"]) {
            null -> { notes.add("memoryLimit defaulted to 1024"); 1024 }
            is JsonValue.JNumber -> {
                val d = v.literal.toBigDecimalOrNull() ?: return rej("SCHEMA", "memoryLimit", "not a finite number")
                if (!isMathematicalInteger(d)) return rej("SCHEMA", "memoryLimit", "not an integer")
                if (d < BigDecimal.ONE || d > BigDecimal(65536)) return rej("SCHEMA", "memoryLimit", "out of range 1..65536")
                d.intValueExact()
            }
            else -> return rej("SCHEMA", "memoryLimit", "not a number")
        }

        val group: String? = when (val v = obj["group"]) {
            null, JsonValue.JNull -> null
            is JsonValue.JString -> {
                val g = normalizeText(v.value)
                if (hasBadControl(g)) return rej("SCHEMA", "group", "control characters")
                if (g.codePointCount(0, g.length) > 300) return rej("SCHEMA", "group", "longer than 300")
                g
            }
            else -> return rej("SCHEMA", "group", "not a string")
        }

        val interactive = when (val v = obj["interactive"]) {
            null -> false
            is JsonValue.JBool -> v.value
            else -> return rej("SCHEMA", "interactive", "not a boolean")
        }

        val batchId: UUID
        val batchSize: Int
        var synthesized = false
        when (val b = obj["batch"]) {
            null -> { batchId = UUID.randomUUID(); batchSize = 1; synthesized = true }
            is JsonValue.JObject -> {
                val idStr = (b.fields["id"] as? JsonValue.JString)?.value
                val parsedId = idStr?.takeIf { UUID_RE.matches(it) }
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (parsedId == null) {
                    notes.add("batch.id invalid; synthesized")
                    batchId = UUID.randomUUID(); batchSize = 1; synthesized = true
                } else {
                    batchId = parsedId
                    batchSize = when (val s = b.fields["size"]) {
                        null -> 1
                        is JsonValue.JNumber -> {
                            val d = s.literal.toBigDecimalOrNull() ?: return rej("SCHEMA", "batch.size", "not a number")
                            if (!isMathematicalInteger(d)) return rej("SCHEMA", "batch.size", "not an integer")
                            if (d < BigDecimal.ONE || d > BigDecimal(500)) return rej("SCHEMA", "batch.size", "out of range 1..500")
                            d.intValueExact()
                        }
                        else -> return rej("SCHEMA", "batch.size", "not a number")
                    }
                }
            }
            else -> return rej("SCHEMA", "batch", "not an object")
        }

        val m = NAME_SPLIT.matchEntire(name)
        val displayIndex = m?.groupValues?.get(1)
        val title = m?.groupValues?.get(2) ?: name

        return ValidationResult.Ok(
            ReceivedTask(
                name = name, displayIndex = displayIndex, title = title, group = group, url = url,
                interactive = interactive, timeLimitMs = timeLimitMs, memoryLimitMb = memoryLimitMb,
                tests = tests, batchId = batchId, batchSize = batchSize, batchSynthesized = synthesized,
                receiptSequence = receiptSequence, receivedAt = receivedAt, notes = notes,
            )
        )
    }

    private fun rej(code: String, field: String?, reason: String) = ValidationResult.Rejected(code, field, reason)

    private fun isMathematicalInteger(d: BigDecimal): Boolean = d.stripTrailingZeros().scale() <= 0

    private fun utf8Len(s: String): Int = s.toByteArray(StandardCharsets.UTF_8).size

    private fun hasBadControl(s: String): Boolean = s.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }

    /** Trim and collapse whitespace runs to a single space. */
    fun normalizeText(s: String): String = s.trim().replace(WS_RUN, " ")

    /** CRLF / CR to LF; ensure trailing LF on non-empty data. Idempotent. */
    fun normalizeTestData(s: String): String {
        val lf = s.replace("\r\n", "\n").replace('\r', '\n')
        return if (lf.isEmpty() || lf.endsWith("\n")) lf else lf + "\n"
    }
}
