package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.util.DebugLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Downloads and extracts the on-device ONNX chat model (onnxruntime-genai) for local LLM chat —
 * the ONNX replacement for GGUF chat models.
 *
 * Model: Qwen3-0.6B instruct, ONNX INT4 (cpu-int4), a genai package (genai_config.json +
 * model.onnx + tokenizer). Downloaded as a tarball to app-private storage; not bundled in the APK.
 */
object OnnxChatModelBootstrap {

    private const val TAG = "OnnxChatModelBootstrap"
    private const val MODEL_VERSION = "qwen3_0_6b_int4_v1"
    const val MODEL_ID = "onnx-qwen3-0.6b"

    /** Qwen3-0.6B genai tarball, mirrored in XNet-NGO/deps. */
    private const val TARBALL_URL =
        "https://github.com/XNet-NGO/deps/releases/download/qwen3-0.6b-chat/qwen3.6-0.6b-onnx-int4.tar.gz"

    /** The genai model.onnx is ~525 MiB; floor guards truncated/HTML downloads. */
    private const val MIN_MODEL_BYTES = 400L * 1024 * 1024

    fun modelDir(ctx: Context): File = File(File(ctx.filesDir, "models"), "qwen3-chat")
    private fun modelFile(ctx: Context) = File(modelDir(ctx), "model.onnx")
    private fun configFile(ctx: Context) = File(modelDir(ctx), "genai_config.json")
    private fun marker(ctx: Context) = File(modelDir(ctx), ".$MODEL_VERSION")

    fun isInstalled(ctx: Context): Boolean {
        val m = modelFile(ctx)
        return marker(ctx).exists() &&
            configFile(ctx).isFile &&
            m.isFile && m.length() >= MIN_MODEL_BYTES
    }

    fun installedBytes(ctx: Context): Long = modelFile(ctx).let { if (it.isFile) it.length() else 0L }

    fun remove(ctx: Context) {
        modelDir(ctx).deleteRecursively()
    }

    /** Download + extract the tarball. Call on a background thread. */
    fun setup(ctx: Context, logCb: (String) -> Unit): Boolean {
        val l: (String) -> Unit = { msg -> DebugLog.d(TAG, msg); logCb(msg) }
        return try {
            val dir = modelDir(ctx)
            dir.mkdirs()
            if (isInstalled(ctx)) {
                l("Chat model already installed")
                return true
            }
            val tmp = File(dir, "model.tar.gz.part")
            if (tmp.exists()) tmp.delete()
            l("Downloading Qwen3-0.6B chat model (~487MB)...")
            if (!download(TARBALL_URL, tmp, l)) {
                tmp.delete()
                l("ERROR: chat model download failed")
                return false
            }
            l("Extracting...")
            if (!extractTarGz(tmp, dir, l)) {
                tmp.delete()
                l("ERROR: extraction failed")
                return false
            }
            tmp.delete()
            if (!(configFile(ctx).isFile && modelFile(ctx).length() >= MIN_MODEL_BYTES)) {
                l("ERROR: extracted model incomplete")
                return false
            }
            marker(ctx).writeText(MODEL_VERSION)
            l("Chat model ready (${modelFile(ctx).length() / 1024 / 1024}MB)")
            true
        } catch (e: Exception) {
            l("ERROR: ${e.javaClass.simpleName}")
            DebugLog.e(TAG, "setup failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun extractTarGz(tarGz: File, destDir: File, log: (String) -> Unit): Boolean {
        return try {
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(tarGz.inputStream()))).use { tin ->
                var entry = tin.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast("./").substringAfterLast('/')
                    if (!entry.isDirectory && name.isNotBlank()) {
                        val out = File(destDir, name)
                        // Guard against path traversal: keep files inside destDir.
                        if (out.canonicalPath.startsWith(destDir.canonicalPath)) {
                            FileOutputStream(out).use { tin.copyTo(it) }
                        }
                    }
                    entry = tin.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            log("ERROR: extract ${e.javaClass.simpleName}")
            false
        }
    }

    private fun download(urlStr: String, dest: File, log: (String) -> Unit): Boolean {
        var url = URL(urlStr)
        var redirects = 0
        var conn: HttpURLConnection
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: break
                url = URL(url, loc)
                conn.disconnect()
                if (++redirects > 5) { log("ERROR: too many redirects"); return false }
                continue
            }
            if (code != 200) { log("ERROR: HTTP $code"); conn.disconnect(); return false }
            break
        }
        val total = conn.contentLength.toLong()
        var downloaded = 0L
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0 && downloaded % (4 * 1024 * 1024) < 65536) {
                        log("  ${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB (${downloaded * 100 / total}%)")
                    }
                }
            }
        }
        conn.disconnect()
        log("  Download complete (${dest.length() / 1024 / 1024}MB)")
        return true
    }
}
