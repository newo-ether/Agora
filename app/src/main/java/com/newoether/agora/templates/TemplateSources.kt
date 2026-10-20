package com.newoether.agora.templates

import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.viewmodel.GenerationContext

fun validateTemplateDirectory(path: String) {
    require(path.length <= 4096 && !path.any(Char::isISOControl) && path.split('/').none { it == "." || it == ".." } &&
        (path == "/mnt/shared" || path.startsWith("/mnt/shared/") || path == "/home/agora" || path.startsWith("/home/agora/"))) {
        "Use an absolute Alpine directory under /mnt/shared or /home/agora"
    }
}

fun isTemplateFilePath(path: String, nested: Boolean = true): Boolean = path.length in 1..1000 &&
    !path.startsWith('/') && '\\' !in path && !path.any(Char::isISOControl) &&
    path.split('/').all { it.isNotBlank() && it != "." && it != ".." } && (nested || '/' !in path)

/** The legacy Skills/Alpine reference contract remains Markdown-only. */
fun isTemplateSourcePath(path: String, nested: Boolean): Boolean =
    isTemplateFilePath(path, nested) && path.endsWith(".md", true)

/** Read-only UI filesystem boundary shared by local launch, Continue and completed-Skill tools. */
internal class TemplateSources(private val sandbox: SandboxManager?, private val context: GenerationContext, private val android: TemplateAndroidSources? = null) {
    private suspend fun manager(root: String): SandboxManager {
        validateTemplateDirectory(root)
        require(context.sandboxEnabled) { "Enable the Alpine sandbox before opening this template" }
        require(!root.startsWith("/mnt/shared") || context.sandboxSharedStorageEnabled) { "Enable shared storage first" }
        return checkNotNull(sandbox) { "Alpine is unavailable in this build" }.also {
            check(it.isAvailable()) { "Install the Alpine sandbox first" }
        }
    }
    suspend fun prepare(template: InteractiveTemplate): InteractiveTemplate {
        template.sourceFolderId?.let { folder ->
            val access = checkNotNull(android) { "Android reference folders are unavailable" }
            require(access.connected(folder)) { "Connect this template's reference folder in Settings → Templates" }
            val paths = if (template.sourceFilesAuto) access.list(folder) else template.resourceFiles
            return injectTemplateSources(template.copy(resourceFiles = paths))
        }
        val root = template.sourceDirectory?.trimEnd('/') ?: return template
        val manager = manager(root)
        val (files, truncated) = manager.fileGlob("*.md", root, 5)
        require(!truncated && files.size <= 500) { "Reference folder is too large; choose a smaller folder" }
        val paths = files.map { file ->
            require(file.startsWith("$root/")) { "Source listing escaped the configured directory" }
            file.removePrefix("$root/").also { require(isTemplateSourcePath(it, true)) { "Invalid source path" } }
        }.distinct().sorted()
        require(paths.isNotEmpty()) { "No Markdown files found in $root" }
        return injectTemplateSources(template.copy(resourceFiles = paths))
    }
    suspend fun read(template: InteractiveTemplate, relative: String): String {
        require(isTemplateFilePath(relative) && (template.sourceFolderId != null || relative in template.resourceFiles && isTemplateSourcePath(relative, true))) { "Source is not declared by this template" }
        template.sourceFolderId?.let { folder ->
            return checkNotNull(android) { "Android reference folders are unavailable" }.read(folder, relative)
        }
        val root = checkNotNull(template.sourceDirectory).trimEnd('/')
        val manager = manager(root)
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        // Fixed read-only command; filenames are shell-quoted, and both canonical paths are checked.
        val command = "r=\$(readlink -f -- ${quote(root)}) && p=\$(readlink -f -- ${quote("$root/$relative")}) && " +
            "case \"\$p\" in \"\$r\"/*) [ -f \"\$p\" ] && printf '%s' \"\$p\";; *) exit 1;; esac"
        val resolved = manager.executeCommand(command, timeoutMs = 10000)
        require(resolved.exitCode == 0 && resolved.stdout.isNotBlank()) { "Source moved, is unreadable or escaped its directory" }
        val result = manager.fileRead(resolved.stdout.trim(), limit = 128000)
        require(result.error == null && !result.truncated && result.content.isNotBlank() && '\u0000' !in result.content) {
            result.error ?: "Selected reference is empty, unsupported or too large"
        }
        return result.content
    }
}
