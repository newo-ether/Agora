package com.newoether.agora.data

import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.ThinkingLevels
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

internal data class RestoredCustomFont(
    val path: String,
    val displayName: String,
)

/**
 * The explicit cross-device settings allowlist used by native backup v4.
 *
 * Nothing enters this object merely because it lives in DataStore. Derived model catalogs,
 * device-local paths/models/sandbox state, app lifecycle metadata, backup scheduling, secrets,
 * and per-conversation overrides are intentionally absent.
 */
internal fun importedContextCompactThresholdPercent(value: Int?): Int? =
    value?.takeIf(CONTEXT_COMPACT_THRESHOLD_PERCENT_RANGE::contains)

internal object PortableSettingsArchive {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun toJsonObject(
        sm: SettingsManager,
        customFontIncluded: Boolean,
    ): JsonObject = buildJsonObject {
        put("selectedModel", JsonPrimitive(sm.selectedModel.first()))
        putEncoded("customModels", sm.customModels.first())
        putEncoded("enabledModels", sm.enabledModels.first())
        putEncoded("modelAliases", sm.modelAliases.first())
        putEncoded("modelProviderNames", sm.modelProviderNames.first())
        put("contextTokenBudget", JsonPrimitive(sm.maxContextWindow.first()))
        put("visualizeContextRollout", JsonPrimitive(sm.visualizeContextRollout.first()))
        put("contextCompactEnabled", JsonPrimitive(sm.contextCompactEnabled.first()))
        putNullableString("contextCompactModel", sm.contextCompactModel.first())
        put("contextCompactPrompt", JsonPrimitive(sm.contextCompactPrompt.first()))
        put("contextCompactRetainCount", JsonPrimitive(sm.contextCompactRetainCount.first()))
        put(
            "contextCompactPreserveSystemPrompt",
            JsonPrimitive(sm.contextCompactPreserveSystemPrompt.first()),
        )
        put("contextCompactThresholdPercent", JsonPrimitive(sm.contextCompactThresholdPercent.first()))
        put("codeExecutionEnabled", JsonPrimitive(sm.codeExecutionEnabled.first()))
        put("googleSearchEnabled", JsonPrimitive(sm.googleSearchEnabled.first()))
        put("thinkingEnabled", JsonPrimitive(sm.thinkingEnabled.first()))
        put("thinkingLevel", JsonPrimitive(sm.thinkingLevel.first()))
        put("thinkingBudgetEnabled", JsonPrimitive(sm.thinkingBudgetEnabled.first()))
        put("thinkingBudgetTokens", JsonPrimitive(sm.thinkingBudgetTokens.first()))
        put("openAiServiceTierEnabled", JsonPrimitive(sm.openAiServiceTierEnabled.first()))
        put("openAiServiceTier", JsonPrimitive(sm.openAiServiceTier.first()))
        put("openAiResponsesApiEnabled", JsonPrimitive(sm.openAiResponsesApiEnabled.first()))
        put("anthropicCacheEnabled", JsonPrimitive(sm.anthropicCacheEnabled.first()))
        put("anthropicCacheTtl", JsonPrimitive(sm.anthropicCacheTtl.first()))
        putEncoded("providerBaseUrls", sm.providerBaseUrls.first())
        put("titleGenerationEnabled", JsonPrimitive(sm.titleGenerationEnabled.first()))
        putNullableString("titleGenerationModel", sm.titleGenerationModel.first())
        put("titleGenerationPrompt", JsonPrimitive(sm.titleGenerationPrompt.first()))
        put(
            "titleGenerationNotificationsEnabled",
            JsonPrimitive(sm.titleGenerationNotificationsEnabled.first()),
        )
        put("accessPastConversations", JsonPrimitive(sm.accessPastConversations.first()))
        put("accessSavedMemories", JsonPrimitive(sm.accessSavedMemories.first()))
        put("accessActiveMemory", JsonPrimitive(sm.accessActiveMemory.first()))
        put("accessSkills", JsonPrimitive(sm.accessSkills.first()))
        put("ragSearchEnabled", JsonPrimitive(sm.ragSearchEnabled.first()))
        put("modelSearchMethod", JsonPrimitive(sm.modelSearchMethod.first()))
        put("manualSearchMethod", JsonPrimitive(sm.manualSearchMethod.first()))

        val remoteEmbeddingModels = sm.embeddingModels.first()
            .mapNotNull(EmbeddingModelConfig::asPortableRemoteConfig)
        putEncoded("remoteEmbeddingModels", remoteEmbeddingModels)
        val activeEmbeddingId = sm.activeEmbeddingModelId.first()
            .takeIf { activeId -> remoteEmbeddingModels.any { it.id == activeId } }
        putNullableString("activeRemoteEmbeddingModelId", activeEmbeddingId)

        put("appLanguage", JsonPrimitive(sm.appLanguage.first()))
        put("webSearchEnabled", JsonPrimitive(sm.webSearchEnabled.first()))
        put("webSearchProvider", JsonPrimitive(sm.webSearchProvider.first()))
        put("webSearchNumResults", JsonPrimitive(sm.webSearchNumResults.first()))
        put("webSearchBaseUrl", JsonPrimitive(sm.webSearchBaseUrl.first()))
        put("imageGenEnabled", JsonPrimitive(sm.imageGenEnabled.first()))
        putNullableString("imageGenModel", sm.imageGenModel.first())
        put("imageGenSize", JsonPrimitive(sm.imageGenSize.first()))
        put("searchContextWindow", JsonPrimitive(sm.searchContextWindow.first()))
        put("searchMatchLimit", JsonPrimitive(sm.searchMatchLimit.first()))
        put("ragThreshold", JsonPrimitive(sm.ragThreshold.first()))
        put("autoCacheEnabled", JsonPrimitive(sm.autoCacheEnabled.first()))
        put("showUncachedNotification", JsonPrimitive(sm.showUncachedNotification.first()))
        put("autoUpdateCheck", JsonPrimitive(sm.autoUpdateCheck.first()))

        put("imageTranscriptionEnabled", JsonPrimitive(sm.imageTranscriptionEnabled.first()))
        putEncoded(
            "imageTranscriptionEnabledModels",
            sm.imageTranscriptionEnabledModels.first(),
        )
        putNullableString("imageTranscriptionModel", sm.imageTranscriptionModel.first())
        put("imageTranscriptionBatchSize", JsonPrimitive(sm.imageTranscriptionBatchSize.first()))
        put("imageTranscriptionPrompt", JsonPrimitive(sm.imageTranscriptionPrompt.first()))

        put("shellEnabled", JsonPrimitive(sm.shellEnabled.first()))
        put("shellConfirmEnabled", JsonPrimitive(sm.shellConfirmEnabled.first()))
        put("askUserEnabled", JsonPrimitive(sm.askUserEnabled.first()))
        putEncoded("shellDevices", sm.shellDevices.first().map(ShellDeviceConfig::withoutSecrets))
        put("automationToolsEnabled", JsonPrimitive(sm.automationToolsEnabled.first()))
        put("exactExecutionEnabled", JsonPrimitive(sm.exactExecutionEnabled.first()))
        put("automationWakeLockEnabled", JsonPrimitive(sm.automationWakeLockEnabled.first()))
        putEncoded("customProviders", sm.customProviders.first())
        putEncoded("mcpServers", sm.mcpServers.first().map(McpServerConfig::withoutSecrets))

        put("proxyEnabled", JsonPrimitive(sm.proxyEnabled.first()))
        put("proxyType", JsonPrimitive(sm.proxyType.first()))
        put("proxyHost", JsonPrimitive(sm.proxyHost.first()))
        put("proxyPort", JsonPrimitive(sm.proxyPort.first()))
        put("proxyUsername", JsonPrimitive(sm.proxyUsername.first()))
        put("proxyBypass", JsonPrimitive(sm.proxyBypass.first()))

        put("showDocumentationFab", JsonPrimitive(sm.showDocumentationFab.first()))
        put("themeMode", JsonPrimitive(sm.themeMode.first()))
        put("amoledEnabled", JsonPrimitive(sm.amoledEnabled.first()))
        put("colorScheme", JsonPrimitive(sm.colorScheme.first()))
        put("dynamicColor", JsonPrimitive(sm.dynamicColor.first()))
        put("blurEffectsEnabled", JsonPrimitive(sm.blurEffectsEnabled.first()))
        put("reduceMotion", JsonPrimitive(sm.reduceMotion.first()))
        put("stickToBottom", JsonPrimitive(sm.stickToBottom.first()))
        put("parseInlineDollarMath", JsonPrimitive(sm.parseInlineDollarMath.first()))
        put("autoWrapCodeBlocks", JsonPrimitive(sm.autoWrapCodeBlocks.first()))
        put("hapticsEnabled", JsonPrimitive(sm.hapticsEnabled.first()))
        put("detailedTokenUsage", JsonPrimitive(sm.detailedTokenUsage.first()))
        put("toolCallDisplayMode", JsonPrimitive(sm.toolCallDisplayMode.first()))
        put("thinkingSegmentDisplayMode", JsonPrimitive(sm.thinkingSegmentDisplayMode.first()))
        put("autoExpandActiveGroup", JsonPrimitive(sm.autoExpandActiveGroup.first()))
        put("schemeStyle", JsonPrimitive(sm.schemeStyle.first()))

        val fontPreference = sm.fontPreference.first()
        put(
            "fontPreference",
            JsonPrimitive(
                if (fontPreference == "custom" && !customFontIncluded) {
                    "app_default"
                } else {
                    fontPreference
                },
            ),
        )
        if (customFontIncluded) {
            put("customFontName", JsonPrimitive(sm.customFontName.first()))
        }

        putNullableFloat("defaultTemperature", sm.defaultTemperature.first())
        putNullableInt("defaultMaxTokens", sm.defaultMaxTokens.first())
        putNullableFloat("defaultTopP", sm.defaultTopP.first())
        putNullableFloat("defaultFrequencyPenalty", sm.defaultFrequencyPenalty.first())
        putNullableFloat("defaultPresencePenalty", sm.defaultPresencePenalty.first())
        putNullableString("activeSystemPromptId", sm.activeSystemPromptId.first())
    }

