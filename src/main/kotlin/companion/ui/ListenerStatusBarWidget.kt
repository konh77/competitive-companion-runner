package companion.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import companion.listener.CompanionListenerService
import companion.listener.ListenerState
import companion.listener.ListenerStateListener
import companion.settings.CompanionAppSettings

class ListenerStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Competitive Companion Listener"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = ListenerStatusBarWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "Companion.StatusBar"
    }
}

class ListenerStatusBarWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.MultipleTextValuesPresentation {

    override fun ID(): String = ListenerStatusBarWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(ListenerStateListener.TOPIC, object : ListenerStateListener {
            override fun stateChanged(state: ListenerState) {
                statusBar.updateWidget(ID())
            }
        })
    }

    override fun getSelectedValue(): String {
        val svc = CompanionListenerService.getInstance()
        val port = CompanionAppSettings.getInstance().state.port
        val glyph = when (svc.state) {
            ListenerState.LISTENING -> "●"
            ListenerState.PARTIAL -> "◐"
            ListenerState.ERROR -> "✖"
            ListenerState.STOPPED -> "○"
        }
        val target = project.name
        return "CC:$port → $target $glyph"
    }

    override fun getTooltipText(): String {
        val svc = CompanionListenerService.getInstance()
        return when (svc.state) {
            ListenerState.LISTENING -> "Competitive Companion listener on 127.0.0.1:${svc.boundPorts.joinToString()} — click for options"
            ListenerState.PARTIAL -> "Some ports failed: ${svc.failedPortReasons}"
            ListenerState.ERROR -> "Listener could not bind: ${svc.failedPortReasons}"
            ListenerState.STOPPED -> "Competitive Companion listener stopped"
        }
    }

    override fun getPopup(): ListPopup? {
        val group = ActionManager.getInstance().getAction("Companion.StatusBarPopup") as? DefaultActionGroup ?: return null
        val context = SimpleDataContext.getProjectContext(project)
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Competitive Companion", group, context, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
        )
    }
}
