package companion.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import companion.listener.CompanionListenerService
import companion.listener.ListenerState
import companion.routing.ProjectRouter
import companion.settings.CompanionAppSettings
import companion.storage.ProblemRepository
import companion.ui.Notifications

class ListenerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (project.basePath == null) return
        ProjectRouter.getInstance().noteFocused(project)
        try {
            ProblemRepository.getInstance(project).loadIndex()
        } catch (e: Exception) {
            Notifications.info(project, "Competitive Companion", "Could not load problem index: ${e.message}")
        }
        if (CompanionAppSettings.getInstance().state.autoStart) {
            val svc = CompanionListenerService.getInstance()
            if (svc.state == ListenerState.STOPPED) {
                svc.start()
                if (svc.state == ListenerState.ERROR) {
                    Notifications.listenerError("Could not bind port(s) ${svc.failedPortReasons.keys.joinToString()}: ${svc.failedPortReasons.values.joinToString()}. Change the port in Settings > Tools > Competitive Companion.")
                }
            }
        }
    }
}