    /**
     * Restores only keys present in [obj]. MERGE therefore cannot reset fields absent from an old
     * archive. REPLACE first clears the portable allowlist, so absent fields resolve to current app
     * defaults without touching device-local or secret state.
     */
    suspend fun restoreFromJsonObject(
        obj: JsonObject,
        sm: SettingsManager,
        replace: Boolean,
        allowLegacySecrets: Boolean,
        restoredCustomFont: RestoredCustomFont?,
        resolveSystemPromptId: (String?) -> String?,
    ): List<String> {
        val warnings = mutableListOf<String>()
        val previousShellDevices = sm.shellDevices.first()
        val previousMcpServers = sm.mcpServers.first()
        val previousEmbeddingModels = sm.embeddingModels.first()
        val previousFontPath = sm.customFontPath.first()
        val previousFontName = sm.customFontName.first()
        val previousCustomProviders = sm.customProviders.first()
        val cacheRecords = listOf(obj) + (obj["customProviders"] as? JsonArray)
            .orEmpty().mapNotNull { it as? JsonObject }
        cacheRecords.forEach { record ->
            record["anthropicCacheTtl"]?.let { value ->
                require(value is JsonPrimitive && value.isString && value.content in setOf("5m", "1h")) {
                    "Invalid Anthropic cache duration"
                }
            }
            record["anthropicCacheEnabled"]?.let { value ->
                require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) {
                    "Invalid Anthropic cache switch"
                }
            }
        }
        val importedCacheTtl = obj.string("anthropicCacheTtl")
        val importedProviderIdentities = prepareImportedCustomProviders(
            raw = obj.decode<List<CustomProviderConfig>>("customProviders").orEmpty(),
            existing = previousCustomProviders,
            replace = replace,
        )
        if (importedProviderIdentities.rejected.isNotEmpty()) {
            warnings += "skipped invalid custom provider name(s): " +
                importedProviderIdentities.rejected.joinToString { it.name }
        }
        fun remapModel(modelId: String): String = modelId.remapProviderReference(
            importedProviderIdentities.modelReferenceRemap,
        )

