package companion.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import companion.settings.CompanionProjectSettings
import companion.settings.CompareMode
import companion.storage.Hashing
import companion.storage.ProblemRecord
import companion.storage.ProblemRepository
import companion.storage.TestCaseRef
import companion.storage.TestKind
import companion.ui.Notifications
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

interface RunResultsListener {
    fun runChanged(problemKey: String)

    companion object {
        val TOPIC: Topic<RunResultsListener> = Topic.create("companion.runs", RunResultsListener::class.java)
    }
}

private class RunHandle(val runId: String, val problemKey: String) {
    val cancelled = AtomicBoolean(false)
    val executors = ConcurrentHashMap.newKeySet<CaseExecutor>()
}

/** Runs sample / custom tests for a problem with the project's interpreter. */
@Service(Service.Level.PROJECT)
class TestRunnerService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(TestRunnerService::class.java)
    private val results = ConcurrentHashMap<String, RunResult>()
    private val active = ConcurrentHashMap<String, RunHandle>()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()

    fun result(problemKey: String): RunResult? = results[problemKey]
    fun isRunning(problemKey: String): Boolean = active.containsKey(problemKey)

    fun runAll(record: ProblemRecord) = run(record, null)

    fun run(record: ProblemRecord, only: Set<String>?) {
        val key = record.problemKey
        if (WAITING.get() >= MAX_WAITING_RUNS) {
            Notifications.info(project, "Competitive Companion", "Run queue is full ($MAX_WAITING_RUNS waiting). Try again shortly.")
            return
        }
        cancel(key)
        val handle = RunHandle(UUID.randomUUID().toString(), key)
        val previous = active.put(key, handle)
        previous?.cancelled?.set(true)
        WAITING.incrementAndGet()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Running tests: ${record.manifest.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                WAITING.decrementAndGet()
                try {
                    execute(record, only, handle, indicator)
                } finally {
                    active.remove(key, handle)
                    publish(key)
                }
            }

            override fun onCancel() {
                handle.cancelled.set(true)
                handle.executors.forEach { it.requestStop(StopReason.CANCELLED) }
            }
        })
    }

    fun cancel(problemKey: String) {
        val h = active[problemKey] ?: return
        h.cancelled.set(true)
        h.executors.forEach { it.requestStop(StopReason.CANCELLED) }
    }

    private fun execute(record: ProblemRecord, only: Set<String>?, handle: RunHandle, indicator: ProgressIndicator) {
        val runDir = runsRoot().resolve(handle.runId)
        try {
            executeInDirectory(record, only, handle, indicator, runDir)
        } finally {
            try { deleteTree(runDir) } catch (e: IOException) { log.warn("run dir cleanup failed", e) }
        }
    }

    private fun executeInDirectory(record: ProblemRecord, only: Set<String>?, handle: RunHandle, indicator: ProgressIndicator, runDir: Path) {
        val key = record.problemKey
        val settings = CompanionProjectSettings.getInstance(project).state
        val startedAt = Instant.now()
        fun fail(msg: String) {
            storeResult(handle, RunResult(handle.runId, key, record.manifest.activeSampleRevision, "", startedAt, Instant.now(), null, emptyList(), false, msg))
        }

        // 1. Save editors.
        ApplicationManager.getApplication().invokeAndWait {
            if (!project.isDisposed) FileDocumentManager.getInstance().saveAllDocuments()
        }
        if (!Files.isRegularFile(record.solutionPath)) return fail("solution file missing: ${record.manifest.solutionPath}")

        // 2. Snapshot.
        val repo = ProblemRepository.getInstance(project)
        val tests = repo.listTests(record).filter { only == null || it.name in only }
        val solutionCopy: Path
        val solutionSha: String
        val cases: List<CaseSpec>
        try {
            Files.createDirectories(runDir)
            solutionCopy = runDir.resolve("solution.py")
            if (Files.size(record.solutionPath) > MAX_SOLUTION_BYTES) return fail("solution larger than 4 MiB")
            Files.copy(record.solutionPath, solutionCopy, StandardCopyOption.REPLACE_EXISTING)
            solutionSha = Hashing.sha256Hex(solutionCopy)
            if (tests.size > MAX_CASES) return fail("more than $MAX_CASES test cases")
            val allowed = ceil(record.manifest.timeLimitMs * settings.timeLimitMultiplier.coerceIn(0.1, 10.0)).toLong()
            val compare = CompareSettings(
                record.manifest.compareMode?.let { runCatching { CompareMode.valueOf(it) }.getOrNull() } ?: settings.compareMode,
                record.manifest.absTol ?: settings.absTol,
                record.manifest.relTol ?: settings.relTol,
            )
            cases = tests.map { t -> loadCase(t, allowed, settings.terminationGraceMs.toLong().coerceIn(0, 2000), record.manifest.judge == "MANUAL", compare) }
        } catch (e: IOException) {
            return fail("snapshot failed: ${e.message}")
        }

        // 3. Interpreter.
        val interp = when (val r = InterpreterResolver.resolve(project, record.solutionPath)) {
            is InterpreterResult.Ok -> r.interpreter
            is InterpreterResult.Failed -> {
                storeResult(handle, RunResult(handle.runId, key, record.manifest.activeSampleRevision, solutionSha, startedAt, Instant.now(), null, cases.map { pending(it, Verdict.IE, r.reason) }, false, r.reason))
                return
            }
        }
        val runner = runnerScript() ?: return fail("could not extract runner script")

        val extraArgs = settings.extraInterpreterArgs.map { it.trim() }.filter { it.isNotEmpty() }
        val bad = extraArgs.firstOrNull { it == "-c" || it == "-m" || it == "-" || !it.startsWith("-") }
        if (bad != null) return fail("unsupported extra interpreter argument: $bad")

        // 4. Run.
        val resultsArr = arrayOfNulls<CaseResult>(cases.size)
        for (i in cases.indices) resultsArr[i] = pending(cases[i], Verdict.PENDING, null)
        fun snapshotResult(finished: Boolean) = RunResult(
            runId = handle.runId, problemKey = key, sampleRevision = record.manifest.activeSampleRevision,
            solutionSha256 = solutionSha, startedAt = startedAt,
            finishedAt = if (finished) Instant.now() else null, interpreter = interp.description,
            cases = resultsArr.map { it!! },
            stale = finished && currentSolutionSha(record) != solutionSha, message = null,
        )
        fun updateCase(index: Int, result: CaseResult) = synchronized(resultsArr) {
            resultsArr[index] = result
            storeResult(handle, snapshotResult(false))
            indicator.fraction = resultsArr.count { it!!.verdict !in setOf(Verdict.PENDING, Verdict.RUNNING) }.toDouble() / cases.size
        }
        storeResult(handle, snapshotResult(false))
        publish(key)

        val parallelism = settings.parallelism.coerceIn(1, MAX_GLOBAL_CASES)
        val pool = if (parallelism > 1) Executors.newFixedThreadPool(parallelism) else null
        try {
            val futures = ArrayList<Future<*>>()
            for (i in cases.indices) {
                val job = Runnable {
                    val spec = cases[i]
                    val r: CaseResult = when {
                        record.manifest.interactive -> pending(spec, Verdict.SKIP, "interactive problem")
                        handle.cancelled.get() || indicator.isCanceled -> pending(spec, Verdict.CANCELLED, null)
                        else -> {
                            GLOBAL_CASES.acquire()
                            try {
                                updateCase(i, pending(spec, Verdict.RUNNING, null))
                                publish(key)
                                val ex = CaseExecutor(interp.executable, extraArgs, runner, solutionCopy, record.solutionPath, scheduler, handle.cancelled)
                                handle.executors.add(ex)
                                try { ex.execute(spec) } finally { handle.executors.remove(ex) }
                            } finally {
                                GLOBAL_CASES.release()
                            }
                        }
                    }
                    updateCase(i, r)
                    publish(key)
                }
                if (pool != null) futures.add(pool.submit(job)) else job.run()
            }
            futures.forEach { it.get() }
        } finally {
            pool?.shutdownNow()
            synchronized(resultsArr) { storeResult(handle, snapshotResult(true)) }
        }
    }

    /** A cancelled run may finish after its replacement; only the current run owns the UI result. */
    private fun storeResult(handle: RunHandle, result: RunResult) {
        results.compute(handle.problemKey) { _, previous ->
            if (active[handle.problemKey] === handle) result else previous
        }
    }

    private fun pending(spec: CaseSpec, v: Verdict, msg: String?) = CaseResult(
        spec.testName, spec.kind, v, null, null, String(spec.input, StandardCharsets.UTF_8), spec.expected, "", "",
        stdoutLimitExceeded = false, stderrTruncated = false, cleanupIncomplete = false, message = msg,
    )

    private fun loadCase(t: TestCaseRef, allowedMs: Long, graceMs: Long, manual: Boolean, compare: CompareSettings): CaseSpec {
        if (Files.size(t.inputPath) > MAX_TEST_BYTES) throw IOException("${t.name}.in larger than 8 MiB")
        val input = Files.readAllBytes(t.inputPath)
        val expected = t.outputPath?.let {
            if (Files.size(it) > MAX_TEST_BYTES) throw IOException("${t.name}.out larger than 8 MiB")
            Files.readString(it, StandardCharsets.UTF_8)
        }
        return CaseSpec(t.name, t.kind, input, expected, allowedMs, graceMs, manual, compare)
    }

    private fun currentSolutionSha(record: ProblemRecord): String = try {
        Hashing.sha256Hex(record.solutionPath)
    } catch (e: IOException) { "" }

    fun isStale(record: ProblemRecord): Boolean {
        val r = results[record.problemKey] ?: return false
        if (r.running) return false
        return r.stale || r.sampleRevision != record.manifest.activeSampleRevision || currentSolutionSha(record) != r.solutionSha256
    }

    private fun publish(key: String) {
        if (!project.isDisposed) project.messageBus.syncPublisher(RunResultsListener.TOPIC).runChanged(key)
    }

    private fun runsRoot(): Path = Path.of(PathManager.getSystemPath(), "companion", "runs")

    @Synchronized
    private fun runnerScript(): Path? {
        val target = Path.of(PathManager.getSystemPath(), "companion", "pluginRunner.py")
        val bytes = javaClass.getResourceAsStream("/runner/pluginRunner.py")?.use { it.readAllBytes() } ?: return null
        try {
            if (!Files.exists(target) || !Files.readAllBytes(target).contentEquals(bytes)) {
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
            }
        } catch (e: IOException) {
            return null
        }
        return target
    }

    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    override fun dispose() {
        active.values.forEach { h -> h.cancelled.set(true); h.executors.forEach { it.requestStop(StopReason.CANCELLED) } }
    }

    companion object {
        const val MAX_SOLUTION_BYTES = 4L * 1024 * 1024
        const val MAX_TEST_BYTES = 8L * 1024 * 1024
        const val MAX_CASES = 200
        const val MAX_GLOBAL_CASES = 4
        const val MAX_WAITING_RUNS = 8
        private val GLOBAL_CASES = Semaphore(MAX_GLOBAL_CASES)
        private val WAITING = AtomicInteger()

        fun getInstance(project: Project): TestRunnerService = project.getService(TestRunnerService::class.java)
    }
}
