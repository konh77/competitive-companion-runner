package companion.routing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import companion.diagnostics.DiagLevel
import companion.diagnostics.Diagnostics
import companion.listener.IngressItem
import companion.model.ReceivedTask
import companion.model.TaskValidator
import companion.model.ValidationResult
import companion.run.TestRunnerService
import companion.settings.CompanionProjectSettings
import companion.storage.ProblemRepository
import companion.storage.SaveOutcome
import companion.ui.Notifications
import java.time.Instant

/** Ingress worker pipeline: validate, route, save, notify. Runs on the single ingress thread. */
object IngressProcessor {
    private val diag get() = Diagnostics.getInstance()

    fun process(item: IngressItem) {
        val task = when (val v = TaskValidator.validate(item.body, item.receiptSequence, item.receivedAt)) {
            is ValidationResult.Ok -> v.task
            is ValidationResult.Rejected -> {
                diag.rejected.incrementAndGet()
                diag.record(DiagLevel.WARN, "VALIDATE_${v.code}", "${v.field ?: "-"}: ${v.reason}", item.receiptSequence)
                Notifications.throttledWarning("VALIDATE", "Competitive Companion sent data this plugin could not accept (${v.code}: ${v.field ?: ""} ${v.reason}).")
                return
            }
            is ValidationResult.Unsupported -> {
                diag.rejected.incrementAndGet()
                diag.record(DiagLevel.WARN, "UNSUPPORTED_URL", v.reason, item.receiptSequence)
                Notifications.throttledWarning("UNSUPPORTED", "Received a problem from an unsupported judge (${v.reason}). Only AtCoder problems are stored.")
                return
            }
        }
        diag.validated.incrementAndGet()
        diag.lastValidatedAt = Instant.now()
        task.notes.forEach { diag.record(DiagLevel.INFO, "NOTE", it, task.receiptSequence) }

        val registry = BatchRegistry.getInstance()
        val (batch, route) = if (task.batchSynthesized) {
            null to ProjectRouter.getInstance().resolve(item.projectSnapshot, task.name)
        } else {
            registry.batchFor(task) { ProjectRouter.getInstance().resolve(item.projectSnapshot, task.group ?: task.name) }
        }
        val project = when (route) {
            is RouteResult.Target -> route.project
            is RouteResult.Failed -> {
                diag.failed.incrementAndGet()
                diag.record(DiagLevel.WARN, "ROUTE", "${task.problemKey}: ${route.reason}", task.receiptSequence)
                if (batch != null) registry.recordFailed(batch, task, route.reason)
                Notifications.throttledWarning("ROUTE", "Could not pick a project for ${task.name}: ${route.reason}")
                return
            }
        }

        val repo = ProblemRepository.getInstance(project)
        when (val outcome = repo.save(task)) {
            is SaveOutcome.Saved -> {
                diag.saved.incrementAndGet()
                diag.lastSavedAt = Instant.now()
                diag.record(DiagLevel.INFO, "SAVED", "${task.problemKey} -> ${outcome.record.manifest.solutionPath}", task.receiptSequence)
                outcome.notes.forEach { diag.record(DiagLevel.INFO, "SAVE_NOTE", it, task.receiptSequence) }
                if (batch != null) registry.recordSaved(batch, task, outcome.record)
                afterSave(project, task, outcome, singleNotification = batch == null || batch.declaredSize <= 1)
            }
            is SaveOutcome.Failed -> {
                diag.failed.incrementAndGet()
                diag.record(DiagLevel.ERROR, "SAVE_${outcome.code}", "${task.problemKey}: ${outcome.reason}", task.receiptSequence)
                if (batch != null) registry.recordFailed(batch, task, outcome.reason)
                if (batch == null || batch.declaredSize <= 1) Notifications.saveFailed(project, task, outcome)
            }
        }
    }

    private fun afterSave(project: Project, task: ReceivedTask, outcome: SaveOutcome.Saved, singleNotification: Boolean) {
        val settings = CompanionProjectSettings.getInstance(project).state
        val record = outcome.record
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(record.testsDir.parent ?: record.testsDir)
            val solutionVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(record.solutionPath)
            if (dir != null) VfsUtil.markDirtyAndRefresh(true, true, true, dir)
            if (solutionVf != null) {
                VfsUtil.markDirtyAndRefresh(true, false, false, solutionVf)
                if (settings.openOnReceive && singleNotification) {
                    FileEditorManager.getInstance(project).openFile(solutionVf, true)
                }
            }
            if (singleNotification) Notifications.saved(project, task, outcome)
            if (settings.autoRunOnReceive && outcome.samplesChanged) {
                TestRunnerService.getInstance(project).runAll(record)
            }
        }, project.disposed)
    }
}
