package companion.ui

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import javax.swing.SwingConstants
import companion.run.CaseResult
import companion.storage.ProblemRecord

object CompanionDataKeys {
    val RECORD: DataKey<ProblemRecord> = DataKey.create("companion.record")
    val CASE: DataKey<CaseResult> = DataKey.create("companion.case")
    val PANEL: DataKey<CompanionPanel> = DataKey.create("companion.panel")
}

/** Panels of the tool window for one project. */
class CompanionPanels(val tests: CompanionPanel, val browser: ProblemBrowser?, val toolWindow: ToolWindow) {
    fun showTests() { toolWindow.contentManager.setSelectedContent(toolWindow.contentManager.getContent(0)!!); toolWindow.show() }
    fun showBrowser() { toolWindow.contentManager.setSelectedContent(toolWindow.contentManager.getContent(1)!!); toolWindow.show() }
}

class CompanionToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val browserHolder = Disposer.newDisposable("companion.browser")
        val browser = ProblemBrowserFactory.instance()?.create(project, browserHolder)
        val tests = CompanionPanel(project) { browser?.setRecord(it) }
        val testsContent = factory.createContent(tests, "Tests", false).apply { setDisposer(tests) }
        toolWindow.contentManager.addContent(testsContent)
        val browserComponent = browser?.ui ?: JBLabel(
            "Embedded browser needs the bundled “Web Browser (JCEF)” plugin. Use “Open Problem in External Browser”.",
            SwingConstants.CENTER,
        )
        val browserContent = factory.createContent(browserComponent, "Problem", false).apply { setDisposer(browserHolder) }
        toolWindow.contentManager.addContent(browserContent)
        project.putUserData(PANELS, CompanionPanels(tests, browser, toolWindow))
    }

    companion object {
        const val ID = "Companion"
        val PANELS: Key<CompanionPanels> = Key.create("companion.panels")

        /** Ensures the tool window content exists and returns its panels. */
        fun panels(project: Project): CompanionPanels? {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return null
            if (project.getUserData(PANELS) == null) tw.contentManager.contents // forces content creation
            return project.getUserData(PANELS)
        }
    }
}
