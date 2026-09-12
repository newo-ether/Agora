package com.newoether.agora.data

object SkillFrontmatterParser {

    sealed interface Result {
        data class Ok(val id: String, val description: String, val body: String) : Result
        data class Err(val reason: String) : Result
    }

    private val idRegex = Regex("^[a-z0-9-]+$")

    fun parse(markdown: String): Result {
        val lines = markdown.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") {
            return Result.Ok(id = "", description = "", body = markdown)
        }
        var endIdx = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                endIdx = i
                break
            }
        }
        if (endIdx == -1) {
            return Result.Ok(id = "", description = "", body = markdown)
        }

        var id = ""
        var description = ""
        for (i in 1 until endIdx) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim().trim('"', '\'')
                when (key) {
                    "name" -> id = value
                    "description" -> description = value
                }
            }
        }

        if (id.isEmpty()) return Result.Err("Missing 'name' field in frontmatter")
        if (id.length > 64) return Result.Err("Name exceeds 64 characters")
        if (!idRegex.matches(id)) return Result.Err("Name must contain only lowercase letters, digits, and hyphens")

        val body = lines.drop(endIdx + 1).joinToString("\n").trim()
        return Result.Ok(id, description, body)
    }

    fun displayName(id: String): String {
        return id.split('-')
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }
}
