package com.newoether.agora.templates

/** Shared by the slash picker and Send so the same text cannot take different routes. */
internal fun templateMenuQuery(text: String): String? =
    text.takeIf { it.matches(Regex("/[a-zA-Z0-9-]{0,64}")) }?.drop(1)?.lowercase()

internal fun matchingMenuTemplates(query: String, templates: List<InteractiveTemplate>) =
    templates.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) ||
        it.shortcut?.startsWith(query, ignoreCase = true) == true }.sortedBy { it.name.lowercase() }

/** Explicit shortcuts win. Otherwise Send uses the same name/shortcut matches as the picker. */
internal fun templateCommandMatches(text: String, templates: List<InteractiveTemplate>): List<InteractiveTemplate> {
    val command = text.trim()
    resolveTemplateShortcut(command, templates)?.let { return listOf(it) }
    val query = templateMenuQuery(command) ?: return emptyList()
    return matchingMenuTemplates(query, templates)
}
