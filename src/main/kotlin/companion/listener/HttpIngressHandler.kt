package companion.listener

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import companion.diagnostics.DiagLevel
import companion.diagnostics.Diagnostics
import companion.routing.ProjectRouter
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Validates one request per the 6.3 rules and enqueues the raw body.
 * 200 means "accepted into the memory queue", nothing more.
 */
class HttpIngressHandler(
    private val service: CompanionListenerService,
    private val queue: IngressQueue,
    private val scheduler: ScheduledExecutorService,
) : HttpHandler {

    private val diag = Diagnostics.getInstance()

    override fun handle(exchange: HttpExchange) {
        diag.httpArrived.incrementAndGet()
        diag.lastHttpAt = Instant.now()
        // Absolute deadline for reading the body; closing the exchange aborts a blocked read.
        val watchdog = scheduler.schedule({
            try { exchange.close() } catch (_: Exception) {}
        }, BODY_DEADLINE_MS, TimeUnit.MILLISECONDS)
        try {
            handleInner(exchange)
        } catch (e: IOException) {
            diag.record(DiagLevel.WARN, "HTTP_IO", "request aborted: ${e.message}")
        } finally {
            watchdog.cancel(false)
            try { exchange.close() } catch (_: Exception) {}
        }
    }

    private fun handleInner(exchange: HttpExchange) {
        val headers = exchange.requestHeaders
        val localPort = exchange.localAddress.port

        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.add("Allow", "POST")
            return reject(exchange, 405, "method ${exchange.requestMethod}")
        }
        val uri = exchange.requestURI
        if (uri.scheme != null || uri.rawPath != "/" || uri.rawQuery != null || uri.rawFragment != null) {
            return reject(exchange, 404, "path")
        }
        val hosts = headers["Host"] ?: emptyList()
        if (hosts.size != 1) return reject(exchange, 400, "Host header count ${hosts.size}")
        if (!isAllowedHost(hosts[0], localPort)) return reject(exchange, 400, "Host ${hosts[0]}")

        val origins = headers["Origin"]
        if (origins != null) {
            if (origins.size != 1 || !isAllowedOrigin(origins[0])) return reject(exchange, 403, "Origin")
        }

        val contentTypes = headers["Content-Type"] ?: emptyList()
        if (contentTypes.size != 1 || !isJsonUtf8(contentTypes[0])) return reject(exchange, 415, "Content-Type")
        val encodings = headers["Content-Encoding"]
        if (encodings != null && encodings.any { !it.trim().equals("identity", ignoreCase = true) }) return reject(exchange, 415, "Content-Encoding")
        if (headers.containsKey("Expect")) return reject(exchange, 417, "Expect")

        val lengths = headers["Content-Length"]
        val transfer = headers["Transfer-Encoding"]
        if (lengths != null && lengths.size > 1) return reject(exchange, 400, "duplicate Content-Length")
        if (lengths != null && transfer != null) return reject(exchange, 400, "Content-Length with Transfer-Encoding")
        var declared: Long = -1
        if (lengths != null) {
            declared = lengths[0].trim().toLongOrNull() ?: return reject(exchange, 400, "Content-Length syntax")
            if (declared < 0) return reject(exchange, 400, "negative Content-Length")
            if (declared == 0L) return reject(exchange, 400, "empty body")
            if (declared > MAX_BODY_BYTES) return reject(exchange, 413, "Content-Length $declared")
        } else if (transfer == null || transfer.none { it.contains("chunked", ignoreCase = true) }) {
            return reject(exchange, 400, "empty body")
        }

        if (!service.isAccepting) return reject(exchange, 503, "stopping")

        val body = readBounded(exchange, declared) ?: return reject(exchange, 413, "body exceeds ${MAX_BODY_BYTES} bytes")
        if (body.isEmpty()) return reject(exchange, 400, "empty body")

        val seq = service.nextReceiptSequence()
        val item = IngressItem(seq, Instant.now(), body, ProjectRouter.getInstance().snapshot())
        if (!queue.offer(item)) return reject(exchange, 503, "queue full")

        diag.accepted.incrementAndGet()
        diag.lastAcceptedAt = item.receivedAt
        exchange.responseHeaders.add("Connection", "close")
        exchange.sendResponseHeaders(200, -1)
    }

    private fun readBounded(exchange: HttpExchange, declared: Long): ByteArray? {
        val limit = if (declared > 0) declared.toInt() else MAX_BODY_BYTES
        val buf = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        exchange.requestBody.use { input ->
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > limit) return null
                buf.write(chunk, 0, n)
            }
        }
        if (declared > 0 && total != declared) throw IOException("body shorter than Content-Length")
        return buf.toByteArray()
    }

    private fun reject(exchange: HttpExchange, status: Int, why: String) {
        diag.rejected.incrementAndGet()
        diag.record(DiagLevel.WARN, "HTTP_$status", why)
        exchange.responseHeaders.add("Connection", "close")
        try {
            exchange.sendResponseHeaders(status, -1)
        } catch (_: IOException) {
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        const val BODY_DEADLINE_MS = 5_000L

        fun isAllowedHost(host: String, port: Int): Boolean {
            val h = host.trim()
            return h.equals("localhost:$port", ignoreCase = true) || h == "127.0.0.1:$port" || h == "[::1]:$port"
        }

        fun isAllowedOrigin(origin: String): Boolean {
            val uri = try { URI(origin.trim()) } catch (e: URISyntaxException) { return false }
            val scheme = uri.scheme ?: return false
            if (scheme != "chrome-extension" && scheme != "moz-extension") return false
            if (uri.rawAuthority.isNullOrEmpty()) return false
            if (uri.rawUserInfo != null || uri.port != -1) return false
            if (!uri.rawPath.isNullOrEmpty()) return false
            if (uri.rawQuery != null || uri.rawFragment != null) return false
            return true
        }

        fun isJsonUtf8(contentType: String): Boolean {
            val parts = contentType.split(';').map { it.trim() }
            if (!parts[0].equals("application/json", ignoreCase = true)) return false
            for (p in parts.drop(1)) {
                val kv = p.split('=', limit = 2)
                if (kv.size == 2 && kv[0].trim().equals("charset", ignoreCase = true)) {
                    val cs = kv[1].trim().trim('"')
                    if (!cs.equals("utf-8", ignoreCase = true) && !cs.equals("utf8", ignoreCase = true)) return false
                }
            }
            return true
        }
    }
}
