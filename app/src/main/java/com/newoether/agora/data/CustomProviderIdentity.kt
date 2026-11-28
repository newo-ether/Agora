package com.newoether.agora.data

import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.model.apiModelName
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SnackbarEvent
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal data class CustomProviderIdentityMigration(
    val legacyReference: String,
    val providerId: String,
)

internal data class CustomProviderIdentityNormalization(
    val providers: List<CustomProviderConfig>,
    val migrations: List<CustomProviderIdentityMigration>,
)

/** Pure identity policy shared by DataStore normalization, import, runtime lookup, and tests. */
internal object CustomProviderIdentityPolicy {
    private const val PREFIX = "custom-provider-"
    private const val STABLE_ID_PATTERN =
        "custom-provider-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    private val stableIdPattern = Regex(
        "^$STABLE_ID_PATTERN$",
        RegexOption.IGNORE_CASE,
    )
    private val stableIdInTextPattern = Regex(
        STABLE_ID_PATTERN,
        RegexOption.IGNORE_CASE,
    )

    fun newId(): String = PREFIX + UUID.randomUUID().toString()

    /** Stable only for upgrading a legacy name-keyed config; persisted IDs never change on rename. */
    fun legacyId(name: String): String = PREFIX + UUID.nameUUIDFromBytes(
        ("agora/custom-provider/legacy/" + name.trim().lowercase(Locale.ROOT))
            .toByteArray(StandardCharsets.UTF_8),
    ).toString()

    fun isStableId(value: String): Boolean = stableIdPattern.matches(value.trim())

    /** Cheap precheck: text without the prefix cannot hold a stable ID. */
    fun mayContainStableId(text: String?): Boolean = text != null && text.contains(PREFIX, ignoreCase = true)

    fun replaceStableIdsForDisplay(
        text: String,
        replacement: (String) -> String,
    ): String = if (!mayContainStableId(text)) text else stableIdInTextPattern.replace(text) { match ->
        replacement(match.value)
    }

    fun normalize(
        rawProviders: List<CustomProviderConfig>,
        occupiedIds: Set<String> = emptySet(),
        newId: (CustomProviderConfig) -> String = { newId() },
    ): CustomProviderIdentityNormalization {
        val sanitized = CustomProviderNamePolicy.sanitize(rawProviders).accepted
        val usedIds = occupiedIds.filterTo(linkedSetOf(), ::isStableId)
        val normalized = sanitized.map { raw ->
            val originalId = raw.id.trim()
            val retainedId = originalId.takeIf { isStableId(it) && it !in usedIds }
            val stableId = retainedId ?: generateUniqueId(raw, usedIds, newId)
            usedIds += stableId
            val legacyNames = buildSet {
                addAll(raw.legacyNames.map(String::trim).filter(String::isNotEmpty))
                if (retainedId == null) add(raw.name.trim())
            }.filterNotTo(linkedSetOf()) { it == stableId }
            raw.copy(
                name = raw.name.trim(),
                id = stableId,
                legacyNames = legacyNames,
            )
        }
        return CustomProviderIdentityNormalization(
            providers = normalized,
            migrations = normalized.flatMap { provider ->
                provider.legacyNames.map { legacy ->
                    CustomProviderIdentityMigration(legacy, provider.id)
                }
            }.distinct(),
        )
    }

    private fun generateUniqueId(
        provider: CustomProviderConfig,
        usedIds: Set<String>,
        newId: (CustomProviderConfig) -> String,
    ): String {
        repeat(100) { attempt ->
            val candidate = if (attempt == 0) {
                newId(provider).trim()
            } else {
                CustomProviderIdentityPolicy.newId()
            }
            if (isStableId(candidate) && candidate !in usedIds) return candidate
        }
        error("Unable to allocate a unique custom provider ID")
    }
}

internal fun String.remapProviderReference(
    migrations: Map<String, String>,
): String {
    val match = migrations.entries
        .asSequence()
        .filter { (legacy, _) -> legacy.isNotEmpty() && startsWith("$legacy:") }
        .maxByOrNull { (legacy, _) -> legacy.length }
        ?: return this
    return match.value + removePrefix(match.key)
}

