package com.newoether.agora.ui.chat.message

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.newoether.agora.api.HttpClient
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.model.MarkdownImage
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.remote.RemoteImageCache
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.tool.ConchBackend
import com.newoether.agora.tool.ToolImageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Display-only loader for shell and HTTP(S) Markdown images in Agora-owned conversations.
 *
 * Bytes are inspected without model preparation and land in immutable first-read snapshots in
 * app-private files. The receipt retains an occurrence identity and content hash across process
 * restarts; neither bytes nor receipt enter Provider context. Loading starts with the admitted parse.
 */
internal class ShellMarkdownMedia(
    private val context: Context,
    private val devices: () -> List<ShellDeviceConfig>,
    private val sandboxEnabled: () -> Boolean,
    private val sandbox: () -> SandboxManager?,
    private val scope: CoroutineScope,
) {
    // Created on the first rendered image, never during ViewModel construction.
    private val directory by lazy { File(context.applicationContext.filesDir, "markdown-media") }
    private val store by lazy { ToolImageStore(context, directory, prepareForModel = false) }
    private val cache by lazy { MarkdownImageSnapshotStore(directory) }
    private val previewDirectory by lazy { File(context.applicationContext.cacheDir, "markdown-media") }
    private val previewStore by lazy { ToolImageStore(context, previewDirectory, prepareForModel = false) }
    private val previewCache by lazy { RemoteImageCache(previewDirectory) }
    private val states = mutableStateMapOf<String, MarkdownImage>()
    private val inFlight = HashMap<String, Deferred<Unit>>()
    private val lock = Any()

    /** Current slot state. Destination alone determines geometry, never snapshot identity. */
    fun peek(link: String, occurrence: String? = null): MarkdownImage? {
        val target = markdownImageSnapshotKey(link) ?: return null
        return occurrence?.let { states["$it\n$target"] } ?: MarkdownImage()
    }

    /** Each message occurrence freezes its first successful read, independently of later writes. */
    suspend fun load(link: String, occurrence: String) {
        startLoad(link, occurrence)?.await()
    }

    /** Admit every parsed slot immediately, without serializing later slots behind network IO. */
    fun preload(link: String, occurrence: String) {
        startLoad(link, occurrence)
    }

    private fun startLoad(link: String, occurrence: String): Deferred<Unit>? {
        val target = markdownImageSnapshotKey(link) ?: return null
        val key = "$occurrence\n$target"
        if (states[key]?.attachment != null) return null
        // Parsing admits the read. A streaming reparse or an offscreen slot must not cancel it.
        val task = synchronized(lock) {
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                val snapshot = try {
                    cache.load(key) { fetch(link) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                withContext(Dispatchers.Main.immediate) {
                    states[key] = if (snapshot != null) {
                        MarkdownImage(snapshot.image, snapshotSha256 = snapshot.sha256)
                    } else MarkdownImage(failed = true)
                }
            }.also { job ->
                inFlight[key] = job
                job.invokeOnCompletion { synchronized(lock) { inFlight.remove(key, job) } }
            }
        }
        task.start()
        return task
    }

    /** Explicit preview capabilities supply their validated message occurrence identity. */
    suspend fun previewImage(link: String, occurrence: String? = null): ToolImageAttachment? {
        val target = markdownImageSnapshotKey(link) ?: return null
        return try {
            if (occurrence == null) {
                // HTML previews retain the existing disposable shell-only resource cache.
                val shell = parseShellMediaLink(link) ?: return null
                previewCache.load(shell.key) { fetchShell(shell, previewStore) }
            } else cache.load("$occurrence\n$target") { fetch(link) }.image
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetch(link: String): ToolImageAttachment {
        parseShellMediaLink(link)?.let { return fetchShell(it) }
        val url = markdownImageSnapshotKey(link) ?: throw IOException("Unsupported image destination")
        val bytes = HttpClient.getBytes(url, maxBytes = ToolImageStore.MAX_IMAGE_BYTES)
            ?: throw IOException("Image download failed")
        val mime = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
            .outMimeType?.takeIf { it.startsWith("image/") }
            ?: throw IOException("Unsupported image type")
        return store.persistBytes(bytes, mime, "markdown")
    }

    private suspend fun fetchShell(target: ShellMediaLink, imageStore: ToolImageStore = store): ToolImageAttachment {
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
            return file.inputStream().use { imageStore.persistStream(it, mime, "markdown") }
        }
        val device = devices().firstOrNull { it.name.equals(target.server, ignoreCase = true) }
            ?: throw IOException("Unknown shell device")
        if (device.type == "ssh") throw IOException("SSH devices do not serve images")
        val result = ConchBackend(device).viewImage(target.path)
        result.error?.let { throw IOException(it) }
        return imageStore.persistBase64(result.data, result.mimeType, "markdown")
    }
}

/** Provided by native chat only; Remote sessions keep their own image ownership. */
internal val LocalShellMarkdownMedia = staticCompositionLocalOf<ShellMarkdownMedia?> { null }
