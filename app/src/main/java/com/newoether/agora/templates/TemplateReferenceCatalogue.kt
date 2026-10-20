package com.newoether.agora.templates

import com.newoether.agora.model.InteractiveAnswer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TemplateReference(val path: String, val label: String, val categories: List<String>)

/** Display-only normalization. The original case-sensitive path always opens the document. */
fun templateReferenceLabel(name: String, prefix: String = "", stripExtension: Boolean = true): String = name
    .let { if (stripExtension) it.replace(Regex("(?i)\\.[a-z0-9]{1,12}$"), "") else it }
    .let { if (prefix.isNotEmpty() && it.startsWith(prefix)) it.removePrefix(prefix) else it }
    .replace(Regex("[-_]+"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { name }

fun templateReferenceCatalogue(paths: List<String>, prefix: String = ""): List<TemplateReference> = paths.map { path ->
    require(isTemplateFilePath(path)) { "Invalid reference path" }
    val parts = path.split('/')
    TemplateReference(path, templateReferenceLabel(parts.last(), prefix), parts.dropLast(1).map { templateReferenceLabel(it, stripExtension = false) })
}.sortedWith(compareBy({ it.categories.joinToString("/").lowercase() }, { it.label.lowercase() }, { it.path }))

internal fun injectTemplateSources(template: InteractiveTemplate): InteractiveTemplate {
    val references = templateReferenceCatalogue(template.resourceFiles, template.referencePrefix)
    fun safe(text: String) = text.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    val expressions = listOf(
        "globalThis.agoraReferences = ${safe(Json.encodeToString(references))};",
        "globalThis.agoraSourceFiles = globalThis.agoraReferences.map(reference => reference.path);",
    )
    return template.copy(answer = template.answer.copy(jsExpressions = expressions + template.answer.jsExpressions.filterNot {
        it.startsWith("globalThis.agoraSourceFiles =") || it.startsWith("globalThis.agoraReferences =")
    })).validate()
}

@Serializable
internal data class TemplateEditCode(val answer: InteractiveAnswer, val resourceFiles: List<String> = emptyList(),
    val referencePrefix: String = "", val sourceFilesAuto: Boolean = true, val kind: String = "agora.template-edit/v1")

internal fun templateEditCode(template: InteractiveTemplate): String = Json { prettyPrint = true; encodeDefaults = true }.encodeToString(
    TemplateEditCode(template.answer, template.resourceFiles, template.referencePrefix, template.sourceFilesAuto))

internal fun applyTemplateEditCode(template: InteractiveTemplate, code: String): InteractiveTemplate {
    require(code.toByteArray().size <= InteractiveTemplate.MAX_FILE_BYTES) { "Template source exceeds 100,000 bytes" }
    val root = Json.parseToJsonElement(code) as? kotlinx.serialization.json.JsonObject ?: error("Paste a JSON object")
    return if (root["kind"] == kotlinx.serialization.json.JsonPrimitive("agora.template-edit/v1")) {
        val edit = Json.decodeFromString<TemplateEditCode>(code)
        template.copy(answer = edit.answer, resourceFiles = edit.resourceFiles, referencePrefix = edit.referencePrefix,
            sourceFilesAuto = edit.sourceFilesAuto).validate()
    } else template.copy(answer = InteractiveAnswer.decode(code)).validate()
}

internal fun templateAiEditingBrief(template: InteractiveTemplate): String = """
Edit this Agora template. Return one complete agora.template-edit/v1 JSON object, without Markdown fences.
Keep its existing purpose and accessibility. Skills own workflow instructions; do not duplicate them.
Android supplies an optional recursive catalogue: window.agoraReferences [{path,label,categories}]
and window.agoraSourceFiles [relativePath]. Do not assume Markdown or any particular folder layout.
For arbitrary folders, empty folders, or large libraries, use the asynchronous native bridge:
await Websandbox.connection.remote.listDirectory({path:"",offset:0,limit:200})
Returns {entries:[{path,name,directory,mimeType,size}],nextOffset:null|number}. Follow nextOffset for more.
Use a directory entry's exact path to browse it. Empty folders return an empty entries array.
await Websandbox.connection.remote.readTextFile({path:"config.json"})
Reads bounded UTF-8 text locally; parse JSON/CSV/etc in your JavaScript as needed. Never execute file contents.
For binary data: await Websandbox.connection.remote.readFile({path:"item.custom",encoding:"base64"}).
These calls stay inside the user's connected folder, need no Shell and make no AI request.
Filter names/types only when this template needs it; an empty filter means any file.
Set sourceFilesAuto=false to skip recursive discovery and build your menu using listDirectory instead.
Start submits user input and selected exact paths through the existing bridge:
await Websandbox.connection.remote.sendPrompt({text: JSON.stringify({selection: selected.name}), sourceFiles: [selected.path]})
The host attaches the selected file through ordinary attachment processing before the AI request.
Images/PDFs retain their existing processing; unreadable formats fail before sending.
Use at most 16 selected files. Browsing/reading must not submit automatically.
window.agoraSourceFiles and Websandbox.connection.remote.listReferenceFiles() remain available for older catalogue templates.
No folder URI, credentials, file bodies or Skill instructions are included in this editing bundle.

${templateEditCode(template)}
""".trimIndent()

/** The bridge returns only the host's immutable catalogue snapshot, never arbitrary file access. */
internal fun templateReferenceSnapshot(answer: InteractiveAnswer): List<TemplateReference> {
    val prefix = "globalThis.agoraReferences = "
    val encoded = answer.jsExpressions.singleOrNull { it.startsWith(prefix) } ?: return emptyList()
    val references = Json.decodeFromString<List<TemplateReference>>(encoded.removePrefix(prefix).removeSuffix(";"))
    require(references.size <= 500 && references.map { it.path }.distinct().size == references.size) { "Invalid reference catalogue" }
    references.forEach { require(isTemplateFilePath(it.path)) { "Invalid reference path" } }
    return references
}
