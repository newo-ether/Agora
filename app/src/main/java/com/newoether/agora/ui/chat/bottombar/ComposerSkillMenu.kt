package com.newoether.agora.ui.chat.bottombar

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.data.SkillManager
import com.newoether.agora.skills.SkillAuthoring
import com.newoether.agora.templates.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local slash commands are Skills only; templates are opened by native Skill workflows. */
@Composable
internal fun ComposerSkillMenu(text: String, enabled: Boolean, skillManager: SkillManager?, onSelect: (String) -> Unit) {
    if (!enabled || skillManager == null) return
    val explicitSkills = text == "/skills" || text.startsWith("/skills ")
    val query = if (explicitSkills) text.removePrefix("/skills").trim() else templateMenuQuery(text) ?: return
    val revision by skillManager.catalogRevision.collectAsState()
    val entries by produceState<List<ComposerCommand>?>(null, skillManager, query, revision) {
        value = null
        try { value = withContext(Dispatchers.IO) {
            val skills = skillManager.listFiles().toMutableList()
            if (skills.none { it.name == SkillAuthoring.NAME }) skills += SkillManager.SkillFileInfo(
                SkillAuthoring.NAME, SkillAuthoring.document.description)
            localComposerCommands(query, skills)
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = emptyList() }
    }
    ComposerCommandMenu(entries ?: return, enabled = true,
        emptyLabel = stringResource(if (query.isEmpty()) R.string.skills_empty else R.string.search_no_results),
        onSelect = { onSelect(it.input) })
}

internal fun localComposerCommands(query: String, skills: List<SkillManager.SkillFileInfo>): List<ComposerCommand> {
    val commands = mutableSetOf<String>()
    val names = mutableSetOf<String>()
    return skills.sortedBy { it.name.lowercase() }.mapNotNull { skill ->
        val stem = skill.name.removeSuffix(".md")
        if (query.isNotEmpty() && !stem.contains(query, true) && !skill.shortcut.startsWith(query, true)) return@mapNotNull null
        val command = skill.shortcut.ifBlank { stem }
        if (!commands.add(command.lowercase()) || !names.add(stem.lowercase())) return@mapNotNull null
        val input = if (skill.shortcut.isNotBlank()) "/${skill.shortcut} " else "/skill ${Json.encodeToString(skill.name)} "
        ComposerCommand("skill:${skill.name}", "/$command", skill.description, input)
    }
}
