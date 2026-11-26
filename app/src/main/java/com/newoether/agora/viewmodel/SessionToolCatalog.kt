package com.newoether.agora.viewmodel

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.tool.SessionToolGroup
import com.newoether.agora.tool.featureEnabled

/** Why a listed group currently contributes nothing to the request. */
internal enum class SessionToolUnavailable {
    /** The conversation switch for this family of tools is off. */
    SWITCH_OFF,

    /** Low Context Mode sends only the compact Shell and Web definitions. */
    LOW_CONTEXT,

    /** The switch is on, but this session published no tool for it (no server, no model, ...). */
    NO_TOOLS,
}

internal data class SessionToolEntry(
    val name: String,
    val description: String,
    /** Provider-visible cost of this one definition, priced like the context indicator. */
    val tokens: Int,
)

internal data class SessionToolGroupTools(
    val group: SessionToolGroup,
    val tools: List<SessionToolEntry>,
    val tokens: Int,
    val unavailable: SessionToolUnavailable?,
)

/**
 * Everything the session tool selector needs, captured once when it opens.
 *
 * Costs come from the same estimator and cost model as the context indicator, so a group's number
 * is the same cost the request pays for those definitions. [enabledGroups] reflects what the
 * conversation currently sends; the dialog edits a copy and writes it back.
 */
internal data class SessionToolCatalog(
    val groups: List<SessionToolGroupTools>,
    val enabledGroups: Set<SessionToolGroup>,
    /** Ids exactly as the conversation stored them, including groups this session cannot show. */
    val storedDisabledIds: Set<String>,
) {
    val totalToolCount: Int get() = groups.sumOf { it.tools.size }

    val enabledToolCount: Int
        get() = groups.filter { it.group in enabledGroups }.sumOf { it.tools.size }

    val enabledToolTokens: Int
        get() = groups.filter { it.group in enabledGroups }.sumOf { it.tokens }

    /**
     * Ids to persist after the dialog's edit. Only a group this session can switch follows the
     * new switches; a group that is off for another reason keeps whatever was stored, and ids this
     * build no longer knows survive untouched.
     */
    fun disabledIds(enabled: Set<SessionToolGroup>): Set<String> {
        val ids = storedDisabledIds.toMutableSet()
        groups.forEach { groupTools ->
            if (groupTools.unavailable != null) return@forEach
            val id = groupTools.group.id
            if (groupTools.group in enabled) ids -= id else ids += id
        }
        return ids
    }

    companion object {
        /** The only groups whose definitions a Low Context request still carries. */
        private val LOW_CONTEXT_GROUPS = setOf(SessionToolGroup.TERMINAL, SessionToolGroup.WEB)

        fun build(
            modelId: String,
            context: GenerationContext,
            definitionsByGroup: Map<SessionToolGroup, List<ToolDefinition>>,
            lowContextMode: Boolean = false,
        ): SessionToolCatalog {
            val estimator = ContextTokenEstimator.forModel(
                modelId, com.newoether.agora.api.LocalModelRuntime::exactTokenCountOrNull,
            )
            val emptyCost = estimator.estimateFixed(systemPrompt = null, tools = emptyList())
            fun cost(tools: List<ToolDefinition>): Int =
                (estimator.estimateFixed(systemPrompt = null, tools = tools) - emptyCost)
                    .coerceAtLeast(0)
            val groups = SessionToolGroup.entries.map { group ->
                val definitions = if (lowContextMode && group !in LOW_CONTEXT_GROUPS) {
                    emptyList()
                } else {
                    definitionsByGroup[group].orEmpty()
                }
                val entries = definitions.map { definition ->
                    SessionToolEntry(
                        name = definition.function.name,
                        description = definition.function.description,
                        tokens = cost(listOf(definition)),
                    )
                }
                SessionToolGroupTools(
                    group = group,
                    tools = entries,
                    tokens = cost(definitions),
                    unavailable = when {
                        lowContextMode && group !in LOW_CONTEXT_GROUPS ->
                            SessionToolUnavailable.LOW_CONTEXT
                        !group.featureEnabled(context) -> SessionToolUnavailable.SWITCH_OFF
                        entries.isEmpty() -> SessionToolUnavailable.NO_TOOLS
                        else -> null
                    },
                )
            }
            val disabled = SessionToolGroup.fromIds(context.disabledToolGroups)
            return SessionToolCatalog(
                groups = groups,
                enabledGroups = SessionToolGroup.entries.filterNotTo(mutableSetOf()) { it in disabled },
                storedDisabledIds = context.disabledToolGroups,
            )
        }
    }
}
