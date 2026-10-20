package com.newoether.agora.templates

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class TemplateDirectoryEntry(val path: String, val name: String, val directory: Boolean,
    val mimeType: String? = null, val size: Long? = null)

@Serializable
data class TemplateDirectoryPage(val entries: List<TemplateDirectoryEntry>, val nextOffset: Int?)

/** A read-only request relative to the folder selected in native settings, never a URI or grant ID. */
internal data class TemplateFileRequest(val method: String, val path: String, val encoding: String = "utf8",
    val offset: Int = 0, val limit: Int = 200) {
    companion object {
        fun parse(method: String, value: JsonElement?): TemplateFileRequest {
            require(method in setOf("listDirectory", "readTextFile", "readFile")) { "Unknown folder operation" }
            val args = value as? JsonObject ?: error("Pass a folder request object")
            val path = (args["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing relative path")
            require(path.isEmpty() && method == "listDirectory" || isTemplateFilePath(path)) { "Invalid relative path" }
            val encoding = if ("encoding" !in args) "utf8" else
                (args["encoding"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid encoding")
            require(encoding in setOf("utf8", "base64") && (method == "readFile" || encoding == "utf8")) { "Unsupported encoding" }
            fun number(key: String, fallback: Int) = if (key !in args) fallback else
                (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("Invalid $key")
            val offset = number("offset", 0); val limit = number("limit", 200)
            require(offset in 0..5000 && limit in 1..200) { "Invalid directory page" }
            return TemplateFileRequest(method, path, encoding, offset, limit)
        }
    }
}

/** Called on IO. All document resolution remains inside the persisted Android tree grant. */
internal fun TemplateAndroidSources.execute(template: InteractiveTemplate, request: TemplateFileRequest): JsonElement {
    val folder = checkNotNull(template.sourceFolderId) { "Choose this template's folder in Settings → Templates" }
    return when (request.method) {
        "listDirectory" -> {
            require(request.offset in 0..5000 && request.limit in 1..200) { "Invalid directory page" }
            val entries = listDirectory(folder, request.path)
            val end = minOf(entries.size, request.offset + request.limit)
            Json.parseToJsonElement(Json.encodeToString(TemplateDirectoryPage(entries.drop(request.offset).take(request.limit),
                end.takeIf { it < entries.size })))
        }
        "readTextFile", "readFile" -> {
            val bytes = readBytes(folder, request.path)
            JsonPrimitive(if (request.encoding == "base64") Base64.encodeToString(bytes, Base64.NO_WRAP)
                else decodeTemplateFileText(bytes))
        }
        else -> error("Unknown folder operation")
    }
}
