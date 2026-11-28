package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class MemoryToolProvider(
    private val memoryManager: MemoryManager
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessSavedMemories && !ctx.accessActiveMemory) return emptyList()
        val tools = mutableListOf<ToolDefinition>()
        if (ctx.accessSavedMemories) {
            tools.addAll(
                listOf(
                    ToolDefinition(
                        function = ToolFunction(
                            name = "memory_list",
                            description = "List named entries in Agora's internal long-term memory store with their descriptions. Entries are not disk files; use file_* for device files.",
                            parameters = ToolParameters(properties = emptyMap())
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "memory_read",
                            description = "Read one or more named memory entries.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "name" to ToolProperty("string", "The entry name to read."),
                                    "names" to ToolProperty(
                                        "array",
                                        "Multiple entry names to read in one call.",
                                        items = ToolProperty("string", "An entry name.")
                                    )
                                ),
                                required = emptyList()
                            )
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "memory_create",
                            description = "Create a named memory entry with content and an optional description.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "name" to ToolProperty("string", "The entry name to create (e.g., 'notes.md')."),
                                    "content" to ToolProperty("string", "The markdown content for the entry."),
                                    "description" to ToolProperty(
                                        "string",
                                        "A short description of what this entry contains (optional)."
                                    )
                                ),
                                required = listOf("name", "content")
                            )
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "memory_edit",
                            description = "Edit a named memory entry. operation must be replace, patch, rename, or describe.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "name" to ToolProperty("string", "The current entry name."),
                                    "operation" to ToolProperty(
                                        "string",
                                        "One of: replace, patch, rename, describe."
                                    ),
                                    "content" to ToolProperty(
                                        "string",
                                        "Complete replacement content for replace. May be empty."
                                    ),
                                    "old_string" to ToolProperty(
                                        "string",
                                        "Exact non-empty unique text to replace for patch."
                                    ),
                                    "new_string" to ToolProperty(
                                        "string",
                                        "Replacement text for patch. May be empty to delete the match."
                                    ),
                                    "new_name" to ToolProperty(
                                        "string",
                                        "The target entry name for rename."
                                    ),
                                    "description" to ToolProperty(
                                        "string",
                                        "The new description for describe. May be empty to remove it."
                                    )
                                ),
                                required = listOf("name", "operation")
                            )
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "memory_delete",
                            description = "Delete a named memory entry.",
                            parameters = ToolParameters(
                                properties = mapOf("name" to ToolProperty("string", "The entry name to delete.")),
                                required = listOf("name")
                            )
                        )
                    )
                )
            )
        }
        if (ctx.accessActiveMemory) {
            tools.add(
                ToolDefinition(
                    function = ToolFunction(
                        name = "active_memory_read",
                        description = "Read Agora's active memory. Unlike the prompt copy captured once per turn, this reflects edits made during the current turn.",
                        parameters = ToolParameters(properties = emptyMap())
                    )
                )
            )
            tools.add(
                ToolDefinition(
                    function = ToolFunction(
                        name = "active_memory_update",
                        description = "Update Agora's active memory (injected into the system prompt every turn; editing invalidates the prompt cache, so batch changes). Specify mode explicitly: 'replace' (overwrite with 'content'), 'append' (add 'content' to end), 'prepend' (add 'content' to beginning), 'patch' (find 'old_string' exactly once and replace with 'new_string').",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "content" to ToolProperty("string", "The content to write (for replace/append/prepend modes)."),
                                "mode" to ToolProperty(
                                    "string",
                                    "Required. One of: replace, append, prepend, patch."
                                ),
                                "old_string" to ToolProperty(
                                    "string",
                                    "Exact string to find and replace in the active memory. Required for patch mode. Must match exactly once."
                                ),
                                "new_string" to ToolProperty(
                                    "string",
                                    "Replacement string for old_string in patch mode. Pass empty string to delete the matched text."
                                )
                            ),
                            required = listOf("content", "mode")
                        )
                    )
                )
            )
        }
        return tools
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = executeOneShotResult(name, arguments, ctx).text

    override suspend fun executeOneShotResult(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val argsStr = arguments.ifBlank { "{}" }
        val args =
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        fun arg(key: String): String = arg(args, key)

        val toolName = BuiltInToolNames.canonical(name)
        val requestedNames = if (toolName == "memory_read") {
            (args["names"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.content
            }.orEmpty().ifEmpty { listOf(arg("name")) }
        } else {
            listOf(arg("name"))
        }
        if (toolName in setOf("memory_read", "memory_edit")) {
            val invalidName = requestedNames.firstOrNull { entry ->
                '/' in entry || '\\' in entry
            }
            if (invalidName != null) return@withContext ToolExecutionResult(
                "Error: Memory entry not found or invalid name: $invalidName. This looks like a disk path; use file_read and specify server.",
                isError = true,
            )
        }
        try {
            ToolExecutionResult(when (BuiltInToolNames.canonical(name)) {
            "memory_list" -> {
                val files = memoryManager.listFiles()
                if (files.isEmpty()) {
                    buildJsonObject {
                        put("type", "memory_list")
                        putJsonArray("files") {}
                    }.toString()
                } else {
                    buildJsonObject {
                        put("type", "memory_list")
                        putJsonArray("files") {
                            files.forEach { f ->
                                add(
                                    buildJsonObject {
                                        put("name", f.name)
                                        put("description", f.description)
                                    }
                                )
                            }
                        }
                    }.toString()
                }
            }

            "memory_read" -> {
                val singleName = arg("name")
                val namesArray = args["names"] as? JsonArray
                if (namesArray != null && namesArray.isNotEmpty()) {
                    val names = namesArray.map {
                        (it as? JsonPrimitive)?.content ?: ""
                    }.filter { it.isNotEmpty() }
                    names.joinToString("\n\n") { name ->
                        "--- $name ---\n${memoryManager.readFile(name)}"
                    }
                } else if (singleName.isNotEmpty()) {
                    memoryManager.readFile(singleName)
                } else {
                    return@withContext ToolExecutionResult(
                        "Error: No entry name provided. Use 'name' for a single entry or 'names' for multiple entries.",
                        isError = true,
                    )
                }
            }

            "memory_create" -> memoryManager.createFile(
                arg("name"),
                arg("content"),
                arg("description")
            )

            "memory_edit" -> {
                val fileName = arg("name")
                when (arg("operation").trim().lowercase()) {
                    "replace" -> memoryManager.editFile(
                        name = fileName,
                        content = arg("content"),
                    )
                    "patch" -> {
                        val oldString = arg("old_string")
                        if (oldString.isEmpty()) {
                            return@withContext ToolExecutionResult(
                                "Error: patch requires a non-empty old_string.", isError = true,
                            )
                        } else {
                            memoryManager.editFile(
                                name = fileName,
                                oldString = oldString,
                                newString = arg("new_string"),
                            )
                        }
                    }
                    "rename" -> {
                        val newName = arg("new_name").takeIf(String::isNotBlank)
                        if (newName == null) {
                            return@withContext ToolExecutionResult(
                                "Error: rename requires a non-blank new_name.", isError = true,
                            )
                        } else {
                            memoryManager.editFile(
                                name = fileName,
                                newName = newName,
                            )
                        }
                    }
                    "describe" -> memoryManager.editFile(
                        name = fileName,
                        description = arg("description"),
                    )
                    else -> return@withContext ToolExecutionResult(
                        "Error: operation must be replace, patch, rename, or describe.", isError = true,
                    )
                }
            }

            "memory_delete" -> memoryManager.deleteFile(arg("name"))

            "active_memory_read" -> memoryManager.getActiveMemory()

            "active_memory_update" -> {
                val mode = arg("mode")
                if (mode.isBlank()) {
                    return@withContext ToolExecutionResult("Error: 'mode' is required.", isError = true)
                }
                if (mode !in setOf("replace", "append", "prepend", "patch")) {
                    return@withContext ToolExecutionResult(
                        "Error: mode must be replace, append, prepend, or patch.", isError = true,
                    )
                }
                val oldStr = arg("old_string").ifBlank { null }
                val newStr = arg("new_string").ifBlank { null }
                if (mode == "patch" && oldStr == null) {
                    return@withContext ToolExecutionResult(
                        "Error: 'old_string' is required for patch mode.", isError = true,
                    )
                } else {
                    memoryManager.updateActiveMemory(arg("content"), mode, oldStr, newStr)
                }
            }

            else -> return@withContext ToolExecutionResult("Unknown tool: $name", isError = true)
            })
        } catch (error: IllegalArgumentException) {
            if (BuiltInToolNames.canonical(name) !in setOf("memory_read", "memory_edit") ||
                error.message?.startsWith("File not found:") != true) throw error
            ToolExecutionResult(
                "Error: ${error.message}. This looks like a disk path; use file_read and specify server.",
                isError = true,
            )
        }
    }

    override fun handles(name: String): Boolean = BuiltInToolNames.canonical(name) in setOf(
        "memory_list",
        "memory_read",
        "memory_create",
        "memory_edit",
        "memory_delete",
        "active_memory_read",
        "active_memory_update"
    )
}
