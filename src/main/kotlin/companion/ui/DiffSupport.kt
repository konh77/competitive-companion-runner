package companion.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import companion.run.CaseResult

object DiffSupport {
    /** Shows expected vs actual from the run snapshot (never re-reads disk). */
    fun showDiff(project: Project, case: CaseResult) {
        val expected = case.expected ?: return
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "${case.testName}: expected vs actual",
            factory.create(project, expected),
            factory.create(project, case.stdout),
            "Expected", "Actual",
        )
        DiffManager.getInstance().showDiff(project, request)
    }
}
