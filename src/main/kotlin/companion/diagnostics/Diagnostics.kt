package companion.diagnostics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

enum class DiagLevel { INFO, WARN, ERROR }

data class DiagEntry(
    val at: Instant,
    val level: DiagLevel,
    val code: String,
    val message: String,
    val receiptSequence: Long? = null,
)

/**
 * Application-wide diagnostics: ring buffer of the last [CAPACITY] events plus counters
 * shown in the diagnostics panel. Never stores raw bodies or sample contents.
 */
@Service(Service.Level.APP)
class Diagnostics {
    private val log = Logger.getInstance("#companion.listener")
    private val entries = ArrayDeque<DiagEntry>(CAPACITY)
    private val lock = Any()

    val httpArrived = AtomicLong()
    val accepted = AtomicLong()
    val validated = AtomicLong()
    val saved = AtomicLong()
    val rejected = AtomicLong()
    val failed = AtomicLong()

    @Volatile var lastHttpAt: Instant? = null
    @Volatile var lastAcceptedAt: Instant? = null
    @Volatile var lastValidatedAt: Instant? = null
    @Volatile var lastSavedAt: Instant? = null

    fun record(level: DiagLevel, code: String, message: String, receiptSequence: Long? = null) {
        val e = DiagEntry(Instant.now(), level, code, message, receiptSequence)
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.pollFirst()
            entries.addLast(e)
        }
        when (level) {
            DiagLevel.INFO -> log.info("[$code] $message")
            DiagLevel.WARN -> log.warn("[$code] $message")
            DiagLevel.ERROR -> log.warn("[$code] $message")
        }
        ApplicationManager.getApplication().messageBus.syncPublisher(DiagnosticsListener.TOPIC).changed()
    }

    fun snapshot(): List<DiagEntry> = synchronized(lock) { entries.toList() }

    companion object {
        const val CAPACITY = 100
        fun getInstance(): Diagnostics = ApplicationManager.getApplication().getService(Diagnostics::class.java)
    }
}

interface DiagnosticsListener {
    fun changed()

    companion object {
        val TOPIC = com.intellij.util.messages.Topic.create("companion.diagnostics", DiagnosticsListener::class.java)
    }
}
