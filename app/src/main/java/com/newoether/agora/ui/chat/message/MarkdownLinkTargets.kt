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
