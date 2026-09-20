package companion.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import companion.diagnostics.Diagnostics
import companion.listener.CompanionListenerService
import companion.routing.ProjectRouter
import companion.run.InterpreterResolver
import companion.run.InterpreterResult
import companion.settings.CompanionAppSettings
import companion.storage.ProblemRepository
import java.awt.Font
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent

class DiagnosticsDialog(private val project: Project) : DialogWrapper(project, true) {
    init {
        title = "Competitive Companion Diagnostics"
        setOKButtonText("Close")
        init()
    }

    override fun createActions() = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val area = JBTextArea(buildText())
        area.isEditable = false
        area.font = Font(Font.MONOSPACED, Font.PLAIN, area.font.size)
        val scroll = JBScrollPane(area)
        scroll.preferredSize = JBUI.size(760, 520)
        return scroll
    }

    private fun buildText(): String {
        val listener = CompanionListenerService.getInstance()
        val diag = Diagnostics.getInstance()
        val settings = CompanionAppSettings.getInstance().state
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        fun t(i: java.time.Instant?) = i?.let { fmt.format(it) } ?: "-"
        val sb = StringBuilder()
        sb.appendLine("Listener state : ${listener.state}")
        sb.appendLine("Bound ports    : ${listener.boundPorts.joinToString().ifEmpty { "-" }} (configured: ${CompanionAppSettings.getInstance().allPorts.joinToString()})")
        listener.failedPortReasons.forEach { (p, r) -> sb.appendLine("  port $p failed: $r") }
        sb.appendLine("Add to Competitive Companion > Options > Custom ports: ${settings.port}")
        sb.appendLine()
        sb.appendLine("Target mode    : ${settings.targetProjectMode}  pinned=${settings.pinnedProjectPath ?: "-"}")
        val snap = ProjectRouter.getInstance().snapshot()
        sb.appendLine("Last focused   : ${snap.lastFocusedPath ?: "-"}")
        sb.appendLine("Candidates     : ${snap.candidatePaths.joinToString().ifEmpty { "- (no trusted open project)" }}")
        sb.appendLine()
        sb.appendLine("HTTP arrived   : ${diag.httpArrived.get()}   last ${t(diag.lastHttpAt)}")
        sb.appendLine("accepted       : ${diag.accepted.get()}   last ${t(diag.lastAcceptedAt)}")
        sb.appendLine("validated      : ${diag.validated.get()}   last ${t(diag.lastValidatedAt)}")
        sb.appendLine("saved          : ${diag.saved.get()}   last ${t(diag.lastSavedAt)}")
        sb.appendLine("rejected/failed: ${diag.rejected.get()} / ${diag.failed.get()}")
        sb.appendLine("queue          : ${listener.pendingCount} item(s), ${listener.pendingBytes} bytes")
        sb.appendLine()
        val repo = ProblemRepository.getInstance(project)
        sb.appendLine("Project root   : ${repo.root}")
        sb.appendLine("Problems       : ${repo.all().size}")
        repo.lastRebuildNote?.let { sb.appendLine("Last rebuild   : $it") }
        val firstSolution = repo.all().firstOrNull()?.solutionPath ?: repo.root.resolve("main.py")
        when (val r = InterpreterResolver.resolve(project, firstSolution)) {
            is InterpreterResult.Ok -> sb.appendLine("Interpreter    : ${r.interpreter.description} @ ${r.interpreter.executable}")
            is InterpreterResult.Failed -> sb.appendLine("Interpreter    : NOT AVAILABLE — ${r.reason}")
        }
        sb.appendLine()
        sb.appendLine("Test command (bash):")
        sb.appendLine(curlCommand(settings.port))
        sb.appendLine()
        sb.appendLine("Recent events (newest last):")
        for (e in diag.snapshot()) {
            sb.appendLine("${fmt.format(e.at)} ${e.level.name.padEnd(5)} ${e.code.padEnd(16)} ${e.receiptSequence?.let { "#$it " } ?: ""}${e.message}")
        }
        return sb.toString()
    }

    companion object {
        fun curlCommand(port: Int): String {
            val json = """{"name":"A - Test Problem","group":"AtCoder - Test","url":"https://atcoder.jp/contests/abc999/tasks/abc999_a","interactive":false,"memoryLimit":1024,"timeLimit":2000,"tests":[{"input":"1 2\n","output":"3\n"}],"testType":"single","input":{"type":"stdin"},"output":{"type":"stdout"},"languages":{"java":{"mainClass":"Main","taskClass":"ATestProblem"}},"batch":{"id":"00000000-0000-4000-8000-000000000001","size":1}}"""
            return "curl -sS -X POST http://127.0.0.1:$port/ -H 'Content-Type: application/json' --data-binary '${json.replace("'", "'\\''")}'"
        }
    }
}
