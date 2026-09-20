package companion

import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.UIUtil
import companion.model.TaskValidator
import companion.model.ValidationResult
import companion.run.TestRunnerService
import companion.run.RunResultsListener
import companion.run.Verdict
import companion.settings.CompanionProjectSettings
import companion.settings.InterpreterMode
import companion.storage.ProblemRepository
import companion.storage.SaveOutcome
import companion.storage.TestKind
import companion.storage.Hashing
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch

/** Heavy (real directory) tests: receive → files → run → verdicts → rebuild. */
class RepositoryIntegrationTest : HeavyPlatformTestCase() {

    private fun task(json: String) = (TaskValidator.validate(json.toByteArray(), 1, Instant.now()) as ValidationResult.Ok).task

    private val abc400a = """{"name":"A - ABC400 Party","group":"AtCoder - AtCoder Beginner Contest 400","url":"https://atcoder.jp/contests/abc400/tasks/abc400_a","interactive":false,"memoryLimit":1024,"timeLimit":2000,"tests":[{"input":"10\n","output":"40\n"},{"input":"7\n","output":"-1\n"}],"batch":{"id":"123e67c8-03c6-44a4-a3f9-5918533f9fb2","size":1}}"""

    private fun repo() = ProblemRepository.getInstance(project)

    fun testReceiveCreatesLayout() {
        val outcome = repo().save(task(abc400a)) as SaveOutcome.Saved
        assertTrue(outcome.isNew && outcome.createdSolution && outcome.samplesChanged)
        val root = repo().root
        assertTrue(Files.isRegularFile(root.resolve("abc400/abc400_a.py")))
        assertTrue(Files.isRegularFile(root.resolve("abc400/tests/abc400_a/problem.json")))
        assertTrue(Files.isRegularFile(root.resolve(".companion/index.json")))
        val tests = repo().listTests(outcome.record)
        assertEquals(listOf("sample_01", "sample_02"), tests.map { it.name })
        assertEquals("10\n", Files.readString(tests[0].inputPath))
        assertFalse(Files.exists(root.resolve("abc400/tests/abc400_a/.staging")) && Files.list(root.resolve("abc400/tests/abc400_a/.staging")).use { it.findAny().isPresent })
    }

    fun testReReceiveKeepsSolutionAndReplacesSamples() {
        val first = repo().save(task(abc400a)) as SaveOutcome.Saved
        val sol = first.record.solutionPath
        Files.writeString(sol, "print('mine')\n")
        val rev1 = first.record.manifest.activeSampleRevision

        val same = repo().save(task(abc400a)) as SaveOutcome.Saved
        assertFalse(same.samplesChanged)
        assertEquals(rev1, same.record.manifest.activeSampleRevision)
        assertEquals("print('mine')\n", Files.readString(sol))

        val changed = repo().save(task(abc400a.replace("\"output\":\"40\\n\"", "\"output\":\"41\\n\""))) as SaveOutcome.Saved
        assertTrue(changed.samplesChanged)
        assertNotSame(rev1, changed.record.manifest.activeSampleRevision)
        assertEquals("print('mine')\n", Files.readString(sol))
        assertEquals("41\n", Files.readString(repo().listTests(changed.record)[0].outputPath!!))
        assertEquals(3, changed.record.manifest.metadataRevision)
        assertTrue(Files.isRegularFile(changed.record.testsDir.resolve("problem.previous.json")))
        // old revision garbage collected except current + previous
        val revs = Files.list(changed.record.testsDir.resolve("samples")).use { it.map { p -> p.fileName.toString() }.toList() }
        assertTrue(revs.contains(changed.record.manifest.activeSampleRevision))
    }

    fun testEmptyReceiveKeepsExistingSamples() {
        repo().save(task(abc400a))
        val outcome = repo().save(task(abc400a.replace(Regex("\"tests\":\\[.*?\\]"), "\"tests\":[]"))) as SaveOutcome.Saved
        assertFalse(outcome.samplesChanged)
        assertEquals(2, outcome.record.manifest.sampleCount)
        assertEquals(0, outcome.record.manifest.lastReceivedSampleCount)
        assertTrue(outcome.notes.any { it.contains("kept existing") })
    }

    fun testConflictRejected() {
        repo().save(task(abc400a))
        val settings = CompanionProjectSettings.getInstance(project).state
        settings.solutionPathTemplate = "abc400/abc400_a.py" // literal path colliding with existing problem
        val outcome = repo().save(task(abc400a.replace("abc400_a", "abc400_b")))
        assertTrue(outcome.toString(), outcome is SaveOutcome.Failed)
        settings.solutionPathTemplate = CompanionProjectSettings.DEFAULT_SOLUTION_TEMPLATE
    }