internal fun <T> remapModelPreferenceKeys(
    aliases: Map<String, T>,
    migrations: Map<String, String>,
): Map<String, T> {
    val result = linkedMapOf<String, T>()
    aliases.forEach { (modelId, alias) ->
        if (modelId.remapProviderReference(migrations) == modelId) result[modelId] = alias
    }
    aliases.forEach { (modelId, alias) ->
        val remapped = modelId.remapProviderReference(migrations)
        if (remapped != modelId) result.putIfAbsent(remapped, alias)
    }
    return result
}

internal fun canonicalCustomModelId(
    modelId: String,
    customProviders: List<CustomProviderConfig>,
): String = modelId.remapProviderReference(
    customProviders.flatMap { provider ->
        provider.legacyNames.map { legacy -> legacy to provider.providerId } +
            (provider.name to provider.providerId)
    }.toMap(),
)

/**
 * Repairs the short-lived development-build failure where a stale legacy provider write could
 * replace an already-normalized random ID while aliases remained under the first ID. The repair
 * is intentionally conservative: an orphan namespace moves only when the current model catalog
 * identifies exactly one active custom provider. Ambiguous aliases remain untouched.
 */
internal fun <T> repairOrphanedCustomProviderPreferenceKeys(
    aliases: Map<String, T>,
    knownModelReferences: Collection<String>,
    activeProviderIds: Set<String>,
): Map<String, T> {
    val activeModels = knownModelReferences.asSequence()
        .map(ModelId::parse)
        .filter { it.providerName in activeProviderIds }
        .groupBy(ModelId::providerName, ModelId::modelName)
        .mapValues { (_, models) -> models.toSet() }
    val orphanModels = aliases.keys.asSequence()
        .map(ModelId::parse)
        .filter {
            CustomProviderIdentityPolicy.isStableId(it.providerName) &&
                it.providerName !in activeProviderIds
        }
        .groupBy(ModelId::providerName, ModelId::modelName)
        .mapValues { (_, models) -> models.toSet() }
    val recoveredProviders = orphanModels.mapNotNull { (orphanId, models) ->
        val scored = activeModels.mapValues { (_, active) -> models.count(active::contains) }
        val bestScore = scored.values.maxOrNull() ?: 0
        val candidates = scored.filterValues { it == bestScore && it > 0 }.keys
        candidates.singleOrNull()?.let { orphanId to it }
    }.toMap()
    if (recoveredProviders.isEmpty()) return aliases

    val result = linkedMapOf<String, T>()
    aliases.forEach { (modelId, alias) ->
        val parsed = ModelId.parse(modelId)
        if (parsed.providerName !in recoveredProviders) result[modelId] = alias
    }
    aliases.forEach { (modelId, alias) ->
        val parsed = ModelId.parse(modelId)
        val targetProvider = recoveredProviders[parsed.providerName] ?: return@forEach
        result.putIfAbsent(ModelId(targetProvider, parsed.modelName).prefixed, alias)
    }
    return result
}

fun providerDisplayName(
    providerReference: String,
    customProviders: List<CustomProviderConfig>,
): String {
    val resolved = customProviders.firstOrNull { provider ->
        provider.ownsIdentity(providerReference) || provider.name == providerReference
    }?.name ?: providerReference
    return CustomProviderIdentityPolicy.replaceStableIdsForDisplay(resolved) { "Custom" }
}

fun replaceCustomProviderIdsForDisplay(
    text: String,
    customProviders: List<CustomProviderConfig>,
): String = CustomProviderIdentityPolicy.replaceStableIdsForDisplay(text) { providerId ->
    providerDisplayName(providerId, customProviders)
}

fun SnackbarEvent.forDisplay(
    customProviders: List<CustomProviderConfig>,
): SnackbarEvent = copy(
    message = replaceCustomProviderIdsForDisplay(message, customProviders),
    actionLabel = actionLabel?.let {
        replaceCustomProviderIdsForDisplay(it, customProviders)
    },
)

fun ChatConversation.forDisplay(
    customProviders: List<CustomProviderConfig>,
): ChatConversation = copy(
    title = replaceCustomProviderIdsForDisplay(title, customProviders),
)

private fun String?.replaceCustomProviderIdsForDisplayOrNull(
    customProviders: List<CustomProviderConfig>,
): String? = this?.let { replaceCustomProviderIdsForDisplay(it, customProviders) }

