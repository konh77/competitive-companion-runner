package companion.ui.jcef

import companion.ui.ProblemBrowser
import companion.ui.ProblemBrowserFactory
import com.intellij.openapi.Disposable as IjDisposable
import javax.swing.JComponent

import companion.actions.openSubmitInBrowser
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import companion.storage.ProblemRecord
import companion.storage.ProblemRepository
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.BorderLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Date
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Embedded browser (JCEF) tab for:
 *  - a local summary built from problem.json + samples (no network),
 *  - the problem page on atcoder.jp.
 * Submissions open in the normal browser for login and CAPTCHA compatibility.
 * The user logs in inside this browser, or imports the session cookie of their normal browser.
 */
class ProblemBrowserPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable, ProblemBrowser {
    override val ui: JComponent get() = this
    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser.createBuilder().setOffScreenRendering(false).build() else null
    private val status = JBLabel("", SwingConstants.LEFT).apply { border = JBUI.Borders.empty(2, 8) }

    @Volatile var currentRecord: ProblemRecord? = null
        private set
    @Volatile var following: Boolean = false
        private set

    init {
        val group = DefaultActionGroup(
            simple("Back", AllIcons.Actions.Back, { browser?.cefBrowser?.canGoBack() == true }) { browser?.cefBrowser?.goBack() },
            simple("Reload", AllIcons.Actions.Refresh) { browser?.cefBrowser?.reload() },
            simple("Local Summary", AllIcons.Actions.ListFiles, { currentRecord != null }, "Name, limits and samples from problem.json (no network)") { currentRecord?.let { showLocal(it) } },
            simple("Problem Statement", AllIcons.Actions.PreviewDetails, { currentRecord != null }, "Load the problem page from atcoder.jp") { currentRecord?.let { showProblem(it) } },
            simple("Submit in Browser", AllIcons.Actions.Upload, { currentRecord != null }, "Copy the solution and open the submit page in your normal browser", requiresBrowser = false) { currentRecord?.let { openSubmitInBrowser(project, it) } },
            simple("Import Session Cookie…", AllIcons.Actions.AddFile, description = "Paste REVEL_SESSION from your logged-in browser") { importSession() },
            simple("Clear Session", AllIcons.Actions.GC, description = "Delete atcoder.jp cookies from the embedded browser") { clearSession() },
            simple("Open in External Browser", AllIcons.General.Web) { browser?.cefBrowser?.url?.takeIf { it.startsWith("http") }?.let { BrowserUtil.browse(it) } },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val center = JPanel(BorderLayout())
        center.add(status, BorderLayout.NORTH)
        if (browser != null) {
            Disposer.register(this, browser)
            center.add(browser.component, BorderLayout.CENTER)
            status.text = "Select a problem. “Local Summary” works offline; “Problem Statement” loads atcoder.jp."
        } else {
            center.add(JBLabel("Embedded browser (JCEF) is not available in this IDE runtime. Use “Open Problem in External Browser”.", SwingConstants.CENTER), BorderLayout.CENTER)
        }
        setContent(center)
    }

    override val isAvailable: Boolean get() = browser != null

    override fun setRecord(record: ProblemRecord?) {
        currentRecord = record
        if (following && record != null) showProblem(record)
    }

    override fun showLocal(record: ProblemRecord) {
        currentRecord = record
        following = false
        status.text = "${record.manifest.name} — local summary"
        browser?.loadHTML(localSummaryHtml(record))
    }

    override fun showProblem(record: ProblemRecord) {
        currentRecord = record
        following = true
        load(record.manifest.url + "?lang=ja", "${record.manifest.name} — ${record.manifest.url}  (login required during a contest: log in here or “Import Session Cookie…”)")
    }

    private fun load(url: String, text: String) {
        status.text = text
        browser?.loadURL(url)
    }

    // ---------------------------------------------------------------- session cookie

    private fun importSession() {
        val value = Messages.showInputDialog(
            project,
            "In the browser where you are logged in to atcoder.jp: DevTools (⌥⌘I / F12) → Application → Cookies → https://atcoder.jp → copy the Value of REVEL_SESSION and paste it here.\n" +
                "The cookie is stored only in this IDE's embedded browser profile.",
            "Import AtCoder Session Cookie", null,
        )?.trim()?.trim('"') ?: return
        if (value.isEmpty()) return
        val now = Date()
        val expires = Date(now.time + 30L * 24 * 3600 * 1000)
        val cookie = CefCookie("REVEL_SESSION", value, "atcoder.jp", "/", true, true, now, now, true, expires)
        val ok = CefCookieManager.getGlobalManager().setCookie("https://atcoder.jp/", cookie)
        status.text = if (ok) "Session cookie imported. Reloading…" else "Could not set the cookie."
        if (ok) currentRecord?.let { showProblem(it) } ?: browser?.loadURL("https://atcoder.jp/home")
    }

    private fun clearSession() {
        CefCookieManager.getGlobalManager().deleteCookies("https://atcoder.jp/", "")
        status.text = "atcoder.jp cookies deleted from the embedded browser."
    }

    // ---------------------------------------------------------------- helpers

    private fun simple(text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, description: String? = null, requiresBrowser: Boolean = true, run: () -> Unit) =
        object : AnAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = (!requiresBrowser || browser != null) && enabled() }
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun localSummaryHtml(record: ProblemRecord): String {
        val m = record.manifest
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset='utf-8'><style>")
        sb.append("body{font-family:-apple-system,Segoe UI,sans-serif;margin:16px;line-height:1.5}")
        sb.append("pre{background:#f4f4f4;border:1px solid #ddd;padding:8px;white-space:pre-wrap}")
        sb.append("h3{margin:16px 0 4px}small{color:#666}")
        sb.append("@media(prefers-color-scheme:dark){body{background:#1e1f22;color:#ddd}pre{background:#2b2d30;border-color:#444}small{color:#999}}")
        sb.append("</style></head><body>")
        sb.append("<h2>").append(esc(m.name)).append("</h2>")
        sb.append("<p><small>").append(esc(m.group ?: "")).append("<br>Time limit ${m.timeLimitMs} ms / Memory ${m.memoryLimitMb} MiB")
        if (m.interactive) sb.append(" / interactive")
        sb.append("<br><a href='").append(esc(m.url)).append("'>").append(esc(m.url)).append("</a></small></p>")
        sb.append("<p><small>Competitive Companion sends only the samples, not the statement. Use “Problem Statement” for the full page.</small></p>")
        val tests = ProblemRepository.getInstance(project).listTests(record)
        if (tests.isEmpty()) sb.append("<p>No samples.</p>")
        for (t in tests) {
            val input = runCatching { Files.readString(t.inputPath, StandardCharsets.UTF_8) }.getOrDefault("")
            val output = t.outputPath?.let { runCatching { Files.readString(it, StandardCharsets.UTF_8) }.getOrDefault("") }
            sb.append("<h3>").append(esc(t.name)).append(" — input</h3><pre>").append(esc(input)).append("</pre>")
            if (output != null) sb.append("<h3>").append(esc(t.name)).append(" — output</h3><pre>").append(esc(output)).append("</pre>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    override fun dispose() {}

    companion object : DumbAware
}

/** Registered only when the bundled JCEF plugin (`com.intellij.modules.jcef`) is present. */
class JcefProblemBrowserFactory : ProblemBrowserFactory {
    override fun create(project: Project, parent: IjDisposable): ProblemBrowser {
        val panel = ProblemBrowserPanel(project)
        Disposer.register(parent, panel)
        return panel
    }
}
