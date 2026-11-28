package com.newoether.agora.tool

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Disk-read formatting and backend lifetime, independent of shell command execution. */
internal suspend fun executeDiskFileRead(
    arguments: String,
    openBackend: suspend (String) -> Backend?,
    missingServer: (String) -> String,
): String {
    val args = parseToolArgs(arguments)
    val path = arg(args, "path")
    if (path.isBlank()) return jsonError("file_read", "path is required")
    val serverName = arg(args, "server")
    val (readArgs, argError) = parseFileReadArgs(args)
    if (readArgs == null) return jsonError("file_read", argError ?: "Invalid arguments")
    val backend = openBackend(serverName)
        ?: return withMemoryEntryHint(path, jsonError("file_read", missingServer(serverName)))
    val serverLabel = backend.device?.name ?: "Local Sandbox"
    try {
        val output = if (readArgs.lineMode) {
            lineModeFileReadJson(serverLabel, path, readArgs.offset, readArgs.startLine, readArgs.maxLines) { at, size ->
                backend.fileRead(path, at, size)
            }
        } else {
            val result = try {
                backend.fileRead(path, readArgs.offset, readArgs.limit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return withMemoryEntryHint(path, fileReadErrorJson(serverLabel, path, error.message ?: "Read failed"))
            }
            if (result.error != null) fileReadErrorJson(serverLabel, path, result.error)
            else boundedFileReadJson(server = serverLabel, path = path, result = result)
        }
        return withMemoryEntryHint(path, output)
    } finally {
        backend.close()
    }
}

/** Normalize only explicit failed reads. The backend diagnostic remains available for inspection. */
internal fun fileReadErrorJson(server: String, path: String, diagnostic: String): String {
    val reason = diagnostic.trim()
    val missingReasons = setOf(
        "The system cannot find the file specified.",
        "The system cannot find the path specified.",
        "no such file or directory",
        "file does not exist",
    )
    val missing = reason.equals("File not found: $path", ignoreCase = true) ||
        listOf("open", "stat", "lstat").any { operation ->
            // Conch wraps an OS PathError in the operation name: "open: open <path>: <reason>".
            listOf("$operation $path: ", "$operation: $operation $path: ").any { prefix ->
                reason.startsWith(prefix) && reason.removePrefix(prefix).let { suffix ->
                    missingReasons.any { it.equals(suffix, ignoreCase = true) }
                }
            }
        } || listOf(
            "$path (No such file or directory)",
            "$path (The system cannot find the file specified)",
        ).any { reason.equals(it, ignoreCase = true) }
    val message = if (missing) "File not found: $path" else diagnostic
    return buildJsonObject {
        put("type", "file_read")
        put("error", "error")
        put("message", message)
        put("server", server)
        put("path", path)
        if (missing && diagnostic != message) put("detail", diagnostic)
    }.toString()
}

/** A bare Markdown name is legal on disk: add a hint without preventing a relative read. */
internal fun withMemoryEntryHint(path: String, output: String): String {
    if ('/' in path || '\\' in path || !path.endsWith(".md", ignoreCase = true)) return output
    val root = runCatching { Json.parseToJsonElement(output) as? JsonObject }.getOrNull() ?: return output
    return JsonObject(root + ("hint" to JsonPrimitive(
        "If this is an Agora memory entry, use memory_read with its name. Disk files use file_read and server.",
    ))).toString()
}