/**
 * Whether any displayed field may hold a stable provider ID. Display projection runs for every
 * message of the open path on each stream snapshot, so a message without one is returned as is,
 * without a regex pass or a copy, and keeps its identity for Compose skipping.
 */
private fun ChatMessage.mayHoldProviderIds(): Boolean {
    val ids = CustomProviderIdentityPolicy::mayContainStableId
    return ids(text) || ids(thoughts) || ids(thoughtTitle) || ids(retryText) ||
        toolCall?.let { call ->
            ids(call.toolName) || ids(call.arguments) || ids(call.result) || ids(call.displayName) ||
                ids(call.target) || ids(call.resultText) || ids(call.structuredResult)
        } == true ||
        segments?.any { segment ->
            segment.type != "citation" && (ids(segment.content) || ids(segment.toolName) ||
                ids(segment.toolArgs) || ids(segment.toolResult) || ids(segment.toolProgress) ||
                ids(segment.toolTarget) || ids(segment.toolDisplayName) || ids(segment.toolResultText) ||
                ids(segment.toolStructuredResult))
        } == true ||
        attachmentMeta?.items?.any { item ->
            ids(item.fileName) || ids(item.warning) || ids(item.textContent) || ids(item.transcription)
        } == true
}

fun ChatMessage.forDisplay(
    customProviders: List<CustomProviderConfig>,
): ChatMessage = if (!mayHoldProviderIds()) this else copy(
    text = replaceCustomProviderIdsForDisplay(text, customProviders),
    thoughts = thoughts.replaceCustomProviderIdsForDisplayOrNull(customProviders),
    thoughtTitle = thoughtTitle.replaceCustomProviderIdsForDisplayOrNull(customProviders),
    toolCall = toolCall?.forDisplay(customProviders),
    segments = segments?.map { it.forDisplay(customProviders) },
    attachmentMeta = attachmentMeta?.forDisplay(customProviders),
    retryText = retryText.replaceCustomProviderIdsForDisplayOrNull(customProviders),
)

private fun ToolCallData.forDisplay(
    customProviders: List<CustomProviderConfig>,
): ToolCallData = copy(
    toolName = replaceCustomProviderIdsForDisplay(toolName, customProviders),
    arguments = replaceCustomProviderIdsForDisplay(arguments, customProviders),
    result = replaceCustomProviderIdsForDisplay(result, customProviders),
    displayName = displayName.replaceCustomProviderIdsForDisplayOrNull(customProviders),
    target = target.replaceCustomProviderIdsForDisplayOrNull(customProviders),
    resultText = resultText.replaceCustomProviderIdsForDisplayOrNull(customProviders),
    structuredResult = structuredResult.replaceCustomProviderIdsForDisplayOrNull(customProviders),
)

private fun MessageSegment.forDisplay(
    customProviders: List<CustomProviderConfig>,
): MessageSegment {
    if (type == "citation") return this
    return copy(
        content = replaceCustomProviderIdsForDisplay(content, customProviders),
        toolName = toolName.replaceCustomProviderIdsForDisplayOrNull(customProviders),
        toolArgs = toolArgs.replaceCustomProviderIdsForDisplayOrNull(customProviders),
        toolResult = toolResult.replaceCustomProviderIdsForDisplayOrNull(customProviders),
        toolProgress = toolProgress.replaceCustomProviderIdsForDisplayOrNull(customProviders),
        toolTarget = toolTarget.replaceCustomProviderIdsForDisplayOrNull(customProviders),
        toolDisplayName = toolDisplayName.replaceCustomProviderIdsForDisplayOrNull(customProviders),
        toolResultText = toolResultText.replaceCustomProviderIdsForDisplayOrNull(customProviders),
        toolStructuredResult = toolStructuredResult.replaceCustomProviderIdsForDisplayOrNull(customProviders),
    )
}

private fun AttachmentMeta.forDisplay(
    customProviders: List<CustomProviderConfig>,
): AttachmentMeta = copy(
    items = items.map { item ->
        item.copy(
            fileName = item.fileName.replaceCustomProviderIdsForDisplayOrNull(customProviders),
            warning = item.warning.replaceCustomProviderIdsForDisplayOrNull(customProviders),
            textContent = item.textContent.replaceCustomProviderIdsForDisplayOrNull(customProviders),
            transcription = item.transcription.replaceCustomProviderIdsForDisplayOrNull(customProviders),
        )
    },
)

