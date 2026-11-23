package com.newoether.agora.ui.chat.message

import android.content.Context
import android.os.Environment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import com.newoether.agora.sandbox.SandboxSharedStorageAccess
import java.io.File

/**
 * One destination a Markdown link can open. Only network destinations and the two storage roots
 * the app can read directly are admitted; everything else stays inert text.
 */
internal sealed interface MarkdownLinkTarget {
    data class Remote(val url: String) : MarkdownLinkTarget
    data class Local(val file: LocalFileTarget) : MarkdownLinkTarget
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
)

/** Characters that end a bare path written in prose, including full-width punctuation. */
private const val PATH_DELIMITERS = " \t\r\n\"'`<>()[]{}|，。；：！？、）】》”’"

private val PATH_TRAILING_CHARS = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')

/**
 * Classifies a link destination. Network schemes stay with the platform handler; sandbox and
 * shared-storage paths resolve to host files. Unsupported schemes return null (inert text).
 */
internal fun classifyMarkdownLink(raw: String): MarkdownLinkTarget? {
    val link = raw.trim().removeSurrounding("<", ">")
    if (link.isEmpty()) return null
    parseShellMediaLink(link)?.let { shell ->
        return if (shell.server.equals(LOCAL_SANDBOX_SERVER, ignoreCase = true)) {
            sandboxTarget(shell.path)
        } else null
    }
    val lower = link.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
        return MarkdownLinkTarget.Remote(link)
    }
    if (link == "/home/agora") return null
    if (link.startsWith(SANDBOX_HOME_PREFIX)) return sandboxTarget(link)
    SHARED_STORAGE_PREFIXES.firstOrNull { link.startsWith(it) }?.let { prefix ->
        return sharedTarget(link.removePrefix(prefix.trimEnd('/')))
    }
    return null
}

private fun sandboxTarget(path: String): MarkdownLinkTarget? {
    // A `shell://` path is the host path on the sandbox device: only the guest home is readable.
    if (!path.startsWith(SANDBOX_HOME_PREFIX)) return null
    val relative = path.removePrefix(SANDBOX_HOME_PREFIX)
    return relative.takeIf { it.isNotEmpty() && ".." !in it.split('/') }
        ?.let { MarkdownLinkTarget.Local(LocalFileTarget(LocalFileRoot.SANDBOX_HOME, it)) }
}

private fun sharedTarget(path: String): MarkdownLinkTarget? {
    val relative = path.trimStart('/')
    return relative.takeIf { it.isNotEmpty() && ".." !in it.split('/') }
        ?.let { MarkdownLinkTarget.Local(LocalFileTarget(LocalFileRoot.SHARED_STORAGE, it)) }
}

/** Resolves a link destination through the same roots the sandbox mounts expose. */
internal fun resolveLocalFileTarget(context: Context, target: LocalFileTarget): LocalFileResolution {
    val root = when (target.root) {
        LocalFileRoot.SANDBOX_HOME -> File(context.applicationContext.filesDir, "sandbox-home")
        LocalFileRoot.SHARED_STORAGE -> {
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
    val supported = candidate.startsWith(SANDBOX_HOME_PREFIX) || SHARED_STORAGE_PREFIXES.any {
        candidate.startsWith(it)
    }
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