        if (replace) sm.resetPortableSettingsForImport()
        obj.boolean("anthropicCacheEnabled")?.let { sm.saveAnthropicCacheEnabled(it) }
        importedCacheTtl?.let { sm.saveAnthropicCacheTtl(it) }

        obj.string("selectedModel")?.let { sm.saveSelectedModel(remapModel(it)) }

        obj.decode<Set<String>>("customModels")?.let { imported ->
            val remapped = imported.mapTo(linkedSetOf(), ::remapModel)
            val value = if (replace) remapped else sm.customModels.first() + remapped
            sm.saveCustomModels(value)
        }
        obj.decode<Set<String>>("enabledModels")?.let { imported ->
            val remapped = imported.mapTo(linkedSetOf(), ::remapModel)
            val value = if (replace) remapped else sm.enabledModels.first() + remapped
            sm.saveEnabledModels(value)
        }
        obj.decode<Map<String, String>>("modelAliases")?.let { imported ->
            val remapped = imported.entries.associateTo(linkedMapOf()) { (modelId, alias) ->
                remapModel(modelId) to alias
            }
            val value = if (replace) remapped else sm.modelAliases.first() + remapped
            sm.saveModelAliases(value)
        }
        obj.decode<Map<String, Boolean>>("modelProviderNames")?.let { imported ->
            sm.saveModelProviderNames(
                remapModelPreferenceKeys(imported, importedProviderIdentities.modelReferenceRemap),
                replace = replace,
            )
        }

