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
import com.newoether.agora.tool.FileTransferEndpoint
import com.newoether.agora.tool.TRANSFER_CHUNK_BYTES
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.tool.transferLong
import com.newoether.agora.tool.transferString
import com.newoether.agora.util.ShellClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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

    /** Remote `shell://` videos are copied on demand into the media cache before playback. */
    val videos = ShellMarkdownVideo(context, devices, scope)

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
                // HTML previews keep a disposable cache and still refuse network destinations.
                val destination = resolveMarkdownDestination(link)
                if (destination == null || destination is MarkdownDestination.Web) return null
                previewCache.load(target) { fetchInto(link, previewStore) }
            } else cache.load("$occurrence\n$target") { fetch(link) }.image
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetch(link: String): ToolImageAttachment = fetchInto(link, store)

    /** One read path per destination kind, so a spelling opens the same bytes everywhere. */
    private suspend fun fetchInto(link: String, imageStore: ToolImageStore): ToolImageAttachment {
        return when (val destination = resolveMarkdownDestination(link)) {
            is MarkdownDestination.File -> fetchLocal(destination.target, imageStore)
            is MarkdownDestination.Device -> fetchShell(destination.link, imageStore)
            is MarkdownDestination.Web -> fetchWeb(destination.url, imageStore)
            null -> throw IOException("Unsupported image destination")
        }
    }

    private suspend fun fetchWeb(url: String, imageStore: ToolImageStore): ToolImageAttachment {
        val bytes = HttpClient.getBytes(url, maxBytes = ToolImageStore.MAX_IMAGE_BYTES)
            ?: throw IOException("Image download failed")
        val mime = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
            .outMimeType?.takeIf { it.startsWith("image/") }
            ?: throw IOException("Unsupported image type")
        return imageStore.persistBytes(bytes, mime, "markdown")
    }

    /** A path on this phone, read in place: the sandbox home or shared storage. */
    private suspend fun fetchLocal(target: LocalFileTarget, imageStore: ToolImageStore = store): ToolImageAttachment {
        val file = when (val resolved = resolveLocalFileTarget(context, target)) {
            is LocalFileResolution.Ready -> resolved.file
            LocalFileResolution.NotFound -> throw IOException("Not a regular file")
            LocalFileResolution.NeedsSharedStorageAccess -> throw IOException("Shared storage access is required")
            LocalFileResolution.Unavailable -> throw IOException("File is unavailable")
        }
        if (file.length() !in 1..ToolImageStore.MAX_IMAGE_BYTES) throw IOException("Image size is out of bounds")
        val mime = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(file.absolutePath, it) }
            .outMimeType?.takeIf { it.startsWith("image/") }
            ?: throw IOException("Unsupported image type")
        return file.inputStream().use { imageStore.persistStream(it, mime, "markdown") }
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

    /**
     * Copies one non-image file named by a Markdown link into the shared cache, so the system app
     * that opens it reads a local file. A copy is named after the source SHA-256, which makes an
     * existing file provably current and lets a repeated tap skip the transfer.
     */
    suspend fun downloadDeviceFile(target: ShellMediaLink): File = withContext(Dispatchers.IO) {
        if (target.server.equals(LOCAL_SANDBOX_SERVER, ignoreCase = true)) return@withContext copySandboxFile(target.path)
        val device = devices().firstOrNull { it.name.equals(target.server, ignoreCase = true) }
            ?: throw IOException("Unknown shell device")
        if (device.type == "ssh") throw IOException("SSH devices do not serve files")
        val endpoint = FileTransferEndpoint(
            device.name,
            client = ShellClient(device.serverUrl.trimEnd('/'), device.apiKey, device.conchPublicKey),
        )
        endpoint.checkSupport()
        val info = endpoint.info(target.path)
        val actual = info.transferString("path")
        val size = info.transferLong("size")
        val hash = info.transferString("sha256").lowercase()
        if (size !in 1..MARKDOWN_FILE_MAX_BYTES) throw MarkdownDeviceFileTooLarge(size)
        require(MARKDOWN_FILE_SHA256.matches(hash)) { "Invalid file source metadata" }
        val directory = sharedFileDirectory()
        val file = File(directory, "${hash.take(16)}_${downloadFileName(actual)}")
        if (file.isFile && file.length() == size) return@withContext file
        val staging = File.createTempFile(".markdown-file-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            FileOutputStream(staging).use { output ->
                while (offset < size) {
                    currentCoroutineContext().ensureActive()
                    val chunk = endpoint.read(actual, offset, size)
                    check(chunk.size == minOf(TRANSFER_CHUNK_BYTES.toLong(), size - offset).toInt()) {
                        "Source returned an invalid file chunk"
                    }
                    output.write(chunk)
                    digest.update(chunk)
                    offset += chunk.size
                }
                output.fd.sync()
            }
            check(digest.digest().joinToString("") { "%02x".format(it) }.equals(hash, ignoreCase = true)) {
                "File changed during transfer"
            }
            Files.move(
                staging.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            file
        } finally {
            staging.delete()
        }
    }

    /** A rootfs file of the Local Sandbox has no FileProvider root of its own; copy it out. */
    private suspend fun copySandboxFile(path: String): File {
        if (!sandboxEnabled()) throw IOException("Local Sandbox is disabled")
        val manager = sandbox() ?: throw IOException("Local Sandbox is unavailable")
        if (!manager.isAvailable()) throw IOException("Local Sandbox is not installed")
        val source = manager.resolveHostFile(path) ?: throw IOException("Local Sandbox is unavailable")
        if (!source.isFile) throw IOException("Not a regular file")
        val size = source.length()
        if (size <= 0L) throw IOException("File is empty")
        if (size > MARKDOWN_FILE_MAX_BYTES) throw MarkdownDeviceFileTooLarge(size)
        val directory = sharedFileDirectory()
        // Path, size and mtime name one version, so repeated taps reuse the copy instead of
        // leaving one cache file per tap.
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$path\u0000$size\u0000${source.lastModified()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(directory, "sandbox_${key.take(16)}_${downloadFileName(path)}")
        if (file.isFile && file.length() == size) return file
        val staging = File.createTempFile(".markdown-file-", ".tmp", directory)
        return try {
            source.inputStream().use { input ->
                staging.outputStream().use { output -> input.copyTo(output, TRANSFER_CHUNK_BYTES) }
            }
            Files.move(
                staging.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            file
        } finally {
            staging.delete()
        }
    }

    /** `cache/shared` is declared for the app FileProvider, so the copy can reach a system app. */
    private fun sharedFileDirectory(): File =
        File(context.applicationContext.cacheDir, "shared").apply { mkdirs() }
}

/** A Conch file over the cache limit; a preview of its bytes cannot be produced. */
internal class MarkdownDeviceFileTooLarge(size: Long) :
    IOException("File is $size bytes, over the ${MARKDOWN_FILE_MAX_BYTES shr 20} MiB limit")

internal const val MARKDOWN_FILE_MAX_BYTES = 512L shl 20

private val MARKDOWN_FILE_SHA256 = Regex("[a-f0-9]{64}")

/** Safe cache name that keeps the extension FileProvider uses to pick a MIME type. */
private fun downloadFileName(path: String): String {
    val raw = path.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val extension = raw.substringAfterLast('.', "").lowercase()
        .filter { it.isLetterOrDigit() }.take(8)
    val stem = raw.substringBeforeLast('.', raw).filter { it.isLetterOrDigit() || it in "_-" }
        .take(48).ifEmpty { "file" }
    return if (extension.isEmpty()) stem else "$stem.$extension"
}

/** Provided by native chat only; Remote sessions keep their own image ownership. */
internal val LocalShellMarkdownMedia = staticCompositionLocalOf<ShellMarkdownMedia?> { null }
