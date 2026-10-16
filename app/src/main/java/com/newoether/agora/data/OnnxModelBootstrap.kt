package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.util.DebugLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the Bekko multilingual embedding model (ONNX) at runtime for on-device RAG — the ONNX
 * replacement for GGUF local embedding models.
 *
 * Model: hotchpotch/bekko-embedding-v1-a8m
 *  - 7.67M active params, 8192-token context, 384-dim (Matryoshka), MIT license
 *  - ONNX artifact ~124 MiB (int8 vocab table, fp32 transformer)
 *
 * Downloaded to app-private storage (filesDir/models/bekko) so it is not bundled in the APK.
 */
object OnnxModelBootstrap {

    private const val TAG = "OnnxModelBootstrap"
    private const val MODEL_VERSION = "bekko_a8m_v1"

    /** Bekko embedding ONNX, mirrored in XNet-NGO/deps. */
    private const val MODEL_URL =
        "https://github.com/XNet-NGO/deps/releases/download/bekko-embedding/bekko-model.onnx"

    /** HuggingFace fast-tokenizer definition (read directly by the DJL tokenizer). */
    private const val TOKENIZER_URL =
        "https://github.com/XNet-NGO/deps/releases/download/bekko-embedding/bekko-tokenizer.json"

    /** Expected on-disk size (~124 MiB). Sanity floor, not exact validation. */
    private const val MIN_VALID_BYTES = 90L * 1024 * 1024

    /** tokenizer.json is a few MB (256k vocab). Floor guards against truncated/HTML downloads. */
    private const val MIN_TOKENIZER_BYTES = 256L * 1024

    fun modelsDir(ctx: Context) = File(File(ctx.filesDir, "models"), "bekko")
    fun modelFile(ctx: Context) = File(modelsDir(ctx), "model.onnx")
    fun tokenizerFile(ctx: Context) = File(modelsDir(ctx), "tokenizer.json")
    private fun marker(ctx: Context) = File(modelsDir(ctx), ".$MODEL_VERSION")

    fun isInstalled(ctx: Context): Boolean {
        val m = modelFile(ctx)
        val t = tokenizerFile(ctx)
        return marker(ctx).exists() &&
            m.isFile && m.length() >= MIN_VALID_BYTES &&
            t.isFile && t.length() >= MIN_TOKENIZER_BYTES
    }

    /** Size on disk in bytes (model only), or 0 if not present. */
    fun installedBytes(ctx: Context): Long = modelFile(ctx).let { if (it.isFile) it.length() else 0L }

    /** Remove the downloaded model, tokenizer, and version marker. */
    fun remove(ctx: Context) {
        modelFile(ctx).delete()
        tokenizerFile(ctx).delete()
        marker(ctx).delete()
    }

    /**
     * Download the ONNX model and tokenizer. Call on a background thread.
     * @return true on success.
     */
    fun setup(ctx: Context, logCb: (String) -> Unit): Boolean {
        val l: (String) -> Unit = { msg ->
            DebugLog.d(TAG, msg)
            logCb(msg)
        }
        return try {
            val dir = modelsDir(ctx)
            dir.mkdirs()

            if (isInstalled(ctx)) {
                l("Model already installed")
                return true
            }

            // 1. Tokenizer (small, fast — do it first so failures surface quickly)
            val tokTmp = File(dir, "tokenizer.json.part")
            if (tokTmp.exists()) tokTmp.delete()
            l("Downloading tokenizer...")
            if (!download(TOKENIZER_URL, tokTmp, l)) {
                tokTmp.delete()
                l("ERROR: tokenizer download failed")
                return false
            }
            if (tokTmp.length() < MIN_TOKENIZER_BYTES) {
                l("ERROR: tokenizer file too small (${tokTmp.length() / 1024}KB)")
                tokTmp.delete()
                return false
            }
            val tokDest = tokenizerFile(ctx)
            if (tokDest.exists()) tokDest.delete()
            if (!tokTmp.renameTo(tokDest)) {
                tokTmp.inputStream().use { i -> tokDest.outputStream().use { o -> i.copyTo(o) } }
                tokTmp.delete()
            }

            // 2. Model (large)
            val dest = modelFile(ctx)
            val tmp = File(dir, "model.onnx.part")
            if (tmp.exists()) tmp.delete()

            l("Downloading Bekko embedding model (~124MB)...")
            if (!download(MODEL_URL, tmp, l)) {
                tmp.delete()
                l("ERROR: model download failed")
                return false
            }
            if (tmp.length() < MIN_VALID_BYTES) {
                l("ERROR: downloaded file too small (${tmp.length() / 1024}KB)")
                tmp.delete()
                return false
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                tmp.delete()
            }

            marker(ctx).writeText(MODEL_VERSION)
            l("Model ready (${dest.length() / 1024 / 1024}MB + tokenizer)")
            true
        } catch (e: Exception) {
            l("ERROR: ${e.javaClass.simpleName}")
            DebugLog.e(TAG, "setup failed: ${e.javaClass.simpleName}")
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
        val total = conn.contentLength.toLong()
        var downloaded = 0L
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0 && downloaded % (2 * 1024 * 1024) < 65536) {
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
