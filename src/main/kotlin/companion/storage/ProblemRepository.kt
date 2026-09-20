package companion.storage

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import companion.model.ReceivedTask
import companion.settings.CompanionProjectSettings
import companion.settings.TemplateSource
import companion.template.PathTemplate
import companion.template.SolutionTemplate
import companion.template.TemplateException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class TestKind { SAMPLE, CUSTOM }

data class TestCaseRef(val name: String, val kind: TestKind, val inputPath: Path, val outputPath: Path?)

data class ProblemRecord(val manifest: ProblemManifest, val solutionPath: Path, val testsDir: Path) {
    val problemKey: String get() = manifest.problemKey
    val displayName: String get() = manifest.name
}

sealed class SaveOutcome {
    data class Saved(
        val record: ProblemRecord,
        val isNew: Boolean,
        val samplesChanged: Boolean,
        val createdSolution: Boolean,
        val linkedExistingSolution: Boolean,
        val notes: List<String>,
    ) : SaveOutcome()

    data class Failed(val code: String, val reason: String) : SaveOutcome()
}

interface ProblemsListener {
    fun problemsChanged()

    companion object {
        val TOPIC: Topic<ProblemsListener> = Topic.create("companion.problems", ProblemsListener::class.java)
    }
}

/**
 * Owns every problem stored in one project: manifests, immutable sample revisions,
 * custom tests and the rebuildable `.companion/index.json` cache.
 */
@Service(Service.Level.PROJECT)
class ProblemRepository(private val project: Project) : Disposable {
    private val log = Logger.getInstance(ProblemRepository::class.java)

    val root: Path = run {
        val base = project.basePath ?: throw IllegalStateException("project has no base path")
        val p = Paths.get(base).toAbsolutePath()
        try { p.toRealPath() } catch (e: IOException) { p.normalize() }
    }

    private val records = ConcurrentHashMap<String, ProblemRecord>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val indexLock = ReentrantLock()

    @Volatile var lastRebuildNote: String? = null
        private set

    val companionDir: Path get() = root.resolve(COMPANION_DIR)
    val indexPath: Path get() = companionDir.resolve("index.json")

    fun all(): List<ProblemRecord> = records.values.sortedWith(compareBy({ it.manifest.contestId }, { it.manifest.taskId }))

    fun find(problemKey: String): ProblemRecord? = records[problemKey]

    fun findBySolution(path: Path): ProblemRecord? {
        val abs = path.toAbsolutePath().normalize()
        return records.values.firstOrNull { it.solutionPath == abs }
    }

    private fun lockFor(key: String): ReentrantLock = locks.computeIfAbsent(key) { ReentrantLock() }

    // ---------------------------------------------------------------- receive / commit

