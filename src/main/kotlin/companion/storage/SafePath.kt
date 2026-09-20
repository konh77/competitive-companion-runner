package companion.storage

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class UnsafePathException(message: String) : Exception(message)

/** Path safety rules shared by generation, deletion, restore and settings validation. */
object SafePath {
    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    private val PLAIN_ID = Regex("^[a-z0-9_-]+$")
    private val SEGMENT = Regex("^[^/\\\\:*?\"<>|\\p{Cntrl}]+$")
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

    fun isReserved(segment: String): Boolean {
        val base = segment.substringBefore('.')
        return RESERVED.contains(base.uppercase())
    }

    /**
     * Encodes an AtCoder ID for use as a file-name component. IDs that are already
     * lower-case ASCII `[a-z0-9_-]` and not a Windows reserved name are used as-is.
     * Anything else becomes `~` + lower-case unpadded Base32 of the ASCII bytes, which
     * cannot collide with a plain ID because `~` is not allowed in IDs.
     */
    fun safeComponent(id: String): String {
        if (PLAIN_ID.matches(id) && !isReserved(id)) return id
        return "~" + base32(id.toByteArray(Charsets.US_ASCII))
    }

    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(BASE32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(BASE32[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /**
     * Validates a project-relative path string and resolves it under [root].
     * Rejects absolute paths, empty / `.` / `..` segments, reserved names and illegal characters.
     */
    fun resolveRelative(root: Path, relative: String): Path {
        if (relative.isEmpty()) throw UnsafePathException("empty path")
        if (relative.startsWith("/") || relative.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(relative)) {
            throw UnsafePathException("absolute path not allowed: $relative")
        }
        val segments = relative.split('/', '\\')
        var p = root
        for (seg in segments) {
            validateSegment(seg)
            p = p.resolve(seg)
        }
        val normalized = p.normalize()
        if (!normalized.startsWith(root)) throw UnsafePathException("path escapes project root: $relative")
        return normalized
    }

    fun validateSegment(seg: String) {
        if (seg.isEmpty() || seg == "." || seg == "..") throw UnsafePathException("invalid segment '$seg'")
        if (!SEGMENT.matches(seg)) throw UnsafePathException("illegal characters in segment '$seg'")
        if (seg.endsWith(" ") || seg.endsWith(".")) throw UnsafePathException("segment must not end with space or dot: '$seg'")
        if (isReserved(seg)) throw UnsafePathException("reserved name '$seg'")
    }

    /**
     * Ensures no existing ancestor of [target] (up to and excluding [root]) and [target] itself
     * is a symbolic link or other reparse point.
     */
    fun checkNoLinks(root: Path, target: Path) {
        var p: Path? = target
        while (p != null && p != root && p.startsWith(root)) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink || attrs.isOther) throw UnsafePathException("link in path: $p")
            }
            p = p.parent
        }
    }

    fun toRelativeString(root: Path, path: Path): String =
        root.relativize(path).toString().replace('\\', '/')
}