        (obj.int("contextTokenBudget") ?: obj.int("maxContextWindow"))
            ?.let { sm.saveMaxContextWindow(it) }
        obj.boolean("visualizeContextRollout")?.let { sm.saveVisualizeContextRollout(it) }
        obj.boolean("contextCompactEnabled")?.let { sm.saveContextCompactEnabled(it) }
        if (obj.containsKey("contextCompactModel")) {
            sm.saveContextCompactModel(obj.nullableString("contextCompactModel")?.let(::remapModel))
        }
        obj.string("contextCompactPrompt")?.let { sm.saveContextCompactPrompt(it) }
        obj.int("contextCompactRetainCount")?.takeIf { it >= 0 }?.let { sm.saveContextCompactRetainCount(it) }
        obj.boolean("contextCompactPreserveSystemPrompt")?.let {
            sm.saveContextCompactPreserveSystemPrompt(it)
        }
        importedContextCompactThresholdPercent(
            obj.int("contextCompactThresholdPercent"),
        )?.let { sm.saveContextCompactThresholdPercent(it) }
        obj.boolean("codeExecutionEnabled")?.let { sm.saveCodeExecutionEnabled(it) }
        obj.boolean("googleSearchEnabled")?.let { sm.saveGoogleSearchEnabled(it) }
        obj.boolean("thinkingEnabled")?.let { sm.saveThinkingEnabled(it) }
        obj.string("thinkingLevel")?.let { level ->
            val legacyBudgetTokens = ThinkingLevels.legacyBudgetTokens(level)
            sm.saveThinkingLevel(ThinkingLevels.normalize(level))
            if (!obj.containsKey("thinkingBudgetEnabled") && legacyBudgetTokens != null) {
                sm.saveThinkingBudgetEnabled(true)
            }
            if (!obj.containsKey("thinkingBudgetTokens") && legacyBudgetTokens != null) {
                sm.saveThinkingBudgetTokens(legacyBudgetTokens)
            }
        }
        obj.boolean("thinkingBudgetEnabled")?.let { sm.saveThinkingBudgetEnabled(it) }
        obj.int("thinkingBudgetTokens")?.let { sm.saveThinkingBudgetTokens(it) }
        obj.boolean("openAiServiceTierEnabled")?.let { sm.saveOpenAiServiceTierEnabled(it) }
        obj.string("openAiServiceTier")?.let {
            sm.saveOpenAiServiceTier(OpenAiServiceTiers.normalize(it))
        }
        obj.boolean("openAiResponsesApiEnabled")?.let {
            sm.saveOpenAiResponsesApiEnabled(it)
        }

