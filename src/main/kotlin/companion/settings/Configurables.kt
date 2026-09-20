package companion.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import companion.listener.CompanionListenerService
import companion.template.PathTemplate

class AppConfigurable : BoundConfigurable("Competitive Companion") {
    private val settings = CompanionAppSettings.getInstance().state
    private var extraPortsText = settings.extraPorts.joinToString(", ")
    private var portBefore = settings.port
    private var extraBefore = settings.extraPorts.toList()

    override fun createPanel() = panel {
        group("Listener") {
            row("Port:") {
                intTextField(CompanionAppSettings.MIN_PORT..CompanionAppSettings.MAX_PORT).bindIntText(settings::port)
                comment("Add this number under Competitive Companion → Options → Custom ports.")
            }
            row("Extra ports:") {
                textField().bindText({ extraPortsText }, { extraPortsText = it }).columns(COLUMNS_LARGE)
                    .comment("Comma-separated, at most ${CompanionAppSettings.MAX_EXTRA_PORTS}.")
            }
            row { checkBox("Start listener automatically").bindSelected(settings::autoStart) }
        }
        group("Receive Target") {
            row("When several projects are open:") {
                comboBox(TargetProjectMode.entries.toList()).bindItem(settings::targetProjectMode.toNullableProperty())
            }
            row { comment("LAST_FOCUSED: the project window used most recently. PINNED: use the status bar / tool window 'Pin' action. ASK: choose per batch (30 s).") }
        }
        group("Diagnostics") {
            row { comment("The listener only accepts loopback POST requests with Content-Type application/json. It never contacts atcoder.jp.<br>Use the Companion tool window → Diagnostics for the test command and recent events.") }
        }
    }

    override fun apply() {
        val parsed = parseExtraPorts(extraPortsText)
        super.apply()
        settings.extraPorts = parsed.toMutableList()
        if (settings.port != portBefore || settings.extraPorts != extraBefore) {
            portBefore = settings.port
            extraBefore = settings.extraPorts.toList()
            val svc = CompanionListenerService.getInstance()
            if (svc.state != companion.listener.ListenerState.STOPPED) svc.restart()
        }
    }

    override fun isModified(): Boolean = super.isModified() || parseExtraPortsOrNull(extraPortsText) != settings.extraPorts

    private fun parseExtraPortsOrNull(text: String): List<Int>? = try { parseExtraPorts(text) } catch (e: ConfigurationException) { null }

    private fun parseExtraPorts(text: String): List<Int> {
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val ports = parts.map { it.toIntOrNull() ?: throw ConfigurationException("Invalid port: $it") }
        ports.forEach { if (it !in CompanionAppSettings.MIN_PORT..CompanionAppSettings.MAX_PORT) throw ConfigurationException("Port out of range: $it") }
        if (ports.distinct().size != ports.size) throw ConfigurationException("Duplicate extra port")
        if (ports.size > CompanionAppSettings.MAX_EXTRA_PORTS) throw ConfigurationException("At most ${CompanionAppSettings.MAX_EXTRA_PORTS} extra ports")
        if (ports.contains(settings.port)) throw ConfigurationException("Extra ports must differ from the main port")
        return ports
    }
}

