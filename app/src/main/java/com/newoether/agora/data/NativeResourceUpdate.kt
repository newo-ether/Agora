package com.newoether.agora.data

import com.newoether.agora.skills.SkillDocument
import kotlinx.serialization.json.Json

/** Explicit Update changes only incoming resource filenames; it never clears either library. */
internal fun updateArchivedResources(entries: List<Pair<String, String>>, memories: MemoryManager, skills: SkillManager): Int {
    val files = entries.toMap()
    fun metadata(path: String) = files[path]?.let { Json.decodeFromString<Map<String, String>>(it) }.orEmpty()
    val skillMeta = metadata("memories/skill_db/skill_meta.json")
    val memoryMeta = metadata("memories/memory_db/memory_meta.json")
    val documents = entries.filter { it.first.startsWith("memories/skill_db/") && it.first.endsWith(".md") }.map { (path, body) ->
        val name = path.removePrefix("memories/skill_db/")
        val document = SkillDocument.decode(name, body)
        val shortcut = document.configuration.shortcut.ifBlank { skillMeta["@shortcut:$name"].orEmpty() }
        val updated = document.copy(configuration = document.configuration.copy(shortcut = shortcut),
            description = skillMeta[name].orEmpty()).also { it.encode() }
        updated to if (updated.configuration == document.configuration) body else updated.encode()
    }
    val existingSkills = skills.listFiles().map { it.name }.toSet()
    var count = 0
    documents.forEach { (document, body) ->
        // No-overwrite create or the existing synchronized body/metadata replacement with rollback.
        val description = skillMeta[document.name]
        if (document.name in existingSkills) skills.editFile(document.name, content = body,
            description = description, preserveConfiguration = false)
        else skills.createFile(document.name, body, description.orEmpty())
        count++
    }
    val existingMemories = memories.listFiles().map { it.name }.toSet()
    entries.filter { it.first.startsWith("memories/memory_db/") && it.first.endsWith(".md") }.forEach { (path, body) ->
        val name = path.removePrefix("memories/memory_db/")
        if (name in existingMemories) memories.editFile(name, content = body, description = memoryMeta[name])
        else memories.createFile(name, body, memoryMeta[name].orEmpty())
        count++
    }
    files["memories/active_memory.md"]?.let { memories.updateActiveMemory(it, "replace"); count++ }
    return count
}