        obj.decode<Map<String, String>>("providerBaseUrls")?.let { imported ->
            val remapped = imported.entries.associateTo(linkedMapOf()) { (name, url) ->
                (importedProviderIdentities.providerNameRemap[name] ?: name) to url
            }
            val value = if (replace) remapped else sm.providerBaseUrls.first() + remapped
            sm.saveProviderBaseUrls(value)
        }
        obj.boolean("titleGenerationEnabled")?.let { sm.saveTitleGenerationEnabled(it) }
        if (obj.containsKey("titleGenerationModel")) {
            sm.saveTitleGenerationModel(obj.nullableString("titleGenerationModel")?.let(::remapModel))
        }
        obj.string("titleGenerationPrompt")?.let { sm.saveTitleGenerationPrompt(it) }
        obj.boolean("titleGenerationNotificationsEnabled")?.let {
            sm.saveTitleGenerationNotificationsEnabled(it)
        }
        obj.boolean("accessPastConversations")?.let { sm.saveAccessPastConversations(it) }
        obj.boolean("accessSavedMemories")?.let { sm.saveAccessSavedMemories(it) }
        obj.boolean("accessActiveMemory")?.let { sm.saveAccessActiveMemory(it) }
        obj.boolean("accessSkills")?.let { sm.saveAccessSkills(it) }
        obj.boolean("ragSearchEnabled")?.let { sm.saveRagSearchEnabled(it) }
        obj.string("modelSearchMethod")?.let { sm.saveModelSearchMethod(it) }
        obj.string("manualSearchMethod")?.let { sm.saveManualSearchMethod(it) }

        val remoteModelsElement = obj["remoteEmbeddingModels"] ?: obj["embeddingModels"]
        if (remoteModelsElement != null || replace) {
            val importedRemote = remoteModelsElement
                ?.let { runCatching { json.decodeFromJsonElement<List<EmbeddingModelConfig>>(it) }.getOrNull() }
                .orEmpty()
                .mapNotNull(EmbeddingModelConfig::asPortableRemoteConfig)
            val previousById = previousEmbeddingModels.associateBy { it.id }
            val restoredRemote = importedRemote.map { imported ->
                val legacyKey = if (allowLegacySecrets) {
                    runCatching {
                        json.decodeFromJsonElement<List<EmbeddingModelConfig>>(remoteModelsElement!!)
                            .firstOrNull { it.id == imported.id }
                            ?.remoteApiKey
                    }.getOrNull().orEmpty()
                } else {
                    ""
                }
                imported.copy(
                    remoteApiKey = legacyKey.ifBlank {
                        previousById[imported.id]?.remoteApiKey.orEmpty()
                    },
                    localFilePath = "",
                )
            }
            val existingLocal = previousEmbeddingModels.filter { it.type == EmbeddingModelType.LOCAL }
            val finalRemote = if (replace) {
                restoredRemote
            } else {
                mergeById(
                    previousEmbeddingModels.filter { it.type == EmbeddingModelType.REMOTE },
                    restoredRemote,
                ) { it.id }
            }
            val finalModels = existingLocal + finalRemote
            sm.saveEmbeddingModels(finalModels)

            val requestedActive = when {
                obj.containsKey("activeRemoteEmbeddingModelId") ->
                    obj.nullableString("activeRemoteEmbeddingModelId")
                // v1-v3 compatibility.
                obj.containsKey("activeEmbeddingModelId") -> obj.string("activeEmbeddingModelId")
                else -> null
            }
            val previousActive = sm.activeEmbeddingModelId.first()
            val active = requestedActive
                ?.takeIf { requested -> finalModels.any { it.id == requested } }
                ?: previousActive.takeIf { previous ->
                    finalModels.any { it.id == previous && it.type == EmbeddingModelType.LOCAL }
                }
                .orEmpty()
            sm.setActiveEmbeddingModelId(active)
        }

