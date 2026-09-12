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
                            name = "list_memory_files",
                            description = "List all files in the memory database with their names and descriptions.",
                            parameters = ToolParameters(properties = emptyMap())
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "read_memory_file",
                            description = "Read the content of one or more files from the memory database.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "name" to ToolProperty("string", "The file name to read."),
                                    "names" to ToolProperty(
                                        "array",
                                        "Multiple file names to read in one call.",
                                        items = ToolProperty("string", "A file name.")
                                    )
                                ),
                                required = emptyList()
                            )
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "create_memory_file",
                            description = "Create a new file in the memory database with the given content and optional description.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "name" to ToolProperty("string", "The file name to create (e.g., 'notes.md')."),
                                    "content" to ToolProperty("string", "The markdown content for the file."),
                                    "description" to ToolProperty(
                                        "string",
                                        "A short description of what this file contains (optional)."
                                    )
                                ),
                                required = listOf("name", "content")
                            )
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "edit_memory_file",
                            description = "Edit one aspect of a memory file. operation must be replace, patch, rename, or describe.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "name" to ToolProperty("string", "The current file name."),
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
                                        "The target file name for rename."
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
                            name = "delete_memory_file",
                            description = "Delete a file from the memory database.",
                            parameters = ToolParameters(
                                properties = mapOf("name" to ToolProperty("string", "The file name to delete.")),
                                required = listOf("name")
                            )
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "pin_memory_file",
                            description = "Pin or unpin a memory file. Pinned files are considered promoted and will no longer be suggested as promotion candidates.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "name" to ToolProperty("string", "The file name to pin/unpin."),
                                    "pinned" to ToolProperty("boolean", "True to pin, false to unpin.")
                                ),
                                required = listOf("name", "pinned")
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
                        name = "update_active_memory",
                        description = "Update the active memory context. Modes: 'replace' (overwrite with 'content'), 'append' (add 'content' to end), 'prepend' (add 'content' to beginning), 'patch' (find 'old_string' exactly once and replace with 'new_string'). Default is replace.",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "content" to ToolProperty("string", "The content to write (for replace/append/prepend modes)."),
                                "mode" to ToolProperty(
                                    "string",
                                    "One of: replace, append, prepend, patch. Default is replace."
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
                            required = listOf("content")
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
    ): String = withContext(Dispatchers.IO) {
        val argsStr = arguments.ifBlank { "{}" }
        val args =
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        fun arg(key: String): String =
            (args[key] as? JsonPrimitive)?.content ?: ""
        fun argBool(key: String): Boolean =
            (args[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

        when (name) {
            "list_memory_files" -> {
                val files = memoryManager.listFiles()
                if (files.isEmpty()) {
                    buildJsonObject {
                        put("type", "list_memory_files")
                        putJsonArray("files") {}
                    }.toString()
                } else {
                    buildJsonObject {
                        put("type", "list_memory_files")
                        putJsonArray("files") {
                            files.forEach { f ->
                                add(
                                    buildJsonObject {
                                        put("name", f.name)
                                        put("description", f.description)
                                        put("pinned", f.pinned)
                                    }
                                )
                            }
                        }
                    }.toString()
                }
            }

            "read_memory_file" -> {
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
                    "Error: No file name provided. Use 'name' for a single file or 'names' for multiple files."
                }
            }

            "create_memory_file" -> memoryManager.createFile(
                arg("name"),
                arg("content"),
                arg("description")
            )

            "edit_memory_file" -> {
                val fileName = arg("name")
                when (arg("operation").trim().lowercase()) {
                    "replace" -> memoryManager.editFile(
                        name = fileName,
                        content = arg("content"),
                    )
                    "patch" -> {
                        val oldString = arg("old_string")
                        if (oldString.isEmpty()) {
                            "Error: patch requires a non-empty old_string."
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
                            "Error: rename requires a non-blank new_name."
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
                    else -> "Error: operation must be replace, patch, rename, or describe."
                }
            }

            "delete_memory_file" -> memoryManager.deleteFile(arg("name"))
            
            "pin_memory_file" -> memoryManager.pinFile(arg("name"), argBool("pinned"))

            "update_active_memory" -> {
                val mode = arg("mode").ifBlank { "replace" }
                val oldStr = arg("old_string").ifBlank { null }
                val newStr = arg("new_string").ifBlank { null }
                if (mode == "patch" && oldStr == null) {
                    "Error: 'old_string' is required for patch mode."
                } else {
                    memoryManager.updateActiveMemory(arg("content"), mode, oldStr, newStr)
                }
            }

            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in setOf(
        "list_memory_files",
        "read_memory_file",
        "create_memory_file",
        "edit_memory_file",
        "delete_memory_file",
        "pin_memory_file",
        "update_active_memory"
    )
}
