package companion.template

import companion.model.ReceivedTask
import companion.storage.SafePath
import companion.storage.UnsafePathException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TemplateException(message: String) : Exception(message)

private val VAR = Regex("\\$\\$\\{|\\$\\{([A-Za-z][A-Za-z0-9]*)}")

/** Single-pass `${var}` expansion; `$${` is a literal `${`. Values are never re-expanded. */
internal fun expandOnce(template: String, vars: Map<String, String>, onUnknown: (String) -> Nothing): String {
    val sb = StringBuilder()
    var last = 0
    for (m in VAR.findAll(template)) {
        sb.append(template, last, m.range.first)
        if (m.value == "$\${") {
            sb.append("\${")
        } else {
            val name = m.groupValues[1]
            sb.append(vars[name] ?: onUnknown(name))
        }
        last = m.range.last + 1
    }
    sb.append(template, last, template.length)
    return sb.toString()
}

object PathTemplate {
    private val ALLOWED = setOf("contestId", "contestNumber", "taskId", "taskIndex", "taskIndexUpper", "date")
    private val CONTEST_NUMBER = Regex("^[A-Za-z]+([0-9]+)$")

    /** `abc476` -> `476`, `arc189` -> `189`; anything else falls back to the whole contestId. */
    fun contestNumber(contestId: String): String = CONTEST_NUMBER.matchEntire(contestId)?.groupValues?.get(1) ?: contestId

    fun variables(task: ReceivedTask): Map<String, String> = variables(task.url.contestId, task.url.taskId, task.url.taskIndex, task.receivedAt.atZone(ZoneId.systemDefault()).toLocalDate())

    fun variables(contestId: String, taskId: String, taskIndex: String, date: LocalDate = LocalDate.now()): Map<String, String> = mapOf(
        "contestId" to SafePath.safeComponent(contestId),
        "contestNumber" to SafePath.safeComponent(contestNumber(contestId)),
        "taskId" to SafePath.safeComponent(taskId),
        "taskIndex" to SafePath.safeComponent(taskIndex.lowercase()),
        "taskIndexUpper" to SafePath.safeComponent(taskIndex.uppercase()),
        "date" to date.format(DateTimeFormatter.ISO_LOCAL_DATE),
    )

    /** Expands and validates; returns a project-relative path with `/` separators. */
    fun expand(template: String, vars: Map<String, String>): String {
        if (template.isBlank()) throw TemplateException("path template is empty")
        val expanded = expandOnce(template.trim(), vars) { name ->
            throw TemplateException("unknown variable \${$name} (allowed: ${ALLOWED.joinToString()})")
        }
        val rel = expanded.replace('\\', '/')
        if (rel.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(rel)) throw TemplateException("absolute path not allowed")
        for (seg in rel.split('/')) {
            try {
                SafePath.validateSegment(seg)
            } catch (e: UnsafePathException) {
                throw TemplateException(e.message ?: "invalid segment")
            }
        }
        return rel
    }

    /** Settings-time validation with placeholder values. */
    fun validateForSettings(template: String): String? = try {
        expand(template, variables("abc400", "abc400_a", "a"))
        null
    } catch (e: TemplateException) {
        e.message
    }
}

object SolutionTemplate {
    const val BUILTIN = """# ${'$'}{name}
# ${'$'}{url}

"""

    private val UNTRUSTED = setOf("name", "title", "group")

    fun variables(task: ReceivedTask): Map<String, String> {
        val zdt = task.receivedAt.atZone(ZoneId.systemDefault())
        return mapOf(
            "name" to sanitize(task.name),
            "title" to sanitize(task.title),
            "group" to sanitize(task.group ?: ""),
            "url" to task.url.canonicalUrl,
            "contestId" to task.url.contestId,
            "taskId" to task.url.taskId,
            "taskIndex" to task.url.taskIndex,
            "taskIndexUpper" to task.url.taskIndex.uppercase(),
            "timeLimitMs" to task.timeLimitMs.toString(),
            "memoryLimitMb" to task.memoryLimitMb.toString(),
            "date" to zdt.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
            "datetime" to OffsetDateTime.from(zdt).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
    }

    private fun sanitize(s: String): String = s.replace(Regex("[\\r\\n\\t\\p{Cntrl}]+"), " ").trim()

    /**
     * Renders the template. Untrusted variables (`name`, `title`, `group`) may only appear on
     * lines whose first non-blank character is `#` (a Python comment).
     */
    fun render(template: String, vars: Map<String, String>): String {
        if (template.toByteArray().size > 1 shl 20) throw TemplateException("template larger than 1 MiB")
        val normalized = template.replace("\r\n", "\n")
        for (line in normalized.split('\n')) {
            val isComment = line.trimStart().startsWith("#")
            if (!isComment) {
                for (m in VAR.findAll(line)) {
                    val name = m.groupValues.getOrNull(1) ?: continue
                    if (name in UNTRUSTED) throw TemplateException("\${$name} may only be used inside a '#' comment line")
                }
            }
        }
        return expandOnce(normalized, vars) { name -> throw TemplateException("unknown template variable \${$name}") }
    }
}
