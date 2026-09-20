package companion.model

import java.time.Instant
import java.util.UUID

data class ReceivedTest(val input: String, val output: String)

data class ReceivedTask(
    val name: String,
    val displayIndex: String?,
    val title: String,
    val group: String?,
    val url: AtCoderUrl,
    val interactive: Boolean,
    val timeLimitMs: Int,
    val memoryLimitMb: Int,
    val tests: List<ReceivedTest>,
    val batchId: UUID,
    val batchSize: Int,
    /** True when batch.id was missing/invalid and replaced by a fresh UUID (never aggregated). */
    val batchSynthesized: Boolean,
    val receiptSequence: Long,
    val receivedAt: Instant,
    /** Notes about defaulted fields, for diagnostics. */
    val notes: List<String>,
) {
    val problemKey: String get() = url.canonicalUrl
}
