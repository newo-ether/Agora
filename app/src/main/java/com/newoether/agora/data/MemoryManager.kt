package com.newoether.agora.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

class MemoryManager(context: Context) {
    private val memoryDir: File =
        File(context.filesDir, "memory_db").also { it.mkdirs() }

    private val activeMemoryFile: File =
        File(context.filesDir, "active_memory.md")

    private val metaFile: File =
        File(memoryDir, "memory_meta.json")
        
    private val hitsFile: File = 
        File(memoryDir, "memory_hits.json")

    private val pinnedFile: File =
        File(memoryDir, "memory_pinned.json")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val metadata = DescriptionMetadataStore(metaFile, json)
    private val hitsStore = DescriptionMetadataStore(hitsFile, json)
    private val pinnedStore = DescriptionMetadataStore(pinnedFile, json)
    private val _activeMemoryRevision = MutableStateFlow(0L)
    val activeMemoryRevision = _activeMemoryRevision.asStateFlow()
    private val _catalogRevision = MutableStateFlow(0L)
    val catalogRevision = _catalogRevision.asStateFlow()

    data class MemoryFileInfo(
        val name: String,
        val description: String = "",
        val pinned: Boolean = false,
    )

    @Synchronized
    fun getActiveMemory(): String =
        if (activeMemoryFile.exists()) activeMemoryFile.readText() else ""

    @Synchronized
    fun updateActiveMemory(
        content: String,
        mode: String = "replace",
        oldString: String? = null,
        newString: String? = null,
    ): String {
        val result = when (mode) {
            "append" -> {
                activeMemoryFile.appendText("\n$content")
                "Appended to active memory."
            }
            "prepend" -> {
                val existing = getActiveMemory()
                activeMemoryFile.writeText("$content\n$existing")
                "Prepended to active memory."
            }
            "patch" -> {
                require(!oldString.isNullOrEmpty()) {
                    "old_string is required for patch mode"
                }
                val existing = getActiveMemory()
                val count = existing.countOccurrences(oldString)
                require(count == 1) {
                    if (count == 0) "old_string not found in active memory"
                    else "old_string matches $count times in active memory; it must be unique"
                }
                activeMemoryFile.writeText(existing.replace(oldString, newString.orEmpty()))
                "Active memory patched."
            }
            else -> {
                activeMemoryFile.writeText(content)
                "Active memory updated."
            }
        }
        _activeMemoryRevision.value += 1
        return result
    }

    @Synchronized
    fun getDescription(name: String): String {
        val resolved = resolveFile(name)
        if (!resolved.exists()) return ""
        return metadata.read()[resolved.name].orEmpty()
    }

    @Synchronized
    fun setDescription(name: String, description: String) {
        val resolved = resolveFile(name)
        require(resolved.exists()) { "File not found: $name" }
        val values = metadata.read()
        val original = values.toMap()
        updateDescription(values, resolved.name, description)
        if (values != original) {
            metadata.write(values)
            _catalogRevision.value += 1
        }
    }

    @Synchronized
    fun listFiles(): List<MemoryFileInfo> {
        val values = metadata.read()
        val pinned = pinnedStore.read()
        return memoryDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { MemoryFileInfo(it.name, values[it.name].orEmpty(), pinned[it.name] == "true") }
            ?.sortedBy { it.name }
            .orEmpty()
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
        incrementHitCount(file.name)
        return file.readText()
    }
    
    private fun incrementHitCount(name: String) {
        val hits = hitsStore.read()
        val count = hits[name]?.toIntOrNull() ?: 0
        hits[name] = (count + 1).toString()
        hitsStore.write(hits)
    }

    @Synchronized
    fun getPromotionCandidates(minHits: Int = 5): List<MemoryFileInfo> {
        val hits = hitsStore.read()
        val meta = metadata.read()
        val pinned = pinnedStore.read()
        return hits.mapNotNull { (name, countStr) ->
            if (pinned[name] == "true") return@mapNotNull null
            val count = countStr.toIntOrNull() ?: 0
            if (count >= minHits) {
                MemoryFileInfo(name, meta[name].orEmpty(), false)
            } else null
        }.sortedByDescending { hits[it.name]?.toIntOrNull() ?: 0 }
    }

    @Synchronized
    fun pinFile(name: String, pinned: Boolean): String {
        val file = resolveFile(name)
        require(file.exists()) { "File not found: $name" }
        val values = pinnedStore.read()
        if (pinned) values[file.name] = "true" else values.remove(file.name)
        pinnedStore.write(values)
        _catalogRevision.value += 1
        return if (pinned) "Pinned ${file.name}" else "Unpinned ${file.name}"
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
            
            // Also rename in hitsStore and pinnedStore
            val hits = hitsStore.read()
            hits.remove(file.name)?.let { hits[renameTarget.name] = it }
            hitsStore.write(hits)
            
            val pins = pinnedStore.read()
            pins.remove(file.name)?.let { pins[renameTarget.name] = it }
            pinnedStore.write(pins)
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
        
        val hits = hitsStore.read()
        hits.remove(file.name)
        hitsStore.write(hits)
        
        val pins = pinnedStore.read()
        pins.remove(file.name)
        pinnedStore.write(pins)
        
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
        val file = File(memoryDir, if (sanitized.endsWith(".md")) sanitized else "$sanitized.md")
        val canonicalDirectory = memoryDir.canonicalFile
        val canonicalFile = file.canonicalFile
        require(canonicalFile.parentFile == canonicalDirectory) { "Invalid file name: $name" }
        return canonicalFile
    }
}
