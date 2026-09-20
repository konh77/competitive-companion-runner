package companion.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import companion.model.ReceivedTask
import companion.routing.BatchState
import companion.run.TestRunnerService
import companion.settings.CompanionProjectSettings
import companion.storage.ProblemRecord
import companion.storage.SaveOutcome
import java.util.concurrent.ConcurrentHashMap

object Notifications {
    const val GROUP = "Companion"
    private const val THROTTLE_MS = 10_000L
    private val lastShown = ConcurrentHashMap<String, Long>()
    private val suppressed = ConcurrentHashMap<String, Int>()

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)

    private fun anyProject(): Project? = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }

    fun saved(project: Project, task: ReceivedTask, outcome: SaveOutcome.Saved) {
        val rec = outcome.record
        val samples = rec.manifest.sampleCount
        val sb = StringBuilder()
        sb.append(if (outcome.isNew) "Received " else "Updated ").append(escape(task.name))
        sb.append(" (").append(if (samples == 0) "no samples" else "$samples sample${if (samples == 1) "" else "s"}").append(")")
        if (task.interactive) sb.append("<br>Interactive problem: automatic judging is not possible.")
        if (outcome.linkedExistingSolution) sb.append("<br>Linked existing solution file ${escape(rec.manifest.solutionPath)} (not overwritten).")
        outcome.notes.forEach { sb.append("<br>").append(escape(it)) }
        val type = if (task.interactive || outcome.notes.isNotEmpty()) NotificationType.WARNING else NotificationType.INFORMATION
        val n = group().createNotification("Competitive Companion", sb.toString(), type)
        n.addAction(openAction(project, rec))
        if (!task.interactive) n.addAction(runAction(project, rec))
        n.notify(project)
    }

    fun saveFailed(project: Project, task: ReceivedTask, outcome: SaveOutcome.Failed) {
        val n = group().createNotification(
            "Competitive Companion: could not save ${escape(task.name)}",
            "${escape(outcome.code)}: ${escape(outcome.reason)}", NotificationType.ERROR,
        )
        n.addAction(diagnosticsAction(project))
        n.addAction(settingsAction(project))
        n.notify(project)
    }

    fun batchSummary(project: Project, state: BatchState, first: ProblemRecord?, updated: Boolean) {
        val text = buildString {
            append("${state.saved.size} of ${state.declaredSize} saved")
            if (state.failed.isNotEmpty()) append(", ${state.failed.size} failed")
            if (state.missing > 0) append(", ${state.missing} not received")
            if (updated) append(" (updated)")
            state.failed.forEach { (k, v) -> append("<br>").append(escape(k.substringAfterLast('/'))).append(": ").append(escape(v)) }
        }
        val type = if (state.failed.isEmpty() && state.missing == 0) NotificationType.INFORMATION else NotificationType.WARNING
        val n = group().createNotification("Competitive Companion: contest ${escape(state.contestId)}", text, type)
        if (first != null) n.addAction(openAction(project, first, "Open first saved problem"))
        n.addAction(diagnosticsAction(project))
        n.notify(project)
        if (!updated && first != null && CompanionProjectSettings.getInstance(project).state.openOnReceive) {
            openSolution(project, first)
        }
    }

    /** One notification per [code] per 10 s; suppressed count is folded into the next one. */
    fun throttledWarning(code: String, message: String) {
        val now = System.currentTimeMillis()
        val last = lastShown[code] ?: 0L
        if (now - last < THROTTLE_MS) {
            suppressed.merge(code, 1, Int::plus)
            return
        }
        lastShown[code] = now
        val extra = suppressed.remove(code) ?: 0
        val text = if (extra > 0) "$message<br>($extra similar event(s) suppressed)" else message
        val project = anyProject()
        val n = group().createNotification("Competitive Companion", escape(text).replace("&lt;br&gt;", "<br>"), NotificationType.WARNING)
        if (project != null) n.addAction(diagnosticsAction(project))
        n.notify(project)
    }

    fun listenerError(message: String) {
        val project = anyProject()
        val n = group().createNotification("Competitive Companion listener", escape(message), NotificationType.ERROR)
        if (project != null) n.addAction(settingsAction(project))
        n.notify(project)
    }

    fun info(project: Project?, title: String, message: String) {
        group().createNotification(title, escape(message), NotificationType.INFORMATION).notify(project)
    }

    fun openSolution(project: Project, rec: ProblemRecord) {
        ApplicationManager.getApplication().invokeLater({
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rec.solutionPath) ?: return@invokeLater
            FileEditorManager.getInstance(project).openFile(vf, true)
        }, project.disposed)
    }

    private fun openAction(project: Project, rec: ProblemRecord, text: String = "Open") = object : NotificationAction(text) {
        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
            openSolution(project, rec)
        }
    }

    private fun runAction(project: Project, rec: ProblemRecord) = object : NotificationAction("Run Tests") {
        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
            CompanionToolWindowFactory.panels(project)?.let { it.showTests(); it.tests.selectRecord(rec) }
            TestRunnerService.getInstance(project).runAll(rec)
        }
    }

    private fun diagnosticsAction(project: Project) = object : NotificationAction("Diagnostics") {
        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
            DiagnosticsDialog(project).show()
        }
    }

    private fun settingsAction(project: Project) = object : NotificationAction("Settings") {
        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Competitive Companion")
        }
    }

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
