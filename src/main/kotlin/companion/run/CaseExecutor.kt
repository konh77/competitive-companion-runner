package companion.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import companion.storage.TestKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CaseSpec(
    val testName: String,
    val kind: TestKind,
    val input: ByteArray,
    val expected: String?,
    val allowedMs: Long,
    val terminationGraceMs: Long,
    val judgeManual: Boolean,
    val compare: CompareSettings,
)

/** Reason that ended a process before it finished on its own. First writer wins. */
enum class StopReason { TLE, OLE, CANCELLED }

/**
 * Runs one test case: independent deadline monitor, stdin writer, bounded stdout / stderr
 * readers and best-effort process-tree termination.
 */
class CaseExecutor(
    private val interpreter: Path,
    private val extraArgs: List<String>,
    private val runnerScript: Path,
    private val solutionCopy: Path,
    private val originalSolution: Path,
    private val scheduler: ScheduledExecutorService,
    private val cancelFlag: AtomicBoolean,
) {
    private data class StopRequest(val reason: StopReason, val atNs: Long = System.nanoTime())
    private val stopRequest = AtomicReference<StopRequest?>(null)

    fun execute(spec: CaseSpec): CaseResult {
        val cmd = GeneralCommandLine()
            .withExePath(interpreter.toString())
            .withParameters(extraArgs)
            .withParameters(runnerScript.toString(), solutionCopy.toString(), originalSolution.toString())
            .withWorkDirectory(originalSolution.parent.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment("PYTHONIOENCODING", "utf-8")
            .withEnvironment("PYTHONUTF8", "1")
            .withEnvironment("PYTHONDONTWRITEBYTECODE", "1")
            .withCharset(StandardCharsets.UTF_8)

        if (cancelFlag.get()) return base(spec, Verdict.CANCELLED, null, null, "", "", "cancelled before start")

        val stdout = BoundedSink(STDOUT_LIMIT)
        val stderr = BoundedSink(STDERR_LIMIT)
        var deadline: ScheduledFuture<*>? = null
        val t0 = System.nanoTime()
        val p: Process
        try {
            // Deadline is armed before start so a slow launch still counts.
            deadline = scheduler.schedule({ requestStop(StopReason.TLE) }, spec.allowedMs, TimeUnit.MILLISECONDS)
            p = cmd.createProcess()
        } catch (e: ExecutionException) {
            deadline?.cancel(false)
            return base(spec, Verdict.IE, null, null, "", "", "failed to start interpreter: ${e.message}")
        }
        if (cancelFlag.get()) requestStop(StopReason.CANCELLED)

        val cancelPoll = scheduler.scheduleWithFixedDelay({ if (cancelFlag.get()) requestStop(StopReason.CANCELLED) }, 50, 50, TimeUnit.MILLISECONDS)

        val stdinThread = Thread({
            try {
                p.outputStream.use { it.write(spec.input); it.flush() }
            } catch (_: IOException) {
                // Child exited early / closed stdin; handled by exit code.
            }
        }, "companion-stdin")
        val stdoutThread = Thread({
            pump(p.inputStream, stdout) { requestStop(StopReason.OLE) }
        }, "companion-stdout")
        val stderrThread = Thread({
            pump(p.errorStream, stderr, null)
        }, "companion-stderr")
        listOf(stdinThread, stdoutThread, stderrThread).forEach { it.isDaemon = true; it.start() }

        var exitCode: Int? = null
        var interrupted = false
        // Retain handles while the parent is alive: after it exits, descendants may
        // be reparented and disappear from p.descendants().
        val descendants = LinkedHashSet<ProcessHandle>()
        val signalled = HashSet<ProcessHandle>()
        try {
            while (true) {
                p.descendants().use { it.forEach(descendants::add) }
                if (cancelFlag.get()) requestStop(StopReason.CANCELLED)
                if (System.nanoTime() - t0 >= TimeUnit.MILLISECONDS.toNanos(spec.allowedMs)) requestStop(StopReason.TLE)
                val stop = stopRequest.get()
                if (stop != null) {
                    val handles = descendants + p.toHandle()
                    // Process.destroy() can close stdin while a writer holds its
                    // monitor. ProcessHandle signals without blocking on that pipe.
                    handles.filter { it.isAlive && signalled.add(it) }.forEach { it.destroy() }
                    val sinceStop = System.nanoTime() - stop.atNs
                    if (sinceStop >= TimeUnit.MILLISECONDS.toNanos(spec.terminationGraceMs)) {
                        handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
                    }
                    if (sinceStop >= TimeUnit.MILLISECONDS.toNanos(spec.terminationGraceMs + FORCE_CONFIRM_MS)) break
                }
                try {
                    if (p.waitFor(20, TimeUnit.MILLISECONDS)) {
                        exitCode = p.exitValue()
                        break
                    }
                } catch (_: InterruptedException) {
                    interrupted = true
                    requestStop(StopReason.CANCELLED)
                }
            }
        } finally {
            deadline?.cancel(false)
            cancelPoll.cancel(false)
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        // Re-check the deadline: scheduler delay must not turn a late finish into AC.
        if (elapsedMs > spec.allowedMs) requestStop(StopReason.TLE)

        // Reap before joining readers: a child can keep stdout / stderr open.
        val cleanupIncomplete = !reapDescendants(descendants + p.toHandle(), spec.terminationGraceMs)
        stdoutThread.join(2_000)
        stderrThread.join(2_000)
        stdinThread.join(500)

        val outText = stdout.text()
        val errText = stderr.text()
        val reason = stopRequest.get()?.reason

        val verdict: Verdict
        var message: String? = null
        when {
            reason == StopReason.CANCELLED -> verdict = Verdict.CANCELLED
            reason == StopReason.TLE -> { verdict = Verdict.TLE; message = "exceeded ${spec.allowedMs} ms" }
            reason == StopReason.OLE -> { verdict = Verdict.OLE; message = "stdout exceeded ${STDOUT_LIMIT / (1024 * 1024)} MiB" }
            exitCode == null -> { verdict = Verdict.IE; message = "exit status unavailable" }
            cleanupIncomplete -> { verdict = Verdict.IE; message = "child processes could not be terminated" }
            exitCode != 0 -> { verdict = Verdict.RE; message = "exit code $exitCode" }
            spec.expected == null || spec.judgeManual -> verdict = Verdict.MANUAL
            stdout.invalidUtf8 -> { verdict = Verdict.WA; message = "stdout contains invalid UTF-8" }
            else -> when (val c = OutputComparator.compare(outText, spec.expected, spec.compare)) {
                is CompareResult.Match -> verdict = Verdict.AC
                is CompareResult.Mismatch -> { verdict = Verdict.WA; message = c.detail }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return CaseResult(
            testName = spec.testName, kind = spec.kind, verdict = verdict, elapsedMs = elapsedMs, exitCode = exitCode,
            input = String(spec.input, StandardCharsets.UTF_8), expected = spec.expected,
            stdout = outText, stderr = errText,
            stdoutLimitExceeded = stdout.overflowed, stderrTruncated = stderr.overflowed,
            cleanupIncomplete = cleanupIncomplete, message = message,
        )
    }

    private fun base(spec: CaseSpec, v: Verdict, elapsed: Long?, exit: Int?, out: String, err: String, msg: String?) = CaseResult(
        spec.testName, spec.kind, v, elapsed, exit, String(spec.input, StandardCharsets.UTF_8), spec.expected, out, err,
        stdoutLimitExceeded = false, stderrTruncated = false, cleanupIncomplete = false, message = msg,
    )

    fun requestStop(reason: StopReason) {
        stopRequest.compareAndSet(null, StopRequest(reason))
    }

    /** Waits for the grace period, then force-kills the whole tree. Returns true when everything is gone. */
    private fun reapDescendants(tracked: Collection<ProcessHandle>, graceMs: Long): Boolean {
        val handles = tracked.filter { it.isAlive }
        if (handles.isEmpty()) return true
        handles.forEach { it.destroy() }
        val end = System.nanoTime() + graceMs * 1_000_000
        while (System.nanoTime() < end && handles.any { it.isAlive }) Thread.sleep(10)
        handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
        val hardEnd = System.nanoTime() + FORCE_CONFIRM_MS * 1_000_000
        while (System.nanoTime() < hardEnd && handles.any { it.isAlive }) Thread.sleep(10)
        return handles.none { it.isAlive }
    }

    private fun pump(input: InputStream, sink: BoundedSink, onOverflow: (() -> Unit)?) {
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val fitted = sink.write(buf, n)
                if (!fitted && onOverflow != null) {
                    onOverflow()
                    // Keep draining so the child never blocks on a full pipe.
                }
            }
        } catch (_: IOException) {
        }
    }

    /** Keeps at most [limit] bytes; records overflow and drains the rest. */
    private class BoundedSink(private val limit: Int) {
        private val data = ByteArrayOutputStream()
        @Volatile var overflowed = false
        var invalidUtf8 = false

        @Synchronized
        fun write(buf: ByteArray, n: Int): Boolean {
            val room = limit - data.size()
            if (n <= room) { data.write(buf, 0, n); return true }
            if (room > 0) data.write(buf, 0, room)
            overflowed = true
            return false
        }

        @Synchronized
        fun text(): String {
            val bytes = data.toByteArray()
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                invalidUtf8 = true
                String(bytes, StandardCharsets.UTF_8)
            }
        }
    }

    companion object {
        const val STDOUT_LIMIT = 8 * 1024 * 1024
        const val STDERR_LIMIT = 8 * 1024 * 1024
        const val FORCE_CONFIRM_MS = 1_000L
    }
}
