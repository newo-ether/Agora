package com.newoether.agora.skills

import kotlinx.serialization.Serializable

/** Native routing snapshot carried by an ordinary opened template, never executed by HTML. */
@Serializable
data class SkillTemplateFlow(
    val skill: String,
    val phase: String,
    val input: String = "",
    val nextSkill: String? = null,
    val parent: String? = null,
    val ancestors: List<String> = emptyList(),
    val sourceDirectory: String? = null,
    val sourcePath: String? = null,
    val remainingTemplates: List<String> = emptyList(),
    val sources: List<SkillTemplateSource> = emptyList(),
) {
    fun validate() {
        require(remainingTemplates.size <= 15 && sources.size <= 16) { "Too many template steps or sources" }
        remainingTemplates.forEach { require(runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false)) { "Invalid template reference" } }
        sources.forEach { it.validate() }
        require(phase in setOf("before", "after")) { "Unknown template phase" }
        require(skill.isNotBlank() && skill.length <= 200 && '/' !in skill && '\\' !in skill) { "Invalid Skill filename" }
        require(input.length <= 8000 && ancestors.size <= SkillInvocation.MAX_DEPTH && ancestors.distinct().size == ancestors.size) { "Invalid workflow ancestry" }
        require((sourceDirectory == null) == (sourcePath == null)) { "Incomplete source reference" }
        sourceDirectory?.let { com.newoether.agora.templates.validateTemplateDirectory(it) }
        sourcePath?.let { require(com.newoether.agora.templates.isTemplateSourcePath(it, true)) { "Invalid source reference" } }
        require(parent == null && ancestors.isEmpty() || !parent.isNullOrBlank() && ancestors.isNotEmpty()) { "Missing workflow parent" }
    }
    fun target(): String? = if (phase == "before") skill else nextSkill
    fun checkTarget(name: String) {
        require(name !in ancestors && ancestors.size < SkillInvocation.MAX_DEPTH) { "Cyclic or excessive Skill chain" }
    }
}

@Serializable
data class SkillTemplateSource(val path: String, val directory: String? = null, val folderId: String? = null) {
    fun validate() {
        directory?.let { com.newoether.agora.templates.validateTemplateDirectory(it) }
        require(folderId == null || directory == null) { "Conflicting reference locations" }
        folderId?.let { require(runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false)) { "Invalid reference folder" } }
        require(if (folderId != null) com.newoether.agora.templates.isTemplateFilePath(path) else com.newoether.agora.templates.isTemplateSourcePath(path, directory != null)) { "Invalid source reference" }
    }
}

internal fun bindSkillTemplate(template: com.newoether.agora.templates.InteractiveTemplate, flow: SkillTemplateFlow): com.newoether.agora.templates.InteractiveTemplate {
    flow.validate()
    val input = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), flow.input).replace("<", "\\u003c")
    return template.copy(linkedSkill = null, skillFlow = flow, answer = template.answer.copy(
        jsExpressions = listOf("globalThis.agoraWorkflowInput = $input;") + template.answer.jsExpressions,
    )).validate()
}

/** Small native history receipt; UI code and routing are excluded from provider context. */
@Serializable
internal data class SkillCompletionReceipt(val token: String, val kind: String = "agora.skill-completion/v1") {
    fun encode(): String = kotlinx.serialization.json.Json.encodeToString(serializer(), this)
    companion object {
        fun token(raw: String?): String? = raw?.let {
            runCatching { kotlinx.serialization.json.Json.decodeFromString(serializer(), it)
                .takeIf { receipt -> receipt.kind == "agora.skill-completion/v1" }?.token }.getOrNull()
                ?: runCatching { com.newoether.agora.templates.InteractiveTemplate.decode(it).skillFlow
                    ?.takeIf { flow -> flow.phase == "after" }?.parent }.getOrNull()
        }
    }
}
