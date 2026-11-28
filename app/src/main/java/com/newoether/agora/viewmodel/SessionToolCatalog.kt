package com.newoether.agora.viewmodel

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.tool.SessionToolGroup
import com.newoether.agora.tool.featureEnabled

/** Stand-in id so a New Chat can list and price tools before it owns a conversation row. */
internal const val SESSION_TOOLS_PREVIEW_CONVERSATION_ID = "session-tools-preview-conversation"

/** Why a listed group currently contributes nothing to the request. */
internal enum class SessionToolUnavailable {
    /** The switch for this family of tools is off (the global one, for a linked group). */
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
    /**
     * Tool-use preamble the provider charges once per request that carries tools. Group and tool
     * figures exclude it; a total adds it once, as the context indicator does.
     */
    val toolSetTokens: Int = 0,
) {
    val totalToolCount: Int get() = groups.sumOf { it.tools.size }

    val enabledToolCount: Int
        get() = groups.filter { it.group in enabledGroups }.sumOf { it.tools.size }

    val enabledToolTokens: Int
        get() = tokensOf(groups.filter { it.group in enabledGroups })

    /** Request cost of [selected] together: their definitions plus one tool-use preamble. */
    fun tokensOf(selected: List<SessionToolGroupTools>): Int =
        selected.sumOf { it.tokens } +
            if (selected.any { it.tools.isNotEmpty() }) toolSetTokens else 0

    /**
     * Ids to persist after the dialog's edit. Only a group this session can switch follows the
     * new switches; a group that is off for another reason keeps whatever was stored, and ids this
     * build no longer knows survive untouched. Linked groups never appear: their feature switch
     * holds their state, and ids older builds stored for them are dropped.
     */
    fun disabledIds(enabled: Set<SessionToolGroup>): Set<String> {
        val ids = storedDisabledIds.filterTo(mutableSetOf()) { SessionToolGroup.fromId(it)?.linked != true }
        groups.forEach { groupTools ->
            if (groupTools.unavailable != null || groupTools.group.linked) return@forEach
            val id = groupTools.group.id
            if (groupTools.group in enabled) ids -= id else ids += id
        }
        return ids
    }

    companion object {
        /** The only groups whose definitions a Low Context request still carries. */
        private val LOW_CONTEXT_GROUPS = setOf(SessionToolGroup.TERMINAL, SessionToolGroup.WEB)

        /**
         * [probe] is [context] with each linked group's feature switch at its global value, so a
         * linked group the conversation turned off still lists its tools and can be switched back
         * on here. [definitionsByGroup] must come from the same probe.
         */
        fun build(
            modelId: String,
            context: GenerationContext,
            definitionsByGroup: Map<SessionToolGroup, List<ToolDefinition>>,
            lowContextMode: Boolean = false,
            probe: GenerationContext = context,
        ): SessionToolCatalog {
            val estimator = ContextTokenEstimator.forModel(
                modelId, com.newoether.agora.api.LocalModelRuntime::exactTokenCountOrNull,
            )
            val emptyCost = estimator.estimateFixed(systemPrompt = null, tools = emptyList())
            // The preamble is charged once per request; counting it inside every group and every
            // tool made the selector total exceed the context indicator by one preamble per group.
            val toolSetTokens = estimator.toolSetOverheadTokens()
            fun cost(tools: List<ToolDefinition>): Int =
                if (tools.isEmpty()) 0
                else (estimator.estimateFixed(systemPrompt = null, tools = tools) - emptyCost - toolSetTokens)
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
                        !group.featureEnabled(probe) -> SessionToolUnavailable.SWITCH_OFF
                        entries.isEmpty() -> SessionToolUnavailable.NO_TOOLS
                        else -> null
                    },
                )
            }
            val disabled = SessionToolGroup.fromIds(context.disabledToolGroups)
            return SessionToolCatalog(
                groups = groups,
                enabledGroups = SessionToolGroup.entries.filterTo(mutableSetOf()) { group ->
                    if (group.linked) group.featureEnabled(context) else group !in disabled
                },
                storedDisabledIds = context.disabledToolGroups,
                toolSetTokens = toolSetTokens,
            )
        }
    }
}