        obj.string("appLanguage")?.let { sm.saveAppLanguage(it) }
        obj.boolean("webSearchEnabled")?.let { sm.saveWebSearchEnabled(it) }
        obj.string("webSearchProvider")?.let { sm.saveWebSearchProvider(it) }
        obj.int("webSearchNumResults")?.let { sm.saveWebSearchNumResults(it) }
        obj.string("webSearchBaseUrl")?.let { sm.saveWebSearchBaseUrl(it) }
        obj.boolean("imageGenEnabled")?.let { sm.saveImageGenEnabled(it) }
        if (obj.containsKey("imageGenModel")) {
            sm.saveImageGenModel(obj.nullableString("imageGenModel")?.let(::remapModel))
        }
        obj.string("imageGenSize")?.let { sm.saveImageGenSize(it) }
        obj.int("searchContextWindow")?.let { sm.saveSearchContextWindow(it) }
        obj.int("searchMatchLimit")?.let { sm.saveSearchMatchLimit(it) }
        obj.float("ragThreshold")?.let { sm.saveRagThreshold(it) }
        obj.boolean("autoCacheEnabled")?.let { sm.saveAutoCacheEnabled(it) }
        obj.boolean("showUncachedNotification")?.let { sm.saveShowUncachedNotification(it) }
        obj.boolean("autoUpdateCheck")?.let { sm.saveAutoUpdateCheck(it) }

        obj.boolean("imageTranscriptionEnabled")?.let { sm.saveImageTranscriptionEnabled(it) }
        obj.decode<Set<String>>("imageTranscriptionEnabledModels")?.let { imported ->
            val remapped = imported.mapTo(linkedSetOf(), ::remapModel)
            val value = if (replace) remapped else {
                sm.imageTranscriptionEnabledModels.first() + remapped
            }
            sm.saveImageTranscriptionEnabledModels(value)
        }
        if (obj.containsKey("imageTranscriptionModel")) {
            sm.saveImageTranscriptionModel(
                obj.nullableString("imageTranscriptionModel")?.let(::remapModel),
            )
        }
        obj.int("imageTranscriptionBatchSize")?.let { sm.saveImageTranscriptionBatchSize(it) }
        obj.string("imageTranscriptionPrompt")?.let { sm.saveImageTranscriptionPrompt(it) }

        obj.boolean("shellEnabled")?.let { sm.saveShellEnabled(it) }
        obj.boolean("shellConfirmEnabled")?.let { sm.saveShellConfirmEnabled(it) }
        obj.boolean("askUserEnabled")?.let { sm.saveAskUserEnabled(it) }
        val shellElement = obj["shellDevices"]
        if (shellElement != null || replace) {
            val decoded = shellElement
                ?.let { runCatching { json.decodeFromJsonElement<List<ShellDeviceConfig>>(it) }.getOrNull() }
                .orEmpty()
            val previousById = previousShellDevices.associateBy { it.id }
            val imported = decoded.map { raw ->
                val existing = previousById[raw.id]
                raw.copy(
                    apiKey = if (allowLegacySecrets) raw.apiKey else existing?.apiKey.orEmpty(),
                    sshPassword = if (allowLegacySecrets) {
                        raw.sshPassword
                    } else {
                        existing?.sshPassword.orEmpty()
                    },
                )
            }
            sm.saveShellDevices(
                if (replace) imported else mergeById(previousShellDevices, imported) { it.id },
            )
        }
        obj.boolean("automationToolsEnabled")?.let { sm.saveAutomationToolsEnabled(it) }
        obj.boolean("exactExecutionEnabled")?.let { sm.saveExactExecutionEnabled(it) }
        obj.boolean("automationWakeLockEnabled")?.let {
            sm.saveAutomationWakeLockEnabled(it)
        }

        if (obj.containsKey("customProviders")) {
            val value = if (replace) {
                importedProviderIdentities.providers
            } else {
                mergeById(
                    previousCustomProviders,
                    importedProviderIdentities.providers,
                ) { it.providerId }
            }
            sm.saveCustomProviders(value)
        }

