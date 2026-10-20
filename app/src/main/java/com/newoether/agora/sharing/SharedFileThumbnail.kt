package com.newoether.agora.sharing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import android.util.Xml
import com.newoether.agora.model.SelectedAttachment
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

/** Optional display metadata, generated from owned bytes while the sender grant is still live. */
internal object SharedFileThumbnail {
    fun create(context: Context, uri: Uri, attachment: SelectedAttachment): String? =
        runCatching { createOwned(context, uri, attachment) }.getOrNull()

    private fun createOwned(context: Context, uri: Uri, attachment: SelectedAttachment): String? {
        val source = File(attachment.localPath ?: return null)
        val target = File(source.parentFile, "preview.jpg")
        val provider = if (Build.VERSION.SDK_INT >= 29) runCatching {
            context.contentResolver.loadThumbnail(uri, Size(160, 220), null)
        }.getOrNull() else null
        if (provider != null) {
            try { save(provider, target)?.let { return it } } finally { provider.recycle() }
        }
        return runCatching {
            val epub = attachment.mimeType == "application/epub+zip" || attachment.fileName?.endsWith(".epub", true) == true
            val bitmap = when {
                epub -> epubCover(source)
                attachment.mimeType?.startsWith("image/") == true -> decode(source.readBounded(8 * 1024 * 1024))
                else -> null
            } ?: return@runCatching null
            try { save(bitmap, target) } finally { bitmap.recycle() }
        }.getOrNull()
    }

    private fun save(bitmap: Bitmap, target: File): String? {
        if (bitmap.width < 1 || bitmap.height < 1 || bitmap.width.toLong() * bitmap.height > 20_000_000) return null
        val factor = minOf(1f, 160f / bitmap.width, 220f / bitmap.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * factor).toInt()), maxOf(1, (bitmap.height * factor).toInt()), false)
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)) return null
                output.toByteArray()
            }
            if (bytes.size > 90_000) return null
            target.writeBytes(bytes)
            target.absolutePath
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth.toLong() * bounds.outHeight > 20_000_000) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (bounds.outWidth / options.inSampleSize > 320 || bounds.outHeight / options.inSampleSize > 440) options.inSampleSize *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun epubCover(file: File): Bitmap? = ZipFile(file).use { zip ->
        var packagePath: String? = null
        parse(zip.readEntry("META-INF/container.xml", 64 * 1024)) { parser ->
            if (parser.name == "rootfile" && packagePath == null) packagePath = parser.getAttributeValue(null, "full-path")
        }
        val opf = packagePath ?: return@use null
        val items = linkedMapOf<String, String>()
        var coverId: String? = null
        var coverHref: String? = null
        var guide: String? = null
        parse(zip.readEntry(opf, 1024 * 1024)) { parser ->
            when (parser.name) {
                "meta" -> if (parser.getAttributeValue(null, "name") == "cover") coverId = parser.getAttributeValue(null, "content")
                "item" -> {
                    val id = parser.getAttributeValue(null, "id")
                    val href = parser.getAttributeValue(null, "href")
                    if (id != null && href != null) items[id] = href
                    if (parser.getAttributeValue(null, "properties").orEmpty().split(Regex("\\s+")).contains("cover-image")) coverHref = href
                }
                "reference" -> if (parser.getAttributeValue(null, "type") == "cover") guide = parser.getAttributeValue(null, "href")
            }
        }
        val image = coverHref ?: items[coverId]
        if (image != null) return@use decode(zip.readEntry(resolve(opf, image), 8 * 1024 * 1024))
        // EPUB 2 books can identify a cover page rather than an image resource.
        val page = guide?.let { resolve(opf, it) } ?: return@use null
        val bytes = zip.readEntry(page, 1024 * 1024)
        decode(bytes)?.let { return@use it }
        var pageImage: String? = null
        parse(bytes) { parser ->
            if (pageImage == null) pageImage = when (parser.name) {
                "img" -> parser.getAttributeValue(null, "src")
                "image" -> parser.getAttributeValue("http://www.w3.org/1999/xlink", "href") ?: parser.getAttributeValue(null, "href")
                else -> null
            }
        }
        pageImage?.let { decode(zip.readEntry(resolve(page, it), 8 * 1024 * 1024)) }
    }

    private fun resolve(base: String, href: String): String {
        val relative = URI(href.replace(" ", "%20"))
        require(!relative.isAbsolute && relative.rawAuthority == null) { "Only embedded EPUB cover resources are supported" }
        val resolved = URI(null, null, "/$base", null).resolve(relative).normalize()
        return resolved.path.removePrefix("/").also { require(!it.startsWith("../")) }
    }

    private fun parse(bytes: ByteArray, onTag: (XmlPullParser) -> Unit) {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(ByteArrayInputStream(bytes), null)
        }
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            require(parser.eventType != XmlPullParser.DOCDECL) { "EPUB preview does not resolve XML declarations" }
            if (parser.eventType == XmlPullParser.START_TAG) onTag(parser)
            parser.nextToken()
        }
    }

    private fun ZipFile.readEntry(name: String, limit: Int): ByteArray =
        getInputStream(requireNotNull(getEntry(name))).use { it.readBounded(limit) }
    private fun File.readBounded(limit: Int): ByteArray = inputStream().use { it.readBounded(limit) }
    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= limit) { "EPUB preview resource is too large" }
            output.write(buffer, 0, count)
        }
    }
}