class ProjectConfigurable(private val project: Project) : BoundConfigurable("Project") {
    private val settings = CompanionProjectSettings.getInstance(project).state
    private var extraArgsText = settings.extraInterpreterArgs.joinToString(" ")
    private val customPathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Select Python Interpreter"))
        text = settings.customInterpreterPath
    }

    override fun createPanel() = panel {
        group("Files") {
            row("Solution path template:") {
                textField().bindText(settings::solutionPathTemplate).columns(COLUMNS_LARGE)
                    .validationOnInput { PathTemplate.validateForSettings(it.text)?.let { m -> error(m) } }
                    .validationOnApply { PathTemplate.validateForSettings(it.text)?.let { m -> error(m) } }
            }
            row("Tests dir template:") {
                textField().bindText(settings::testsDirTemplate).columns(COLUMNS_LARGE)
                    .validationOnInput { PathTemplate.validateForSettings(it.text)?.let { m -> error(m) } }
                    .validationOnApply { PathTemplate.validateForSettings(it.text)?.let { m -> error(m) } }
            }
            row { comment("Variables: \${contestId} \${contestNumber} (abc476 → 476) \${taskId} \${taskIndex} \${taskIndexUpper} \${date}. Flat example: \${contestNumber}-\${taskIndex}.py with tests dir .tests/\${taskId}. Defaults: ${CompanionProjectSettings.DEFAULT_SOLUTION_TEMPLATE} / ${CompanionProjectSettings.DEFAULT_TESTS_TEMPLATE}") }
            row("Solution template:") {
                comboBox(TemplateSource.entries.toList()).bindItem(settings::templateSource.toNullableProperty())
            }
            row("Template file (project-relative):") { textField().bindText(settings::templateFilePath).columns(COLUMNS_LARGE) }
            row("Inline template:") {
                textArea().bindText(settings::inlineTemplate).align(AlignX.FILL).applyToComponent { rows = 8 }
            }
            row { comment("Template variables: \${name} \${title} \${group} (comment lines only) \${url} \${contestId} \${taskId} \${taskIndex} \${timeLimitMs} \${memoryLimitMb} \${date} \${datetime}") }
            row { checkBox("Open solution file when a problem is received").bindSelected(settings::openOnReceive) }
            row { checkBox("Overwrite stored samples when a problem is received again").bindSelected(settings::overwriteSamples) }
            row { checkBox("Run tests automatically after receiving (only when samples changed)").bindSelected(settings::autoRunOnReceive) }
        }
        group("Execution") {
            row("Interpreter:") {
                comboBox(InterpreterMode.entries.toList()).bindItem(settings::interpreterMode.toNullableProperty())
            }
            row("Custom interpreter path:") { cell(customPathField).align(AlignX.FILL) }
            row("Extra interpreter args:") {
                textField().bindText({ extraArgsText }, { extraArgsText = it }).columns(COLUMNS_LARGE)
                    .comment("Space-separated flags such as -X dev. -c, -m and script paths are rejected.")
            }
            row("Time limit multiplier:") {
                textField().bindText({ settings.timeLimitMultiplier.toString() }, { settings.timeLimitMultiplier = it.toDoubleOrNull()?.coerceIn(0.1, 10.0) ?: 1.0 }).columns(8)
            }
            row("Termination grace (ms):") { intTextField(0..2000).bindIntText(settings::terminationGraceMs) }
            row("Parallel cases:") { intTextField(1..4).bindIntText(settings::parallelism) }
        }
        group("Comparison") {
            row("Mode:") { comboBox(CompareMode.entries.toList()).bindItem(settings::compareMode.toNullableProperty()) }
            row("Absolute tolerance:") {
                textField().bindText({ settings.absTol.toString() }, { settings.absTol = it.toDoubleOrNull()?.takeIf { d -> d.isFinite() && d in 0.0..1.0 } ?: 1e-6 }).columns(12)
            }
            row("Relative tolerance:") {
                textField().bindText({ settings.relTol.toString() }, { settings.relTol = it.toDoubleOrNull()?.takeIf { d -> d.isFinite() && d in 0.0..1.0 } ?: 1e-6 }).columns(12)
            }
            row { comment("LINES: trailing whitespace and blank lines ignored. TOKENS: whitespace-insensitive. FLOAT: TOKENS with numeric tolerance.") }
        }
    }

    override fun apply() {
        super.apply()
        settings.customInterpreterPath = customPathField.text.trim()
        settings.extraInterpreterArgs = extraArgsText.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
    }

    override fun isModified(): Boolean = super.isModified() ||
        customPathField.text.trim() != settings.customInterpreterPath ||
        extraArgsText.split(Regex("\\s+")).filter { it.isNotEmpty() } != settings.extraInterpreterArgs
}
