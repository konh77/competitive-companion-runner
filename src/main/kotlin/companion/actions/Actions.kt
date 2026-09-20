package companion.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import companion.listener.CompanionListenerService
import companion.listener.ListenerState
import companion.run.TestRunnerService
import companion.settings.CompanionAppSettings
import companion.settings.TargetProjectMode
import companion.storage.ProblemRecord
import companion.storage.ProblemRepository
import companion.storage.TestKind
import companion.ui.CompanionDataKeys
import companion.ui.CompanionToolWindowFactory
import companion.ui.DiagnosticsDialog
import companion.ui.DiffSupport
import companion.ui.Notifications
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

/** Resolves the target problem from the tool window selection or the active editor file. */
internal fun recordFrom(e: AnActionEvent): ProblemRecord? {
    e.getData(CompanionDataKeys.RECORD)?.let { return it }
    val project = e.project ?: return null
    val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
        ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        ?: return null
    if (!vf.isInLocalFileSystem) return null
    return ProblemRepository.getInstance(project).findBySolution(vf.toNioPath())
}

abstract class RecordAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val rec = recordFrom(e)
        e.presentation.isEnabled = rec != null && e.project != null && enabledFor(e, rec)
    }
    protected open fun enabledFor(e: AnActionEvent, rec: ProblemRecord): Boolean = true
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val rec = recordFrom(e) ?: return
        perform(project, rec, e)
    }
    abstract fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent)
}

class RunAllTestsAction : RecordAction() {
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        val panels = CompanionToolWindowFactory.panels(project)
        panels?.showTests()
        panels?.tests?.selectRecord(rec)
        TestRunnerService.getInstance(project).runAll(rec)
    }
}

class RunSelectedTestAction : RecordAction() {
    override fun enabledFor(e: AnActionEvent, rec: ProblemRecord) = e.getData(CompanionDataKeys.CASE) != null
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        val case = e.getData(CompanionDataKeys.CASE) ?: return
        TestRunnerService.getInstance(project).run(rec, setOf(case.testName))
    }
}

class CancelRunAction : RecordAction() {
    override fun enabledFor(e: AnActionEvent, rec: ProblemRecord) = TestRunnerService.getInstance(e.project!!).isRunning(rec.problemKey)
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        TestRunnerService.getInstance(project).cancel(rec.problemKey)
    }
}

class ShowDiffAction : RecordAction() {
    override fun enabledFor(e: AnActionEvent, rec: ProblemRecord) = e.getData(CompanionDataKeys.CASE)?.expected != null
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        e.getData(CompanionDataKeys.CASE)?.let { DiffSupport.showDiff(project, it) }
    }
}

class AddCustomTestAction : RecordAction() {
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        val dialog = CustomTestDialog(project, "", "", false)
        if (!dialog.showAndGet()) return
        runIo(project) {
            ProblemRepository.getInstance(project).addCustomTest(rec, dialog.input, if (dialog.hasExpected) dialog.expected else null)
            refreshDir(rec)
        }
    }
}

class CopySampleToCustomAction : RecordAction() {
    override fun enabledFor(e: AnActionEvent, rec: ProblemRecord) = e.getData(CompanionDataKeys.CASE)?.kind == TestKind.SAMPLE
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        val case = e.getData(CompanionDataKeys.CASE) ?: return
        val dialog = CustomTestDialog(project, case.input, case.expected ?: "", case.expected != null)
        if (!dialog.showAndGet()) return
        runIo(project) {
            ProblemRepository.getInstance(project).addCustomTest(rec, dialog.input, if (dialog.hasExpected) dialog.expected else null)
            refreshDir(rec)
        }
    }
}

class EditCustomTestAction : RecordAction() {
    override fun enabledFor(e: AnActionEvent, rec: ProblemRecord) = e.getData(CompanionDataKeys.CASE)?.kind == TestKind.CUSTOM
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        val case = e.getData(CompanionDataKeys.CASE) ?: return
        val ref = ProblemRepository.getInstance(project).listTests(rec).firstOrNull { it.name == case.testName } ?: return
        val fs = LocalFileSystem.getInstance()
        listOfNotNull(ref.outputPath, ref.inputPath).forEach { p ->
            fs.refreshAndFindFileByNioFile(p)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        }
    }
}

class DeleteCustomTestAction : RecordAction() {
    override fun enabledFor(e: AnActionEvent, rec: ProblemRecord) = e.getData(CompanionDataKeys.CASE)?.kind == TestKind.CUSTOM
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        val case = e.getData(CompanionDataKeys.CASE) ?: return
        val repo = ProblemRepository.getInstance(project)
        val ref = repo.listTests(rec).firstOrNull { it.name == case.testName } ?: return
        if (Messages.showYesNoDialog(project, "Delete ${ref.name}?", "Competitive Companion", Messages.getQuestionIcon()) != Messages.YES) return
        runIo(project) {
            repo.deleteCustomTest(rec, ref)
            refreshDir(rec)
        }
    }
}

class ToggleJudgeModeAction : RecordAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val rec = recordFrom(e)
        e.presentation.text = if (rec?.manifest?.judge == "MANUAL") "Judge Mode: Manual (switch to Exact)" else "Judge Mode: Exact (switch to Manual)"
    }
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        val next = if (rec.manifest.judge == "MANUAL") "EXACT" else "MANUAL"
        runIo(project) { ProblemRepository.getInstance(project).updateManifest(rec) { it.copy(judge = next) } }
    }
}

