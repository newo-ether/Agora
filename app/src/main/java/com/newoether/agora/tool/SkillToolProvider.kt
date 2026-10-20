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
                    name = "list_skill_files",
                    description = "List saved Skill filenames only. Descriptions and instructions are loaded only when a Skill is called.",
                ),
                tool(
                    name = "read_skill_file",
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
                    name = "create_skill_file",
                    description = "Create a saved Markdown skill file.",
                    properties = mapOf(
                        "name" to ToolProperty("string", "The skill file name."),
                        "content" to ToolProperty("string", "The complete Markdown instructions."),
                        "description" to ToolProperty("string", "A concise description of at most ten words."),
                    ),
                    required = listOf("name", "content"),
                ),
                tool(
                    name = "edit_skill_file",
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
                    name = "delete_skill_file",
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
        if (!ctx.skillReadAccess && name in READ_TOOL_NAMES) {
            return@withContext ToolExecutionResult("Error: Skill read access is disabled.", isError = true)
        }
        if (!ctx.skillModifyAccess && name in MODIFY_TOOL_NAMES) {
            return@withContext ToolExecutionResult("Error: Skill modify access is disabled.", isError = true)
        }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
            arguments.ifBlank { "{}" },
        )
        fun arg(key: String): String = arg(args, key)
        ToolExecutionResult(when (name) {
            "list_skill_files" -> buildJsonObject {
                put("type", "list_skill_files")
                putJsonArray("files") {
                    skillManager.listFiles().forEach { file ->
                        add(
                            buildJsonObject {
                                put("name", file.name)
                            },
                        )
                    }
                }
            }.toString()
            "read_skill_file" -> {
                val names = (args["names"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                    .orEmpty()
                when {
                    names.isNotEmpty() -> names.joinToString("\n\n") { fileName ->
                        readSelectedSkill(fileName)
                    }
                    arg("name").isNotBlank() -> readSelectedSkill(arg("name"))
                    else -> return@withContext ToolExecutionResult("Error: Provide name or names.", isError = true)
                }
            }
            "create_skill_file" -> skillManager.createFile(
                name = arg("name"),
                content = arg("content"),
                description = arg("description"),
            )
            "edit_skill_file" -> {
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
            "delete_skill_file" -> skillManager.deleteFile(arg("name"))
            else -> return@withContext ToolExecutionResult("Unknown tool: $name", isError = true)
        })
    }

    private fun readSelectedSkill(name: String): String {
        val doc = skillManager.document(name)
        return "--- ${doc.name} ---\nDescription: ${doc.description}\n${doc.instructions}"
    }

    override fun handles(name: String): Boolean = name in TOOL_NAMES

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
            "list_skill_files",
            "read_skill_file",
            "create_skill_file",
            "edit_skill_file",
            "delete_skill_file",
        )
        val READ_TOOL_NAMES = setOf("list_skill_files", "read_skill_file")
        val MODIFY_TOOL_NAMES = setOf("create_skill_file", "edit_skill_file", "delete_skill_file")
    }
}
