package companion.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

enum class TargetProjectMode { LAST_FOCUSED, PINNED, ASK }

@Service(Service.Level.APP)
@State(name = "CompanionAppSettings", storages = [Storage("competitiveCompanion.xml")])
class CompanionAppSettings : PersistentStateComponent<CompanionAppSettings.State> {

    class State {
        var port: Int = 10046
        var extraPorts: MutableList<Int> = mutableListOf()
        var autoStart: Boolean = true
        var targetProjectMode: TargetProjectMode = TargetProjectMode.LAST_FOCUSED
        /** Project base path pinned for this IDE session (not persisted across restarts on purpose). */
        @Transient
        var pinnedProjectPath: String? = null
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    val allPorts: List<Int>
        get() = (listOf(state.port) + state.extraPorts).filter { it in 1024..65535 }.distinct()

    companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MAX_EXTRA_PORTS = 4

        fun getInstance(): CompanionAppSettings = ApplicationManager.getApplication().getService(CompanionAppSettings::class.java)
    }
}