class OpenProblemUrlAction : RecordAction() {
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) = BrowserUtil.browse(rec.manifest.url)
}

/** Copies the solution to the clipboard and opens the submit page in the embedded browser (external browser as fallback). */
class OpenSubmitPageAction : RecordAction() {
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        val code = copySolution(rec)
        val panels = CompanionToolWindowFactory.panels(project)
        val browser = panels?.browser
        if (panels != null && browser != null && browser.isAvailable) {
            panels.showBrowser()
            browser.showSubmit(rec, code)
        } else {
            BrowserUtil.browse("https://atcoder.jp/contests/${rec.manifest.contestId}/submit?taskScreenName=${rec.manifest.taskId}")
            Notifications.info(project, "Competitive Companion", "Solution copied to the clipboard. Submit page opened in your browser.")
        }
    }
}

/** Shows the problem statement in the embedded browser tab. */
class ShowProblemPageAction : RecordAction() {
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        val panels = CompanionToolWindowFactory.panels(project)
        val browser = panels?.browser
        if (panels != null && browser != null && browser.isAvailable) {
            panels.tests.selectRecord(rec)
            panels.showBrowser()
            browser.showProblem(rec)
        } else BrowserUtil.browse(rec.manifest.url)
    }
}

class OpenSolutionAction : RecordAction() {
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) = Notifications.openSolution(project, rec)
}

class CopySolutionAction : RecordAction() {
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) { copySolution(rec) }
}

/** Copies the editor's current text (unsaved edits included) and returns it. */
internal fun copySolution(rec: ProblemRecord): String? {
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rec.solutionPath) ?: return null
    val doc: Document? = FileDocumentManager.getInstance().getDocument(vf)
    val text = doc?.text ?: String(vf.contentsToByteArray(), Charsets.UTF_8)
    CopyPasteManager.getInstance().setContents(StringSelection(text))
    return text
}

/** Shows name / limits / samples from local files in the Problem tab (no network). */
class ShowLocalSummaryAction : RecordAction() {
    override fun perform(project: Project, rec: ProblemRecord, e: AnActionEvent) {
        val panels = CompanionToolWindowFactory.panels(project) ?: return
        panels.tests.selectRecord(rec)
        panels.showBrowser()
        panels.browser?.showLocal(rec)
    }
}

class RefreshIndexAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runIo(project) {
            val note = ProblemRepository.getInstance(project).rebuild()
            ApplicationManager.getApplication().invokeLater { Notifications.info(project, "Competitive Companion", note) }
        }
    }
}

class OpenDiagnosticsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DiagnosticsDialog(project).show()
    }
}

class ToggleListenerAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val running = CompanionListenerService.getInstance().state != ListenerState.STOPPED
        e.presentation.text = if (running) "Stop Listener" else "Start Listener"
    }
    override fun actionPerformed(e: AnActionEvent) {
        val svc = CompanionListenerService.getInstance()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (svc.state != ListenerState.STOPPED) svc.stop() else svc.start()
        }
    }
}

class PinReceiveProjectAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val s = CompanionAppSettings.getInstance().state
        val pinned = e.project?.basePath != null && s.targetProjectMode == TargetProjectMode.PINNED && s.pinnedProjectPath == e.project?.basePath
        e.presentation.text = if (pinned) "Unpin Receive Target" else "Pin Receive Target to This Project"
        e.presentation.isEnabled = e.project?.basePath != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val s = CompanionAppSettings.getInstance().state
        if (s.targetProjectMode == TargetProjectMode.PINNED && s.pinnedProjectPath == project.basePath) {
            s.targetProjectMode = TargetProjectMode.LAST_FOCUSED
            s.pinnedProjectPath = null
            Notifications.info(project, "Competitive Companion", "Receive target unpinned (last focused project).")
        } else {
            s.targetProjectMode = TargetProjectMode.PINNED
            s.pinnedProjectPath = project.basePath
            Notifications.info(project, "Competitive Companion", "Receiving into ${project.name} until unpinned or closed.")
        }
    }
}

class OpenSettingsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, "Competitive Companion")
    }
}

// ------------------------------------------------------------------ helpers

private fun runIo(project: Project, block: () -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            block()
        } catch (ex: Exception) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) Messages.showErrorDialog(project, ex.message ?: ex.javaClass.simpleName, "Competitive Companion")
            }
        }
    }
}

private fun refreshDir(rec: ProblemRecord) {
    ApplicationManager.getApplication().invokeLater {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rec.testsDir)?.let { VfsUtil.markDirtyAndRefresh(true, true, true, it) }
    }
}

class CustomTestDialog(project: Project, input: String, expected: String, hasExpected: Boolean) : DialogWrapper(project, true) {
    private val inputArea = JBTextArea(input, 8, 60)
    private val expectedArea = JBTextArea(expected, 8, 60)
    private val expectedCheck = JBCheckBox("Has expected output (uncheck for manual check)", hasExpected)

    val input: String get() = inputArea.text
    val expected: String get() = expectedArea.text
    val hasExpected: Boolean get() = expectedCheck.isSelected

    init {
        title = "Custom Test"
        expectedCheck.addActionListener { expectedArea.isEnabled = expectedCheck.isSelected }
        expectedArea.isEnabled = hasExpected
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Input:"), JBScrollPane(inputArea), true)
        .addComponent(expectedCheck)
        .addLabeledComponent(JBLabel("Expected output:"), JBScrollPane(expectedArea), true)
        .panel.apply { border = JBUI.Borders.empty(4) }

    override fun getPreferredFocusedComponent(): JComponent = inputArea
}
