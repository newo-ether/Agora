package com.newoether.agora.ui.chat.message

import android.content.Context
import android.os.Environment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import com.newoether.agora.sandbox.SandboxSharedStorageAccess
import java.io.File

/**
 * One normalized Markdown destination. Links, images and videos all classify through
 * [resolveMarkdownDestination], so one spelling of a path behaves the same in every component.
 */
internal sealed interface MarkdownDestination {
    /** A network destination the platform handler opens. */
    data class Web(val url: String) : MarkdownDestination

    /** A file this app reads through one of its own storage roots. */
    data class File(val target: LocalFileTarget) : MarkdownDestination

    /** A file on another shell device; only that device can serve its bytes. */
    data class Device(val link: ShellMediaLink) : MarkdownDestination
}

/** Stable identity for snapshot caches, independent of how the destination was spelled. */
internal fun MarkdownDestination.identity(): String = when (this) {
    is MarkdownDestination.Web -> "web:$url"
    is MarkdownDestination.File -> "file:${target.root}:${target.relativePath}"
    is MarkdownDestination.Device -> "device:${link.key}"
}

internal enum class LocalFileRoot {
    /** Guest `/home/agora`, backed by `filesDir/sandbox-home`. */
    SANDBOX_HOME,

    /** Android shared storage, reachable while all-files access is granted. */
    SHARED_STORAGE,
}

internal data class LocalFileTarget(val root: LocalFileRoot, val relativePath: String)

internal sealed interface LocalFileResolution {
    data class Ready(val file: File) : LocalFileResolution
    data object NotFound : LocalFileResolution
    data object NeedsSharedStorageAccess : LocalFileResolution
    data object Unavailable : LocalFileResolution
}

private const val SANDBOX_HOME_PREFIX = "/home/agora/"
private val SHARED_STORAGE_PREFIXES = listOf(
    "/mnt/shared/",
    "/storage/emulated/0/",
    "/storage/self/primary/",
    "/sdcard/",
)

/** Characters that end a bare path written in prose, including full-width punctuation. */
private const val PATH_DELIMITERS = " \t\r\n\"'`<>()[]{}|，。；：！？、）】》”’"

private val PATH_TRAILING_CHARS = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')

/**
 * Classifies a Markdown destination into the one file or address it names. A `shell://` path names
 * a shell device, except on the Local Sandbox, where its home and shared storage join this app's
 * own roots and everything else stays a sandbox-served path. Unsupported schemes return null and
 * stay inert text.
 */
internal fun resolveMarkdownDestination(raw: String): MarkdownDestination? {
    val link = raw.trim().removeSurrounding("<", ">")
    if (link.isEmpty()) return null
    parseShellMediaLink(link)?.let { shell ->
        if (!shell.server.equals(LOCAL_SANDBOX_SERVER, ignoreCase = true)) return MarkdownDestination.Device(shell)
        return localDestination(shell.path) ?: MarkdownDestination.Device(shell)
    }
    val lower = link.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
        return MarkdownDestination.Web(link)
    }
    // `file:` names a path on this device; any authority in the URL is dropped like Android does.
    return localPathDestination(if (lower.startsWith("file:")) fileUrlPath(link) else link)
}

/** A hand-written path may percent-encode a space; a malformed escape keeps its literal `%`. */
private fun localPathDestination(path: String): MarkdownDestination? =
    localDestination(percentDecodePath(path) ?: path)

/** Path of a `file:` URL: `file:/p`, `file:///p` and `file://host/p` all fold to `/p`. */
internal fun fileUrlPath(link: String): String {
    val rest = link.substring("file:".length)
    if (!rest.startsWith("//")) return rest
    val afterSlashes = rest.substring(2)
    if (afterSlashes.startsWith("/")) return afterSlashes
    val path = afterSlashes.substringAfter('/', "")
    return if (path.isEmpty()) "" else "/$path"
}

/** Cheap check before parsing: some supported destination spelling appears in [text]. */
internal fun mentionsMarkdownDestination(text: String): Boolean =
    text.contains("shell://", ignoreCase = true) ||
        text.contains("file://", ignoreCase = true) ||
        text.contains("http://", ignoreCase = true) ||
        text.contains("https://", ignoreCase = true) ||
        SANDBOX_HOME_PREFIX in text || SHARED_STORAGE_PREFIXES.any { it in text }

