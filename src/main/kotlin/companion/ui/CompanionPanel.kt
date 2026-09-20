package companion.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import companion.run.CaseResult
import companion.run.RunResult
import companion.run.RunResultsListener
import companion.run.TestRunnerService
import companion.run.Verdict
import companion.storage.ProblemRecord
import companion.storage.ProblemRepository
import companion.storage.ProblemsListener
import companion.storage.TestKind
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class CompanionPanel(private val project: Project, private val onSelect: (ProblemRecord?) -> Unit = {}) : SimpleToolWindowPanel(true, true), Disposable, UiDataProvider {
    private val repo = ProblemRepository.getInstance(project)
    private val runner = TestRunnerService.getInstance(project)

    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private val tableModel = CasesTableModel()
    private val table = JBTable(tableModel)
    private val summaryLabel = JBLabel()
    private val inputArea = area()
    private val expectedArea = area()
    private val actualArea = area()
    private val stderrArea = area()

    @Volatile private var selectedRecord: ProblemRecord? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = ProblemRenderer()
        tree.addTreeSelectionListener { onTreeSelection() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedRecord?.let { Notifications.openSolution(project, it) }
            }
        })
        val treeGroup = ActionManager.getInstance().getAction("Companion.ProblemPopup") as? DefaultActionGroup
        if (treeGroup != null) PopupHandler.installPopupMenu(tree, treeGroup, ActionPlaces.TOOLWINDOW_POPUP)

        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setDefaultRenderer(Any::class.java, VerdictRenderer())
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showCaseDetails() }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedCase()?.let { DiffSupport.showDiff(project, it) }
            }
        })
        val tableGroup = ActionManager.getInstance().getAction("Companion.CasePopup") as? DefaultActionGroup
        if (tableGroup != null) PopupHandler.installPopupMenu(table, tableGroup, ActionPlaces.TOOLWINDOW_POPUP)

        val toolbarGroup = ActionManager.getInstance().getAction("Companion.Toolbar") as DefaultActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, toolbarGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val details = JBTabbedPane()
        details.addTab("Input", JBScrollPane(inputArea))
        details.addTab("Expected", JBScrollPane(expectedArea))
        details.addTab("Actual", JBScrollPane(actualArea))
        details.addTab("stderr", JBScrollPane(stderrArea))

        val right = JPanel(BorderLayout())
        summaryLabel.border = JBUI.Borders.empty(4, 8)
        right.add(summaryLabel, BorderLayout.NORTH)
        val rightSplit = OnePixelSplitter(true, 0.45f)
        rightSplit.firstComponent = JBScrollPane(table)
        rightSplit.secondComponent = details
        right.add(rightSplit, BorderLayout.CENTER)

        val split = OnePixelSplitter(false, 0.35f)
        split.firstComponent = JBScrollPane(tree)
        split.secondComponent = right
        setContent(split)

        val bus = project.messageBus.connect(this)
        bus.subscribe(ProblemsListener.TOPIC, object : ProblemsListener {
            override fun problemsChanged() = onEdt { rebuildTree() }
        })
        bus.subscribe(RunResultsListener.TOPIC, object : RunResultsListener {
            override fun runChanged(problemKey: String) = onEdt {
                tree.repaint()
                if (selectedRecord?.problemKey == problemKey) refreshCases()
            }
        })
        rebuildTree()
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) block() }, project.disposed)
    }

    private fun area(): JBTextArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
    }

    fun rebuildTree() {
        val expanded = HashSet<String>()
        for (i in 0 until tree.rowCount) {
            val node = tree.getPathForRow(i).lastPathComponent as DefaultMutableTreeNode
            if (tree.isExpanded(i)) (node.userObject as? ContestNode)?.let { expanded.add(it.contestId) }
        }
        val selectedKey = selectedRecord?.problemKey
        rootNode.removeAllChildren()
        val byContest = repo.all().groupBy { it.manifest.contestId }
        var selectPath: TreePath? = null
        for ((contestId, records) in byContest.toSortedMap()) {
            val cNode = DefaultMutableTreeNode(ContestNode(contestId, records.firstOrNull()?.manifest?.group))
            rootNode.add(cNode)
            for (r in records.sortedWith(compareBy({ naturalKey(it.manifest.displayIndex) }, { it.manifest.taskId }))) {
                val pNode = DefaultMutableTreeNode(r)
                cNode.add(pNode)
                if (r.problemKey == selectedKey) selectPath = TreePath(pNode.path)
            }
        }
        treeModel.reload()
        for (i in 0 until tree.rowCount) {
            val node = tree.getPathForRow(i).lastPathComponent as DefaultMutableTreeNode
            val c = node.userObject as? ContestNode ?: continue
            if (expanded.isEmpty() || c.contestId in expanded) tree.expandRow(i)
        }
        if (selectPath != null) tree.selectionPath = selectPath else { selectedRecord = null; refreshCases() }
    }

    private fun naturalKey(displayIndex: String?): String {
        if (displayIndex == null) return "~"
        val n = displayIndex.toIntOrNull()
        return if (n != null) "%09d".format(n) else displayIndex.lowercase()
    }

    private fun onTreeSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        selectedRecord = node?.userObject as? ProblemRecord
        refreshCases()
        onSelect(selectedRecord)
    }

    fun selectRecord(record: ProblemRecord) {
        for (i in 0 until tree.rowCount) {
            val node = tree.getPathForRow(i).lastPathComponent as DefaultMutableTreeNode
            if ((node.userObject as? ProblemRecord)?.problemKey == record.problemKey) {
                tree.selectionPath = tree.getPathForRow(i)
                return
            }
        }
    }

    private fun refreshCases() {
        val rec = selectedRecord
        val result = rec?.let { runner.result(it.problemKey) }
        val selectedName = selectedCase()?.testName
        tableModel.setCases(result?.cases ?: emptyList())
        summaryLabel.text = summaryText(rec, result)
        if (selectedName != null) {
            val idx = tableModel.cases.indexOfFirst { it.testName == selectedName }
            if (idx >= 0) table.setRowSelectionInterval(idx, idx)
        }
        showCaseDetails()
    }

    private fun summaryText(rec: ProblemRecord?, result: RunResult?): String {
        if (rec == null) return "Select a problem"
        val sb = StringBuilder("<html><b>${Notifications.escape(rec.manifest.name)}</b> &nbsp; ${rec.manifest.timeLimitMs} ms / ${rec.manifest.memoryLimitMb} MiB")
        if (rec.manifest.interactive) sb.append(" &nbsp; <i>interactive</i>")
        if (rec.manifest.judge == "MANUAL") sb.append(" &nbsp; <i>judge: manual</i>")
        if (rec.manifest.sampleCount == 0) sb.append(" &nbsp; no samples")
        if (result != null) {
            sb.append("<br>")
            if (result.message != null) sb.append("<font color='#d33'>${Notifications.escape(result.message)}</font>")
            else {
                val s = result.totalOf(TestKind.SAMPLE)
                val c = result.totalOf(TestKind.CUSTOM)
                if (s > 0) sb.append("samples ${result.countOf(TestKind.SAMPLE, Verdict.AC)}/$s passed")
                if (c > 0) sb.append(if (s > 0) ", " else "").append("custom ${result.countOf(TestKind.CUSTOM, Verdict.AC)}/$c passed")
                if (s == 0 && c == 0) sb.append("no tests")
                if (result.running) sb.append(" &nbsp; <i>running…</i>")
                else if (runner.isStale(rec)) sb.append(" &nbsp; <font color='#c80'>result predates current code/tests — re-run</font>")
                result.interpreter?.let { sb.append("<br><small>${Notifications.escape(it)}</small>") }
            }
        }
        return sb.append("</html>").toString()
    }

    private fun selectedCase(): CaseResult? {
        val row = table.selectedRow
        return if (row in tableModel.cases.indices) tableModel.cases[row] else null
    }

    private fun showCaseDetails() {
        val c = selectedCase()
        inputArea.text = c?.input ?: ""
        expectedArea.text = c?.expected ?: if (c != null) "(no expected output — manual check)" else ""
        actualArea.text = (c?.stdout ?: "") + if (c?.stdoutLimitExceeded == true) "\n… (output limit exceeded)" else ""
        stderrArea.text = (c?.stderr ?: "") + if (c?.stderrTruncated == true) "\n… (truncated)" else ""
        listOf(inputArea, expectedArea, actualArea, stderrArea).forEach { it.caretPosition = 0 }
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[CompanionDataKeys.PANEL] = this
        selectedRecord?.let { sink[CompanionDataKeys.RECORD] = it }
        selectedCase()?.let { sink[CompanionDataKeys.CASE] = it }
    }

    override fun dispose() {}

    // ------------------------------------------------------------ nodes / renderers

    class ContestNode(val contestId: String, val group: String?)

    private inner class ProblemRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                is ContestNode -> {
                    icon = AllIcons.Nodes.Folder
                    append(obj.contestId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    obj.group?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is ProblemRecord -> {
                    val result = runner.result(obj.problemKey)
                    icon = when {
                        result == null -> AllIcons.RunConfigurations.TestNotRan
                        result.running -> AllIcons.RunConfigurations.TestState.Run
                        result.message != null -> AllIcons.RunConfigurations.TestError
                        runner.isStale(obj) -> AllIcons.RunConfigurations.TestIgnored
                        result.allAc -> AllIcons.RunConfigurations.TestPassed
                        result.cases.any { it.verdict == Verdict.MANUAL || it.verdict == Verdict.SKIP } && result.cases.none { it.verdict in setOf(Verdict.WA, Verdict.RE, Verdict.TLE, Verdict.OLE, Verdict.IE) } -> AllIcons.RunConfigurations.TestUnknown
                        else -> AllIcons.RunConfigurations.TestFailed
                    }
                    append(obj.manifest.name)
                    if (result != null && !result.running && result.message == null) {
                        val s = result.totalOf(TestKind.SAMPLE)
                        if (s > 0) append("  ${result.countOf(TestKind.SAMPLE, Verdict.AC)}/$s", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    toolTipText = "${obj.manifest.taskId} — ${obj.manifest.solutionPath}"
                }
            }
        }
    }

    private class CasesTableModel : AbstractTableModel() {
        var cases: List<CaseResult> = emptyList()
            private set
        private val columns = arrayOf("Test", "Verdict", "Time", "Exit", "Message")

        fun setCases(list: List<CaseResult>) { cases = list; fireTableDataChanged() }
        override fun getRowCount() = cases.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val c = cases[rowIndex]
            return when (columnIndex) {
                0 -> c.testName
                1 -> c.verdict
                2 -> c.elapsedMs?.let { "$it ms" } ?: ""
                3 -> c.exitCode?.toString() ?: ""
                else -> c.message ?: ""
            }
        }
    }

    private class VerdictRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            val c = super.getTableCellRendererComponent(table, value?.toString() ?: "", isSelected, hasFocus, row, column)
            if (value is Verdict && !isSelected) {
                foreground = when (value) {
                    Verdict.AC -> JBColor(0x1a8f3a, 0x5fb865)
                    Verdict.WA, Verdict.RE, Verdict.OLE -> JBColor(0xc42b1c, 0xf07178)
                    Verdict.TLE -> JBColor(0x7a3db8, 0xb48ead)
                    Verdict.MANUAL -> JBColor(0xb08000, 0xe5c07b)
                    else -> UIUtil.getLabelDisabledForeground()
                }
            } else if (!isSelected) foreground = UIUtil.getLabelForeground()
            return c
        }
    }
}
