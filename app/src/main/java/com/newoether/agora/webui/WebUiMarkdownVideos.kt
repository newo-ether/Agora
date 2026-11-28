package com.newoether.agora.webui

import android.content.Context
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.tool.FileTransferEndpoint
import com.newoether.agora.tool.TRANSFER_MAX_BYTES
import com.newoether.agora.tool.transferLong
import com.newoether.agora.util.ShellClient
import com.newoether.agora.ui.chat.message.LOCAL_SANDBOX_SERVER
import com.newoether.agora.ui.chat.message.LocalFileResolution
import com.newoether.agora.ui.chat.message.MarkdownDocumentReferenceHandler
import com.newoether.agora.ui.chat.message.MarkdownDestination
import com.newoether.agora.ui.chat.message.resolveMarkdownDestination
import com.newoether.agora.ui.chat.message.embedHtmlVideoTags
import com.newoether.agora.ui.chat.message.embedStandaloneVideoPaths
import com.newoether.agora.ui.chat.message.markdownImageLink
import com.newoether.agora.ui.chat.message.markdownReferenceDefinitions
import com.newoether.agora.ui.chat.message.markdownVideoLink
import com.newoether.agora.ui.chat.message.namesLinkDestination
import com.newoether.agora.ui.chat.message.parseShellMediaLink
import com.newoether.agora.ui.chat.message.resolveLocalFileTarget
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * One Markdown video the browser may read. A readable root becomes a plain file; any other Conch
 * device streams through the authenticated file-transfer envelope, one bounded chunk at a time.
 */
internal sealed interface WebUiMarkdownVideoSource {
    data class Local(val file: File, val mimeType: String) : WebUiMarkdownVideoSource
    data class Conch(
        val endpoint: FileTransferEndpoint,
        val path: String,
        val size: Long,
        val mimeType: String,
    ) : WebUiMarkdownVideoSource
}

/**
 * Only a parsed image destination inside the owning message grants a video read, exactly like the
 * Markdown image route: prose, code and ordinary links never become a file path. A line that is
 * only a video destination and a `<video src>` element count too, because the native renderer
 * rewrites both into that image node ([embedStandaloneVideoPaths], [embedHtmlVideoTags]); the
 * rewrite moves offsets, so that fallback checks the whole document.
 */
internal fun ChatMessage.ownsMarkdownVideo(link: String, sourceStart: Int, document: String): Boolean {
    if (sourceStart < 0) return false
    val source = markdownDocumentText(document) ?: return false
    if (sourceStart > source.length) return false
    val destination = markdownVideoLink(link) ?: return false
    fun owns(text: String, from: Int): Boolean {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
        val references = MarkdownDocumentReferenceHandler(markdownReferenceDefinitions(root, text))
        fun contains(node: ASTNode): Boolean =
            if (node.type == MarkdownElementTypes.IMAGE) {
                node.startOffset >= from &&
                    markdownImageLink(text, node, references)?.let(::markdownVideoLink) == destination
            } else node.children.any(::contains)
        return contains(root)
    }
    if (owns(source, sourceStart)) return true
    val embedded = source.embedHtmlVideoTags().embedStandaloneVideoPaths()
    return embedded != source && owns(embedded, 0)
}

/**
 * A Markdown text link or bare path to a local image or video, as native [MarkdownLinkOpener] opens
 * it in the media viewer. Only a readable local root the owning message spells out is admitted;
 * other files and other devices stay inert in the browser.
 */
internal fun ChatMessage.ownsMarkdownLinkedMedia(link: String, document: String): Boolean {
    if (linkedMediaMimeType(link) == null || resolveMarkdownDestination(link) !is MarkdownDestination.File) return false
    val source = markdownDocumentText(document) ?: return false
    return namesLinkDestination(source, link)
}

