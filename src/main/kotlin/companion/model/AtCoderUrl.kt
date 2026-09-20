package companion.model

import java.net.URI
import java.net.URISyntaxException

/** Validated AtCoder problem URL. [canonicalUrl] is the problemKey. */
data class AtCoderUrl(val contestId: String, val taskId: String) {
    val canonicalUrl: String get() = "https://atcoder.jp/contests/$contestId/tasks/$taskId"
    val submitUrl: String get() = "https://atcoder.jp/contests/$contestId/submit?taskScreenName=$taskId"

    /** Display-only short index: text after the last `_`, or the whole taskId when that is empty. */
    val taskIndex: String
        get() {
            val i = taskId.lastIndexOf('_')
            val tail = if (i < 0) taskId else taskId.substring(i + 1)
            return tail.ifEmpty { taskId }
        }

    sealed class ParseResult {
        data class Ok(val url: AtCoderUrl) : ParseResult()
        data class Unsupported(val reason: String) : ParseResult()
    }

    companion object {
        private val PATH = Regex("^/contests/([A-Za-z0-9_-]{1,128})/tasks/([A-Za-z0-9_-]{1,128})$")

        fun parse(raw: String): ParseResult {
            if (raw.isEmpty() || raw.length > 2048) return ParseResult.Unsupported("url length")
            val uri = try { URI(raw) } catch (e: URISyntaxException) { return ParseResult.Unsupported("not a URI") }
            if (uri.scheme == null || !uri.scheme.equals("https", ignoreCase = true)) return ParseResult.Unsupported("scheme")
            if (uri.rawUserInfo != null) return ParseResult.Unsupported("userinfo")
            if (uri.host == null || !uri.host.equals("atcoder.jp", ignoreCase = true)) return ParseResult.Unsupported("host")
            if (uri.port != -1 && uri.port != 443) return ParseResult.Unsupported("port")
            var path = uri.rawPath ?: return ParseResult.Unsupported("path")
            if (path.contains('%')) return ParseResult.Unsupported("percent-encoded path")
            if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
            val m = PATH.matchEntire(path) ?: return ParseResult.Unsupported("path shape")
            return ParseResult.Ok(AtCoderUrl(m.groupValues[1], m.groupValues[2]))
        }
    }
}
