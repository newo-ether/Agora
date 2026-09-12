package com.newoether.agora.data

data class SkillMarketplace(
    val name: String,
    val owner: String,
    val repo: String,
    val ref: String = "main",
    val root: String = "skills",
    val skills: List<String>? = null,
    val exclude: Set<String> = emptySet(),
)

val curatedSkillMarketplaces = listOf(
    SkillMarketplace(
        name = "Anthropic Skills",
        owner = "anthropics",
        repo = "skills",
        exclude = setOf(
            "mcp-builder", "skill-creator", "theme-factory", "web-artifacts-builder",
            "webapp-testing", "internal-comms", "frontend-design", "doc-coauthoring",
            "canvas-design", "brand-guidelines", "claude-api"
        )
    ),
    SkillMarketplace(
        name = "Superpowers",
        owner = "obra",
        repo = "superpowers",
        skills = listOf("brainstorming", "writing-plans")
    )
)

sealed interface SkillSource {
    data class GitHub(
        val owner: String,
        val repo: String,
        val ref: String,
        val path: String
    ) : SkillSource
}

data class RegistrySkillEntry(
    val id: String,
    val description: String,
    val owner: String,
    val repo: String,
    val ref: String,
    val skillPath: String,
    val requiresSandbox: Boolean,
    val sourceName: String? = null,
)

data class DownloadedSkill(
    val id: String,
    val description: String,
    val rawSkillMd: String,
    val files: Map<String, String>,
)
