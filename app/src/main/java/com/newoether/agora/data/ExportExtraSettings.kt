package com.newoether.agora.data

import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.model.OpenAiServiceTiers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.float
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull

/**
 * Extra settings serialized as JsonObject to avoid D8 field-count crash on large @Serializable classes.
 * All serialization is done at runtime (no compile-time serializer codegen).
 */
object ExportExtraSettings {
    suspend fun restoreLegacyFromJsonObject(
        obj: JsonObject,
        sm: SettingsManager,
        replace: Boolean,
        allowSecrets: Boolean,
        allowedConversationIds: Set<String>,
    ) {
        // System prompts are restored standalone from system_prompts.json — NOT duplicated here.
        obj["imageTranscriptionEnabled"]?.jsonPrimitive?.boolean?.let {
            sm.saveImageTranscriptionEnabled(it)
        }
        obj["imageTranscriptionEnabledModels"]?.jsonPrimitive?.contentOrNull?.let {
            val set = it.split(",").filter { s -> s.isNotBlank() }.toSet()
            sm.saveImageTranscriptionEnabledModels(set)
        }
        obj["imageTranscriptionModel"]?.jsonPrimitive?.contentOrNull?.let { sm.saveImageTranscriptionModel(it) }
        obj["imageTranscriptionBatchSize"]?.jsonPrimitive?.int?.let { sm.saveImageTranscriptionBatchSize(it) }
        obj["imageTranscriptionPrompt"]?.jsonPrimitive?.contentOrNull?.let { sm.saveImageTranscriptionPrompt(it) }
        obj["webSearchNumResults"]?.jsonPrimitive?.int?.let { sm.saveWebSearchNumResults(it) }
        obj["searchContextWindow"]?.jsonPrimitive?.int?.let { sm.saveSearchContextWindow(it) }
        obj["searchMatchLimit"]?.jsonPrimitive?.int?.let { sm.saveSearchMatchLimit(it) }
        obj["defaultTemperature"]?.jsonPrimitive?.float?.let { sm.saveDefaultTemperature(it) }
        obj["defaultMaxTokens"]?.jsonPrimitive?.int?.let { sm.saveDefaultMaxTokens(it) }
        obj["defaultTopP"]?.jsonPrimitive?.float?.let { sm.saveDefaultTopP(it) }
        obj["defaultFrequencyPenalty"]?.jsonPrimitive?.float?.let { sm.saveDefaultFrequencyPenalty(it) }
        obj["defaultPresencePenalty"]?.jsonPrimitive?.float?.let { sm.saveDefaultPresencePenalty(it) }
        obj["conversationSettings"]?.jsonObject?.forEach { (convId, settingsJson) ->
            if (convId !in allowedConversationIds) return@forEach
            val s = settingsJson.jsonObject
            val legacyBudgetTokens = ThinkingLevels.legacyBudgetTokens(s["thinkingLevel"]?.jsonPrimitive?.contentOrNull)
            val cs = ConversationSettings(
                contextWindow = s["contextWindow"]?.jsonPrimitive?.int,
                temperature = s["temperature"]?.jsonPrimitive?.float,
                maxTokens = s["maxTokens"]?.jsonPrimitive?.int,
                topP = s["topP"]?.jsonPrimitive?.float,
                frequencyPenalty = s["frequencyPenalty"]?.jsonPrimitive?.float,
                presencePenalty = s["presencePenalty"]?.jsonPrimitive?.float,
                codeExecutionEnabled = s["codeExecutionEnabled"]?.jsonPrimitive?.boolean,
                googleSearchEnabled = s["googleSearchEnabled"]?.jsonPrimitive?.boolean,
                openAiWebSearchEnabled = s["openAiWebSearchEnabled"]?.jsonPrimitive?.boolean,
                thinkingEnabled = s["thinkingEnabled"]?.jsonPrimitive?.boolean,
                thinkingLevel = s["thinkingLevel"]?.jsonPrimitive?.contentOrNull?.let { ThinkingLevels.normalize(it) },
                thinkingBudgetEnabled = s["thinkingBudgetEnabled"]?.jsonPrimitive?.boolean
                    ?: legacyBudgetTokens?.let { true },
                thinkingBudgetTokens = s["thinkingBudgetTokens"]?.jsonPrimitive?.int ?: legacyBudgetTokens,
                openAiServiceTierEnabled =
                    s["openAiServiceTierEnabled"]?.jsonPrimitive?.boolean,
                openAiServiceTier = s["openAiServiceTier"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let(OpenAiServiceTiers::normalize),
                webSearchEnabled = s["webSearchEnabled"]?.jsonPrimitive?.boolean,
                shellEnabled = s["shellEnabled"]?.jsonPrimitive?.boolean,
                lowContextModeEnabled = s["lowContextModeEnabled"]?.jsonPrimitive?.boolean,
            )
            if (!cs.isAllNull()) sm.saveConversationSettings(convId, cs)
        }
        obj["showDocumentationFab"]?.jsonPrimitive?.boolean?.let { sm.saveShowDocumentationFab(it) }
        obj["proxyEnabled"]?.jsonPrimitive?.boolean?.let { sm.saveProxyEnabled(it) }
        obj["proxyType"]?.jsonPrimitive?.contentOrNull?.let { sm.saveProxyType(it) }
        obj["proxyHost"]?.jsonPrimitive?.contentOrNull?.let { sm.saveProxyHost(it) }
        obj["proxyPort"]?.jsonPrimitive?.contentOrNull?.let { sm.saveProxyPort(it) }
        obj["proxyUsername"]?.jsonPrimitive?.contentOrNull?.let { sm.saveProxyUsername(it) }
        if (allowSecrets) {
            obj["proxyPassword"]?.jsonPrimitive?.contentOrNull?.let {
                if (it.isNotEmpty()) sm.saveProxyPassword(it)
            }
        }
        obj["proxyBypass"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) sm.saveProxyBypass(it) }
        obj["themeMode"]?.jsonPrimitive?.contentOrNull?.let { sm.saveThemeMode(it) }
        obj["colorScheme"]?.jsonPrimitive?.contentOrNull?.let { sm.saveColorScheme(it) }
        obj["dynamicColor"]?.jsonPrimitive?.boolean?.let { sm.saveDynamicColor(it) }
        obj["blurEffectsEnabled"]?.jsonPrimitive?.boolean?.let { sm.saveBlurEffectsEnabled(it) }
        obj["eInkMode"]?.jsonPrimitive?.boolean?.let { sm.saveEInkMode(it) }
        obj["interactiveAnswersEnabled"]?.jsonPrimitive?.boolean?.let { sm.saveInteractiveAnswersEnabled(it) }
        obj["reduceMotion"]?.jsonPrimitive?.boolean?.let { sm.saveReduceMotion(it) }
        obj["openAiServiceTierEnabled"]?.jsonPrimitive?.boolean?.let {
            sm.saveOpenAiServiceTierEnabled(it)
        }
        obj["openAiServiceTier"]?.jsonPrimitive?.contentOrNull?.let {
            sm.saveOpenAiServiceTier(OpenAiServiceTiers.normalize(it))
        }
        obj["hapticsEnabled"]?.jsonPrimitive?.boolean?.let { sm.saveHapticsEnabled(it) }
        obj["detailedTokenUsage"]?.jsonPrimitive?.boolean?.let {
            sm.saveDetailedTokenUsage(it)
        }
        obj["autoExpandActiveGroup"]?.jsonPrimitive?.boolean?.let {
            sm.saveAutoExpandActiveGroup(it)
        }
        obj["schemeStyle"]?.jsonPrimitive?.contentOrNull?.let { sm.saveSchemeStyle(it) }
        obj["fontPreference"]?.jsonPrimitive?.contentOrNull?.let { sm.saveFontPreference(it) }
        obj["autoUpdateCheck"]?.jsonPrimitive?.boolean?.let { sm.saveAutoUpdateCheck(it) }
        obj["automationToolsEnabled"]?.jsonPrimitive?.boolean?.let { sm.saveAutomationToolsEnabled(it) }
        obj["exactExecutionEnabled"]?.jsonPrimitive?.boolean?.let { sm.saveExactExecutionEnabled(it) }
        obj["automationWakeLockEnabled"]?.jsonPrimitive?.boolean
            ?.let { sm.saveAutomationWakeLockEnabled(it) }

        obj["modelAliases"]?.jsonObject?.let { aliasesObj ->
            val map = aliasesObj.mapNotNull { (k, v) ->
                v.jsonPrimitive?.contentOrNull?.let { k to it }
            }.toMap()
            if (map.isNotEmpty()) {
                sm.saveModelAliases(if (replace) map else sm.modelAliases.first() + map)
            }
        }
        (obj["customModels"] as? JsonArray)?.let { models ->
            val imported = models.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
            sm.saveCustomModels(if (replace) imported else sm.customModels.first() + imported)
        }
    }
}
