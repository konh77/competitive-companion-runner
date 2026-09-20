package companion

import companion.run.CaseExecutor
import companion.run.CaseSpec
import companion.run.CompareSettings
import companion.run.Verdict
import companion.settings.CompareMode
import companion.storage.TestKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real-process tests for the runner. Skipped when no python3 is on PATH. */
class CaseExecutorTest {
    private lateinit var python: Path
    private lateinit var dir: Path
    private lateinit var runner: Path
    private val scheduler = Executors.newScheduledThreadPool(2)
    private val compare = CompareSettings(CompareMode.LINES, 1e-6, 1e-6)

    @Before fun setUp() {
        val found = listOf("/usr/bin/python3", "/usr/local/bin/python3", "/opt/homebrew/bin/python3").map { Path.of(it) }.firstOrNull { Files.isExecutable(it) }
        assumeTrue("python3 not found", found != null)
        python = found!!
        dir = Files.createTempDirectory("companion-test")
        runner = dir.resolve("pluginRunner.py")
        Files.write(runner, javaClass.getResourceAsStream("/runner/pluginRunner.py")!!.readAllBytes())
    }

    @After fun tearDown() {
        scheduler.shutdownNow()
        if (::dir.isInitialized) Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun run(source: String, input: String, expected: String?, allowedMs: Long = 2000, cancel: AtomicBoolean = AtomicBoolean(false)) =
        run(source, input, expected, allowedMs, cancel, manual = false)

    private fun run(source: String, input: String, expected: String?, allowedMs: Long, cancel: AtomicBoolean, manual: Boolean): companion.run.CaseResult {
        val original = dir.resolve("sol.py")
        Files.writeString(original, source)
        val copy = dir.resolve("copy.py")
        Files.writeString(copy, source)
        val ex = CaseExecutor(python, emptyList(), runner, copy, original, scheduler, cancel)
        return ex.execute(CaseSpec("t", TestKind.SAMPLE, input.toByteArray(), expected, allowedMs, 250, manual, compare))
    }

    @Test fun ac() {
        val r = run("import sys\nn=int(sys.stdin.readline())\nprint(n*4)\n", "10\n", "40\n")
        assertEquals(r.message, Verdict.AC, r.verdict)
        assertEquals(0, r.exitCode)
        assertTrue(r.elapsedMs!! >= 0)
    }

    @Test fun wa() {
        val r = run("print(41)\n", "10\n", "40\n")
        assertEquals(Verdict.WA, r.verdict)
        assertEquals("41\n", r.stdout)
    }

    @Test fun re() {
        val r = run("import sys\nprint('partial')\nsys.exit(3)\n", "", "x\n")
        assertEquals(Verdict.RE, r.verdict)
        assertEquals(3, r.exitCode)
    }

    @Test fun reTracebackUsesOriginalPath() {
        val r = run("x = 1\nraise ValueError('boom')\n", "", "x\n")
        assertEquals(Verdict.RE, r.verdict)
        assertTrue(r.stderr, r.stderr.contains("sol.py") && r.stderr.contains("boom"))
    }

    @Test fun tle() {
        val t0 = System.currentTimeMillis()
        val r = run("while True:\n    pass\n", "", "x\n", allowedMs = 300)
        val took = System.currentTimeMillis() - t0
        assertEquals(Verdict.TLE, r.verdict)
        assertTrue("took $took ms", took < 3000)
    }

    @Test fun tleDoesNotWaitForStdin() {
        val big = "1\n".repeat(4 * 1024 * 1024)
        val r = run("while True:\n    pass\n", big, "x\n", allowedMs = 300)
        assertEquals(Verdict.TLE, r.verdict)
    }

    @Test fun ole() {
        val r = run("import sys\nwhile True:\n    sys.stdout.write('x'*65536)\n", "", "x\n", allowedMs = 10000)
        assertEquals(Verdict.OLE, r.verdict)
        assertTrue(r.stdoutLimitExceeded)
    }

    @Test fun manualStillReportsRe() {
        val r = run("raise SystemExit(2)\n", "", null, 2000, AtomicBoolean(false), manual = true)
        assertEquals(Verdict.RE, r.verdict)
        val ok = run("print('anything')\n", "", null, 2000, AtomicBoolean(false), manual = true)
        assertEquals(Verdict.MANUAL, ok.verdict)
    }

    @Test fun cancelled() {
        val flag = AtomicBoolean(false)
        scheduler.schedule({ flag.set(true) }, 200, java.util.concurrent.TimeUnit.MILLISECONDS)
        val r = run("while True:\n    pass\n", "", "x\n", allowedMs = 5000, cancel = flag)
        assertEquals(Verdict.CANCELLED, r.verdict)
    }

    @Test fun childProcessesReaped() {
        val src = "import subprocess, sys\nsubprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])\nwhile True:\n    pass\n"
        val r = run(src, "", "x\n", allowedMs = 300)
        assertEquals(Verdict.TLE, r.verdict)
        assertTrue("cleanup incomplete", !r.cleanupIncomplete)
    }

    @Test fun cwdAndImports() {
        Files.writeString(dir.resolve("helper.py"), "VALUE = 7\n")
        Files.writeString(dir.resolve("data.txt"), "hello")
        val r = run("import helper\nprint(helper.VALUE, open('data.txt').read())\n", "", "7 hello\n")
        assertEquals(r.stderr, Verdict.AC, r.verdict)
    }

    @Test(timeout = 8000) fun timeoutForceKillsUncooperativeSolution() {
        uncooperativeSolution(cancel = false)
    }

    @Test(timeout = 8000) fun cancelForceKillsUncooperativeSolution() {
        uncooperativeSolution(cancel = true)
    }

    private fun uncooperativeSolution(cancel: Boolean) {
        val pidFile = dir.resolve("pid")
        val flag = AtomicBoolean(false)
        val source = """
            import os, signal, time
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            with open('pid', 'w') as f:
                f.write(str(os.getpid()))
            while True:
                time.sleep(0.01)
        """.trimIndent()
        // Keep regressions bounded even if the executor never escalates SIGTERM.
        val safety = scheduler.schedule({ killRecordedProcess(pidFile) }, 3500, TimeUnit.MILLISECONDS)
        if (cancel) scheduler.schedule({ flag.set(true) }, 500, TimeUnit.MILLISECONDS)
        try {
            val started = System.nanoTime()
            val result = run(source, "", "", if (cancel) 10_000 else 500, flag)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(if (cancel) Verdict.CANCELLED else Verdict.TLE, result.verdict)
            assertTrue("termination took $elapsed ms", elapsed < 2500)
            assertTrue("cleanup incomplete", !result.cleanupIncomplete)
        } finally {
            killRecordedProcess(pidFile)
            safety.cancel(false)
        }
    }

    @Test(timeout = 8000) fun childIgnoringTerminationIsStillReaped() {
        val childPid = dir.resolve("child-pid")
        val child = "import os, signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); open('child-pid', 'w').write(str(os.getpid())); time.sleep(60)"
        val source = "import subprocess, sys, time\nsubprocess.Popen([sys.executable, '-c', \"$child\"])\nwhile True: time.sleep(0.01)\n"
        try {
            val result = run(source, "", "", allowedMs = 500)
            assertEquals(Verdict.TLE, result.verdict)
            val pid = Files.readString(childPid).toLong()
            assertTrue("child $pid survived timeout", ProcessHandle.of(pid).map { !it.isAlive }.orElse(true))
            assertTrue("cleanup incomplete", !result.cleanupIncomplete)
        } finally {
            killRecordedProcess(childPid)
        }
    }

    private fun killRecordedProcess(path: Path) {
        if (Files.exists(path)) Files.readString(path).toLongOrNull()?.let { pid ->
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        }
    }
}
