package companion.run

import companion.storage.TestKind
import java.time.Instant

enum class Verdict { AC, WA, RE, TLE, OLE, IE, CANCELLED, MANUAL, SKIP, RUNNING, PENDING }

data class CaseResult(
    val testName: String,
    val kind: TestKind,
    val verdict: Verdict,
    val elapsedMs: Long?,
    val exitCode: Int?,
    val input: String,
    val expected: String?,
    val stdout: String,
    val stderr: String,
    val stdoutLimitExceeded: Boolean,
    val stderrTruncated: Boolean,
    val cleanupIncomplete: Boolean,
    val message: String?,
)

data class RunResult(
    val runId: String,
    val problemKey: String,
    val sampleRevision: String?,
    val solutionSha256: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val interpreter: String?,
    val cases: List<CaseResult>,
    val stale: Boolean,
    val message: String?,
) {
    val running: Boolean get() = finishedAt == null
    fun countOf(kind: TestKind, v: Verdict) = cases.count { it.kind == kind && it.verdict == v }
    fun totalOf(kind: TestKind) = cases.count { it.kind == kind }
    val allAc: Boolean get() = cases.isNotEmpty() && cases.all { it.verdict == Verdict.AC }
}