/** A guest path as the file this app opens: the sandbox home or shared storage. */
private fun localDestination(path: String): MarkdownDestination? {
    if (path == "/home/agora") return null
    if (path.startsWith(SANDBOX_HOME_PREFIX)) {
        return localFile(LocalFileRoot.SANDBOX_HOME, path.removePrefix(SANDBOX_HOME_PREFIX))
    }
    SHARED_STORAGE_PREFIXES.firstOrNull { path.startsWith(it) }?.let { prefix ->
        return localFile(LocalFileRoot.SHARED_STORAGE, path.removePrefix(prefix.trimEnd('/')))
    }
    return null
}

private fun localFile(root: LocalFileRoot, relative: String): MarkdownDestination? {
    val path = relative.trimStart('/')
    return path.takeIf { it.isNotEmpty() && ".." !in it.split('/') }
        ?.let { MarkdownDestination.File(LocalFileTarget(root, it)) }
}

/** Resolves a link destination through the same roots the sandbox mounts expose. */
internal fun resolveLocalFileTarget(context: Context, target: LocalFileTarget): LocalFileResolution {
    val root = when (target.root) {
        LocalFileRoot.SANDBOX_HOME -> File(context.applicationContext.filesDir, "sandbox-home")
        LocalFileRoot.SHARED_STORAGE -> {
            if (!SandboxSharedStorageAccess.isSharedStorageMounted) return LocalFileResolution.Unavailable
            if (!SandboxSharedStorageAccess.isGranted(context)) {
                return LocalFileResolution.NeedsSharedStorageAccess
            }
            Environment.getExternalStorageDirectory()
        }
    }
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
        ?: return LocalFileResolution.Unavailable
    val canonical = runCatching { File(canonicalRoot, target.relativePath).canonicalFile }.getOrNull()
        ?: return LocalFileResolution.Unavailable
    val insideRoot = canonical.path == canonicalRoot.path ||
        canonical.path.startsWith(canonicalRoot.path + File.separator)
    if (!insideRoot) return LocalFileResolution.Unavailable
    return if (canonical.isFile) LocalFileResolution.Ready(canonical) else LocalFileResolution.NotFound
}

/**
 * Destination under [offset], taken from a link annotation when the parser produced one and
 * otherwise from the bare path spelling in prose (`/home/agora/…`, `/mnt/shared/…`).
 */
internal fun markdownLinkAt(content: AnnotatedString, offset: Int): String? {
    if (content.isEmpty()) return null
    val index = offset.coerceIn(0, content.length - 1)
    content.getLinkAnnotations(index, index + 1).firstOrNull()?.let { range ->
        return when (val link = range.item) {
            is LinkAnnotation.Url -> link.url
            is LinkAnnotation.Clickable -> link.tag
            else -> null
        }
    }
    return markdownBarePathAt(content.text, index)
}

/** Sentence marks the GFM lexer leaves at the end of a bare URL, ASCII and full-width. */
private const val LINK_TRAILING_PUNCTUATION = ".,:;!?\"'`…。，、；：！？》」』】”’"

/** Closing brackets that belong to the URL only while the URL keeps their opener. */
private val LINK_BRACKET_PAIRS = mapOf(
    ')' to '(',
    ']' to '[',
    '}' to '{',
    '）' to '（',
    '】' to '【',
    '》' to '《',
    '」' to '「',
    '』' to '『',
)

/**
 * Punctuation that never belongs to a bare URL: general punctuation (dashes, curly quotes, `…`),
 * CJK symbols and punctuation (`、。「」『』【】《》`) and the full-width ASCII punctuation forms
 * (`（）［］！？，：；`). Letters, digits and CJK ideographs stay valid path characters.
 */
internal fun isUrlStopCharacter(char: Char): Boolean = when (char.code) {
    in 0x2010..0x2027, in 0x2030..0x205E, in 0x3000..0x303F,
    in 0xFF01..0xFF0F, in 0xFF1A..0xFF20, in 0xFF3B..0xFF40, in 0xFF5B..0xFF65 -> true
    else -> false
}

/**
 * End of a bare URL written in prose. GFM ends an autolink only at whitespace, so the URL first
 * stops at the first [isUrlStopCharacter] (prose usually continues with no space after `，` or
 * `）`), then trailing ASCII punctuation and unbalanced brackets are trimmed.
 */
