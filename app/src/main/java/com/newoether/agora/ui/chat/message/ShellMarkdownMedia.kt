package com.newoether.agora.ui.chat.message

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.model.MarkdownImage
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.remote.RemoteImageCache
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.tool.ConchBackend
import com.newoether.agora.tool.ToolImageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Display-only loader for `shell://` Markdown images in Agora-owned conversations.
 *
 * Bytes come from the configured device (Local Sandbox file or Conch `/file/image`), are inspected
 * without model preparation, and land in a bounded disposable cache. Nothing here enters Provider
 * context, Room, or the message graph. Loading is demand-driven by the rendered image slot.
 */
internal class ShellMarkdownMedia(
    private val context: Context,
    private val devices: () -> List<ShellDeviceConfig>,
    private val sandboxEnabled: () -> Boolean,
    private val sandbox: () -> SandboxManager?,
) {
    // Created on the first rendered shell image, never during ViewModel construction.
    private val directory by lazy { File(context.applicationContext.cacheDir, "markdown-media") }
    private val store by lazy { ToolImageStore(context, directory, prepareForModel = false) }
    private val cache by lazy { RemoteImageCache(directory) }
    private val states = mutableStateMapOf<String, MarkdownImage>()
    private val inFlight = HashSet<String>()
    private val lock = Any()

    /** Current slot state for a `shell://` link, pending until loaded; null for other links. */
    fun peek(link: String): MarkdownImage? {
        val target = parseShellMediaLink(link) ?: return null
        return states[target.key] ?: MarkdownImage()
    }

    /**
     * Loads [link] once per process unless its cached file disappeared. A failed slot keeps its
     * failed state while a later composition retries, so re-entry never flickers back to pending.
     */
    suspend fun load(link: String) {
        val target = parseShellMediaLink(link) ?: return
        val key = target.key
        val current = states[key]
        val present = withContext(Dispatchers.IO) { current?.attachment?.path?.let { File(it).isFile } == true }
        if (present) return
        synchronized(lock) { if (!inFlight.add(key)) return }
        try {
            val attachment = try {
                cache.load(key) { fetch(target) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            states[key] = if (attachment != null) MarkdownImage(attachment) else MarkdownImage(failed = true)
        } finally {
            synchronized(lock) { inFlight.remove(key) }
        }
    }

    private suspend fun fetch(target: ShellMediaLink): ToolImageAttachment {
        if (target.server.equals(LOCAL_SANDBOX_SERVER, ignoreCase = true)) {
            if (!sandboxEnabled()) throw IOException("Local Sandbox is disabled")
            val manager = sandbox() ?: throw IOException("Local Sandbox is unavailable")
            if (!manager.isAvailable()) throw IOException("Local Sandbox is not installed")
            val file = manager.resolveHostFile(target.path) ?: throw IOException("Local Sandbox is unavailable")
            if (!file.isFile) throw IOException("Not a regular file")
            if (file.length() !in 1..ToolImageStore.MAX_IMAGE_BYTES) throw IOException("Image size is out of bounds")
            val mime = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeFile(file.absolutePath, it) }
                .outMimeType?.takeIf { it.startsWith("image/") }
                ?: throw IOException("Unsupported image type")
            return file.inputStream().use { store.persistStream(it, mime, "markdown") }
        }
        val device = devices().firstOrNull { it.name.equals(target.server, ignoreCase = true) }
            ?: throw IOException("Unknown shell device")
        if (device.type == "ssh") throw IOException("SSH devices do not serve images")
        val result = ConchBackend(device).viewImage(target.path)
        result.error?.let { throw IOException(it) }
        return store.persistBase64(result.data, result.mimeType, "markdown")
    }
}

/** Provided by native chat only; Remote sessions keep their own image ownership. */
internal val LocalShellMarkdownMedia = staticCompositionLocalOf<ShellMarkdownMedia?> { null }
