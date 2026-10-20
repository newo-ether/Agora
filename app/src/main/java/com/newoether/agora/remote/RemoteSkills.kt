package com.newoether.agora.remote

import kotlinx.serialization.Serializable
import java.util.Locale

/** Native session discovery metadata only: never Skill bodies or a local library resource. */
@Serializable
internal data class RemoteSkill(val name: String = "", val description: String = "", val command: String = "")

internal fun distinctRemoteSkills(skills: List<RemoteSkill>): List<RemoteSkill> {
    val names = mutableSetOf<String>()
    val commands = mutableSetOf<String>()
    return skills.take(512).mapNotNull { skill ->
        val name = skill.name.trim()
        val command = skill.command.trim()
        if (name.isBlank() || name.length > 200 || name.any(Char::isISOControl) ||
            !command.matches(Regex("/[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}"))) return@mapNotNull null
        val identity = name.lowercase(Locale.ROOT).removeSuffix(".md").replace(Regex("[ _-]+"), "-")
        if (identity in names || command.lowercase(Locale.ROOT) in commands) return@mapNotNull null
        names += identity; commands += command.lowercase(Locale.ROOT)
        skill.copy(name = name, command = command, description = skill.description.take(1000)
            .filter { !it.isISOControl() || it.isWhitespace() })
    }
}