/** Resolves a browser video read from the message that displays it, never from a caller path. */
internal class WebUiMarkdownVideos(
    private val context: Context,
    private val devices: () -> List<ShellDeviceConfig>,
    private val sandboxEnabled: () -> Boolean = { false },
    private val sandbox: () -> SandboxManager? = { null },
    private val loadMessage: suspend (String, String) -> ChatMessage?,
) {
    suspend fun open(
        conversationId: String,
        messageId: String,
        link: String,
        sourceStart: Int = 0,
        document: String = "answer",
    ): WebUiMarkdownVideoSource? {
        if (link.length > MAX_LINK_LENGTH) return null
        val destination = markdownVideoLink(link) ?: return null
        val message = loadMessage(conversationId, messageId)?.takeIf { it.id == messageId } ?: return null
        val owned = withContext(Dispatchers.Default) { message.ownsMarkdownVideo(destination, sourceStart, document) }
        if (!owned) return null
        val mimeType = videoMimeType(destination)
        val shell = parseShellMediaLink(destination)
        if (shell == null || shell.server.equals(LOCAL_SANDBOX_SERVER, ignoreCase = true)) {
            val target = resolveMarkdownDestination(destination) as? MarkdownDestination.File
            if (target != null) {
                val file = withContext(Dispatchers.IO) {
                    (resolveLocalFileTarget(context, target.target) as? LocalFileResolution.Ready)?.file
                } ?: return null
                return WebUiMarkdownVideoSource.Local(file, mimeType)
            }
            if (shell == null || !sandboxEnabled()) return null
            val manager = sandbox()?.takeIf { it.isAvailable() } ?: return null
            val file = withContext(Dispatchers.IO) {
                runCatching { manager.resolveHostFile(shell.path) }.getOrNull()?.takeIf { it.isFile }
            } ?: return null
            return WebUiMarkdownVideoSource.Local(file, mimeType)
        }
        val device = devices().firstOrNull { it.name.equals(shell.server, ignoreCase = true) } ?: return null
        if (device.type.equals("ssh", ignoreCase = true)) return null
        return withContext(Dispatchers.IO) {
            try {
                val endpoint = FileTransferEndpoint(
                    device.name,
                    client = ShellClient(device.serverUrl.trimEnd('/'), device.apiKey, device.conchPublicKey),
                )
                endpoint.checkSupport()
                val info = endpoint.info(shell.path)
                val size = info.transferLong("size")
                if (size !in 1..TRANSFER_MAX_BYTES) return@withContext null
                WebUiMarkdownVideoSource.Conch(endpoint, shell.path, size, mimeType)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Resolves a text-link image or video the owning message names; see [ownsMarkdownLinkedMedia]. */
    suspend fun openLinked(
        conversationId: String,
        messageId: String,
        link: String,
        document: String = "answer",
    ): WebUiMarkdownVideoSource.Local? {
        if (link.length > MAX_LINK_LENGTH) return null
        val mimeType = linkedMediaMimeType(link) ?: return null
        val message = loadMessage(conversationId, messageId)?.takeIf { it.id == messageId } ?: return null
        val owned = withContext(Dispatchers.Default) { message.ownsMarkdownLinkedMedia(link, document) }
        if (!owned) return null
        val target = resolveMarkdownDestination(link) as? MarkdownDestination.File ?: return null
        val file = withContext(Dispatchers.IO) {
            (resolveLocalFileTarget(context, target.target) as? LocalFileResolution.Ready)?.file
        } ?: return null
        return WebUiMarkdownVideoSource.Local(file, mimeType)
    }

    private companion object {
        const val MAX_LINK_LENGTH = 8_192
    }
}

/** Browser-safe image and video types a text link may open; SVG stays out because it can script. */
internal fun linkedMediaMimeType(link: String): String? {
    val extension = link.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    return LINKED_IMAGE_MIME_TYPES[extension] ?: VIDEO_MIME_TYPES[extension]
}

private val LINKED_IMAGE_MIME_TYPES = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "avif" to "image/avif",
)

/** Container-to-MIME map for the video extensions Markdown destinations may name. */
internal fun videoMimeType(link: String): String {
    val extension = link.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    return VIDEO_MIME_TYPES[extension] ?: "video/mp4"
}

private val VIDEO_MIME_TYPES = mapOf(
    "mp4" to "video/mp4",
    "m4v" to "video/mp4",
    "webm" to "video/webm",
    "mov" to "video/quicktime",
    "mkv" to "video/x-matroska",
    "avi" to "video/x-msvideo",
    "3gp" to "video/3gpp",
    "ogv" to "video/ogg",
)
