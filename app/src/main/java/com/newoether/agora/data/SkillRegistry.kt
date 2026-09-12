package com.newoether.agora.data

import com.newoether.agora.api.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SkillRegistry(private val json: Json = Json { ignoreUnknownKeys = true }) {

    private val sandboxExtensions = setOf("py", "sh", "js", "ts", "rb", "pl", "lua")

    suspend fun browseMarketplaces(marketplaces: List<SkillMarketplace>): Result<List<RegistrySkillEntry>> = runCatching {
        coroutineScope {
            marketplaces
                .map { async { browseMarketplace(it).getOrNull().orEmpty() } }
                .awaitAll()
                .flatten()
                .distinctBy { "${it.owner}/${it.repo}/${it.skillPath}" }
        }
    }

    suspend fun browseMarketplace(marketplace: SkillMarketplace): Result<List<RegistrySkillEntry>> = runCatching {
        val owner = marketplace.owner
        val repo = marketplace.repo
        val ref = marketplace.ref
        val treePaths = fetchRepoTree(owner, repo, ref)

        val manifest = if (marketplace.skills != null) {
            null
        } else {
            fetchRawFile(owner, repo, ref, MARKETPLACE_MANIFEST_PATH)
                ?.let { runCatching { parseMarketplaceManifest(it) }.getOrNull() }
        }

        val skillDirs = selectSkillDirs(treePaths, marketplace.skills, manifest, marketplace.root, marketplace.exclude)

        coroutineScope {
            skillDirs.map { dir ->
                async {
                    val md = fetchRawFile(owner, repo, ref, "$dir/SKILL.md") ?: return@async null
                    val parsed = SkillFrontmatterParser.parse(md)
                    if (parsed !is SkillFrontmatterParser.Result.Ok) return@async null
                    val requiresSandbox = treePaths.any {
                        it.startsWith("$dir/") && it.substringAfterLast('.', "").lowercase() in sandboxExtensions
                    }
                    RegistrySkillEntry(
                        id = parsed.id,
                        description = parsed.description,
                        owner = owner,
                        repo = repo,
                        ref = ref,
                        skillPath = dir,
                        requiresSandbox = requiresSandbox,
                        sourceName = marketplace.name,
                    )
                }
            }.awaitAll().filterNotNull().sortedBy { it.id }
        }
    }

    suspend fun fetchSkillFiles(source: SkillSource): Result<DownloadedSkill> = runCatching {
        val (owner, repo, ref, path) = when (source) {
            is SkillSource.GitHub -> listOf(source.owner, source.repo, source.ref, source.path)
        }

        val skillMd = fetchRawFile(owner, repo, ref, "$path/SKILL.md")
            ?: error("SKILL.md not found at $owner/$repo:$ref:$path/SKILL.md")
        val parsed = when (val r = SkillFrontmatterParser.parse(skillMd)) {
            is SkillFrontmatterParser.Result.Ok -> r
            is SkillFrontmatterParser.Result.Err -> error("Invalid SKILL.md frontmatter: ${r.reason}")
        }

        val tree = runCatching { fetchRepoTree(owner, repo, ref) }.getOrDefault(emptySet())
        val siblings = tree
            .filter { it.startsWith("$path/") && it != "$path/SKILL.md" }
            .map { it.removePrefix("$path/") }

        val files = coroutineScope {
            siblings
                .filter { it.substringAfterLast('.', "").lowercase() !in BINARY_EXTENSIONS }
                .map { file ->
                    async {
                        val content = fetchRawFile(owner, repo, ref, "$path/$file")
                        if (content != null && content.length <= MAX_BUNDLED_FILE_CHARS) file to content else null
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }

        DownloadedSkill(
            id = parsed.id,
            description = parsed.description,
            rawSkillMd = skillMd,
            files = files,
        )
    }

    private suspend fun fetchRepoTree(owner: String, repo: String, ref: String): Set<String> {
        val url = "https://api.github.com/repos/$owner/$repo/git/trees/$ref?recursive=1"
        val responseText = HttpClient.getTextResponse(url, mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28"
        ))
        if (!responseText.isSuccessful) return emptySet()
        val root = runCatching { json.parseToJsonElement(responseText.body).jsonObject }.getOrNull() ?: return emptySet()
        val treeArray = root["tree"] as? JsonArray ?: return emptySet()
        return treeArray.mapNotNull { entry ->
            val obj = entry.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            val path = obj["path"]?.jsonPrimitive?.contentOrNull
            if (type == "blob" && path != null) path else null
        }.toSet()
    }

    private suspend fun fetchRawFile(owner: String, repo: String, ref: String, path: String): String? {
        val url = "https://raw.githubusercontent.com/$owner/$repo/$ref/$path"
        val response = HttpClient.getTextResponse(url)
        return if (response.isSuccessful) response.body else null
    }

    companion object {
        private const val MARKETPLACE_MANIFEST_PATH = ".claude-plugin/marketplace.json"
        private const val MAX_BUNDLED_FILE_CHARS = 256_000

        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
            "zip", "tar", "gz", "bz2", "7z", "rar",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "mp3", "mp4", "wav", "ogg", "flac", "mov", "avi", "webm",
            "ttf", "otf", "woff", "woff2",
            "exe", "dll", "so", "dylib", "bin",
        )

        fun selectSkillDirs(
            treePaths: Set<String>,
            allowlist: List<String>?,
            manifestPaths: List<String>?,
            root: String,
            exclude: Set<String> = emptySet(),
        ): List<String> {
            val hasSkillMd = { dir: String -> "$dir/SKILL.md" in treePaths }
            val selected = when {
                allowlist != null -> allowlist.map { it.trim('/') }.filter(hasSkillMd)
                !manifestPaths.isNullOrEmpty() -> manifestPaths.filter(hasSkillMd)
                else -> {
                    val prefix = "$root/"
                    treePaths
                        .filter { it.startsWith(prefix) && it.endsWith("/SKILL.md") }
                        .map { it.removeSuffix("/SKILL.md") }
                        .filter { it.removePrefix(prefix).none { ch -> ch == '/' } }
                        .distinct()
                }
            }
            return if (exclude.isEmpty()) selected else selected.filter { it.substringAfterLast('/') !in exclude }
        }

        fun parseMarketplaceManifest(jsonText: String): List<String> {
            val root = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return emptyList()
            val plugins = root["plugins"] as? JsonArray ?: return emptyList()
            val out = mutableListOf<String>()
            for (plugin in plugins) {
                val obj = plugin.jsonObject
                val source = obj["source"]?.jsonPrimitive?.contentOrNull
                if (source != null && source != "./" && source != ".") continue
                val skills = obj["skills"] as? JsonArray ?: continue
                for (s in skills) {
                    val raw = s.jsonPrimitive.contentOrNull ?: continue
                    val normalized = raw.trim().removePrefix("./").trim('/')
                    if (normalized.isNotEmpty()) out.add(normalized)
                }
            }
            return out.distinct()
        }
    }
}
