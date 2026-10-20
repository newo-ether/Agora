package com.newoether.agora.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteRuntime
import com.newoether.agora.remote.distinctRemoteSkills
import com.newoether.agora.ui.chat.bottombar.ComposerCommand
import com.newoether.agora.ui.chat.bottombar.ComposerCommandMenu

/** Only the connected session's native commands. Local Skills are never copied or sent here. */
@Composable
internal fun RemoteSkillsMenu(text: String, runtime: RemoteRuntime?, enabled: Boolean, onSelect: (String) -> Unit) {
    val query = remoteSkillsMenuQuery(text) ?: return
    val skills = runtime?.takeIf { it.status in setOf("idle", "ready", "active") }?.skills.orEmpty()
    if (skills.isEmpty()) return
    val entries = remember(query, skills) { distinctRemoteSkills(skills).filter {
        it.name.contains(query, true) || it.command.drop(1).contains(query, true)
    }.map { ComposerCommand("remote:${it.command}", it.command, it.description, "${it.command} ") } }
    ComposerCommandMenu(entries, enabled, stringResource(R.string.search_no_results), onSelect = { onSelect(it.input) })
}

internal fun remoteSkillsMenuQuery(text: String): String? =
    text.takeIf { it.matches(Regex("/[A-Za-z0-9_.:-]{0,200}")) }?.drop(1)
