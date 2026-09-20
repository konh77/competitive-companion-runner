package companion

import companion.listener.HttpIngressHandler
import companion.model.AtCoderUrl
import companion.model.JsonSyntaxException
import companion.model.JsonValue
import companion.model.StrictJsonParser
import companion.model.TaskValidator
import companion.model.ValidationResult
import companion.run.CompareResult
import companion.run.CompareSettings
import companion.run.OutputComparator
import companion.settings.CompareMode
import companion.storage.SafePath
import companion.template.PathTemplate
import companion.template.SolutionTemplate
import companion.template.TemplateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class StrictJsonTest {
    @Test fun parsesBasic() {
        val v = StrictJsonParser.parse("""{"a":[1,2.5,-3e2],"b":"x\u00e9\n","c":true,"d":null}""") as JsonValue.JObject
        assertEquals(JsonValue.JNumber("1"), (v.fields["a"] as JsonValue.JArray).items[0])
        assertEquals("xé\n", (v.fields["b"] as JsonValue.JString).value)
        assertEquals(JsonValue.JNull, v.fields["d"])
    }

    private fun bad(s: String) {
        try { StrictJsonParser.parse(s); fail("expected failure: $s") } catch (e: JsonSyntaxException) {}
    }

    @Test fun rejectsDuplicateKey() = bad("""{"a":1,"a":2}""")
    @Test fun rejectsTrailing() = bad("""{"a":1} x""")
    @Test fun rejectsNaN() = bad("""{"a":NaN}""")
    @Test fun rejectsLeadingZero() = bad("""{"a":01}""")
    @Test fun rejectsComment() = bad("""{"a":1 /* c */}""")
    @Test fun rejectsLoneSurrogate() = bad("""{"a":"\ud800"}""")
    @Test fun rejectsControlChar() = bad("{\"a\":\"x\u0001\"}")
    @Test fun rejectsDepth() {
        val deep = "[".repeat(33) + "]".repeat(33)
        bad(deep)
        StrictJsonParser.parse("[".repeat(32) + "]".repeat(32))
    }
}

class AtCoderUrlTest {
    private fun ok(raw: String, contest: String, task: String, index: String) {
        val r = AtCoderUrl.parse(raw)
        assertTrue("$raw -> $r", r is AtCoderUrl.ParseResult.Ok)
        val u = (r as AtCoderUrl.ParseResult.Ok).url
        assertEquals(contest, u.contestId); assertEquals(task, u.taskId); assertEquals(index, u.taskIndex)
    }

    private fun no(raw: String) = assertTrue(raw, AtCoderUrl.parse(raw) is AtCoderUrl.ParseResult.Unsupported)

    @Test fun examples() {
        ok("https://atcoder.jp/contests/abc400/tasks/abc400_a", "abc400", "abc400_a", "a")
        ok("https://atcoder.jp/contests/abc250/tasks/abc250_h", "abc250", "abc250_h", "h")
        ok("https://atcoder.jp/contests/abc001/tasks/abc001_1", "abc001", "abc001_1", "1")
        ok("https://atcoder.jp/contests/arc189/tasks/arc189_a?lang=en", "arc189", "arc189_a", "a")
        ok("https://atcoder.jp/contests/past202004-open/tasks/past202004_a/", "past202004-open", "past202004_a", "a")
        ok("https://ATCODER.jp/contests/abs/tasks/abc086_a#x", "abs", "abc086_a", "a")
        ok("https://atcoder.jp/contests/x/tasks/noscore_", "x", "noscore_", "noscore_")
    }

    @Test fun rejected() {
        no("https://codeforces.com/problemset/problem/954/G")
        no("http://atcoder.jp/contests/abc400/tasks/abc400_a")
        no("https://atcoder.jp/contests/abc400/tasks")
        no("https://atcoder.jp/contests/abc400/tasks/abc400_a/editorial")
        no("https://atcoder.jp/contests/abc%34/tasks/abc400_a")
        no("https://user@atcoder.jp/contests/abc400/tasks/abc400_a")
        no("https://atcoder.jp:8443/contests/abc400/tasks/abc400_a")
        no("")
    }
}

class TaskValidatorTest {
    private val base = """{"name":" A - ABC400  Party ","group":"AtCoder - ABC 400","url":"https://atcoder.jp/contests/abc400/tasks/abc400_a?lang=ja","interactive":false,"memoryLimit":1024,"timeLimit":2000,"tests":[{"input":"10","output":"40\r\n"}],"batch":{"id":"123E67C8-03c6-44a4-a3f9-5918533f9fb2","size":7}}"""

