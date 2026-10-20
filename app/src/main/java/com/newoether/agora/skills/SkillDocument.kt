package com.newoether.agora.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Native shortcut/UI sequencing metadata; tool calls, paths and child prompts belong in Markdown. */
@Serializable
data class SkillConfiguration(
    val shortcut: String = "",
    val beforeTemplate: String? = null,
    val afterTemplate: String? = null,
    val nextSkill: String? = null,
    val beforeTemplates: List<String> = emptyList(),
    val afterTemplates: List<String> = emptyList(),
) {
    val beforeSteps: List<String> get() = beforeTemplates.ifEmpty { listOfNotNull(beforeTemplate) }
    val afterSteps: List<String> get() = afterTemplates.ifEmpty { listOfNotNull(afterTemplate) }
    fun withTemplates(before: List<String>, after: List<String>) = copy(
        beforeTemplate = null, afterTemplate = null, beforeTemplates = before, afterTemplates = after,
        nextSkill = nextSkill.takeIf { after.isNotEmpty() })
    fun validate(): SkillConfiguration {
        require(shortcut.isEmpty() || shortcut.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Invalid shortcut" }
        require(shortcut !in setOf("skills", "skill")) { "Reserved shortcut" }
        require(beforeSteps.size + afterSteps.size <= 16) { "Use at most 16 template steps" }
        (listOfNotNull(beforeTemplate, afterTemplate) + beforeTemplates + afterTemplates).forEach {
            require(runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false)) { "Invalid template reference" }
        }
        require(nextSkill == null || afterSteps.isNotEmpty() && nextSkill.matches(Regex("[^/\\\\\r\n]{1,200}\\.md"))) {
            "Choose an After template before choosing the next Skill"
        }
        return this
    }
}

@Serializable
data class SkillDocument(
    val name: String,
    val instructions: String,
    val configuration: SkillConfiguration = SkillConfiguration(),
    val description: String = "",
) {
    fun encode(): String {
        configuration.validate()
        require(description.trim().split(Regex("\\s+")).filter(String::isNotEmpty).size <= 10) {
            "Keep the description to 10 words or fewer"
        }
        val text = if (configuration == SkillConfiguration()) instructions else
            "$PREFIX${Json.encodeToString(configuration)}\n-->\n$instructions"
        require(text.toByteArray().size <= MAX_BYTES) { "Skill is too large" }
        return text
    }
    companion object {
        const val MAX_BYTES = 1_048_576
        private const val PREFIX = "<!-- agora-skill/v1\n"
        private val reader = Json { ignoreUnknownKeys = true }
        fun decode(name: String, text: String): SkillDocument {
            require(text.toByteArray().size <= MAX_BYTES) { "Skill is too large" }
            if (!text.startsWith(PREFIX)) return SkillDocument(name, text)
            val end = text.indexOf("\n-->\n", PREFIX.length)
            require(end > 0) { "Incomplete Skill configuration" }
            // Read previous draft files without retaining removed IDs or folder/tool configuration.
            val config = reader.decodeFromString<SkillConfiguration>(text.substring(PREFIX.length, end)).validate()
            return SkillDocument(name, text.substring(end + 5), config)
        }
        fun revision(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Quoted filenames preserve ordinary names containing spaces. */
fun skillCommand(text: String): Pair<String, String>? {
    val value = text.trim()
    if (!value.startsWith("/skill ")) return null
    val rest = value.removePrefix("/skill ").trim()
    require(rest.isNotEmpty()) { "Choose a Skill" }
    if (!rest.startsWith('"')) return rest.substringBefore(' ') to rest.substringAfter(' ', "")
    val match = Regex("^\"(?:[^\"\\\\]|\\\\.)*\"").find(rest) ?: error("Invalid quoted Skill filename")
    return Json.decodeFromString<String>(match.value) to rest.substring(match.value.length).trimStart()
}
