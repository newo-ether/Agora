package com.newoether.agora.tool

import com.newoether.agora.phone.PhoneToolProvider

/**
 * User-facing grouping of the tools one session exposes.
 *
 * Declarations are ordered the way the selector lists them, and each [id] is the persisted key in
 * `ConversationSettings.disabledToolGroups`. A provider that maps to no group is never filtered:
 * unknown extensions stay exactly as the plugin gave them.
 *
 * A [linked] group has no entry in that set: its selector switch is the conversation's own feature
 * switch (Web Search, Shell), so the composer and the selector always agree. Ids of linked groups
 * stored by older builds are ignored.
 */
internal enum class SessionToolGroup(val id: String, val linked: Boolean = false) {
    MEMORY("memory"),
    SKILLS("skills"),
    PAST_CONVERSATIONS("past_conversations"),
    WEB("web", linked = true),
    TERMINAL("terminal", linked = true),
    IMAGE("image"),
    TODO("todo"),
    PHONE("phone"),
    SUBAGENT("subagent"),
    AUTOMATION("automation"),
    ASK_USER("ask_user"),
    MCP("mcp"),
    ;

    companion object {
        private val byId = entries.associateBy(SessionToolGroup::id)

        fun fromId(id: String): SessionToolGroup? = byId[id]

        /**
         * Groups disabled by a stored setting, ignoring ids this build no longer knows and linked
         * groups, which only their feature switch controls.
         */
        fun fromIds(ids: Iterable<String>): Set<SessionToolGroup> =
            ids.mapNotNullTo(mutableSetOf()) { id -> fromId(id)?.takeUnless { it.linked } }

        fun of(provider: ToolProvider): SessionToolGroup? = when (provider) {
            is MemoryToolProvider -> MEMORY
            is SkillToolProvider -> SKILLS
            is RagToolProvider -> PAST_CONVERSATIONS
            is WebSearchToolProvider -> WEB
            is ShellToolProvider -> TERMINAL
            is ImageGenToolProvider -> IMAGE
            is TodoToolProvider -> TODO
            is PhoneToolProvider -> PHONE
            is SubagentToolProvider -> SUBAGENT
            is AutomationToolProvider -> AUTOMATION
            is AskUserToolProvider -> ASK_USER
            is McpToolProvider -> MCP
            else -> null
        }
    }
}

/** The conversation switch that gates each group before its provider emits any definition. */
internal fun SessionToolGroup.featureEnabled(
    context: com.newoether.agora.viewmodel.GenerationContext,
): Boolean = when (this) {
    SessionToolGroup.MEMORY -> context.accessSavedMemories || context.accessActiveMemory
    SessionToolGroup.SKILLS -> context.skillReadAccess || context.skillModifyAccess
    SessionToolGroup.PAST_CONVERSATIONS -> context.accessPastConversations
    SessionToolGroup.WEB -> context.webSearchEnabled
    SessionToolGroup.TERMINAL -> context.shellEnabled
    SessionToolGroup.IMAGE -> context.imageGenEnabled
    SessionToolGroup.TODO -> context.todoEnabled
    SessionToolGroup.PHONE -> context.phoneEnabled
    SessionToolGroup.SUBAGENT -> context.subagentToolsEnabled
    SessionToolGroup.AUTOMATION -> context.automationToolsEnabled
    SessionToolGroup.ASK_USER -> context.askUserEnabled
    SessionToolGroup.MCP -> context.mcpToolsEnabled
}
