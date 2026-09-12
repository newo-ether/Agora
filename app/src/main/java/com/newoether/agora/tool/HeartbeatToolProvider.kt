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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tool provider for heartbeat operations.
 * Currently provides: promote_learning
 */
class HeartbeatToolProvider(
    private val skillManager: SkillManager,
    private val settingsManager: com.newoether.agora.data.SettingsManager,
) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "promote_learning",
                description = "Promote a detected pattern to a persistent skill. Use when the heartbeat identifies a recurring pattern worth saving.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern_key" to ToolProperty(
                            "string",
                            "Unique identifier for the pattern (e.g., 'daily_standup_summary', 'code_review_checklist')",
                        ),
                        "skill_name" to ToolProperty(
                            "string",
                            "Human-readable name for the new skill",
                        ),
                        "skill_description" to ToolProperty(
                            "string",
                            "What this skill does and when to use it",
                        ),
                        "skill_instructions" to ToolProperty(
                            "string",
                            "Detailed instructions for the model when using this skill",
                        ),
                        "skill_tools" to ToolProperty(
                            "array",
                            "List of tool names this skill is expected to use (optional, recorded as a note in the skill file)",
                            items = ToolProperty("string", "Tool name"),
                        ),
                    ),
                    required = listOf("pattern_key", "skill_name", "skill_description", "skill_instructions"),
                ),
            ),
        ),
    )

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        when (name) {
            "promote_learning" -> promoteLearning(arguments)
            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name == "promote_learning"

    private fun promoteLearning(arguments: String): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = json.decodeFromString<Map<String, JsonElement>>(argsStr)
        fun arg(key: String): String? = (args[key] as? JsonPrimitive)?.content

        val patternKey = arg("pattern_key") ?: return "Missing required argument: pattern_key"
        val skillName = arg("skill_name") ?: return "Missing required argument: skill_name"
        val skillDescription = arg("skill_description") ?: return "Missing required argument: skill_description"
        val skillInstructions = arg("skill_instructions") ?: return "Missing required argument: skill_instructions"
        val skillTools = (args["skill_tools"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()

        val fileName = "promoted_${patternKey.replace(Regex("[^A-Za-z0-9_-]"), "_")}"
        val content = buildString {
            appendLine("# $skillName")
            appendLine()
            appendLine(skillDescription)
            appendLine()
            appendLine("## Instructions")
            appendLine()
            appendLine(skillInstructions)
            if (skillTools.isNotEmpty()) {
                appendLine()
                appendLine("## Tools typically used")
                skillTools.forEach { appendLine("- $it") }
            }
        }

        return try {
            skillManager.createFile(fileName, content, skillDescription)
            "Skill '$skillName' created and installed ($fileName.md). It is now available for use. Pattern '$patternKey' promoted."
        } catch (e: IllegalArgumentException) {
            "Could not create skill: ${e.message}"
        }
    }
}
