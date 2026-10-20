package com.newoether.agora.templates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** A device-local folder grant. Portable templates contain its opaque ID, never its URI. */
class TemplateAndroidSources(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val preferences get() = context.getSharedPreferences("template-source-folders", Context.MODE_PRIVATE)
    data class Entry(val id: String, val name: String, val directory: Boolean, val mimeType: String? = null, val size: Long? = null)

    fun connect(uri: Uri): String {
        require(DocumentsContract.isTreeUri(uri)) { "Choose a reference folder" }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val id = UUID.randomUUID().toString()
        check(preferences.edit().putString(id, uri.toString()).commit()) { "Could not save folder access" }
        return id
    }

    fun connected(id: String): Boolean = runCatching { tree(id); true }.getOrDefault(false)
    fun displayName(id: String): String = runCatching {
        val uri = tree(id)
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Reference folder"
    }.getOrDefault("Reconnect reference folder")

    private fun tree(id: String): Uri {
        val uri = preferences.getString(id, null)?.let(Uri::parse) ?: error("Connect this template's reference folder first")
        require(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) { "Folder access expired; reconnect it in template settings" }
        return uri
    }

    private fun children(tree: Uri, id: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    require(size < 5000) { "Folder contains too many entries" }
                    add(Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, cursor.getString(2),
                        if (cursor.columnCount > 3 && !cursor.isNull(3)) cursor.getLong(3).takeIf { it >= 0 } else null))
                }
            }
        } ?: error("Could not read reference folder")
    }

    /** Lists metadata only; automatic templates refresh on each fresh opening. */
    fun list(id: String): List<String> {
        val uri = tree(id)
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var entries = 0
        fun walk(parent: String, prefix: String, depth: Int) {
            require(depth <= 8 && visited.add(parent)) { "Reference folder is too deep or cyclic" }
            val children = children(uri, parent)
            entries += children.size
            require(entries <= 5000) { "Reference folder contains too many entries" }
            require(children.map { it.name }.distinct().size == children.size) { "Ambiguous duplicate filenames in reference folder" }
            children.forEach { entry ->
                require(entry.name.isNotBlank() && '/' !in entry.name && '\\' !in entry.name && entry.name !in setOf(".", "..") && !entry.name.any(Char::isISOControl)) { "Unsupported reference filename" }
                val path = prefix + entry.name
                if (entry.directory) walk(entry.id, "$path/", depth + 1)
                else {
                    require(isTemplateFilePath(path)) { "Invalid reference path" }
                    result += path
                    require(result.size <= 500) { "Choose a folder with at most 500 files for a recursive catalogue" }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(uri), "", 0)
        return result.sorted()
    }

    /** Resolves only exact names under the persisted tree grant; provider IDs stay private. */
    internal fun resolve(folder: String, relative: String, directory: Boolean = false): Pair<Uri, Entry> {
        require(relative.isEmpty() && directory || isTemplateFilePath(relative)) { "Invalid folder path" }
        val uri = tree(folder)
        var entry = Entry(DocumentsContract.getTreeDocumentId(uri), "", true)
        val parts = relative.split('/')
        if (relative.isNotEmpty()) parts.forEachIndexed { index, name ->
            entry = children(uri, entry.id).filter { it.name == name }.singleOrNull()
                ?: error("File is missing or ambiguous: $relative")
            require(entry.directory == (index < parts.lastIndex || directory)) { "Unexpected file or folder: $relative" }
        }
        return DocumentsContract.buildDocumentUriUsingTree(uri, entry.id) to entry
    }

    fun listDirectory(folder: String, path: String = ""): List<TemplateDirectoryEntry> {
        val (_, parent) = resolve(folder, path, directory = true)
        return children(tree(folder), parent.id).map { entry ->
            val relative = if (path.isEmpty()) entry.name else "$path/${entry.name}"
            require(isTemplateFilePath(relative) && '/' !in entry.name && '\\' !in entry.name) { "Unsupported filename" }
            TemplateDirectoryEntry(relative, entry.name, entry.directory, entry.mimeType, entry.size)
        }.also { entries -> require(entries.map { it.name }.distinct().size == entries.size) { "Ambiguous duplicate filenames" } }
            .sortedWith(compareBy({ !it.directory }, { it.name.lowercase() }, { it.path }))
    }

    internal fun readBytes(folder: String, relative: String): ByteArray {
        val (document, _) = resolve(folder, relative)
        return resolver.openInputStream(document)?.use { it.readBytesBounded(128000) }
            ?: error("Could not open file: $relative")
    }

    fun read(folder: String, relative: String): String = decodeTemplateReference(readBytes(folder, relative))

}

internal fun decodeTemplateReference(bytes: ByteArray): String {
    require(bytes.size <= 128000) { "Selected reference is too large" }
    val text = decodeTemplateFileText(bytes)
    require(text.isNotBlank()) { "Selected reference is empty" }
    return text
}

internal fun decodeTemplateFileText(bytes: ByteArray): String {
    require(bytes.size <= 128000) { "Selected file is too large" }
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (failure: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("File is not valid UTF-8 text", failure)
    }
    require('\u0000' !in text) { "File contains binary data" }
    return text
}
