package companion.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

const val KIND_PROBLEM = "competitive-companion-problem"
const val KIND_INDEX = "competitive-companion-index"
const val KIND_SAMPLE_SET = "competitive-companion-sample-set"
const val SCHEMA_VERSION = 2

/** `problem.json` — the commit point of a problem. */
data class ProblemManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND_PROBLEM,
    var metadataRevision: Long = 1,
    val problemKey: String,
    val url: String,
    val contestId: String,
    val taskId: String,
    var name: String,
    var displayIndex: String?,
    var title: String,
    var group: String?,
    var interactive: Boolean,
    var timeLimitMs: Int,
    var memoryLimitMb: Int,
    var judge: String = "EXACT",
    var compareMode: String? = null,
    var absTol: Double? = null,
    var relTol: Double? = null,
    val solutionPath: String,
    val testsDir: String,
    var activeSampleRevision: String?,
    var sampleCount: Int,
    var lastReceivedSampleCount: Int,
    var contentFingerprint: String,
    var receivedAt: String,
    var committedAt: String,
    var batchId: String,
    var batchSize: Int,
)

data class SampleEntry(
    val name: String,
    val inputSha256: String,
    val outputSha256: String,
    val inputBytes: Long,
    val outputBytes: Long,
)

/** `sample-set.json` — completion marker of one immutable sample revision. */
data class SampleSet(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND_SAMPLE_SET,
    val revision: String,
    val problemKey: String,
    val samples: List<SampleEntry>,
)

data class IndexEntry(
    val problemKey: String,
    val solutionPath: String,
    val testsDir: String,
    val metadataRevision: Long,
)

data class IndexFile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND_INDEX,
    val problems: List<IndexEntry>,
)

object Json {
    val gson = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()

    inline fun <reified T> read(path: Path, maxBytes: Long = 256L * 1024): T? {
        if (!Files.isRegularFile(path)) return null
        if (Files.size(path) > maxBytes) return null
        val text = Files.readString(path, StandardCharsets.UTF_8)
        return try {
            gson.fromJson(text, T::class.java)
        } catch (e: JsonParseException) {
            null
        }
    }

    fun writeAtomic(path: Path, value: Any) {
        val text = gson.toJson(value)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp-" + System.nanoTime())
        Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
        // Verify round-trip before publishing.
        gson.fromJson(Files.readString(tmp, StandardCharsets.UTF_8), value.javaClass)
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }
}

object Hashing {
    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
