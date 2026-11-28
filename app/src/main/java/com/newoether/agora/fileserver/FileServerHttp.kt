package com.newoether.agora.fileserver

import java.io.BufferedOutputStream
import java.io.File
import java.net.URLEncoder

/**
 * Stateless HTTP response helpers for the file server. Everything here is pure request/response
 * shaping (directory listings, file streaming, auth prompt, MIME/size formatting, path
 * sanitization) so it can be unit tested without opening a socket.
 */
internal object FileServerHttp {

    private const val COPY_BLOCK = 262_144

    /**
     * Normalizes a raw request target into a safe relative path under the server root. Query
     * strings are dropped and `.`/`..`/empty segments are removed, so the result can never climb
     * out of the shared directory.
     */
    fun safeRelativePath(rawPath: String): String =
        rawPath.substringBefore("?").removePrefix("/").split("/")
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")

    fun sendDirectoryListing(out: BufferedOutputStream, dir: File, path: String) {
        val files = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
        val bytes = directoryListingHtml(dir, path, files).toByteArray()
        out.write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(bytes)
    }

    /** Pure HTML builder for the directory index, extracted so tests can assert escaping/links. */
    fun directoryListingHtml(dir: File, path: String, files: List<File>): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
        append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
        append("<title>Agora Files - ${dir.name}</title>")
        append("<style>body{font-family:system-ui;margin:20px;background:#1a1a1a;color:#eee}")
        append("a{color:#6cf;text-decoration:none}a:hover{text-decoration:underline}")
        append(".entry{padding:8px 12px;border-bottom:1px solid #333;display:flex;")
        append("justify-content:space-between}.size{color:#888;font-size:0.9em}</style></head><body>")
        append("<h2>\uD83D\uDCC1 ${dir.name.ifEmpty { "/" }}</h2>")
        if (path != "/") {
            val parent = path.removeSuffix("/").substringBeforeLast("/").ifEmpty { "/" }
            append("<div class='entry'><a href='$parent'>\u2B06\uFE0F ..</a></div>")
        }
        for (f in files) {
            val name = f.name
            val href = if (path.endsWith("/")) "$path$name" else "$path/$name"
            val icon = if (f.isDirectory) "\uD83D\uDCC1" else "\uD83D\uDCC4"
            val size = if (f.isFile) formatSize(f.length()) else ""
            val encoded = URLEncoder.encode(href, "UTF-8").replace("+", "%20")
            append("<div class='entry'><a href='$encoded'>$icon $name</a>")
            append("<span class='size'>$size</span></div>")
        }
        append("<hr><form method='POST' enctype='multipart/form-data' style='margin:12px 0'>")
        append("<input type='file' name='file' multiple style='color:#eee;margin-right:8px'>")
        append("<button type='submit' style='padding:6px 16px;background:#6cf;color:#000;")
        append("border:none;border-radius:4px;cursor:pointer'>Upload</button></form>")
        append("<p style='font-size:0.8em;color:#666'>Agora File Server \u2022 ${files.size} items</p>")
        append("</body></html>")
    }

    fun sendFile(out: BufferedOutputStream, file: File) {
        val mime = guessMime(file.name)
        val bytes = file.length()
        out.write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: $bytes\r\n" +
                    "Content-Disposition: inline; filename=\"${file.name}\"\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.flush()
        file.inputStream().use { it.copyTo(out, COPY_BLOCK) }
    }

    fun sendPinPrompt(out: BufferedOutputStream) {
        val bytes = PIN_PROMPT_HTML.toByteArray()
        out.write(
            (
                "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(bytes)
    }

    fun sendError(out: BufferedOutputStream, code: Int, msg: String) {
        val body = "<html><body><h1>$code $msg</h1></body></html>".toByteArray()
        out.write(
            (
                "HTTP/1.1 $code $msg\r\nContent-Type: text/html\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(body)
    }

    fun guessMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "txt", "log", "md" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "tar", "gz", "tgz" -> "application/gzip"
        else -> "application/octet-stream"
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / 1048576.0)} MB"
        else -> "${"%.1f".format(bytes / 1073741824.0)} GB"
    }

    private val PIN_PROMPT_HTML = """
        <!DOCTYPE html><html><head><meta charset='utf-8'>
        <meta name='viewport' content='width=device-width,initial-scale=1'>
        <title>Agora Files - Login</title>
        <style>body{font-family:system-ui;margin:0;background:#1a1a1a;color:#eee;display:flex;
        align-items:center;justify-content:center;height:100vh}
        .box{background:#222;padding:32px;border-radius:12px;text-align:center}
        input{padding:12px;font-size:18px;border:1px solid #444;border-radius:6px;background:#333;
        color:#eee;text-align:center;letter-spacing:4px;width:150px}
        button{margin-top:16px;padding:10px 24px;background:#6cf;color:#000;border:none;
        border-radius:6px;cursor:pointer;font-size:16px}</style></head>
        <body><div class='box'><h2>&#128274; PIN Required</h2>
        <form onsubmit="fetch('/',{method:'POST',headers:{'X-Pin':document.getElementById('p').value}})
        .then(r=>{if(r.redirected)location.href=r.url;else alert('Wrong PIN')});return false;">
        <input id='p' type='password' maxlength='8' placeholder='PIN' autofocus><br>
        <button type='submit'>Enter</button></form></div></body></html>
    """.trimIndent()
}
