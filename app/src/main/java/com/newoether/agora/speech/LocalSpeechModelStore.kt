package com.newoether.agora.speech

import android.content.Context
import android.os.StatFs
import com.newoether.agora.api.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Install state of one local model, as shown in the editor. */
internal sealed interface LocalSpeechModelStatus {
    data object Missing : LocalSpeechModelStatus
    data class Downloading(val downloaded: Long, val total: Long) : LocalSpeechModelStatus
    data object Verifying : LocalSpeechModelStatus
    data object Installed : LocalSpeechModelStatus
    data class Failed(val message: String) : LocalSpeechModelStatus
}

/**
 * Downloads, verifies and deletes model files under `filesDir/speech-models/<id>/`. A model counts
 * as installed only after every file passed its SHA-256 and the revision marker was written, so a
 * killed download never loads. Interrupted files keep a `.part` suffix and resume with HTTP Range.
 */
internal class LocalSpeechModelStore(context: Context, private val scope: CoroutineScope) {
    private val root = File(context.applicationContext.filesDir, "speech-models")
    private val jobs = mutableMapOf<String, Job>()
    private val client = HttpClient.client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow(
        LocalSpeechModels.all.associate { it.id to initialStatus(it) },
    )
    val status: StateFlow<Map<String, LocalSpeechModelStatus>> = _status.asStateFlow()

    fun directory(model: LocalSpeechModel): File = File(root, model.id)

    fun isInstalled(model: LocalSpeechModel): Boolean {
        val dir = directory(model)
        val marker = File(dir, MARKER)
        if (!marker.isFile || runCatching { marker.readText() }.getOrNull() != model.revision) return false
        return model.files.all { File(dir, it.path).let { f -> f.isFile && f.length() == it.size } }
    }

    fun download(model: LocalSpeechModel, source: LocalSpeechSource) {
        synchronized(jobs) {
            if (jobs[model.id]?.isActive == true) return
            jobs[model.id] = scope.launch(Dispatchers.IO) {
                try {
                    runDownload(model, source)
                    set(model, LocalSpeechModelStatus.Installed)
                } catch (e: CancellationException) {
                    set(model, initialStatus(model))
                    throw e
                } catch (e: Exception) {
                    set(model, LocalSpeechModelStatus.Failed(e.message ?: e.javaClass.simpleName))
                } finally {
                    synchronized(jobs) { jobs.remove(model.id) }
                }
            }
        }
    }

    /** Stops a running download; partial files stay so the next attempt resumes. */
    fun cancel(model: LocalSpeechModel) {
        synchronized(jobs) { jobs.remove(model.id) }?.cancel()
    }

    suspend fun delete(model: LocalSpeechModel) {
        synchronized(jobs) { jobs.remove(model.id) }?.let { it.cancel(); it.join() }
        withContext(Dispatchers.IO) { directory(model).deleteRecursively() }
        set(model, LocalSpeechModelStatus.Missing)
    }

    private suspend fun runDownload(model: LocalSpeechModel, source: LocalSpeechSource) {
        val dir = directory(model)
        File(dir, MARKER).delete()
        val present = model.files.sumOf { file ->
            val target = File(dir, file.path)
            when {
                target.isFile && target.length() == file.size -> file.size
                else -> File(dir, file.path + PART).takeIf { it.isFile }?.length() ?: 0L
            }
        }
        val needed = model.totalBytes - present
        dir.mkdirs()
        if (StatFs(dir.path).availableBytes < needed + FREE_SPACE_MARGIN) {
            throw IOException("Not enough storage: ${formatSize(needed)} required")
        }
        var done = present
        set(model, LocalSpeechModelStatus.Downloading(done, model.totalBytes))
        for (file in model.files) {
            val target = File(dir, file.path)
            if (target.isFile && target.length() == file.size) continue
            target.parentFile?.mkdirs()
            val part = File(dir, file.path + PART)
            val before = part.takeIf { it.isFile }?.length() ?: 0L
            done -= before
            fetch(source.url(model, file), part, file.size) { written ->
                set(model, LocalSpeechModelStatus.Downloading(done + written, model.totalBytes))
            }
            done += file.size
            set(model, LocalSpeechModelStatus.Verifying)
            val digest = sha256(part)
            if (!digest.equals(file.sha256, ignoreCase = true)) {
                part.delete()
                throw IOException("Checksum mismatch for ${file.path}")
            }
            if (!part.renameTo(target)) throw IOException("Cannot move ${file.path} into place")
            set(model, LocalSpeechModelStatus.Downloading(done, model.totalBytes))
        }
        File(dir, MARKER).writeText(model.revision)
    }

    /** Appends to [part] until it holds [size] bytes; [onProgress] gets the bytes now on disk. */
    private suspend fun fetch(url: String, part: File, size: Long, onProgress: (Long) -> Unit) {
        var offset = part.takeIf { it.isFile }?.length() ?: 0L
        if (offset > size) {
            part.delete()
            offset = 0L
        }
        if (offset == size) return
        val request = Request.Builder().url(url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${part.name.removeSuffix(PART)}")
            // A server that ignores Range sends the whole file again.
            val append = offset > 0 && response.code == 206
            if (!append) offset = 0L
            val body = response.body ?: throw IOException("Empty response")
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReport = offset
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        offset += read
                        if (offset > size) throw IOException("Unexpected file size")
                        if (offset - lastReport >= REPORT_BYTES) {
                            lastReport = offset
                            onProgress(offset)
                        }
                    }
                }
            }
            onProgress(offset)
        }
        if (offset != size) throw IOException("Download interrupted")
    }

    private fun initialStatus(model: LocalSpeechModel): LocalSpeechModelStatus =
        if (isInstalled(model)) LocalSpeechModelStatus.Installed else LocalSpeechModelStatus.Missing

    private fun set(model: LocalSpeechModel, value: LocalSpeechModelStatus) {
        _status.update { it + (model.id to value) }
    }

    private companion object {
        const val MARKER = ".revision"
        const val PART = ".part"
        const val BUFFER_BYTES = 256 * 1024
        const val REPORT_BYTES = 1024L * 1024L
        const val FREE_SPACE_MARGIN = 64L * 1024L * 1024L
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Decimal units with one decimal, e.g. "237.1 MB", matching Hugging Face's listed sizes. */
internal fun formatSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000) "%.2f GB".format(mb / 1000) else "%.1f MB".format(mb)
}