internal fun bareUrlEnd(text: CharSequence, start: Int, end: Int): Int {
    var stop = start
    while (stop < end && !text[stop].isWhitespace() && !isUrlStopCharacter(text[stop])) stop++
    return trimLinkTail(text, start, stop)
}

/** End of the link that stops before trailing punctuation; balanced brackets stay inside. */
internal fun trimLinkTail(text: CharSequence, start: Int, end: Int): Int {
    var trimmed = end
    while (trimmed > start) {
        val last = text[trimmed - 1]
        val opener = LINK_BRACKET_PAIRS[last]
        when {
            opener != null -> {
                var closers = 0
                var openers = 0
                for (index in start until trimmed) {
                    if (text[index] == last) {
                        closers++
                    } else if (text[index] == opener) {
                        openers++
                    }
                }
                if (closers <= openers) return trimmed
                trimmed--
            }
            last in LINK_TRAILING_PUNCTUATION -> trimmed--
            else -> return trimmed
        }
    }
    return trimmed
}

/**
 * The GFM lexer ends a bare URL at whitespace and trims only `.,:;!?"'*_~]` from it, so a closing
 * bracket, a dash or a CJK sentence mark typed right after the URL lands inside the link range and
 * inside its destination. Shorten both here so the tap target, the highlight and the opened address
 * never keep punctuation the author wrote after the link. A bare URL (its visible text is the tail
 * of its destination) also ends at the first [isUrlStopCharacter]; an explicit `[label](url)` keeps
 * its label and only loses trailing punctuation.
 */
internal fun AnnotatedString.trimMarkdownLinkTail(): AnnotatedString {
    if (!hasLinkAnnotations(0, length)) return this
    return mapAnnotations { range ->
        val item = range.item
        if (item !is LinkAnnotation) return@mapAnnotations range
        val shown = text.substring(range.start, range.end)
        if (item is LinkAnnotation.Url && shown.isNotEmpty() && item.url.endsWith(shown)) {
            val end = bareUrlEnd(text, range.start, range.end)
            if (end == range.end) return@mapAnnotations range
            if (end == range.start) return@mapAnnotations AnnotatedString.Range(item, range.start, range.start, range.tag)
            val url = item.url.dropLast(range.end - end)
            return@mapAnnotations AnnotatedString.Range(item.copy(url = url), range.start, end, range.tag)
        }
        val end = trimLinkTail(text, range.start, range.end)
        val destination = if (item is LinkAnnotation.Url) {
            val url = item.url
            val urlEnd = trimLinkTail(url, 0, url.length)
            if (urlEnd == url.length) item else item.copy(url = url.substring(0, urlEnd))
        } else {
            item
        }
        if (end == range.end && destination === item) {
            range
        } else {
            AnnotatedString.Range(destination, range.start, end, range.tag)
        }
    }
}

internal fun markdownBarePathAt(text: CharSequence, offset: Int): String? {
    if (text.isEmpty()) return null
    val index = offset.coerceIn(0, text.length - 1)
    if (text[index] in PATH_DELIMITERS) return null
    var start = index
    while (start > 0 && text[start - 1] !in PATH_DELIMITERS) start--
    var end = index + 1
    while (end < text.length && text[end] !in PATH_DELIMITERS) end++
    val candidate = text.subSequence(start, end).toString().trimEnd(*PATH_TRAILING_CHARS)
    if (candidate.isEmpty()) return null
    val supported = candidate.startsWith(SANDBOX_HOME_PREFIX) ||
        candidate.startsWith("file://", ignoreCase = true) ||
        SHARED_STORAGE_PREFIXES.any { candidate.startsWith(it) }
    return candidate.takeIf { supported }
}

/**
 * Whether [text] spells [link] as one whole token: a bare path in prose, a link destination or a
 * reference definition. A longer path that merely starts with [link] does not count.
 */
internal fun namesLinkDestination(text: CharSequence, link: String): Boolean {
    if (link.isEmpty()) return false
    var from = 0
    while (true) {
        val index = text.indexOf(link, from)
        if (index < 0) return false
        // Sentence marks after a bare path end it only when prose (or the text) ends after them.
        var end = index + link.length
        while (end < text.length && text[end] in PATH_TRAILING_CHARS) end++
        val opens = index == 0 || text[index - 1] in PATH_DELIMITERS
        val closes = end == text.length || text[end] in PATH_DELIMITERS
        if (opens && closes) return true
        from = index + 1
    }
}
