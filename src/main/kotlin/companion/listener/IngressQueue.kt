package companion.listener

import companion.routing.ProjectTargetSnapshot
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class IngressItem(
    val receiptSequence: Long,
    val receivedAt: Instant,
    val body: ByteArray,
    val projectSnapshot: ProjectTargetSnapshot,
)

/**
 * Bounded queue between the HTTP threads and the single ingress worker.
 * Reservations (count + bytes) are held from acceptance until the worker calls [release],
 * i.e. after validation failure or after the save attempt finished.
 */
class IngressQueue(private val maxItems: Int, private val maxBytes: Long) {
    private val queue = LinkedBlockingQueue<IngressItem>()
    private val items = AtomicInteger()
    private val bytes = AtomicLong()

    val pendingCount: Int get() = items.get()
    val pendingBytes: Long get() = bytes.get()

    /** Atomically reserves capacity and enqueues. Returns false when the queue is full. */
    fun offer(item: IngressItem): Boolean {
        synchronized(this) {
            if (items.get() >= maxItems || bytes.get() + item.body.size > maxBytes) return false
            items.incrementAndGet()
            bytes.addAndGet(item.body.size.toLong())
        }
        queue.add(item)
        return true
    }

    fun poll(timeoutMs: Long): IngressItem? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun release(item: IngressItem) {
        items.decrementAndGet()
        bytes.addAndGet(-item.body.size.toLong())
    }

    fun drain(): List<IngressItem> {
        val out = ArrayList<IngressItem>()
        queue.drainTo(out)
        out.forEach { release(it) }
        return out
    }
}