fun modelApiDisplayName(
    modelId: String,
    customProviders: List<CustomProviderConfig>,
): String = replaceCustomProviderIdsForDisplay(
    ModelId.parse(modelId).apiModelName,
    customProviders,
)

private val aliasSeparators = Regex("[-_\\s]+")
private val omittedAliasVariantSuffix = Regex(":(?:batch|free)$", RegexOption.IGNORE_CASE)
private val novaAliasPattern = Regex(
    "^nova-(\\d+(?:\\.\\d+)?)-(.+)-v\\d+(?::\\d+)?$",
    RegexOption.IGNORE_CASE,
)
private val geminiPreviewAliasPattern = Regex(
    "^(gemini-[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?)-preview$",
    RegexOption.IGNORE_CASE,
)
private val claudeFamilyFirstAliasPattern = Regex(
    "^claude-([a-z][a-z0-9]*(?:-[a-z][a-z0-9]*)*)-(\\d{1,2})(?:-(\\d{1,2}))?" +
        "(?:-(\\d{8}))?(?:-fast)?$",
    RegexOption.IGNORE_CASE,
)
private val claudeVersionFirstAliasPattern = Regex(
    "^claude-(\\d{1,2})(?:-(\\d{1,2}))?-([a-z][a-z0-9]*(?:-[a-z][a-z0-9]*)*)" +
        "(?:-(\\d{8}))?(?:-fast)?$",
    RegexOption.IGNORE_CASE,
)
private val snapshotDateFormatter = DateTimeFormatter.BASIC_ISO_DATE
private val exactAliasTokenDisplayNames = mapOf(
    "a3b" to "A3B",
    "a70b" to "A70B",
    "e4b" to "E4B",
    "glm" to "GLM",
    "mimo" to "MiMo",
    "minimax" to "MiniMax",
    "oss" to "OSS",
    "tts" to "TTS",
)
private val aliasTokenDisplayNames = mapOf(
    "ai" to "AI",
    "api" to "API",
    "claude" to "Claude",
    "cohere" to "Cohere",
    "command" to "Command",
    "deepseek" to "DeepSeek",
    "gemini" to "Gemini",
    "gemma" to "Gemma",
    "gpt" to "GPT",
    "grok" to "Grok",
    "kimi" to "Kimi",
    "llama" to "Llama",
    "llm" to "LLM",
    "mistral" to "Mistral",
    "mixtral" to "Mixtral",
    "nova" to "Nova",
    "phi" to "Phi",
    "qwen" to "Qwen",
    "qwq" to "QwQ",
    "vl" to "VL",
    "vlm" to "VLM",
)

/** Human-readable display fallback only; the original model ID remains authoritative. */
internal fun inferModelAlias(modelName: String): String {
    val trimmed = modelName.trim()
    if (trimmed.isEmpty()) return trimmed

    val pathTail = trimmed.substringAfterLast('/').trim().ifEmpty { trimmed }
    val withoutVariant = pathTail.replace(omittedAliasVariantSuffix, "").ifEmpty { pathTail }
    inferGeminiAlias(withoutVariant)?.let { return it }
    inferClaudeAlias(withoutVariant)?.let { return it }
    inferNovaAlias(withoutVariant)?.let { return it }
    return humanizeModelAlias(withoutVariant).ifEmpty { trimmed }
}

private fun inferGeminiAlias(slug: String): String? {
    val match = geminiPreviewAliasPattern.matchEntire(slug) ?: return null
    return humanizeModelAlias(match.groupValues[1])
}

