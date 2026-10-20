package com.newoether.agora.templates

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One atomic catalogue replacement makes CRUD and archive Replace all-or-nothing. */
class InteractiveTemplateRepository(private val directory: File, internal val androidSources: TemplateAndroidSources? = null) {
    constructor(context: Context) : this(File(context.filesDir, "interactive-templates"), TemplateAndroidSources(context.applicationContext))
    private val file get() = AtomicFile(File(directory, "catalog.json"))

    fun list(): List<InteractiveTemplate> = synchronized(lock) { read().sortedBy { it.name.lowercase() } }
    fun get(id: String): InteractiveTemplate = list().firstOrNull { it.id == id } ?: error("Template not found")

    fun save(
        template: InteractiveTemplate,
        replace: Boolean = false,
        expected: InteractiveTemplate? = null,
    ) = synchronized(lock) {
        template.validate()
        require(template.skillFlow == null) { "An opened workflow card cannot replace a library template" }
        require(template.description.trim().split(Regex("\\s+")).filter(String::isNotEmpty).size <= 10) {
            "Keep the description to 10 words or fewer"
        }
        val all = read().toMutableList()
        val index = all.indexOfFirst { it.id == template.id }
        require(replace || index < 0) { "Template ID already exists" }
        require(!replace || index >= 0) { "Template no longer exists" }
        require(expected == null || index >= 0 && all[index] == expected) { "Template changed; reopen it before saving" }
        require(all.none { it.id != template.id && template.shortcut != null && it.shortcut == template.shortcut }) {
            "Shortcut already exists"
        }
        if (index < 0) all.add(template) else all[index] = template
        write(all)
    }

    fun delete(id: String) = synchronized(lock) {
        val all = read()
        require(all.any { it.id == id }) { "Template no longer exists" }
        write(all.filterNot { it.id == id })
    }

    fun exportCatalogue(): String = synchronized(lock) { Json.encodeToString(read()) }

    /** Validate everything before mutation. Merge reports conflicts and preserves existing entries. */
    fun restoreCatalogue(text: String, replace: Boolean, updateExisting: Boolean = false): List<String> = synchronized(lock) {
        require(!replace || !updateExisting) { "Choose either Replace or Update" }
        require(text.toByteArray().size <= MAX_CATALOGUE_BYTES) { "Template catalogue is too large" }
        val incoming = Json.decodeFromString<List<InteractiveTemplate>>(text)
        validateCatalogue(incoming)
        val all = if (replace) mutableListOf() else read().toMutableList()
        val conflicts = mutableListOf<String>()
        incoming.forEach { template ->
            val index = all.indexOfFirst { it.id == template.id }
            val shortcutConflict = all.any { it.id != template.id && template.shortcut != null && it.shortcut == template.shortcut }
            if (shortcutConflict || index >= 0 && !updateExisting) {
                conflicts += "Template conflict: ${template.name}"
            } else if (index >= 0) all[index] = template else all.add(template)
        }
        write(all)
        conflicts
    }

    private fun read(): List<InteractiveTemplate> {
        if (!file.baseFile.exists() && !File(directory, "catalog.json.bak").exists()) return emptyList()
        val bytes = file.openRead().use { it.readBytesBounded(MAX_CATALOGUE_BYTES) }
        return Json.decodeFromString<List<InteractiveTemplate>>(bytes.toString(Charsets.UTF_8)).also(::validateCatalogue)
    }

    private fun write(all: List<InteractiveTemplate>) {
        validateCatalogue(all)
        val bytes = Json.encodeToString(all).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_CATALOGUE_BYTES) { "Template catalogue is too large" }
        check(directory.isDirectory || directory.mkdirs()) { "Could not create template storage" }
        val atomic = file
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun validateCatalogue(all: List<InteractiveTemplate>) {
        all.forEach { it.validate(); require(it.skillFlow == null) { "Workflow routing is not a library template" } }
        require(all.map { it.id }.distinct().size == all.size) { "Duplicate template IDs" }
        val shortcuts = all.mapNotNull { it.shortcut }
        require(shortcuts.distinct().size == shortcuts.size) { "Duplicate shortcuts" }
    }

    companion object {
        private val lock = Any() // all app/provider/import instances share the same mutation fence
        const val ARCHIVE_ENTRY = "templates/catalog.json"
        const val MAX_CATALOGUE_BYTES = 16 * 1024 * 1024
    }
}

fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "File is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
