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
    private val registry = SkillRegistry(json)

    data class SkillFileInfo(
        val name: String,
        val description: String = "",
    )

    @Synchronized
    fun listFiles(): List<SkillFileInfo> {
        val values = metadata.read()
        return skillDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { SkillFileInfo(it.name, values[it.name].orEmpty()) }
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
                    "Only names and descriptions are preloaded.",
            )
            files.forEach { file ->
                append("- ")
                append(file.name)
                if (file.description.isNotBlank()) {
                    append(": ")
                    append(file.description.replace('\n', ' ').trim())
                }
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

    @Synchronized
    fun readFile(name: String): String {
        val file = resolveFile(name)
        require(file.exists()) { "File not found: $name" }
        return file.readText()
    }

    @Synchronized
    fun createFile(name: String, content: String, description: String = ""): String {
        val file = resolveFile(name)
        require(!file.exists()) { "File already exists: ${file.name}" }
        val values = description.takeIf(String::isNotBlank)?.let { metadata.read() }
        try {
            file.writeText(content)
            if (values != null) {
                values[file.name] = description
                metadata.write(values)
            }
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
            content != null -> content
            else -> null
        }

        val values = if (renameTarget != null || description != null) metadata.read() else null
        val originalValues = values?.toMap()
        val targetName = renameTarget?.name ?: file.name
        if (renameTarget != null) {
            values?.remove(file.name)?.let { values[renameTarget.name] = it }
        }
        if (description != null) {
            updateDescription(requireNotNull(values), targetName, description)
        }
        val metadataChanged = values != null && values != originalValues

        var target = file
        var contentTouched = false
        var renamed = false
        try {
            if (replacementContent != null) {
                contentTouched = true
                file.writeText(replacementContent)
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
                    rollbackFile.writeBytes(requireNotNull(originalContent))
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
        if (values != originalValues) {
            try {
                metadata.write(values)
            } catch (error: Exception) {
                try {
                    file.writeBytes(content)
                } catch (restoreError: Exception) {
                    error.addSuppressed(restoreError)
                }
                throw error
            }
        }
        _catalogRevision.value += 1
        return "Deleted ${file.name}"
    }

    private fun updateDescription(
        values: MutableMap<String, String>,
        name: String,
        description: String,
    ) {
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

    suspend fun browseMarketplaces(): Result<List<RegistrySkillEntry>> = registry.browseMarketplaces(curatedSkillMarketplaces)

    suspend fun browseMarketplace(marketplace: SkillMarketplace): Result<List<RegistrySkillEntry>> = registry.browseMarketplace(marketplace)

    suspend fun installFromGitHub(owner: String, repo: String, ref: String, path: String): Result<SkillFileInfo> =
        registry.fetchSkillFiles(SkillSource.GitHub(owner, repo, ref, path)).mapCatching { install(it) }

    suspend fun installFromRegistryEntry(entry: RegistrySkillEntry): Result<SkillFileInfo> =
        installFromGitHub(entry.owner, entry.repo, entry.ref, entry.skillPath)

    internal fun install(downloaded: DownloadedSkill): SkillFileInfo {
        val file = resolveFile(downloaded.id)
        val values = metadata.read()
        file.writeText(downloaded.rawSkillMd)
        values[file.name] = downloaded.description
        metadata.write(values)
        
        // For bundled files, if Agora doesn't enforce a sandbox dir, we could just ignore them
        // or write them. Agora's skills.md contract says "SkillManager owns app-private skill_db Markdown files".
        // So we will just write the main SKILL.md.

        _catalogRevision.value += 1
        return SkillFileInfo(file.name, downloaded.description)
    }
}

fun parseGitHubSkillUrl(input: String): SkillSource.GitHub? {
    val trimmed = input.trim().removePrefix("https://").removePrefix("http://").removePrefix("github.com/")
    if (trimmed.isEmpty()) return null
    val parts = trimmed.trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    val owner = parts[0]
    val repo = parts[1]
    if (parts.size == 2) {
        return SkillSource.GitHub(owner = owner, repo = repo, ref = "main", path = "")
    }
    return if (parts[2] == "tree" && parts.size >= 5) {
        val ref = parts[3]
        val path = parts.drop(4).joinToString("/")
        SkillSource.GitHub(owner = owner, repo = repo, ref = ref, path = path)
    } else {
        val path = parts.drop(2).joinToString("/")
        SkillSource.GitHub(owner = owner, repo = repo, ref = "main", path = path)
    }
}
