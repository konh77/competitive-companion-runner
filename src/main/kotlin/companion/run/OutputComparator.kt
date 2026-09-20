package companion.run

import companion.settings.CompareMode

data class CompareSettings(val mode: CompareMode, val absTol: Double, val relTol: Double)

sealed class CompareResult {
    object Match : CompareResult()
    data class Mismatch(val detail: String) : CompareResult()
}

/** Local output comparison. Not a reproduction of AtCoder's checkers. */
object OutputComparator {
    private val NUMBER = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val ASCII_WS = Regex("[ \\t\\n\\r\\u000B\\u000C]+")
    private val NON_FINITE = Regex("[+-]?(nan|inf|infinity)", RegexOption.IGNORE_CASE)

    fun compare(actual: String, expected: String, settings: CompareSettings): CompareResult {
        val a = actual.replace("\r\n", "\n")
        val e = expected.replace("\r\n", "\n")
        return when (settings.mode) {
            CompareMode.LINES -> compareLines(a, e)
            CompareMode.TOKENS -> compareTokens(tokens(a), tokens(e), null)
            CompareMode.FLOAT -> compareTokens(tokens(a), tokens(e), settings)
        }
    }

    private fun lines(s: String): List<String> {
        val ls = s.split('\n').map { it.trimEnd(' ', '\t') }.toMutableList()
        while (ls.isNotEmpty() && ls.last().isEmpty()) ls.removeAt(ls.size - 1)
        return ls
    }

    private fun compareLines(a: String, e: String): CompareResult {
        val la = lines(a)
        val le = lines(e)
        if (la.size != le.size) return CompareResult.Mismatch("expected ${le.size} line(s), got ${la.size}")
        for (i in la.indices) {
            if (la[i] != le[i]) return CompareResult.Mismatch("line ${i + 1} differs")
        }
        return CompareResult.Match
    }

    private fun tokens(s: String): List<String> = s.split(ASCII_WS).filter { it.isNotEmpty() }

    private fun compareTokens(a: List<String>, e: List<String>, float: CompareSettings?): CompareResult {
        if (a.size != e.size) return CompareResult.Mismatch("expected ${e.size} token(s), got ${a.size}")
        for (i in a.indices) {
            val ta = a[i]
            val te = e[i]
            if (float == null) {
                if (ta != te) return CompareResult.Mismatch("token ${i + 1}: expected '$te', got '$ta'")
                continue
            }
            if (NON_FINITE.matches(ta) || NON_FINITE.matches(te)) return CompareResult.Mismatch("token ${i + 1}: non-finite number")
            val na = NUMBER.matches(ta)
            val ne = NUMBER.matches(te)
            if (na != ne) return CompareResult.Mismatch("token ${i + 1}: expected '$te', got '$ta'")
            if (!na) {
                if (ta != te) return CompareResult.Mismatch("token ${i + 1}: expected '$te', got '$ta'")
                continue
            }
            val da = ta.toDoubleOrNull()
            val de = te.toDoubleOrNull()
            if (da == null || de == null || !da.isFinite() || !de.isFinite()) {
                return CompareResult.Mismatch("token ${i + 1}: non-finite number")
            }
            if (da == de) continue
            val diff = Math.abs(da - de)
            if (!diff.isFinite()) return CompareResult.Mismatch("token ${i + 1}: difference overflowed")
            if (diff <= float.absTol || diff <= float.relTol * Math.abs(de)) continue
            return CompareResult.Mismatch("token ${i + 1}: expected $te, got $ta (diff $diff)")
        }
        return CompareResult.Match
    }
}