        val mcpElement = obj["mcpServers"]
        if (mcpElement != null || replace) {
            val decoded = mcpElement
                ?.let { runCatching { json.decodeFromJsonElement<List<McpServerConfig>>(it) }.getOrNull() }
                .orEmpty()
            val previousById = previousMcpServers.associateBy { it.id }
            val imported = decoded.map { raw ->
                val existing = previousById[raw.id]
                raw.copy(
                    headers = if (allowLegacySecrets) raw.headers else existing?.headers.orEmpty(),
                )
            }
            sm.saveMcpServers(
                if (replace) imported else mergeById(previousMcpServers, imported) { it.id },
            )
        }

        obj.boolean("proxyEnabled")?.let { sm.saveProxyEnabled(it) }
        obj.string("proxyType")?.let { sm.saveProxyType(it) }
        obj.string("proxyHost")?.let { sm.saveProxyHost(it) }
        obj.string("proxyPort")?.let { sm.saveProxyPort(it) }
        obj.string("proxyUsername")?.let { sm.saveProxyUsername(it) }
        obj.string("proxyBypass")?.let { sm.saveProxyBypass(it) }

        obj.boolean("showDocumentationFab")?.let { sm.saveShowDocumentationFab(it) }
        obj.string("themeMode")?.let { sm.saveThemeMode(it) }
        obj.boolean("amoledEnabled")?.let { sm.saveAmoledEnabled(it) }
        obj.string("colorScheme")?.let { sm.saveColorScheme(it) }
        obj.boolean("dynamicColor")?.let { sm.saveDynamicColor(it) }
        obj.boolean("blurEffectsEnabled")?.let { sm.saveBlurEffectsEnabled(it) }
        obj.boolean("reduceMotion")?.let { sm.saveReduceMotion(it) }
        obj.boolean("stickToBottom")?.let { sm.saveStickToBottom(it) }
        obj.boolean("parseInlineDollarMath")?.let { sm.saveParseInlineDollarMath(it) }
        obj.boolean("autoWrapCodeBlocks")?.let { sm.saveAutoWrapCodeBlocks(it) }
        obj.boolean("hapticsEnabled")?.let { sm.saveHapticsEnabled(it) }
        obj.boolean("detailedTokenUsage")?.let { sm.saveDetailedTokenUsage(it) }
        obj.string("toolCallDisplayMode")?.let { sm.saveToolCallDisplayMode(it) }
        obj.string("thinkingSegmentDisplayMode")?.let { sm.saveThinkingSegmentDisplayMode(it) }
        obj.boolean("autoExpandActiveGroup")?.let { sm.saveAutoExpandActiveGroup(it) }
        obj.string("schemeStyle")?.let { sm.saveSchemeStyle(it) }

        if (obj.containsKey("fontPreference") || replace) {
            val requestedPreference = obj.string("fontPreference") ?: "app_default"
            when {
                restoredCustomFont != null -> {
                    sm.saveCustomFontPath(restoredCustomFont.path)
                    sm.saveCustomFontName(restoredCustomFont.displayName)
                    sm.saveFontPreference(requestedPreference)
                }
                requestedPreference == "custom" &&
                    previousFontPath.isNotBlank() &&
                    File(previousFontPath).isFile -> {
                    sm.saveCustomFontPath(previousFontPath)
                    sm.saveCustomFontName(previousFontName)
                    sm.saveFontPreference("custom")
                }
                else -> {
                    if (replace) {
                        sm.saveCustomFontPath("")
                        sm.saveCustomFontName("")
                    }
                    sm.saveFontPreference(
                        if (requestedPreference == "custom") "app_default" else requestedPreference,
                    )
                }
            }
        }

        if (obj.containsKey("defaultTemperature")) {
            sm.saveDefaultTemperature(obj.float("defaultTemperature"))
        }
        if (obj.containsKey("defaultMaxTokens")) {
            sm.saveDefaultMaxTokens(obj.int("defaultMaxTokens"))
        }
        if (obj.containsKey("defaultTopP")) {
            sm.saveDefaultTopP(obj.float("defaultTopP"))
        }
        if (obj.containsKey("defaultFrequencyPenalty")) {
            sm.saveDefaultFrequencyPenalty(obj.float("defaultFrequencyPenalty"))
        }
        if (obj.containsKey("defaultPresencePenalty")) {
            sm.saveDefaultPresencePenalty(obj.float("defaultPresencePenalty"))
        }