private fun inferClaudeAlias(slug: String): String? {
    claudeFamilyFirstAliasPattern.matchEntire(slug)?.let { match ->
        val snapshot = match.groupValues[4].takeIf(String::isNotEmpty)
        if (snapshot != null && !isValidSnapshotDate(snapshot)) return null
        return buildClaudeAlias(
            family = match.groupValues[1],
            major = match.groupValues[2],
            minor = match.groupValues[3].takeIf(String::isNotEmpty),
        )
    }
    claudeVersionFirstAliasPattern.matchEntire(slug)?.let { match ->
        val snapshot = match.groupValues[4].takeIf(String::isNotEmpty)
        if (snapshot != null && !isValidSnapshotDate(snapshot)) return null
        return buildClaudeAlias(
            family = match.groupValues[3],
            major = match.groupValues[1],
            minor = match.groupValues[2].takeIf(String::isNotEmpty),
            versionFirst = true,
        )
    }
    return null
}

private fun buildClaudeAlias(
    family: String,
    major: String,
    minor: String?,
    versionFirst: Boolean = false,
): String {
    val version = listOfNotNull(major, minor).joinToString(".")
    val familyName = humanizeModelAlias(family)
    return if (versionFirst) {
        "Claude $version $familyName"
    } else {
        "Claude $familyName $version"
    }
}

private fun inferNovaAlias(slug: String): String? {
    val match = novaAliasPattern.matchEntire(slug) ?: return null
    return "Nova ${match.groupValues[1]} ${humanizeModelAlias(match.groupValues[2])}"
}

private fun humanizeModelAlias(value: String): String = value
    .split(aliasSeparators)
    .filter(String::isNotEmpty)
    .joinToString(" ", transform = ::formatAliasToken)

private fun formatAliasToken(value: String): String {
    val normalized = value.lowercase(Locale.ROOT)
    exactAliasTokenDisplayNames[normalized]?.let { return it }
    aliasTokenDisplayNames[normalized]?.let { return it }
    aliasTokenDisplayNames.entries.firstOrNull { (token, _) ->
        normalized.length > token.length &&
            normalized.startsWith(token) &&
            normalized[token.length].isDigit()
    }?.let { (token, displayName) ->
        val numericSuffix = normalized.removePrefix(token)
        return if (token == "qwen") "$displayName $numericSuffix" else displayName + numericSuffix
    }
    if (normalized.matches(Regex("[vr]\\d+(?:\\.\\d+)?"))) {
        return normalized.replaceFirstChar(Char::uppercaseChar)
    }
    if (normalized.matches(Regex("o\\d+(?:\\.\\d+)?"))) {
        return normalized.replaceFirstChar(Char::uppercaseChar)
    }
    if (normalized.matches(Regex("\\d+(?:\\.\\d+)?[bkmt]"))) {
        return normalized.dropLast(1) + normalized.last().uppercaseChar()
    }
    return normalized.replaceFirstChar { character ->
        if (character.isLetter()) character.titlecase(Locale.ROOT) else character.toString()
    }
}

private fun isValidSnapshotDate(value: String): Boolean = runCatching {
    LocalDate.parse(value, snapshotDateFormatter)
}.isSuccess

fun modelAliasDisplayName(
    modelId: String,
    aliases: Map<String, String>,
    customProviders: List<CustomProviderConfig>,
): String = aliases[modelId]
    ?.takeIf(String::isNotBlank)
    ?.let { replaceCustomProviderIdsForDisplay(it, customProviders) }
    ?: inferModelAlias(modelApiDisplayName(modelId, customProviders))

fun modelDisplayName(
    modelId: String,
    aliases: Map<String, String>,
    customProviders: List<CustomProviderConfig>,
    showProviderName: Boolean,
): String {
    val alias = modelAliasDisplayName(modelId, aliases, customProviders)
    if (!showProviderName) return alias
    val parsed = ModelId.parse(modelId)
    return "$alias (${providerDisplayName(parsed.providerName, customProviders)})"
}

/**
 * Default Provider-name visibility when the user never toggled it: a custom Provider keeps the name
 * the owner gave it, while a built-in Provider is already implied by the model and only adds noise.
 */
fun providerNameShownByDefault(modelId: String): Boolean {
    val provider = ModelId.parse(modelId).providerName
    return provider !in BUILT_IN_REMOTE_PROVIDERS && provider != Constants.PROVIDER_LOCAL
}

/** Stored switch value for [modelId], else the per-Provider default above; no ID shows nothing. */
fun modelProviderNameShown(modelId: String?, overrides: Map<String, Boolean>): Boolean =
    modelId?.let { overrides[it] ?: providerNameShownByDefault(it) } ?: false