    fun save(task: ReceivedTask): SaveOutcome {
        if (project.isDisposed) return SaveOutcome.Failed("PROJECT_CLOSED", "project is closed")
        val settings = CompanionProjectSettings.getInstance(project).state
        val key = task.problemKey
        return lockFor(key).withLock {
            try {
                doSave(task, settings)
            } catch (e: UnsafePathException) {
                SaveOutcome.Failed("PATH", e.message ?: "unsafe path")
            } catch (e: TemplateException) {
                SaveOutcome.Failed("TEMPLATE", e.message ?: "template error")
            } catch (e: IOException) {
                log.warn("save failed for $key", e)
                SaveOutcome.Failed("IO", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun doSave(task: ReceivedTask, settings: CompanionProjectSettings.State): SaveOutcome {
        val notes = ArrayList<String>(task.notes)
        val key = task.problemKey
        val existing = records[key]?.let { rec ->
            // Re-validate the on-disk manifest before touching anything.
            val onDisk = readManifest(rec.testsDir.resolve(MANIFEST))
            if (onDisk == null || onDisk.problemKey != key) {
                return SaveOutcome.Failed("MANIFEST", "existing manifest for $key is missing or belongs to another problem; run Refresh")
            }
            ProblemRecord(onDisk, rec.solutionPath, rec.testsDir)
        }

        val solutionPath: Path
        val testsDir: Path
        if (existing != null) {
            solutionPath = existing.solutionPath
            testsDir = existing.testsDir
        } else {
            val vars = PathTemplate.variables(task)
            val solRel = PathTemplate.expand(settings.solutionPathTemplate, vars)
            val testsRel = PathTemplate.expand(settings.testsDirTemplate, vars)
            solutionPath = SafePath.resolveRelative(root, solRel)
            testsDir = SafePath.resolveRelative(root, testsRel)
            checkOwnership(key, solutionPath, testsDir)
        }
        SafePath.checkNoLinks(root, solutionPath)
        SafePath.checkNoLinks(root, testsDir)

        val fingerprint = fingerprintOf(task)
        val now = Instant.now().toString()

        // Existing testsDir must be empty, absent, or ours.
        if (existing == null && Files.exists(testsDir, LinkOption.NOFOLLOW_LINKS)) {
            val m = readManifest(testsDir.resolve(MANIFEST))
            if (m != null && m.problemKey != key) {
                return SaveOutcome.Failed("CONFLICT", "tests directory ${rel(testsDir)} belongs to ${m.problemKey}")
            }
            if (m == null && Files.list(testsDir).use { it.findAny().isPresent }) {
                return SaveOutcome.Failed("CONFLICT", "tests directory ${rel(testsDir)} exists and is not empty")
            }
        }

        // Decide whether samples change.
        var samplesChanged = false
        var newRevision: String? = existing?.manifest?.activeSampleRevision
        var sampleCount = existing?.manifest?.sampleCount ?: 0
        var storedFingerprint = existing?.manifest?.contentFingerprint ?: ""
        val needUpdate = existing == null || existing.manifest.contentFingerprint != fingerprint
        if (needUpdate) {
            val keepExisting = existing != null && (
                !settings.overwriteSamples ||
                    (task.tests.isEmpty() && (existing.manifest.sampleCount > 0))
                )
            if (keepExisting) {
                notes.add(
                    if (task.tests.isEmpty()) "received 0 samples; kept existing ${existing!!.manifest.sampleCount}"
                    else "samples differ from stored ones; overwriteSamples is off",
                )
            } else {
                Files.createDirectories(testsDir)
                newRevision = writeSampleRevision(testsDir, key, task)
                sampleCount = task.tests.size
                samplesChanged = true
                storedFingerprint = fingerprint
            }
        }

        // Solution file: create once, never overwrite.
        var createdSolution = false
        var linkedExisting = false
        if (Files.exists(solutionPath, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(solutionPath, LinkOption.NOFOLLOW_LINKS)) {
                return SaveOutcome.Failed("CONFLICT", "solution path ${rel(solutionPath)} exists and is not a regular file")
            }
            if (existing == null) linkedExisting = true
        } else {
            Files.createDirectories(solutionPath.parent)
            val content = renderSolution(task, settings)
            try {
                Files.newOutputStream(solutionPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                    it.write(content.toByteArray(StandardCharsets.UTF_8))
                }
                createdSolution = true
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                linkedExisting = true
            }
        }

        // Manifest commit.
        Files.createDirectories(testsDir)
        val manifest = if (existing != null) {
            existing.manifest.copy(
                metadataRevision = existing.manifest.metadataRevision + 1,
                name = task.name, displayIndex = task.displayIndex, title = task.title, group = task.group,
                interactive = task.interactive, timeLimitMs = task.timeLimitMs, memoryLimitMb = task.memoryLimitMb,
                activeSampleRevision = newRevision, sampleCount = sampleCount,
                lastReceivedSampleCount = task.tests.size, contentFingerprint = storedFingerprint,
                receivedAt = task.receivedAt.toString(), committedAt = now,
                batchId = task.batchId.toString(), batchSize = task.batchSize,
            )
        } else {
            ProblemManifest(
                problemKey = key, url = task.url.canonicalUrl,
                contestId = task.url.contestId, taskId = task.url.taskId,
                name = task.name, displayIndex = task.displayIndex, title = task.title, group = task.group,
                interactive = task.interactive, timeLimitMs = task.timeLimitMs, memoryLimitMb = task.memoryLimitMb,
                solutionPath = rel(solutionPath), testsDir = rel(testsDir),
                activeSampleRevision = newRevision, sampleCount = sampleCount,
                lastReceivedSampleCount = task.tests.size, contentFingerprint = storedFingerprint,
                receivedAt = task.receivedAt.toString(), committedAt = now,
                batchId = task.batchId.toString(), batchSize = task.batchSize,
            )
        }
        commitManifest(testsDir, manifest, existing?.manifest)

        val record = ProblemRecord(manifest, solutionPath, testsDir)
        records[key] = record
        writeIndex()
        gcRevisions(testsDir, keep = setOfNotNull(manifest.activeSampleRevision, existing?.manifest?.activeSampleRevision))
        notifyChanged()
        return SaveOutcome.Saved(record, existing == null, samplesChanged, createdSolution, linkedExisting, notes)
    }

    private fun renderSolution(task: ReceivedTask, settings: CompanionProjectSettings.State): String {
        val template = when (settings.templateSource) {
            TemplateSource.BUILTIN -> SolutionTemplate.BUILTIN
            TemplateSource.INLINE -> settings.inlineTemplate.ifBlank { throw TemplateException("inline template is empty") }
            TemplateSource.FILE -> {
                val p = SafePath.resolveRelative(root, settings.templateFilePath)
                if (!Files.isRegularFile(p)) throw TemplateException("template file not found: ${settings.templateFilePath}")
                if (Files.size(p) > 1 shl 20) throw TemplateException("template file larger than 1 MiB")
                Files.readString(p, StandardCharsets.UTF_8)
            }
        }
        return SolutionTemplate.render(template, SolutionTemplate.variables(task))
    }

    /** Writes a complete, verified sample revision and returns its id. */
    private fun writeSampleRevision(testsDir: Path, key: String, task: ReceivedTask): String {
        val revision = UUID.randomUUID().toString()
        val staging = testsDir.resolve(STAGING_DIR).resolve(revision)
        Files.createDirectories(staging)
        val entries = ArrayList<SampleEntry>()
        val width = maxOf(2, task.tests.size.toString().length)
        for ((i, t) in task.tests.withIndex()) {
            val name = "sample_" + (i + 1).toString().padStart(width, '0')
            val inBytes = t.input.toByteArray(StandardCharsets.UTF_8)
            val outBytes = t.output.toByteArray(StandardCharsets.UTF_8)
            Files.write(staging.resolve("$name.in"), inBytes, StandardOpenOption.CREATE_NEW)
            Files.write(staging.resolve("$name.out"), outBytes, StandardOpenOption.CREATE_NEW)
            entries.add(SampleEntry(name, Hashing.sha256Hex(inBytes), Hashing.sha256Hex(outBytes), inBytes.size.toLong(), outBytes.size.toLong()))
        }
        // Verify what landed on disk.
        for (e in entries) {
            if (Hashing.sha256Hex(staging.resolve(e.name + ".in")) != e.inputSha256) throw IOException("sample verification failed: ${e.name}.in")
            if (Hashing.sha256Hex(staging.resolve(e.name + ".out")) != e.outputSha256) throw IOException("sample verification failed: ${e.name}.out")
        }
        Json.writeAtomic(staging.resolve(SAMPLE_SET), SampleSet(revision = revision, problemKey = key, samples = entries))
        val samplesDir = testsDir.resolve(SAMPLES_DIR)
        Files.createDirectories(samplesDir)
        Files.move(staging, samplesDir.resolve(revision), StandardCopyOption.ATOMIC_MOVE)
        return revision
    }

    private fun commitManifest(testsDir: Path, manifest: ProblemManifest, previous: ProblemManifest?) {
        if (previous != null) {
            Json.writeAtomic(testsDir.resolve(MANIFEST_PREVIOUS), previous)
        }
        Json.writeAtomic(testsDir.resolve(MANIFEST), manifest)
    }

    private fun gcRevisions(testsDir: Path, keep: Set<String>) {
        val samplesDir = testsDir.resolve(SAMPLES_DIR)
        if (!Files.isDirectory(samplesDir)) return
        try {
            Files.list(samplesDir).use { stream ->
                stream.filter { Files.isDirectory(it) && it.fileName.toString() !in keep }.forEach { dir ->
                    try { deleteTree(dir) } catch (e: IOException) { log.warn("revision GC failed: $dir", e) }
                }
            }
        } catch (e: IOException) {
            log.warn("revision GC failed", e)
        }
    }

    private fun deleteTree(dir: Path) {
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult { Files.delete(d); return FileVisitResult.CONTINUE }
        })
    }

    /** Rejects a save target that collides with or nests inside another problem's files. */
    private fun checkOwnership(key: String, solutionPath: Path, testsDir: Path) {
        if (solutionPath.startsWith(testsDir)) throw UnsafePathException("solution must not be inside its tests directory")
        if (solutionPath.startsWith(companionDir) || testsDir.startsWith(companionDir)) throw UnsafePathException("paths inside $COMPANION_DIR are reserved")
        if (testsDir == root) throw UnsafePathException("tests directory must not be the project root")
        for (other in records.values) {
            if (other.problemKey == key) continue
            if (other.solutionPath == solutionPath) throw UnsafePathException("solution path already used by ${other.problemKey}")
            if (other.testsDir == testsDir || other.testsDir.startsWith(testsDir) || testsDir.startsWith(other.testsDir)) {
                throw UnsafePathException("tests directory overlaps ${other.problemKey}")
            }
            if (other.solutionPath.startsWith(testsDir) || solutionPath.startsWith(other.testsDir)) {
                throw UnsafePathException("solution/tests overlap with ${other.problemKey}")
            }
        }
    }

    private fun fingerprintOf(task: ReceivedTask): String {
        val canonical = LinkedHashMap<String, Any?>()
        canonical["url"] = task.url.canonicalUrl
        canonical["name"] = task.name
        canonical["group"] = task.group
        canonical["interactive"] = task.interactive
        canonical["timeLimitMs"] = task.timeLimitMs
        canonical["memoryLimitMb"] = task.memoryLimitMb
        canonical["tests"] = task.tests.map { linkedMapOf("input" to it.input, "output" to it.output) }
        val json = com.google.gson.GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(canonical)
        return Hashing.sha256Hex(json.toByteArray(StandardCharsets.UTF_8))
    }

    // ---------------------------------------------------------------- tests

    fun listTests(record: ProblemRecord): List<TestCaseRef> {
        val result = ArrayList<TestCaseRef>()
        val rev = record.manifest.activeSampleRevision
        if (rev != null) {
            val dir = record.testsDir.resolve(SAMPLES_DIR).resolve(rev)
            val set = Json.read<SampleSet>(dir.resolve(SAMPLE_SET))
            if (set != null && set.problemKey == record.problemKey) {
                for (e in set.samples) {
                    if (!SAMPLE_NAME.matches(e.name)) continue
                    result.add(TestCaseRef(e.name, TestKind.SAMPLE, dir.resolve(e.name + ".in"), dir.resolve(e.name + ".out")))
                }
            }
        }
        if (Files.isDirectory(record.testsDir)) {
            val customs = Files.list(record.testsDir).use { s ->
                s.map { it.fileName.toString() }.filter { CUSTOM_IN.matches(it) }.toList()
            }
            customs.sortedBy { CUSTOM_IN.matchEntire(it)!!.groupValues[1].toInt() }.forEach { fn ->
                val name = fn.removeSuffix(".in")
                val out = record.testsDir.resolve("$name.out")
                result.add(TestCaseRef(name, TestKind.CUSTOM, record.testsDir.resolve(fn), if (Files.isRegularFile(out)) out else null))
            }
        }
        return result
    }

    fun addCustomTest(record: ProblemRecord, input: String, expected: String?): TestCaseRef = lockFor(record.problemKey).withLock {
        Files.createDirectories(record.testsDir)
        val used = listTests(record).filter { it.kind == TestKind.CUSTOM }
            .mapNotNull { CUSTOM_IN.matchEntire(it.name + ".in")?.groupValues?.get(1)?.toInt() }
        val n = (used.maxOrNull() ?: 0) + 1
        val name = "custom_" + n.toString().padStart(2, '0')
        val inPath = record.testsDir.resolve("$name.in")
        Files.write(inPath, companion.model.TaskValidator.normalizeTestData(input).toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW)
        var outPath: Path? = null
        if (expected != null) {
            outPath = record.testsDir.resolve("$name.out")
            Files.write(outPath, companion.model.TaskValidator.normalizeTestData(expected).toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW)
        }
        notifyChanged()
        TestCaseRef(name, TestKind.CUSTOM, inPath, outPath)
    }

    fun deleteCustomTest(record: ProblemRecord, test: TestCaseRef) = lockFor(record.problemKey).withLock {
        require(test.kind == TestKind.CUSTOM) { "only custom tests can be deleted" }
        require(test.inputPath.parent == record.testsDir)
        Files.deleteIfExists(test.inputPath)
        test.outputPath?.let { Files.deleteIfExists(it) }
        notifyChanged()
    }

    fun copySampleToCustom(record: ProblemRecord, test: TestCaseRef): TestCaseRef {
        val input = Files.readString(test.inputPath, StandardCharsets.UTF_8)
        val output = test.outputPath?.let { Files.readString(it, StandardCharsets.UTF_8) }
        return addCustomTest(record, input, output)
    }

    fun updateManifest(record: ProblemRecord, mutate: (ProblemManifest) -> ProblemManifest): ProblemRecord = lockFor(record.problemKey).withLock {
        val current = readManifest(record.testsDir.resolve(MANIFEST)) ?: record.manifest
        val updated = mutate(current.copy()).copy(metadataRevision = current.metadataRevision + 1)
        commitManifest(record.testsDir, updated, current)
        val rec = ProblemRecord(updated, record.solutionPath, record.testsDir)
        records[record.problemKey] = rec
        writeIndex()
        notifyChanged()
        rec
    }

    fun forget(record: ProblemRecord) {
        records.remove(record.problemKey)
        writeIndex()
        notifyChanged()
    }

    // ---------------------------------------------------------------- index

    fun loadIndex() {
        val idx = Json.read<IndexFile>(indexPath)
        if (idx == null || idx.kind != KIND_INDEX || idx.schemaVersion != SCHEMA_VERSION) {
            rebuild()
            return
        }
        var stale = false
        for (e in idx.problems) {
            val rec = try { recordFrom(e.testsDir, e.solutionPath) } catch (ex: Exception) { null }
            if (rec == null || rec.manifest.problemKey != e.problemKey || rec.manifest.metadataRevision != e.metadataRevision) {
                stale = true
                continue
            }
            records[rec.problemKey] = rec
        }
        if (stale) rebuild() else notifyChanged()
    }

    private fun recordFrom(testsDirRel: String, solutionRel: String): ProblemRecord? {
        val testsDir = SafePath.resolveRelative(root, testsDirRel)
        val m = readManifest(testsDir.resolve(MANIFEST)) ?: return null
        val solution = SafePath.resolveRelative(root, m.solutionPath)
        if (m.testsDir != testsDirRel || m.solutionPath != solutionRel) return null
        return ProblemRecord(m, solution, testsDir)
    }

    /** Scans the project for manifests. Layout-independent; bounded. */
    fun rebuild(): String {
        val found = LinkedHashMap<String, ProblemRecord>()
        val conflicts = ArrayList<String>()
        var entries = 0
        var truncated = false
        val skipDirs = setOf(".git", ".idea", ".companion", "node_modules", ".venv", "venv", "__pycache__", "build", "dist", "out", STAGING_DIR, SAMPLES_DIR)
        try {
            Files.walkFileTree(root, setOf(), 32, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink || attrs.isOther) return FileVisitResult.SKIP_SUBTREE
                    if (dir != root && dir.fileName.toString() in skipDirs) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++entries > MAX_SCAN_ENTRIES) { truncated = true; return FileVisitResult.TERMINATE }
                    if (file.fileName.toString() != MANIFEST || !attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val m = readManifest(file) ?: return FileVisitResult.CONTINUE
                    try {
                        val testsDir = file.parent
                        val relTests = rel(testsDir)
                        if (m.testsDir != relTests) { conflicts.add("${m.problemKey}: manifest at $relTests declares ${m.testsDir}"); return FileVisitResult.CONTINUE }
                        val solution = SafePath.resolveRelative(root, m.solutionPath)
                        val rec = ProblemRecord(m, solution, testsDir)
                        val prev = found[m.problemKey]
                        if (prev != null) { conflicts.add("${m.problemKey}: duplicate manifests at ${rel(prev.testsDir)} and $relTests"); return FileVisitResult.CONTINUE }
                        found[m.problemKey] = rec
                    } catch (e: UnsafePathException) {
                        conflicts.add("${m.problemKey}: ${e.message}")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (e: IOException) {
            log.warn("rebuild scan failed", e)
        }
        records.clear()
        records.putAll(found)
        writeIndex()
        val note = buildString {
            append("restored ${found.size} problem(s)")
            if (truncated) append("; scan truncated at $MAX_SCAN_ENTRIES entries (partial)")
            if (conflicts.isNotEmpty()) append("; ${conflicts.size} conflict(s): ").append(conflicts.joinToString(" | "))
        }
        lastRebuildNote = note
        notifyChanged()
        return note
    }

    private fun writeIndex() = indexLock.withLock {
        try {
            Files.createDirectories(companionDir)
            val entries = all().map { IndexEntry(it.problemKey, it.manifest.solutionPath, it.manifest.testsDir, it.manifest.metadataRevision) }
            Json.writeAtomic(indexPath, IndexFile(problems = entries))
        } catch (e: IOException) {
            log.warn("index write failed", e)
        }
    }

    private fun readManifest(path: Path): ProblemManifest? {
        val m = Json.read<ProblemManifest>(path) ?: return null
        if (m.kind != KIND_PROBLEM || m.schemaVersion != SCHEMA_VERSION) return null
        if (m.problemKey.isEmpty() || m.solutionPath.isEmpty() || m.testsDir.isEmpty()) return null
        return m
    }

    private fun rel(p: Path): String = SafePath.toRelativeString(root, p)

    private fun notifyChanged() {
        if (!project.isDisposed) project.messageBus.syncPublisher(ProblemsListener.TOPIC).problemsChanged()
    }

    override fun dispose() {
        records.clear()
    }

    companion object {
        const val COMPANION_DIR = ".companion"
        const val MANIFEST = "problem.json"
        const val MANIFEST_PREVIOUS = "problem.previous.json"
        const val SAMPLE_SET = "sample-set.json"
        const val SAMPLES_DIR = "samples"
        const val STAGING_DIR = ".staging"
        const val MAX_SCAN_ENTRIES = 100_000
        val SAMPLE_NAME = Regex("^sample_[0-9]{2,}$")
        val CUSTOM_IN = Regex("^custom_([0-9]{2,})\\.in$")

        fun getInstance(project: Project): ProblemRepository = project.getService(ProblemRepository::class.java)
    }
}
