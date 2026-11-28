package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.mutableStateMapOf
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.tool.FileTransferEndpoint
import com.newoether.agora.tool.TRANSFER_CHUNK_BYTES
import com.newoether.agora.tool.TRANSFER_MAX_BYTES
import com.newoether.agora.tool.transferLong
import com.newoether.agora.tool.transferString
import com.newoether.agora.util.ShellClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Download state of one remote `shell://` video, keyed by its resolved device path. */
internal sealed interface MarkdownVideoDownload {
    data object Idle : MarkdownVideoDownload
    data class Downloading(val loaded: Long, val total: Long) : MarkdownVideoDownload
    data class Ready(val file: File) : MarkdownVideoDownload
    data class Failed(val message: String) : MarkdownVideoDownload
}

/**
 * On-demand local copy of a video or audio file that lives on a Conch device or inside the Local
 * Sandbox rootfs.
 *
 * The player reads from a cache-backed file (with FileProvider coverage for videos), so a remote
 * or sandbox-rootfs destination is copied once into the app cache. The copy is named after the
 * source SHA-256, which makes an existing file provably current and lets repeated views reuse it
 * without a second copy.
 */
internal class ShellMarkdownVideo(
    private val context: android.content.Context,
    private val devices: () -> List<ShellDeviceConfig>,
    private val scope: CoroutineScope,
    private val cacheDirectoryName: String = "markdown-video",
    private val sandboxEnabled: () -> Boolean = { false },
    private val sandbox: () -> SandboxManager? = { null },
) {
    private val directory by lazy { File(context.applicationContext.cacheDir, cacheDirectoryName) }
    private val receiptDirectory by lazy { File(directory, "receipts") }
    private val posterDirectory by lazy { File(directory, "posters") }
    private var posterSweepStarted = false
    private val states = mutableStateMapOf<String, MarkdownVideoDownload>()
    // Receipt lookups are cached here: peek() runs during composition and must not stat or read
    // the disk again on every recomposition.
    private val resolved = HashMap<String, MarkdownVideoDownload>()
    private val inFlight = HashMap<String, Deferred<Unit>>()
    private val lock = Any()

    /** Current state for [link]; composition reads this directly. */
    fun peek(link: String): MarkdownVideoDownload {
        sweepOrphanPostersOnce()
        val target = parseShellMediaLink(link) ?: return MarkdownVideoDownload.Failed(UNSUPPORTED)
        states[target.key]?.let { return it }
        synchronized(lock) {
            resolved[target.key]?.let { return it }
            // The receipt names the content-addressed copy from an earlier run, so a cached video
            // keeps its poster and its place in the answer's viewer without touching the network.
            val known = readReceipt(target.key) ?: MarkdownVideoDownload.Idle
            resolved[target.key] = known
            return known
        }
    }

    /** Starts the copy for [link] unless one is already running or the cache already holds it. */
    fun start(link: String) {
        val target = parseShellMediaLink(link) ?: return
        synchronized(lock) { inFlight[target.key] }?.let { running ->
            running.start()
            return
        }
        if (states[target.key] is MarkdownVideoDownload.Ready) return
        states[target.key] = MarkdownVideoDownload.Downloading(0, 0)
        val task = synchronized(lock) {
            inFlight[target.key] ?: scope.async(start = CoroutineStart.LAZY) {
                val result = try {
                    MarkdownVideoDownload.Ready(download(target))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    MarkdownVideoDownload.Failed(failure.message ?: "Video download failed")
                }
                withContext(Dispatchers.Main.immediate) { states[target.key] = result }
            }.also { job ->
                inFlight[target.key] = job
                job.invokeOnCompletion { synchronized(lock) { inFlight.remove(target.key, job) } }
            }
        }
        task.start()
    }

    /**
     * Ensures [link] is present in the local cache and returns the file, reusing any cached
     * receipt or in-flight transfer. Throws [IOException] if the destination is unsupported or
     * transfer fails.
     */
    suspend fun fetchFile(link: String): File {
        val target = parseShellMediaLink(link) ?: throw IOException(UNSUPPORTED)
        (peek(link) as? MarkdownVideoDownload.Ready)?.let { return it.file }
        withContext(Dispatchers.Main.immediate) { start(link) }
        val running = synchronized(lock) { inFlight[target.key] }
        running?.await()
        return when (val current = withContext(Dispatchers.Main.immediate) { states[target.key] } ?: peek(link)) {
            is MarkdownVideoDownload.Ready -> current.file
            is MarkdownVideoDownload.Failed -> throw IOException(current.message)
            else -> throw IOException("Media download did not complete")
        }
    }

    private suspend fun download(target: ShellMediaLink): File = withContext(Dispatchers.IO) {
        if (target.server.equals(LOCAL_SANDBOX_SERVER, ignoreCase = true)) {
            return@withContext copySandboxMedia(target)
        }
        val device = devices().firstOrNull { it.name.equals(target.server, ignoreCase = true) }
            ?: throw IOException("Unknown shell device")
        if (device.type == "ssh") throw IOException("SSH devices do not serve videos")
        val endpoint = FileTransferEndpoint(
            device.name,
            client = ShellClient(device.serverUrl.trimEnd('/'), device.apiKey, device.conchPublicKey),
        )
        endpoint.checkSupport()
        val info = endpoint.info(target.path)
        val actual = info.transferString("path")
        val size = info.transferLong("size")
        val hash = info.transferString("sha256").lowercase()
        require(size in 1..TRANSFER_MAX_BYTES && SHA256_HEX.matches(hash)) {
            "Invalid video source metadata"
        }
        val file = File(directory, markdownVideoCacheFileName(hash, target.path))
        if (file.isFile && file.length() == size) {
            writeReceipt(target.key, file)
            return@withContext file
        }
        directory.mkdirs()
        val staging = File.createTempFile(".markdown-video-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            FileOutputStream(staging).use { output ->
                while (offset < size) {
                    currentCoroutineContext().ensureActive()
                    val chunk = endpoint.read(actual, offset, size)
                    check(chunk.size == minOf(TRANSFER_CHUNK_BYTES.toLong(), size - offset).toInt()) {
                        "Source returned an invalid video chunk"
                    }
                    output.write(chunk)
                    digest.update(chunk)
                    offset += chunk.size
                    withContext(Dispatchers.Main.immediate) {
                        states[target.key] = MarkdownVideoDownload.Downloading(offset, size)
                    }
                }
                output.fd.sync()
            }
            check(digest.digest().toHex().equals(hash, ignoreCase = true)) {
                "Video changed during transfer"
            }
            Files.move(
                staging.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            writeReceipt(target.key, file)
            file
        } finally {
            staging.delete()
        }
    }

    /** A rootfs file of the Local Sandbox has no FileProvider root of its own; copy it into cache. */
    private suspend fun copySandboxMedia(target: ShellMediaLink): File {
        if (!sandboxEnabled()) throw IOException("Local Sandbox is disabled")
        val manager = sandbox() ?: throw IOException("Local Sandbox is unavailable")
        if (!manager.isAvailable()) throw IOException("Local Sandbox is not installed")
        val source = manager.resolveHostFile(target.path) ?: throw IOException("Local Sandbox is unavailable")
        if (!source.isFile) throw IOException("Not a regular file")
        val size = source.length()
        if (size !in 1..TRANSFER_MAX_BYTES) throw IOException("Invalid video source metadata")
        directory.mkdirs()
        val staging = File.createTempFile(".markdown-video-", ".tmp", directory)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
            var offset = 0L
            source.inputStream().use { input ->
                FileOutputStream(staging).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        offset += read
                        if (offset > TRANSFER_MAX_BYTES) throw IOException("Invalid video source metadata")
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        withContext(Dispatchers.Main.immediate) {
                            states[target.key] = MarkdownVideoDownload.Downloading(offset, size)
                        }
                    }
                    output.fd.sync()
                }
            }
            if (offset <= 0L) throw IOException("Invalid video source metadata")
            val hash = digest.digest().toHex()
            val file = File(directory, markdownVideoCacheFileName(hash, target.path))
            if (!file.isFile || file.length() != offset) {
                Files.move(
                    staging.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            writeReceipt(target.key, file)
            file
        } finally {
            staging.delete()
        }
    }

    /**
     * Records which content-addressed file serves [key]. The receipt replaces the previous one in a
     * single atomic move, so a process death cannot leave a name pointing at a partial copy.
     */
    private fun writeReceipt(key: String, file: File) {
        runCatching {
            receiptDirectory.mkdirs()
            val staging = File.createTempFile(".receipt-", ".tmp", receiptDirectory)
            staging.writeText("${file.name}\n${file.length()}\n")
            Files.move(
                staging.toPath(),
                receiptFile(key).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun readReceipt(key: String): MarkdownVideoDownload.Ready? {
        val receipt = receiptFile(key)
        if (!receipt.isFile) return null
        val lines = runCatching { receipt.readLines() }.getOrNull() ?: return null
        val name = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val size = lines.getOrNull(1)?.toLongOrNull() ?: return null
        if (size <= 0) return null
        val file = File(directory, name)
        return if (file.isFile && file.length() == size) MarkdownVideoDownload.Ready(file) else null
    }

    private fun receiptFile(key: String): File = File(
        receiptDirectory,
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).toHex(),
    )

    /**
     * Poster cache for a content-addressed copy. The name carries the source hash, so a still can
     * only ever belong to the bytes that produced it, and a replaced source cannot serve a stale
     * frame after a restart.
     */
    fun posterFile(file: File): File = File(posterDirectory, "${file.name}.jpg")

    /**
     * A poster derives from its content-addressed video, so a still whose video is gone can never
     * be served again; sweep those orphans once per process instead of bounding the directory.
     */
    private fun sweepOrphanPostersOnce() {
        synchronized(lock) {
            if (posterSweepStarted) return
            posterSweepStarted = true
        }
        scope.launch(Dispatchers.IO) {
            val posters = posterDirectory.listFiles() ?: return@launch
            for (poster in posters) {
                val videoName = poster.name.removeSuffix(".jpg")
                if (videoName == poster.name || !File(directory, videoName).isFile) {
                    poster.delete()
                }
            }
        }
    }

    private companion object {
        val SHA256_HEX = Regex("[a-f0-9]{64}")
        const val UNSUPPORTED = "Unsupported video destination"
    }
}

/** One cache name per source content, so a stale file can never be played by mistake. */
internal fun markdownVideoCacheFileName(hash: String, path: String): String {
    val extension = path.substringBefore('?').substringBefore('#')
        .substringAfterLast('.', "").lowercase()
    val safe = extension.ifBlank { "mp4" }
    return "$hash.$safe"
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