    private fun v(json: String) = TaskValidator.validate(json.toByteArray(), 1, Instant.EPOCH)

    @Test fun acceptsAndNormalizes() {
        val r = v(base) as ValidationResult.Ok
        val t = r.task
        assertEquals("A - ABC400 Party", t.name)
        assertEquals("A", t.displayIndex); assertEquals("ABC400 Party", t.title)
        assertEquals("https://atcoder.jp/contests/abc400/tasks/abc400_a", t.problemKey)
        assertEquals("10\n", t.tests[0].input); assertEquals("40\n", t.tests[0].output)
        assertEquals(7, t.batchSize); assertFalse(t.batchSynthesized)
        assertEquals("123e67c8-03c6-44a4-a3f9-5918533f9fb2", t.batchId.toString())
    }

    @Test fun defaults() {
        val r = v("""{"name":"A - x","url":"https://atcoder.jp/contests/c/tasks/c_a","tests":[]}""") as ValidationResult.Ok
        assertEquals(2000, r.task.timeLimitMs); assertEquals(1024, r.task.memoryLimitMb)
        assertTrue(r.task.batchSynthesized); assertEquals(1, r.task.batchSize)
        assertTrue(r.task.notes.size >= 2)
    }

    @Test fun rejects() {
        assertTrue(v("""[]""") is ValidationResult.Rejected)
        assertTrue(v("""{"url":"https://atcoder.jp/contests/c/tasks/c_a","tests":[]}""") is ValidationResult.Rejected)
        assertTrue(v(base.replace("\"timeLimit\":2000", "\"timeLimit\":\"2000\"")) is ValidationResult.Rejected)
        assertTrue(v(base.replace("\"timeLimit\":2000", "\"timeLimit\":600001")) is ValidationResult.Rejected)
        assertTrue(v(base.replace("\"memoryLimit\":1024", "\"memoryLimit\":1024.5")) is ValidationResult.Rejected)
        assertTrue(v(base.replace("\"interactive\":false", "\"interactive\":null")) is ValidationResult.Rejected)
        assertTrue(v(base.replace("\"size\":7", "\"size\":501")) is ValidationResult.Rejected)
        assertTrue(v("""{"name":"A","url":"https://codeforces.com/x","tests":[]}""") is ValidationResult.Unsupported)
        assertTrue(v(byteArrayOf(0xff.toByte(), 0xfe.toByte()).let { String(it, Charsets.ISO_8859_1) }) is ValidationResult.Rejected)
        val many = (1..101).joinToString(",") { """{"input":"1","output":"1"}""" }
        assertTrue(v("""{"name":"A","url":"https://atcoder.jp/contests/c/tasks/c_a","tests":[$many]}""") is ValidationResult.Rejected)
    }

    @Test fun invalidBatchIdSynthesized() {
        val r = v(base.replace("123E67C8-03c6-44a4-a3f9-5918533f9fb2", "nope")) as ValidationResult.Ok
        assertTrue(r.task.batchSynthesized); assertEquals(1, r.task.batchSize)
    }

    @Test fun timeLimitFloor() {
        val r = v(base.replace("\"timeLimit\":2000", "\"timeLimit\":2500.9")) as ValidationResult.Ok
        assertEquals(2500, r.task.timeLimitMs)
    }
}

class SafePathTest {
    @Test fun safeComponent() {
        assertEquals("abc400_a", SafePath.safeComponent("abc400_a"))
        assertEquals("past202004-open", SafePath.safeComponent("past202004-open"))
        assertTrue(SafePath.safeComponent("ABC400_a").startsWith("~"))
        assertTrue(SafePath.safeComponent("con").startsWith("~"))
        assertTrue(SafePath.safeComponent("ABC") != SafePath.safeComponent("abc"))
    }

    @Test fun segments() {
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "con", "x.", "x ", "a:b", "a*b")) {
            try { SafePath.validateSegment(bad); fail("accepted '$bad'") } catch (e: Exception) {}
        }
        SafePath.validateSegment("abc400_a.py")
    }
}

class TemplateTest {
    private val vars = PathTemplate.variables("abc400", "abc400_a", "a")

    @Test fun defaults() {
        assertEquals("abc400/abc400_a.py", PathTemplate.expand("\${contestId}/\${taskId}.py", vars))
        assertEquals("abc400/tests/abc400_a", PathTemplate.expand("\${contestId}/tests/\${taskId}", vars))
        assertEquals("abc400/a.py", PathTemplate.expand("\${contestId}/\${taskIndex}.py", vars))
    }

