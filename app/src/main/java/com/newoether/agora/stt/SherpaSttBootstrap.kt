package com.newoether.agora.stt

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Downloads + unpacks the sherpa-onnx streaming zipformer English STT model (int8) for fully
 * offline speech recognition. Archive is a tar.gz containing:
 *   encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt
 * Hosted on XNet-NGO/deps. Apache-2.0 (k2-fsa/sherpa-onnx asr-models).
 *
 * Ported from AIOPE (ngo.xnet.aiope.core.terminal.shell.SherpaSttBootstrap).
 */
object SherpaSttBootstrap {

    private const val TAG = "SherpaSttBootstrap"
    private const val VERSION = "sherpa_stt_kroko_v1"

    const val MODEL_URL =
        "https://github.com/XNet-NGO/deps/releases/download/sherpa-stt-en-kroko/sherpa-stt-en-kroko.tar.gz"

    private const val MIN_ARCHIVE_BYTES = 45L * 1024 * 1024 // real ~52MB

    fun modelsDir(ctx: Context) = File(File(ctx.filesDir, "models"), "sherpa-stt")
    fun encoderFile(ctx: Context) = File(modelsDir(ctx), "encoder.onnx")
    fun decoderFile(ctx: Context) = File(modelsDir(ctx), "decoder.onnx")
    fun joinerFile(ctx: Context) = File(modelsDir(ctx), "joiner.onnx")
    fun tokensFile(ctx: Context) = File(modelsDir(ctx), "tokens.txt")
    private fun marker(ctx: Context) = File(modelsDir(ctx), ".$VERSION")

    fun isConfigured(): Boolean = MODEL_URL.isNotBlank()

    fun isInstalled(ctx: Context): Boolean =
        marker(ctx).exists() &&
            encoderFile(ctx).isFile && encoderFile(ctx).length() > 1_000_000 &&
            decoderFile(ctx).isFile && joinerFile(ctx).isFile &&
            tokensFile(ctx).isFile

    fun remove(ctx: Context) {
        listOf(encoderFile(ctx), decoderFile(ctx), joinerFile(ctx), tokensFile(ctx), marker(ctx))
            .forEach { it.delete() }
    }

    fun installedBytes(ctx: Context): Long =
        listOf(encoderFile(ctx), decoderFile(ctx), joinerFile(ctx), tokensFile(ctx))
            .sumOf { if (it.isFile) it.length() else 0L }

    fun setup(ctx: Context, logCb: (String) -> Unit): Boolean {
        val l: (String) -> Unit = { Log.d(TAG, it); logCb(it) }
        if (MODEL_URL.isBlank()) { l("ERROR: MODEL_URL not configured"); return false }
        return try {
            val dir = modelsDir(ctx); dir.mkdirs()
            if (isInstalled(ctx)) { l("Sherpa STT already installed"); return true }
            // Purge any partial/old state.
            remove(ctx)
            val tmp = File(dir, "model.tar.gz.part"); if (tmp.exists()) tmp.delete()
            l("Downloading speech-to-text model (~52MB)...")
            if (!download(MODEL_URL, tmp, l)) { tmp.delete(); return false }
            if (tmp.length() < MIN_ARCHIVE_BYTES) { l("ERROR: archive too small (${tmp.length() / 1024}KB)"); tmp.delete(); return false }
            l("Extracting model...")
            if (!extractTarGz(tmp, dir, l)) { tmp.delete(); return false }
            tmp.delete()
            if (encoderFile(ctx).isFile && decoderFile(ctx).isFile &&
                joinerFile(ctx).isFile && tokensFile(ctx).isFile
            ) {
                marker(ctx).writeText(VERSION)
            } else {
                l("ERROR: missing files after extract"); return false
            }
            l("Speech-to-text model ready.")
            true
        } catch (e: Exception) {
            l("ERROR: ${e.message}"); Log.e(TAG, "setup failed", e); false
        }
    }

    /** Minimal tar (ustar) reader over a GZIP stream; extracts regular files flat into [outDir]. */
    private fun extractTarGz(archive: File, outDir: File, log: (String) -> Unit): Boolean {
        GZIPInputStream(BufferedInputStream(archive.inputStream())).use { gz ->
            val header = ByteArray(512)
            while (true) {
                val read = readFully(gz, header)
                if (read < 512) break
                // End of archive: two zero blocks (name byte 0).
                if (header[0].toInt() == 0) break
                val name = String(header, 0, 100).trim('\u0000', ' ')
                // size is octal ASCII at offset 124, length 12
                val sizeStr = String(header, 124, 12).trim('\u0000', ' ')
                val size = sizeStr.toLongOrNull(8) ?: 0L
                val typeFlag = header[156].toInt().toChar()
                val baseName = name.substringAfterLast('/')
                if (typeFlag == '0' || typeFlag == '\u0000') {
                    if (baseName.isNotEmpty()) {
                        val out = File(outDir, baseName)
                        FileOutputStream(out).use { fo ->
                            var remaining = size
                            val buf = ByteArray(65536)
                            while (remaining > 0) {
                                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                                val n = gz.read(buf, 0, toRead)
                                if (n < 0) break
                                fo.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                    } else {
                        skip(gz, size)
                    }
                } else {
                    skip(gz, size)
                }
                // Advance past padding to the next 512 boundary.
                val pad = ((size + 511) / 512) * 512 - size
                skip(gz, pad)
            }
        }
        return true
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun skip(input: java.io.InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(65536)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun download(urlStr: String, dest: File, log: (String) -> Unit): Boolean {
        var url = URL(urlStr); var redirects = 0; var conn: HttpURLConnection
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: break
                url = URL(url, loc); conn.disconnect()
                if (++redirects > 6) { log("ERROR: too many redirects"); return false }
                continue
            }
            if (code != 200) { log("ERROR: HTTP $code"); conn.disconnect(); return false }
            break
        }
        val total = conn.contentLength.toLong(); var downloaded = 0L
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536); var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n); downloaded += n
                    if (total > 0 && downloaded % (4 * 1024 * 1024) < 65536) log("  ${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB")
                }
            }
        }
        conn.disconnect(); log("  Download complete (${dest.length() / 1024 / 1024}MB)"); return true
    }
}
