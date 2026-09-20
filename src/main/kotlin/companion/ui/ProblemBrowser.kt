package companion.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import companion.storage.ProblemRecord
import javax.swing.JComponent

/** Embedded-browser tab abstraction; the JCEF implementation lives in an optional module. */
interface ProblemBrowser {
    val ui: JComponent
    val isAvailable: Boolean
    fun setRecord(record: ProblemRecord?)
    fun showLocal(record: ProblemRecord)
    fun showProblem(record: ProblemRecord)
}

interface ProblemBrowserFactory {
    fun create(project: Project, parent: Disposable): ProblemBrowser

    companion object {
        /** Null when the JCEF plugin is not available in this IDE. */
        fun instance(): ProblemBrowserFactory? = ApplicationManager.getApplication().getService(ProblemBrowserFactory::class.java)
    }
}