    fun testCustomTestsAndRebuild() {
        val rec = (repo().save(task(abc400a)) as SaveOutcome.Saved).record
        val c1 = repo().addCustomTest(rec, "5", null)
        val c2 = repo().addCustomTest(rec, "8\n", "50\n")
        assertEquals("custom_01", c1.name); assertNull(c1.outputPath)
        assertEquals("custom_02", c2.name); assertNotNull(c2.outputPath)
        assertEquals(4, repo().listTests(rec).size)
        repo().deleteCustomTest(rec, c1)
        assertEquals(listOf("sample_01", "sample_02", "custom_02"), repo().listTests(rec).map { it.name })

        Files.delete(repo().indexPath)
        val note = repo().rebuild()
        assertTrue(note, note.startsWith("restored 1 problem"))
        assertNotNull(repo().find(rec.problemKey))
        assertNotNull(repo().findBySolution(rec.solutionPath))
    }

    fun testRunVerdicts() {
        val python = listOf("/usr/bin/python3", "/usr/local/bin/python3", "/opt/homebrew/bin/python3").firstOrNull { Files.isExecutable(Path.of(it)) }
        if (python == null) return
        val settings = CompanionProjectSettings.getInstance(project).state
        settings.interpreterMode = InterpreterMode.CUSTOM
        settings.customInterpreterPath = python
        settings.parallelism = 3

        val rec = (repo().save(task(abc400a)) as SaveOutcome.Saved).record
        Files.writeString(rec.solutionPath, "import sys\nn=int(sys.stdin.readline())\nprint(400//n if 400%n==0 else -1)\n")
        repo().addCustomTest(rec, "3\n", "-1\n")
        repo().addCustomTest(rec, "1\n", null)

        val runner = TestRunnerService.getInstance(project)
        runner.runAll(rec)
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            val r = runner.result(rec.problemKey)
            if (r != null && !r.running) break
            Thread.sleep(50)
        }
        val result = runner.result(rec.problemKey)!!
        assertFalse(result.running)
        assertNull(result.message, result.message)
        assertEquals(listOf(Verdict.AC, Verdict.AC, Verdict.AC, Verdict.MANUAL), result.cases.map { it.verdict })
        assertEquals(2, result.countOf(TestKind.SAMPLE, Verdict.AC))
        assertFalse(runner.isStale(rec))

        Files.writeString(rec.solutionPath, "print(41)\n")
        assertTrue(runner.isStale(rec))
    }

    fun testFailedRunRemovesSnapshot() {
        val settings = CompanionProjectSettings.getInstance(project).state
        settings.interpreterMode = InterpreterMode.CUSTOM
        settings.customInterpreterPath = repo().root.resolve("missing-python").toString()
        val record = (repo().save(task(abc400a)) as SaveOutcome.Saved).record
        val runner = TestRunnerService.getInstance(project)
        runner.runAll(record)
        waitUntil { !runner.isRunning(record.problemKey) }
        val result = runner.result(record.problemKey)!!
        assertTrue(result.message.orEmpty(), result.message.orEmpty().contains("interpreter not found"))
        val snapshot = Path.of(PathManager.getSystemPath(), "companion", "runs", result.runId)
        assertFalse("failed run left snapshot at $snapshot", Files.exists(snapshot))
    }

    fun testRerunKeepsLatestResults() {
        val python = listOf("/usr/bin/python3", "/usr/local/bin/python3", "/opt/homebrew/bin/python3").firstOrNull { Files.isExecutable(Path.of(it)) }
        if (python == null) return
        val settings = CompanionProjectSettings.getInstance(project).state
        settings.interpreterMode = InterpreterMode.CUSTOM
        settings.customInterpreterPath = python
        val record = (repo().save(task(abc400a)) as SaveOutcome.Saved).record
        Files.writeString(record.solutionPath, "print('old')\n")
        val runner = TestRunnerService.getInstance(project)
        val firstPublication = CountDownLatch(1)
        val releaseOldRun = CountDownLatch(1)
        val connection = project.messageBus.connect()
        connection.subscribe(RunResultsListener.TOPIC, object : RunResultsListener {
            override fun runChanged(problemKey: String) {
                if (problemKey != record.problemKey) return
                if (firstPublication.count > 0) {
                    firstPublication.countDown()
                    check(releaseOldRun.await(15, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
        })
        try {
            // ProgressManager runs Backgroundable tasks synchronously in unit-test
            // mode. Launch from pooled threads to exercise overlapping runs.
            val oldRun = ApplicationManager.getApplication().executeOnPooledThread { runner.runAll(record) }
            waitUntil { firstPublication.count == 0L }
            Files.writeString(record.solutionPath, "import sys\nn=int(sys.stdin.readline())\nprint(400//n if 400%n==0 else -1)\n")
            val newRun = ApplicationManager.getApplication().executeOnPooledThread { runner.runAll(record) }
            waitUntil { newRun.isDone }
            newRun.get()
            val latestId = runner.result(record.problemKey)!!.runId
            releaseOldRun.countDown()
            waitUntil { oldRun.isDone }
            oldRun.get()
            assertEquals(latestId, runner.result(record.problemKey)!!.runId)
            assertEquals(Hashing.sha256Hex(record.solutionPath), runner.result(record.problemKey)!!.solutionSha256)
            assertTrue(runner.result(record.problemKey)!!.allAc)
        } finally {
            releaseOldRun.countDown()
            connection.disconnect()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertTrue("timed out waiting for background run", condition())
    }
}
