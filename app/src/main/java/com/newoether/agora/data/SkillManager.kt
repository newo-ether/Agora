package com.newoether.agora.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

class SkillManager(context: Context) {
    private val skillDir = File(context.filesDir, "skill_db").also { it.mkdirs() }
    private val metaFile = File(skillDir, "skill_meta.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val metadata = DescriptionMetadataStore(metaFile, json)
    private val _catalogRevision = MutableStateFlow(0L)
    val catalogRevision = _catalogRevision.asStateFlow()

    data class SkillFileInfo(
        val name: String,
        val description: String = "",
        val shortcut: String = "",
    )

    @Synchronized
    fun listFiles(): List<SkillFileInfo> {
        val values = metadata.read()
        skillDir.listFiles()?.filter { it.name.endsWith(".md.bak") }?.forEach {
            recoverBody(File(it.path.removeSuffix(".bak")))
        }
        return skillDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { SkillFileInfo(it.name, values[it.name].orEmpty(), values["@shortcut:${it.name}"].orEmpty()) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    @Synchronized
    fun catalog(): String {
        val files = listFiles()
        if (files.isEmpty()) return ""
        return buildString {
            appendLine("<available_skills>")
            appendLine(
                "Use the skill file tools to read a relevant skill before following it. " +
                    "Only filenames are listed; read the selected Skill on demand.",
            )
            files.forEach { file ->
                append("- ")
                append(file.name)
                appendLine()
            }
            append("</available_skills>")
        }
    }

    @Synchronized
    fun getDescription(name: String): String {
        val file = resolveFile(name)
        if (!file.exists()) return ""
        return metadata.read()[file.name].orEmpty()
    }

    @Synchronized
    fun getMetaJson(): String = metadata.readJson()

    @Synchronized
    fun saveMetaJson(jsonString: String) {
        metadata.replaceJson(jsonString)
        _catalogRevision.value += 1
    }

    /** Merge absent import metadata only for exact bodies, preserving existing user choices. */
    @Synchronized
    internal fun mergeImportedMetadata(incoming: Map<String, String>, bodies: Map<String, String>) {
        if (incoming.isEmpty() || bodies.isEmpty()) return
        val values = metadata.read()
        val original = values.toMap()
        bodies.forEach { (name, expected) ->
            val file = resolveFile(name)
            if (file.isFile && readFile(name) == expected) {
                val description = incoming[name]
                if (!description.isNullOrBlank() && values[file.name].isNullOrBlank()) {
                    updateDescription(values, file.name, description.trim())
                }
                // Older archives stored shortcuts only in metadata, without a Skill header.
                val shortcutKey = "@shortcut:${file.name}"
                val shortcut = incoming["@shortcut:$name"]
                if (!shortcut.isNullOrBlank() && values[shortcutKey].isNullOrBlank()) {
                    com.newoether.agora.skills.SkillConfiguration(shortcut = shortcut).validate()
                    if (values.none { (key, value) -> key.startsWith("@shortcut:") && value == shortcut }) {
                        values[shortcutKey] = shortcut
                    }
                }
            }
        }
        if (values != original) {
            metadata.write(values)
            _catalogRevision.value += 1
        }
    }

    @Synchronized
    fun readFile(name: String): String {
        val file = resolveFile(name)
        recoverBody(file)
        require(file.isFile) { "File not found: $name" }
        require(file.length() <= com.newoether.agora.skills.SkillDocument.MAX_BYTES) { "Skill is too large" }
        return file.readText()
    }

    @Synchronized
    fun createFile(name: String, content: String, description: String = ""): String {
        val file = resolveFile(name)
        require(!file.exists()) { "File already exists: ${file.name}" }
        val document = com.newoether.agora.skills.SkillDocument.decode(file.name, content)
        val shortcut = document.configuration.shortcut
        require(shortcut.isEmpty() || listFiles().none { it.shortcut == shortcut }) { "Skill shortcut already exists" }
        val values = if (description.isNotBlank() || shortcut.isNotEmpty()) metadata.read() else null
        if (values != null) {
            updateDescription(values, file.name, description)
            if (shortcut.isNotEmpty()) values["@shortcut:${file.name}"] = shortcut
        }
        try {
            writeBody(file, content)
            if (values != null) metadata.write(values)
        } catch (error: Exception) {
            if (file.exists() && !file.delete()) {
                error.addSuppressed(IOException("Unable to roll back ${file.name}"))
            }
            throw error
        }
        _catalogRevision.value += 1
        return "Created ${file.name}"
    }

    @Synchronized
    fun editFile(
        name: String,
        content: String? = null,
        newName: String? = null,
        description: String? = null,
        oldString: String? = null,
        newString: String? = null,
        preserveConfiguration: Boolean = true,
    ): String {
        val file = resolveFile(name)
        require(file.exists()) { "File not found: $name" }
        require(content == null || oldString == null) {
            "content and old_string are mutually exclusive"
        }
        require(content != null || oldString != null || newName != null || description != null) {
            "At least one edit must be provided"
        }

        val renameTarget = newName
            ?.let(::resolveFile)
            ?.takeIf { it.name != file.name }
        if (renameTarget != null) {
            require(!renameTarget.exists()) {
                "Target file already exists: ${renameTarget.name}"
            }
        }

        val originalContent = if (content != null || oldString != null) file.readBytes() else null
        val replacementContent = when {
            oldString != null -> {
                require(oldString.isNotEmpty()) { "old_string must not be empty" }
                val existing = String(requireNotNull(originalContent), Charsets.UTF_8)
                val matches = existing.countOccurrences(oldString)
                require(matches == 1) {
                    if (matches == 0) "old_string not found in ${file.name}"
                    else "old_string matches $matches times in ${file.name}; it must be unique"
                }
                existing.replace(oldString, newString.orEmpty())
            }
            content != null -> {
                val old = String(requireNotNull(originalContent), Charsets.UTF_8)
                if (preserveConfiguration && old.startsWith("<!-- agora-skill/v1\n") && !content.startsWith("<!-- agora-skill/v1\n")) {
                    com.newoether.agora.skills.SkillDocument.decode(file.name, old).copy(instructions = content).encode()
                } else content
            }
            else -> null
        }

        val replacementShortcut = replacementContent?.let {
            com.newoether.agora.skills.SkillDocument.decode((renameTarget ?: file).name, it).configuration.shortcut
        }
        val previousShortcut = if (!preserveConfiguration) metadata.read()["@shortcut:${file.name}"].orEmpty() else originalContent?.let {
            com.newoether.agora.skills.SkillDocument.decode(file.name, String(it, Charsets.UTF_8)).configuration.shortcut
        }
        val shortcutChanged = replacementShortcut != null && replacementShortcut != previousShortcut
        require(!shortcutChanged || replacementShortcut.isNullOrEmpty() || listFiles().none {
            it.name != file.name && it.shortcut == replacementShortcut
        }) { "Skill shortcut already exists" }
        val values = if (renameTarget != null || description != null || shortcutChanged) metadata.read() else null
        val originalValues = values?.toMap()
        val targetName = renameTarget?.name ?: file.name
        if (renameTarget != null && values != null) {
            values.remove(file.name)?.let { values[renameTarget.name] = it }
            values.remove("@shortcut:${file.name}")?.let { values["@shortcut:${renameTarget.name}"] = it }
        }
        if (shortcutChanged && values != null) {
            values.remove("@shortcut:$targetName")
            if (!replacementShortcut.isNullOrEmpty()) values["@shortcut:$targetName"] = replacementShortcut
        }
        if (description != null) updateDescription(requireNotNull(values), targetName, description)
        val metadataChanged = values != null && values != originalValues

        var target = file
        var contentTouched = false
        var renamed = false
        try {
            if (replacementContent != null) {
                contentTouched = true
                writeBody(file, replacementContent)
            }
            if (renameTarget != null) {
                if (!file.renameTo(renameTarget)) {
                    throw IOException("Unable to rename ${file.name}")
                }
                target = renameTarget
                renamed = true
            }
            if (metadataChanged) {
                metadata.write(requireNotNull(values))
            }
        } catch (error: Exception) {
            val rollbackFile = if (renamed) target else file
            if (contentTouched) {
                try {
                    writeBody(rollbackFile, String(requireNotNull(originalContent), Charsets.UTF_8))
                } catch (restoreError: Exception) {
                    error.addSuppressed(restoreError)
                }
            }
            if (renamed && !target.renameTo(file)) {
                error.addSuppressed(IOException("Unable to roll back rename to ${file.name}"))
            }
            throw error
        }

        if (replacementContent != null || renamed || metadataChanged) {
            _catalogRevision.value += 1
        }
        return if (replacementContent != null || renamed || metadataChanged) {
            "Updated ${target.name}"
        } else {
            "No changes made."
        }
    }

    @Synchronized
    fun deleteFile(name: String): String {
        val file = resolveFile(name)
        require(file.exists()) { "File not found: $name" }
        val values = metadata.read()
        val originalValues = values.toMap()
        val content = file.readBytes()
        require(file.delete()) { "Unable to delete ${file.name}" }
        values.remove(file.name)
        values.remove("@shortcut:${file.name}")
        if (values != originalValues) {
            try {
                metadata.write(values)
            } catch (error: Exception) {
                try {
                    writeBody(file, String(content, Charsets.UTF_8))
                } catch (restoreError: Exception) {
                    error.addSuppressed(restoreError)
                }
                throw error
            }
        }
        _catalogRevision.value += 1
        return "Deleted ${file.name}"
    }

    @Synchronized
    fun document(reference: String): com.newoether.agora.skills.SkillDocument {
        val file = resolveFile(reference)
        if (!file.exists() && file.name == com.newoether.agora.skills.SkillAuthoring.NAME) {
            return com.newoether.agora.skills.SkillAuthoring.document
        }
        return com.newoether.agora.skills.SkillDocument.decode(file.name, readFile(reference))
            .copy(description = getDescription(reference))
    }

    @Synchronized
    fun saveDocument(document: com.newoether.agora.skills.SkillDocument, description: String,
                     originalName: String? = null, expected: String? = null) {
        val encoded = document.copy(description = description).encode()
        val seen = mutableSetOf(resolveFile(document.name).name)
        var next = document.configuration.nextSkill
        while (next != null) {
            require(seen.add(resolveFile(next).name) && seen.size <= com.newoether.agora.skills.SkillInvocation.MAX_DEPTH) { "Cyclic or excessive Skill chain" }
            next = document(next).configuration.nextSkill
        }
        if (originalName == null) createFile(document.name, encoded, description) else {
            require(expected != null && readFile(originalName) == expected) { "Skill changed; reopen before saving" }
            editFile(originalName, content = encoded, newName = document.name, description = description)
        }
    }

    private fun recoverBody(file: File) {
        val backup = File(file.path + ".bak")
        if (backup.exists()) {
            if (file.exists()) check(backup.delete()) { "Unable to clear Skill backup" }
            else check(backup.renameTo(file)) { "Unable to recover Skill" }
        }
    }

    private fun writeBody(file: File, content: String) {
        require(!file.exists() || file.isFile) { "Skill path is not a regular file" }
        require(content.toByteArray().size <= com.newoether.agora.skills.SkillDocument.MAX_BYTES) { "Skill is too large" }
        recoverBody(file)
        val pending = File(file.path + ".tmp")
        val backup = File(file.path + ".bak")
        pending.outputStream().use { it.write(content.toByteArray()); it.flush(); it.fd.sync() }
        val moved = file.exists()
        if (moved) check(file.renameTo(backup)) { "Unable to stage Skill" }
        try {
            check(pending.renameTo(file)) { "Unable to save Skill" }
            if (backup.exists()) check(backup.delete()) { "Unable to clear Skill backup" }
        } catch (failure: Exception) {
            if (!file.exists() && backup.exists()) backup.renameTo(file)
            throw failure
        } finally { pending.delete() }
    }

    private fun updateDescription(
        values: MutableMap<String, String>,
        name: String,
        description: String,
    ) {
        require(description.trim().split(Regex("\\s+")).filter(String::isNotEmpty).size <= 10) {
            "Keep the description to 10 words or fewer"
        }
        if (description.isBlank()) values.remove(name) else values[name] = description
    }

    private fun String.countOccurrences(value: String): Int {
        require(value.isNotEmpty()) { "old_string must not be empty" }
        var count = 0
        var start = 0
        while (true) {
            val match = indexOf(value, start)
            if (match < 0) return count
            count += 1
            start = match + value.length
        }
    }

    private fun resolveFile(name: String): File {
        val sanitized = name.replace(Regex("""[/\\]"""), "_")
        val file = File(skillDir, if (sanitized.endsWith(".md")) sanitized else "$sanitized.md")
        val canonicalDirectory = skillDir.canonicalFile
        val canonicalFile = file.canonicalFile
        require(canonicalFile.parentFile == canonicalDirectory) { "Invalid file name: $name" }
        return canonicalFile
    }
}
