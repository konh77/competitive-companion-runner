package companion.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

enum class TemplateSource { BUILTIN, FILE, INLINE }
enum class InterpreterMode { PROJECT_SDK, CUSTOM }
enum class CompareMode { LINES, TOKENS, FLOAT }

@Service(Service.Level.PROJECT)
@State(name = "CompanionProjectSettings", storages = [Storage("competitiveCompanion.xml")])
class CompanionProjectSettings : PersistentStateComponent<CompanionProjectSettings.State> {

    class State {
        var solutionPathTemplate: String = DEFAULT_SOLUTION_TEMPLATE
        var testsDirTemplate: String = DEFAULT_TESTS_TEMPLATE
        var templateSource: TemplateSource = TemplateSource.BUILTIN
        var templateFilePath: String = ".companion/template.py"
        var inlineTemplate: String = ""
        var interpreterMode: InterpreterMode = InterpreterMode.PROJECT_SDK
        var customInterpreterPath: String = ""
        var extraInterpreterArgs: MutableList<String> = mutableListOf()
        var timeLimitMultiplier: Double = 1.0
        var terminationGraceMs: Int = 250
        var compareMode: CompareMode = CompareMode.LINES
        var absTol: Double = 1e-6
        var relTol: Double = 1e-6
        var parallelism: Int = 1
        var openOnReceive: Boolean = true
        var autoRunOnReceive: Boolean = false
        var overwriteSamples: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        const val DEFAULT_SOLUTION_TEMPLATE = "\${contestId}/\${taskId}.py"
        const val DEFAULT_TESTS_TEMPLATE = "\${contestId}/tests/\${taskId}"

        fun getInstance(project: Project): CompanionProjectSettings = project.getService(CompanionProjectSettings::class.java)
    }
}
