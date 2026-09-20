package companion.routing

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.IdeFrame
import companion.settings.CompanionAppSettings
import companion.settings.TargetProjectMode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Immutable routing snapshot taken on the HTTP thread at header time. */
data class ProjectTargetSnapshot(
    val mode: TargetProjectMode,
    val pinnedPath: String?,
    val lastFocusedPath: String?,
    val candidatePaths: List<String>,
)

sealed class RouteResult {
    data class Target(val project: Project) : RouteResult()
    data class Failed(val reason: String) : RouteResult()
}

/**
 * Keeps a thread-safe view of eligible receive targets, updated from EDT lifecycle /
 * focus events, so HTTP threads never touch the EDT.
 */
@Service(Service.Level.APP)
class ProjectRouter {
    @Volatile private var lastFocusedPath: String? = null

    fun noteFocused(project: Project?) {
        val p = project ?: return
        if (p.isDisposed || p.isDefault) return
        lastFocusedPath = p.basePath
    }

    fun noteClosed(project: Project) {
        if (lastFocusedPath == project.basePath) lastFocusedPath = null
        val settings = CompanionAppSettings.getInstance().state
        if (settings.pinnedProjectPath == project.basePath) settings.pinnedProjectPath = null
    }

    fun eligibleProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { isEligible(it) }

    fun isEligible(p: Project): Boolean =
        !p.isDisposed && !p.isDefault && p.basePath != null && p.isInitialized && TrustedProjects.isProjectTrusted(p)

    fun snapshot(): ProjectTargetSnapshot {
        val settings = CompanionAppSettings.getInstance().state
        return ProjectTargetSnapshot(
            mode = settings.targetProjectMode,
            pinnedPath = settings.pinnedProjectPath,
            lastFocusedPath = lastFocusedPath,
            candidatePaths = eligibleProjects().mapNotNull { it.basePath },
        )
    }

    /** Resolves a snapshot to a live project. Called on the ingress worker. */
    fun resolve(snapshot: ProjectTargetSnapshot, askTitle: String): RouteResult {
        val byPath = eligibleProjects().associateBy { it.basePath!! }
        fun pick(path: String?, why: String): RouteResult =
            path?.let { byPath[it] }?.let { RouteResult.Target(it) } ?: RouteResult.Failed(why)

        return when (snapshot.mode) {
            TargetProjectMode.PINNED -> pick(snapshot.pinnedPath, "pinned project is not open / trusted")
            TargetProjectMode.LAST_FOCUSED -> {
                val cands = snapshot.candidatePaths.filter { byPath.containsKey(it) }
                when {
                    cands.size == 1 -> pick(cands[0], "candidate closed")
                    cands.isEmpty() -> RouteResult.Failed("no open trusted project")
                    else -> pick(snapshot.lastFocusedPath?.takeIf { it in cands } ?: return RouteResult.Failed("several projects open and none focused recently"), "focused project closed")
                }
            }
            TargetProjectMode.ASK -> {
                val cands = snapshot.candidatePaths.mapNotNull { byPath[it] }
                when (cands.size) {
                    0 -> RouteResult.Failed("no open trusted project")
                    1 -> RouteResult.Target(cands[0])
                    else -> ask(cands, askTitle)
                }
            }
        }
    }

    private fun ask(candidates: List<Project>, title: String): RouteResult {
        val future = CompletableFuture<Project?>()
        ApplicationManager.getApplication().invokeLater {
            val names = candidates.map { it.name }.toTypedArray()
            val idx = Messages.showDialog(
                "Choose the project that should receive:\n$title",
                "Competitive Companion", names, 0, Messages.getQuestionIcon(),
            )
            future.complete(if (idx < 0) null else candidates[idx])
        }
        return try {
            val p = future.get(ASK_TIMEOUT_S, TimeUnit.SECONDS)
            if (p == null || !isEligible(p)) RouteResult.Failed("project selection cancelled") else RouteResult.Target(p)
        } catch (e: TimeoutException) {
            RouteResult.Failed("project selection timed out after ${ASK_TIMEOUT_S}s")
        }
    }

    companion object {
        const val ASK_TIMEOUT_S = 30L
        fun getInstance(): ProjectRouter = ApplicationManager.getApplication().getService(ProjectRouter::class.java)
    }
}

class RouterAppActivationListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        ProjectRouter.getInstance().noteFocused(ideFrame.project)
    }
}

class RouterProjectManagerListener : ProjectManagerListener {
    override fun projectClosed(project: Project) {
        ProjectRouter.getInstance().noteClosed(project)
    }
}

class RouterEditorListener(private val project: Project) : FileEditorManagerListener {
    override fun selectionChanged(event: FileEditorManagerEvent) {
        ProjectRouter.getInstance().noteFocused(project)
    }
}
