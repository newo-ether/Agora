package com.newoether.agora.skills

import com.newoether.agora.model.ToolCallData
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persisted as an ordinary tool result. No process-global stack or additional generation. */
@Serializable
data class SkillInvocation(
    val kind: String = "agora.skill-invocation/v1",
    val token: String,
    val rootToken: String,
    val runId: String,
    val chain: List<String>,
    val revision: String,
    val document: String,
    val name: String,
    val input: String,
    val description: String = "",
) {
    fun encode(): String = Json.encodeToString(this)
    companion object {
        const val MAX_CALLS = 24
        const val MAX_DEPTH = 6
        const val MAX_BYTES = 240_000
        fun history(calls: List<ToolCallData>): List<SkillInvocation> = calls
            .filter { it.toolName == "invoke_skill" }
            .mapNotNull { call -> call.structuredResult?.let { runCatching {
                Json.decodeFromString<SkillInvocation>(it).takeIf { frame ->
                    frame.kind == "agora.skill-invocation/v1" && frame.token == call.toolCallId
                }
            }.getOrNull() } }

        fun create(document: SkillDocument, raw: String, input: String, token: String,
                   parent: String?, history: List<SkillInvocation>, runId: String = "test"): SkillInvocation {
            require(input.length <= 8000) { "Skill input is too long" }
            require(history.count { it.runId == runId } < MAX_CALLS) { "Skill invocation budget exhausted" }
            require(history.filter { it.runId == runId }.sumOf { it.encode().toByteArray().size } + raw.toByteArray().size <= MAX_BYTES) {
                "Skill context budget exhausted"
            }
            val ancestors = if (parent.isNullOrBlank()) emptyList() else
                history.singleOrNull { it.token == parent }?.chain ?: error("Unknown parent Skill invocation")
            require(document.name !in ancestors) { "Cyclic Skill call" }
            require(ancestors.size < MAX_DEPTH) { "Skill nesting limit reached" }
            return SkillInvocation(token = token, rootToken = history.singleOrNull { it.token == parent }?.rootToken ?: token,
                runId = runId, chain = ancestors + document.name,
                revision = SkillDocument.revision(raw), document = raw, name = document.name, input = input, description = document.description)
                .also { frame ->
                    val bytes = frame.encode().toByteArray().size
                    require(bytes <= 80000) { "Skill exceeds tool persistence budget" }
                    require(history.filter { it.runId == runId }.sumOf { it.encode().toByteArray().size } + bytes <= MAX_BYTES) {
                        "Skill context budget exhausted"
                    }
                }
        }
    }
}