        val activePromptKey = when {
            obj.containsKey("activeSystemPromptId") -> "activeSystemPromptId"
            obj.containsKey("active_system_prompt_id") -> "active_system_prompt_id"
            else -> null
        }
        if (activePromptKey != null) {
            sm.setActiveSystemPromptId(
                resolveSystemPromptId(obj.nullableString(activePromptKey)),
            )
        } else if (replace) {
            sm.setActiveSystemPromptId(null)
        }

        sm.invalidatePortableModelCaches()
        return warnings
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.nullableString(key: String): String? =
        if (get(key) == JsonNull) null else string(key)

    private fun JsonObject.boolean(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.float(key: String): Float? =
        (get(key) as? JsonPrimitive)?.floatOrNull

    private inline fun <reified T> JsonObject.decode(key: String): T? =
        get(key)?.let { element ->
            runCatching { json.decodeFromJsonElement<T>(element) }.getOrNull()
        }

    private inline fun <reified T> JsonObjectBuilder.putEncoded(key: String, value: T) {
        put(key, json.encodeToJsonElement(value))
    }

    private fun JsonObjectBuilder.putNullableString(key: String, value: String?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullableInt(key: String, value: Int?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullableFloat(key: String, value: Float?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun <T, K> mergeById(
        existing: List<T>,
        imported: List<T>,
        key: (T) -> K,
    ): List<T> {
        val merged = LinkedHashMap<K, T>()
        existing.forEach { merged[key(it)] = it }
        imported.forEach { merged[key(it)] = it }
        return merged.values.toList()
    }

    internal data class ImportedCustomProviderIdentities(
        val providers: List<CustomProviderConfig>,
        val rejected: List<CustomProviderConfig>,
        val modelReferenceRemap: Map<String, String>,
        val providerNameRemap: Map<String, String>,
    )

    internal fun prepareImportedCustomProviders(
        raw: List<CustomProviderConfig>,
        existing: List<CustomProviderConfig>,
        replace: Boolean,
    ): ImportedCustomProviderIdentities {
        val sanitization = CustomProviderNamePolicy.sanitize(raw)
        require(raw.all { it.anthropicCacheTtl == "5m" || it.anthropicCacheTtl == "1h" }) {
            "Invalid Anthropic cache duration"
        }
        val occupied = if (replace) linkedSetOf() else existing
            .mapTo(linkedSetOf(), CustomProviderConfig::providerId)
        val normalized = mutableListOf<CustomProviderConfig>()
        val modelRemap = linkedMapOf<String, String>()
        val nameRemap = linkedMapOf<String, String>()

        sanitization.accepted.forEach { imported ->
            val importedReference = imported.id
                .takeIf(CustomProviderIdentityPolicy::isStableId)
                ?: imported.name
            val matching = if (replace) null else existing.firstOrNull { current ->
                (CustomProviderIdentityPolicy.isStableId(imported.id) && current.id == imported.id) ||
                    current.name.equals(imported.name, ignoreCase = true)
            }
            val target = if (matching != null) {
                imported.copy(
                    name = matching.name,
                    id = matching.providerId,
                    legacyNames = buildSet {
                        addAll(matching.legacyNames)
                        addAll(imported.legacyNames)
                        if (importedReference != matching.providerId) add(importedReference)
                    },
                )
            } else {
                CustomProviderIdentityPolicy.normalize(
                    rawProviders = listOf(imported),
                    occupiedIds = occupied,
                ).providers.single()
            }
            occupied += target.providerId
            normalized += target
            modelRemap[importedReference] = target.providerId
            imported.legacyNames.forEach { legacy -> modelRemap[legacy] = target.providerId }
            nameRemap[imported.name] = target.name
        }
        return ImportedCustomProviderIdentities(
            providers = normalized,
            rejected = sanitization.rejected,
            modelReferenceRemap = modelRemap,
            providerNameRemap = nameRemap,
        )
    }
}
