package companion.listener

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.sun.net.httpserver.HttpServer
import companion.diagnostics.DiagLevel
import companion.diagnostics.Diagnostics
import companion.routing.IngressProcessor
import companion.settings.CompanionAppSettings
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ListenerState { STOPPED, LISTENING, PARTIAL, ERROR }

interface ListenerStateListener {
    fun stateChanged(state: ListenerState)

    companion object {
        val TOPIC: Topic<ListenerStateListener> = Topic.create("companion.listener.state", ListenerStateListener::class.java)
    }
}

/**
 * Loopback HTTP listener for Competitive Companion. One `HttpServer` per configured port,
 * a bounded ingress queue and a single worker thread that validates and stores tasks.
 */
@Service(Service.Level.APP)
class CompanionListenerService : Disposable {
    private val diag = Diagnostics.getInstance()
    private val queue = IngressQueue(maxItems = 64, maxBytes = 32L * 1024 * 1024)
    private val receiptSeq = AtomicLong()
    private val servers = LinkedHashMap<Int, HttpServer>()
    private val failedPorts = LinkedHashMap<Int, String>()
    private val accepting = AtomicBoolean(false)
    private var worker: Thread? = null
    private var retry: ScheduledFuture<*>? = null

    private val scheduler: ScheduledExecutorService = AppExecutorUtil.getAppScheduledExecutorService()
    private val httpExecutor = Executors.newFixedThreadPool(MAX_CONNECTIONS, namedFactory("companion-http"))

    @Volatile var state: ListenerState = ListenerState.STOPPED
        private set

    val isAccepting: Boolean get() = accepting.get()
    val boundPorts: List<Int> get() = synchronized(this) { servers.keys.toList() }
    val failedPortReasons: Map<Int, String> get() = synchronized(this) { failedPorts.toMap() }
    val pendingCount: Int get() = queue.pendingCount
    val pendingBytes: Long get() = queue.pendingBytes

    fun nextReceiptSequence(): Long = receiptSeq.incrementAndGet()

    @Synchronized
    fun start() {
        if (state == ListenerState.LISTENING) return
        retry?.cancel(false)
        val ports = CompanionAppSettings.getInstance().allPorts
        failedPorts.clear()
        for (port in ports) {
            if (servers.containsKey(port)) continue
            try {
                val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), BACKLOG)
                server.executor = httpExecutor
                server.createContext("/", HttpIngressHandler(this, queue, scheduler))
                server.start()
                servers[port] = server
                diag.record(DiagLevel.INFO, "LISTEN", "listening on 127.0.0.1:$port")
            } catch (e: BindException) {
                failedPorts[port] = "port in use"
                diag.record(DiagLevel.ERROR, "BIND", "port $port: ${e.message}")
            } catch (e: IOException) {
                failedPorts[port] = e.message ?: e.javaClass.simpleName
                diag.record(DiagLevel.ERROR, "BIND", "port $port: ${e.message}")
            }
        }
        accepting.set(servers.isNotEmpty())
        ensureWorker()
        updateState()
        if (failedPorts.isNotEmpty() && retry == null) {
            retry = scheduler.schedule({
                synchronized(this) { retry = null }
                if (state != ListenerState.STOPPED) start()
            }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun stop() {
        accepting.set(false)
        retry?.cancel(false)
        retry = null
        for ((port, server) in servers) {
            try { server.stop(0) } catch (_: Exception) {}
            diag.record(DiagLevel.INFO, "STOP", "stopped port $port")
        }
        servers.clear()
        failedPorts.clear()
        val dropped = queue.drain()
        if (dropped.isNotEmpty()) diag.record(DiagLevel.WARN, "STOP", "${dropped.size} accepted request(s) dropped before validation")
        updateState()
    }

    fun restart() {
        stop()
        start()
    }

    private fun updateState() {
        val s = when {
            servers.isEmpty() && failedPorts.isEmpty() -> ListenerState.STOPPED
            servers.isEmpty() -> ListenerState.ERROR
            failedPorts.isEmpty() -> ListenerState.LISTENING
            else -> ListenerState.PARTIAL
        }
        state = s
        ApplicationManager.getApplication().messageBus.syncPublisher(ListenerStateListener.TOPIC).stateChanged(s)
    }

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        val t = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val item = try { queue.poll(500) } catch (e: InterruptedException) { break } ?: continue
                try {
                    IngressProcessor.process(item)
                } catch (e: Throwable) {
                    diag.record(DiagLevel.ERROR, "WORKER", "unexpected failure: ${e.javaClass.simpleName}: ${e.message}", item.receiptSequence)
                } finally {
                    queue.release(item)
                }
            }
        }, "companion-ingress")
        t.isDaemon = true
        t.start()
        worker = t
    }

    override fun dispose() {
        stop()
        worker?.interrupt()
        httpExecutor.shutdownNow()
    }

    private fun namedFactory(prefix: String) = ThreadFactory { r ->
        Thread(r, "$prefix-${THREAD_COUNTER.incrementAndGet()}").apply { isDaemon = true }
    }

    companion object {
        const val MAX_CONNECTIONS = 8
        const val BACKLOG = 16
        const val RETRY_DELAY_MS = 5_000L
        private val THREAD_COUNTER = AtomicLong()

        fun getInstance(): CompanionListenerService = ApplicationManager.getApplication().getService(CompanionListenerService::class.java)
    }
}