    @Test fun rejects() {
        for (bad in listOf("", "/abs/x.py", "../\${taskId}.py", "\${name}.py", "\${contestId}/./x.py", "C:/x.py", "\${contestId}/\${taskId}/")) {
            try { PathTemplate.expand(bad, vars); fail("accepted '$bad'") } catch (e: TemplateException) {}
        }
        assertNull(PathTemplate.validateForSettings("\${contestId}/\${taskId}.py"))
        assertTrue(PathTemplate.validateForSettings("\${nope}.py") != null)
    }

    @Test fun solutionTemplate() {
        val v = mapOf("name" to "A - x", "url" to "https://atcoder.jp/contests/c/tasks/c_a", "title" to "x", "group" to "g")
        val out = SolutionTemplate.render("# \${name}\n# \${url}\nprint('$\${x}')\n", v)
        assertEquals("# A - x\n# https://atcoder.jp/contests/c/tasks/c_a\nprint('\${x}')\n", out)
        try { SolutionTemplate.render("x = '\${name}'", v); fail() } catch (e: TemplateException) {}
        try { SolutionTemplate.render("# \${unknown}", v); fail() } catch (e: TemplateException) {}
        assertTrue(SolutionTemplate.render(SolutionTemplate.BUILTIN, v).startsWith("# A - x\n# https://"))
    }
}

class ComparatorTest {
    private val lines = CompareSettings(CompareMode.LINES, 1e-6, 1e-6)
    private val tokens = CompareSettings(CompareMode.TOKENS, 1e-6, 1e-6)
    private val float = CompareSettings(CompareMode.FLOAT, 1e-6, 1e-6)

    private fun ok(a: String, e: String, s: CompareSettings) = assertTrue("'$a' vs '$e'", OutputComparator.compare(a, e, s) is CompareResult.Match)
    private fun ng(a: String, e: String, s: CompareSettings) = assertTrue("'$a' vs '$e'", OutputComparator.compare(a, e, s) is CompareResult.Mismatch)

    @Test fun linesMode() {
        ok("1 2 \n3\n\n", "1 2\n3\n", lines)
        ok("1\r\n2\r\n", "1\n2\n", lines)
        ng("1 2\n3\n", "1\n2 3\n", lines)
        ng("1\n", "1\n2\n", lines)
        ok("", "\n", lines)
    }

    @Test fun tokensMode() {
        ok("1 2\n3\n", "1\n2 3\n", tokens)
        ng("1 2\n", "1 2 3\n", tokens)
    }

    @Test fun floatMode() {
        ok("1.0000001\n", "1\n", float)
        ng("1.00002\n", "1\n", float)
        ok("1000001\n", "1000000\n", float)
        ng("NaN\n", "NaN\n", float)
        ng("inf\n", "inf\n", float)
        ng("1e999\n", "1e999\n", float)
        ok("Yes\n", "Yes\n", float)
        ng("1\n", "x\n", float)
    }
}

class HttpRulesTest {
    @Test fun host() {
        assertTrue(HttpIngressHandler.isAllowedHost("localhost:10046", 10046))
        assertTrue(HttpIngressHandler.isAllowedHost("LOCALHOST:10046", 10046))
        assertTrue(HttpIngressHandler.isAllowedHost("127.0.0.1:10046", 10046))
        assertFalse(HttpIngressHandler.isAllowedHost("localhost:10047", 10046))
        assertFalse(HttpIngressHandler.isAllowedHost("evil.example:10046", 10046))
        assertFalse(HttpIngressHandler.isAllowedHost("localhost", 10046))
    }

    @Test fun origin() {
        assertTrue(HttpIngressHandler.isAllowedOrigin("chrome-extension://cjnmckjndlpiamhfimnnjmnckgghkjbl"))
        assertTrue(HttpIngressHandler.isAllowedOrigin("moz-extension://8a7c4e5f-1234-4abc-9def-0123456789ab"))
        assertFalse(HttpIngressHandler.isAllowedOrigin("https://example.com"))
        assertFalse(HttpIngressHandler.isAllowedOrigin("null"))
        assertFalse(HttpIngressHandler.isAllowedOrigin("chrome-extension://abc/path"))
        assertFalse(HttpIngressHandler.isAllowedOrigin("chrome-extension://"))
    }

    @Test fun contentType() {
        assertTrue(HttpIngressHandler.isJsonUtf8("application/json"))
        assertTrue(HttpIngressHandler.isJsonUtf8("Application/JSON; charset=UTF-8"))
        assertFalse(HttpIngressHandler.isJsonUtf8("application/json; charset=latin1"))
        assertFalse(HttpIngressHandler.isJsonUtf8("text/plain"))
    }
}
