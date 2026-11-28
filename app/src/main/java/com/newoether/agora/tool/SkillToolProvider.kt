package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.SkillManager
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class SkillToolProvider(
    private val skillManager: SkillManager,
) : ToolProvider {
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.skillReadAccess && !ctx.skillModifyAccess) return emptyList()
        val readTools = if (ctx.skillReadAccess) {
            listOf(
                tool(
                    name = "skill_list",
                    description = "List saved skill files with their names and compact descriptions.",
                ),
                tool(
                    name = "skill_read",
                    description = "Read one or more saved skill files. Read a relevant skill before applying it.",
                    properties = mapOf(
                        "name" to ToolProperty("string", "One skill file name."),
                        "names" to ToolProperty(
                            "array",
                            "Multiple skill file names.",
                            items = ToolProperty("string", "A skill file name."),
                        ),
                    ),
                ),
            )
        } else {
            emptyList()
        }
        val modifyTools = if (ctx.skillModifyAccess) {
            listOf(
                tool(
                    name = "skill_create",
                    description = "Create a saved Markdown skill file.",
                    properties = mapOf(
                        "name" to ToolProperty("string", "The skill file name."),
                        "content" to ToolProperty("string", "The complete Markdown instructions."),
                        "description" to ToolProperty("string", "A compact catalog description."),
                    ),
                    required = listOf("name", "content"),
                ),
                tool(
                    name = "skill_edit",
                    description = "Edit one aspect of a saved skill file. operation must be replace, patch, rename, or describe.",
                    properties = mapOf(
                        "name" to ToolProperty("string", "The current file name."),
                        "operation" to ToolProperty(
                            "string",
                            "One of: replace, patch, rename, describe.",
                        ),
                        "content" to ToolProperty(
                            "string",
                            "Complete replacement content for replace. May be empty.",
                        ),
                        "old_string" to ToolProperty(
                            "string",
                            "Exact non-empty unique text to replace for patch.",
                        ),
                        "new_string" to ToolProperty(
                            "string",
                            "Replacement text for patch. May be empty to delete the match.",
                        ),
                        "new_name" to ToolProperty("string", "The target file name for rename."),
                        "description" to ToolProperty(
                            "string",
                            "The new compact description for describe. May be empty to remove it.",
                        ),
                    ),
                    required = listOf("name", "operation"),
                ),
                tool(
                    name = "skill_delete",
                    description = "Delete a saved skill file.",
                    properties = mapOf(
                        "name" to ToolProperty("string", "The skill file name to delete."),
                    ),
                    required = listOf("name"),
                ),
            )
        } else {
            emptyList()
        }
        return readTools + modifyTools
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
        if (!ctx.skillReadAccess && BuiltInToolNames.canonical(name) in READ_TOOL_NAMES) {
            return@withContext ToolExecutionResult("Error: Skill read access is disabled.", isError = true)
        }
        if (!ctx.skillModifyAccess && BuiltInToolNames.canonical(name) in MODIFY_TOOL_NAMES) {
            return@withContext ToolExecutionResult("Error: Skill modify access is disabled.", isError = true)
        }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
            arguments.ifBlank { "{}" },
        )
        fun arg(key: String): String = arg(args, key)
        ToolExecutionResult(when (BuiltInToolNames.canonical(name)) {
            "skill_list" -> buildJsonObject {
                put("type", "skill_list")
                putJsonArray("files") {
                    skillManager.listFiles().forEach { file ->
                        add(
                            buildJsonObject {
                                put("name", file.name)
                                put("description", file.description)
                            },
                        )
                    }
                }
            }.toString()
            "skill_read" -> {
                val names = (args["names"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                    .orEmpty()
                when {
                    names.isNotEmpty() -> names.joinToString("\n\n") { fileName ->
                        "--- $fileName ---\n${skillManager.readFile(fileName)}"
                    }
                    arg("name").isNotBlank() -> skillManager.readFile(arg("name"))
                    else -> return@withContext ToolExecutionResult("Error: Provide name or names.", isError = true)
                }
            }
            "skill_create" -> skillManager.createFile(
                name = arg("name"),
                content = arg("content"),
                description = arg("description"),
            )
            "skill_edit" -> {
                val fileName = arg("name")
                when (arg("operation").trim().lowercase()) {
                    "replace" -> skillManager.editFile(
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
                            skillManager.editFile(
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
                            skillManager.editFile(
                                name = fileName,
                                newName = newName,
                            )
                        }
                    }
                    "describe" -> skillManager.editFile(
                        name = fileName,
                        description = arg("description"),
                    )
                    else -> return@withContext ToolExecutionResult(
                        "Error: operation must be replace, patch, rename, or describe.", isError = true,
                    )
                }
            }
            "skill_delete" -> skillManager.deleteFile(arg("name"))
            else -> return@withContext ToolExecutionResult("Unknown tool: $name", isError = true)
        })
    }

    override fun handles(name: String): Boolean = BuiltInToolNames.canonical(name) in TOOL_NAMES

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty> = emptyMap(),
        required: List<String> = emptyList(),
    ) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = properties, required = required),
        ),
    )

    private companion object {
        val TOOL_NAMES = setOf(
            "skill_list",
            "skill_read",
            "skill_create",
            "skill_edit",
            "skill_delete",
        )
        val READ_TOOL_NAMES = setOf("skill_list", "skill_read")
        val MODIFY_TOOL_NAMES = setOf("skill_create", "skill_edit", "skill_delete")
    }
}
