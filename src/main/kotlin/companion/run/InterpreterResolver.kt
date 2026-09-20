package companion.run

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.python.sdk.PythonSdkUtil
import companion.settings.CompanionProjectSettings
import companion.settings.InterpreterMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class Interpreter(val executable: Path, val description: String)

sealed class InterpreterResult {
    data class Ok(val interpreter: Interpreter) : InterpreterResult()
    data class Failed(val reason: String) : InterpreterResult()
}

/** Resolves a local CPython / PyPy executable. Remote SDKs are refused. */
object InterpreterResolver {
    fun resolve(project: Project, solutionPath: Path): InterpreterResult {
        val settings = CompanionProjectSettings.getInstance(project).state
        return when (settings.interpreterMode) {
            InterpreterMode.CUSTOM -> {
                val raw = settings.customInterpreterPath.trim()
                if (raw.isEmpty()) return InterpreterResult.Failed("custom interpreter path is empty")
                val p = Paths.get(raw)
                if (!p.isAbsolute) return InterpreterResult.Failed("custom interpreter path must be absolute")
                check(p, "custom interpreter")
            }
            InterpreterMode.PROJECT_SDK -> {
                val sdk = findSdk(project, solutionPath) ?: return InterpreterResult.Failed("no Python interpreter configured (Settings > Project > Python Interpreter)")
                if (!PythonSdkUtil.isPythonSdk(sdk)) return InterpreterResult.Failed("project SDK '${sdk.name}' is not a Python interpreter")
                if (PythonSdkUtil.isRemote(sdk)) return InterpreterResult.Failed("remote / containerised interpreters are not supported: ${sdk.name}")
                val home = sdk.homePath ?: return InterpreterResult.Failed("SDK '${sdk.name}' has no home path")
                check(Paths.get(home), sdk.name + " (" + (sdk.versionString ?: "?") + ")")
            }
        }
    }

    private fun findSdk(project: Project, solutionPath: Path): Sdk? {
        val vf = LocalFileSystem.getInstance().findFileByNioFile(solutionPath)
        val module = vf?.let { ModuleUtilCore.findModuleForFile(it, project) }
        val moduleSdk = module?.let { PythonSdkUtil.findPythonSdk(it) }
        if (moduleSdk != null) return moduleSdk
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk
        if (projectSdk != null) return projectSdk
        return ProjectJdkTable.getInstance().allJdks.firstOrNull { PythonSdkUtil.isPythonSdk(it) && !PythonSdkUtil.isRemote(it) }
    }

    private fun check(p: Path, description: String): InterpreterResult {
        if (!Files.isRegularFile(p)) return InterpreterResult.Failed("interpreter not found: $p")
        if (!Files.isExecutable(p)) return InterpreterResult.Failed("interpreter is not executable: $p")
        return InterpreterResult.Ok(Interpreter(p, description))
    }
}
