package companion.routing

import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import companion.model.ReceivedTask
import companion.storage.ProblemRecord
import companion.ui.Notifications
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Per-batch bookkeeping: fixed target project, saved / failed problem keys, summary notification. */
class BatchState(
    val id: UUID,
    val projectPath: String,
    val contestId: String,
    val declaredSize: Int,
    val firstAt: Instant,
) {
    val saved = LinkedHashMap<String, ProblemRecord>()
    val failed = LinkedHashMap<String, String>()
    var notified = false
    var lastActivity: Instant = firstAt
    var finishedAt: Instant? = null
    var summaryTimer: ScheduledFuture<*>? = null

    val uniqueProcessed: Int get() = saved.size + failed.size
    val missing: Int get() = maxOf(0, declaredSize - uniqueProcessed)
}

@Service(Service.Level.APP)
class BatchRegistry {
    private val batches = LinkedHashMap<UUID, BatchState>()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()

    /** Returns the batch for this task, creating it with the resolved project if needed. */
    @Synchronized
    fun batchFor(task: ReceivedTask, resolve: () -> RouteResult): Pair<BatchState?, RouteResult> {
        expire()
        val existing = batches[task.batchId]
        if (existing != null) {
            if (existing.finishedAt != null && Instant.now().isAfter(existing.finishedAt!!.plusSeconds(RETAIN_S))) {
                batches.remove(task.batchId)
            } else {
                val project = openProject(existing.projectPath)
                    ?: return existing to RouteResult.Failed("batch project was closed; send again from Competitive Companion")
                if (existing.contestId != task.url.contestId) {
                    return existing to RouteResult.Failed("batch ${task.batchId} is for contest ${existing.contestId}")
                }
                existing.lastActivity = Instant.now()
                return existing to RouteResult.Target(project)
            }
        }
        val route = resolve()
        if (route !is RouteResult.Target) return null to route
        if (batches.count { it.value.finishedAt == null } >= MAX_ACTIVE) {
            return null to RouteResult.Failed("too many active batches ($MAX_ACTIVE)")
        }
        val state = BatchState(task.batchId, route.project.basePath!!, task.url.contestId, task.batchSize, Instant.now())
        batches[task.batchId] = state
        if (task.batchSize > 1) {
            state.summaryTimer = scheduler.schedule({ summarize(task.batchId) }, SUMMARY_DELAY_S, TimeUnit.SECONDS)
        }
        return state to route
    }

    @Synchronized
    fun recordSaved(state: BatchState, task: ReceivedTask, record: ProblemRecord) {
        state.saved[task.problemKey] = record
        state.failed.remove(task.problemKey)
        state.lastActivity = Instant.now()
        if (state.declaredSize > 1 && state.uniqueProcessed >= state.declaredSize) summarize(state.id)
    }

    @Synchronized
    fun recordFailed(state: BatchState, task: ReceivedTask, reason: String) {
        if (!state.saved.containsKey(task.problemKey)) state.failed[task.problemKey] = reason
        state.lastActivity = Instant.now()
        if (state.declaredSize > 1 && state.uniqueProcessed >= state.declaredSize) summarize(state.id)
    }

    @Synchronized
    private fun summarize(id: UUID) {
        val state = batches[id] ?: return
        state.summaryTimer?.cancel(false)
        val project = openProject(state.projectPath) ?: return
        val first = state.saved.values.minWithOrNull(compareBy<ProblemRecord>({ naturalKey(it.manifest.displayIndex) }, { it.manifest.taskId }))
        Notifications.batchSummary(project, state, first, updated = state.notified)
        state.notified = true
    }

    private fun naturalKey(displayIndex: String?): String {
        if (displayIndex == null) return "~"
        val num = displayIndex.toIntOrNull()
        return if (num != null) "%09d".format(num) else displayIndex.lowercase()
    }

    private fun expire() {
        val now = Instant.now()
        val it = batches.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val s = e.value
            if (s.finishedAt == null && now.isAfter(s.lastActivity.plusSeconds(INACTIVITY_S))) s.finishedAt = now
            if (s.finishedAt != null && now.isAfter(s.finishedAt!!.plusSeconds(RETAIN_S))) it.remove()
        }
        while (batches.size > MAX_RETAINED) {
            val oldest = batches.entries.firstOrNull { it.value.finishedAt != null } ?: break
            batches.remove(oldest.key)
        }
    }

    private fun openProject(path: String): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed && it.basePath == path }

    companion object {
        const val SUMMARY_DELAY_S = 10L
        const val INACTIVITY_S = 60L
        const val RETAIN_S = 600L
        const val MAX_ACTIVE = 64
        const val MAX_RETAINED = 256

        fun getInstance(): BatchRegistry = ApplicationManager.getApplication().getService(BatchRegistry::class.java)
    }
}
