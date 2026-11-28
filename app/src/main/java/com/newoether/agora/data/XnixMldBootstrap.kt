package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.util.DebugLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and manages the flagship Google Tensor G5 EdgeTPU model bundle
 * (LFM3-Omni-2B-abliterated-heretic-v2_g5_tpu.litertlm) from files.foxnet.live.
 */
object XnixMldBootstrap {

    private const val TAG = "XnixMldBootstrap"
    const val MODEL_ID = "lfm3-omni-2b-g5-tpu"
    const val MODEL_ALIAS = "LFM3-Omni-2B (G5 EdgeTPU)"

    /** Flagship model hosted on production files.foxnet.live. */
    const val MODEL_URL = "https://files.foxnet.live/LFM3-Omni-2B-abliterated-heretic-v2_g5_tpu.litertlm"

    /** Expected file size ~2.50 GB; minimum floor check to guard truncated downloads. */
    private const val MIN_MODEL_BYTES = 2_400_000_000L

    fun modelDir(ctx: Context): File {
        // Prefer shared storage /storage/emulated/0/Models if accessible, else app filesDir
        val sharedModels = File("/storage/emulated/0/Models")
        if (sharedModels.exists() && sharedModels.canWrite()) {
            return sharedModels
        }
        return File(File(ctx.filesDir, "models"), "litertlm").also { it.mkdirs() }
    }

    fun modelFile(ctx: Context): File {
        val sharedFile = File("/storage/emulated/0/Models/LFM3-Omni-2B-abliterated-heretic-v2_g5_tpu.litertlm")
        if (sharedFile.isFile && sharedFile.length() >= MIN_MODEL_BYTES) {
            return sharedFile
        }
        return File(modelDir(ctx), "LFM3-Omni-2B-abliterated-heretic-v2_g5_tpu.litertlm")
    }

    fun isInstalled(ctx: Context): Boolean {
        val f = modelFile(ctx)
        return f.isFile && f.length() >= MIN_MODEL_BYTES
    }

    fun installedBytes(ctx: Context): Long {
        val f = modelFile(ctx)
        return if (f.isFile) f.length() else 0L
    }

    fun remove(ctx: Context) {
        val f = modelFile(ctx)
        if (f.exists()) f.delete()
    }

    /**
     * Downloads the model bundle with progress reporting. Call on a background thread.
     */
    fun setup(ctx: Context, logCb: (String) -> Unit): Boolean {
        val l: (String) -> Unit = { msg -> DebugLog.d(TAG, msg); logCb(msg) }
        return try {
            val dest = modelFile(ctx)
            if (isInstalled(ctx)) {
                l("G5 EdgeTPU Model already installed")
                return true
            }
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "${dest.name}.part")
            if (tmp.exists()) tmp.delete()

            l("Connecting to files.foxnet.live (~2.50 GB)...")
            if (!download(MODEL_URL, tmp, l)) {
                tmp.delete()
                l("ERROR: Model download failed")
                return false
            }

            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }

            if (!isInstalled(ctx)) {
                l("ERROR: Downloaded file size verification failed")
                return false
            }

            l("LFM3-Omni-2B G5 EdgeTPU Model ready (${dest.length() / 1024 / 1024}MB)")
            true
        } catch (e: Exception) {
            l("ERROR: ${e.javaClass.simpleName} - ${e.message}")
            DebugLog.e(TAG, "setup failed", e)
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
            conn.setRequestProperty("User-Agent", "Agora/2.1.0-XNIX")
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: break
                url = URL(url, loc)
                conn.disconnect()
                if (++redirects > 5) {
                    log("ERROR: too many redirects")
                    return false
                }
                continue
            }
            if (code != 200) {
                log("ERROR: HTTP $code")
                conn.disconnect()
                return false
            }
            break
        }

        val total = conn.contentLengthLong
        var downloaded = 0L
        BufferedInputStream(conn.inputStream, 1024 * 1024).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(1024 * 512) // 512KB chunks
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0 && downloaded % (16 * 1024 * 1024) < (1024 * 512)) {
                        val pct = (downloaded * 100 / total)
                        log("  ${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB ($pct%)")
                    }
                }
            }
        }
        conn.disconnect()
        log("  Download complete (${dest.length() / 1024 / 1024}MB)")
        return true
    }
}
