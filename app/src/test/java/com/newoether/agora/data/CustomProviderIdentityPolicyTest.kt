package com.newoether.agora.data

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.model.citationRecords
import com.newoether.agora.model.toMessageSegment
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderIdentityPolicyTest {
    @Test
    fun legacyProvidersReceiveDistinctStableIdsAndCrashSafeMarkers() {
        val ids = ArrayDeque(
            listOf(
                "custom-provider-00000000-0000-4000-8000-000000000001",
                "custom-provider-00000000-0000-4000-8000-000000000002",
            ),
        )

        val result = CustomProviderIdentityPolicy.normalize(
            rawProviders = listOf(CustomProviderConfig("Relay A"), CustomProviderConfig("Relay B")),
            newId = { ids.removeFirst() },
        )

        assertNotEquals(result.providers[0].id, result.providers[1].id)
        assertEquals(setOf("Relay A"), result.providers[0].legacyNames)
        assertEquals(
            listOf(
                CustomProviderIdentityMigration("Relay A", result.providers[0].id),
                CustomProviderIdentityMigration("Relay B", result.providers[1].id),
            ),
            result.migrations,
        )
    }

    @Test
    fun stableProviderIdentitySurvivesDisplayRenameAndOwnsDisplayResolution() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val renamed = CustomProviderConfig(
            name = "Relay X",
            id = id,
            legacyNames = setOf("Old Relay"),
        )

        assertTrue(renamed.ownsIdentity(id))
        assertTrue(renamed.ownsIdentity("Old Relay"))
        assertEquals("Relay X", providerDisplayName(id, listOf(renamed)))
        assertEquals("Relay X", providerDisplayName("Old Relay", listOf(renamed)))
        assertEquals("Built In", providerDisplayName("Built In", listOf(renamed)))
    }

    @Test
    fun modelReferenceRemapChangesOnlyTheExactProviderComponent() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val migrations = mapOf("Relay" to id)

        assertEquals("$id:model:variant", "Relay:model:variant".remapProviderReference(migrations))
        assertEquals("Relay Two:model", "Relay Two:model".remapProviderReference(migrations))
        assertEquals("unprefixed", "unprefixed".remapProviderReference(migrations))

        val colonNameId = "custom-provider-00000000-0000-4000-8000-000000000002"
        assertEquals(
            "$colonNameId:model",
            "Relay:China:model".remapProviderReference(mapOf("Relay:China" to colonNameId)),
        )
    }

    @Test
    fun canonicalAliasWinsWhenLegacyAndStableKeysCollide() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"

        assertEquals(
            mapOf("$id:model" to "Current alias"),
            remapModelPreferenceKeys(
                aliases = linkedMapOf(
                    "Relay:model" to "Legacy alias",
                    "$id:model" to "Current alias",
                ),
                migrations = mapOf("Relay" to id),
            ),
        )
    }

    @Test
    fun canonicalModelIdAcceptsCurrentAndHistoricalDisplayNamesIncludingColon() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val providers = listOf(
            CustomProviderConfig(
                name = "Relay X",
                id = id,
                legacyNames = setOf("Relay:Old"),
            ),
        )

        assertEquals("$id:model", canonicalCustomModelId("Relay X:model", providers))
        assertEquals("$id:model", canonicalCustomModelId("Relay:Old:model", providers))
        assertEquals("$id:model", canonicalCustomModelId("$id:model", providers))
    }

    @Test
    fun customModelDisplayUsesIndependentProviderPreferenceWithAnyAlias() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val model = "$id:gemini-3.1-pro"
        val providers = listOf(CustomProviderConfig(name = "Relay X", id = id))

        assertEquals("Gemini 3.1 Pro (Relay X)", modelDisplayName(model, emptyMap(), providers, true))
        assertEquals("Gemini 3.1 Pro", modelDisplayName(model, emptyMap(), providers, false))
        assertEquals("Fast (Relay X)", modelDisplayName(model, mapOf(model to "Fast"), providers, true))
        assertEquals("Fast", modelDisplayName(model, mapOf(model to "Fast"), providers, false))
        assertEquals("Gemini 3.1 Pro", modelDisplayName(model, mapOf(model to "  "), providers, false))
        assertEquals(
            "Fast (Renamed)",
            modelDisplayName(model, mapOf(model to "Fast"), listOf(providers.single().copy(name = "Renamed")), true),
        )
    }

    @Test
    fun providerNameDefaultsToCustomProvidersOnly() {
        val customId = "custom-provider-00000000-0000-4000-8000-000000000002"
        val customModel = "$customId:gemini-3.1-pro"
        val builtInModel = "${Constants.PROVIDER_OPENAI}:gpt-5"

        assertTrue(providerNameShownByDefault(customModel))
        assertFalse(providerNameShownByDefault(builtInModel))
        assertFalse(providerNameShownByDefault("${Constants.PROVIDER_GOOGLE}:gemini-3-pro"))
        assertFalse(providerNameShownByDefault("${Constants.PROVIDER_LOCAL}:qwen3-8b"))

        assertTrue(modelProviderNameShown(customModel, emptyMap()))
        assertFalse(modelProviderNameShown(customModel, mapOf(customModel to false)))
        assertTrue(modelProviderNameShown(builtInModel, mapOf(builtInModel to true)))
        assertFalse(modelProviderNameShown(null, emptyMap()))
    }

    @Test
    fun inferredAliasesCoverApprovedMainstreamNamingPatterns() {
        val cases = linkedMapOf(
            "amazon/nova-2-lite-v1" to "Nova 2 Lite",
            "anthropic/claude-fable-5:batch" to "Claude Fable 5",
            "anthropic/claude-opus-5-fast" to "Claude Opus 5",
            "google/gemini-3-flash-preview" to "Gemini 3 Flash",
            "gemini-2.5-pro-preview" to "Gemini 2.5 Pro",
            "gemini-3-flash-PREVIEW" to "Gemini 3 Flash",
            "deepseek/deepseek-v4-flash-0731" to "DeepSeek V4 Flash 0731",
            "deepseek/deepseek-v4-flash-0831" to "DeepSeek V4 Flash 0831",
            "claude-sonnet-4-5-20250929" to "Claude Sonnet 4.5",
            "anthropic/claude-3-5-sonnet-20241022" to "Claude 3.5 Sonnet",
            "deepseek/deepseek-r1-0528" to "DeepSeek R1 0528",
            "openai/gpt-4o-mini" to "GPT 4o Mini",
            "qwen/qwen3.5-27b-vl" to "Qwen 3.5 27B VL",
            "qwen/qwen3.8:free" to "Qwen 3.8",
            "qwen/qwen3.8:FREE" to "Qwen 3.8",
            "anthropic/claude-opus-5:free" to "Claude Opus 5",
        )

        cases.forEach { (modelName, expectedAlias) ->
            assertEquals(modelName, expectedAlias, inferModelAlias(modelName))
        }
    }

    @Test
    fun inferredAliasesApplyOnlyApprovedExactTokenCasing() {
        val cases = linkedMapOf(
            "zai/glm-4.5-air" to "GLM 4.5 Air",
            "xiaomi/mimo-v2-flash" to "MiMo V2 Flash",
            "minimax/minimax-m2.1" to "MiniMax M2.1",
            "vendor/model-a3b-e4b-a70b-oss-tts" to "Model A3B E4B A70B OSS TTS",
            "vendor/GLM-MIMO-MINIMAX-A3B-E4B-A70B-OSS-TTS" to
                "GLM MiMo MiniMax A3B E4B A70B OSS TTS",
        )

        cases.forEach { (modelName, expectedAlias) ->
            assertEquals(modelName, expectedAlias, inferModelAlias(modelName))
        }
    }

    @Test
    fun ambiguousSuffixesAndIdentityTokensSurviveGenericFallback() {
        val cases = linkedMapOf(
            "vendor/vision-2-fast" to "Vision 2 Fast",
            "vendor/model-0731" to "Model 0731",
            "vendor/model-20250929" to "Model 20250929",
            "vendor/bar-v1" to "Bar V1",
            "claude-fast-5" to "Claude Fast 5",
            "claude-opus-5-thinking" to "Claude Opus 5 Thinking",
            "claude-opus-5:thinking" to "Claude Opus 5:thinking",
            "claude-opus-5-20251340" to "Claude Opus 5 20251340",
            "deepseek-v4-flash-1332" to "DeepSeek V4 Flash 1332",
            "vendor/model-preview" to "Model Preview",
            "gemini-2.5-pro-preview-03-25" to "Gemini 2.5 Pro Preview 03 25",
            "gemini--preview" to "Gemini Preview",
            "vendor/glmtoken-mimosa-loss-a8b-tts2" to "Glmtoken Mimosa Loss A8b Tts2",
        )

        cases.forEach { (modelName, expectedAlias) ->
            assertEquals(modelName, expectedAlias, inferModelAlias(modelName))
        }
    }

    @Test
    fun inferredAliasNormalizationIsIdempotentAndBoundarySafe() {
        assertEquals("Claude Opus 5", inferModelAlias("Claude Opus 5"))
        assertEquals("Gemini 3 Flash", inferModelAlias("Gemini 3 Flash"))
        assertEquals("GLM 4.5 Air", inferModelAlias("GLM 4.5 Air"))
        assertEquals("MiMo V2 Flash", inferModelAlias("MiMo V2 Flash"))
        assertEquals("MiniMax M2.1", inferModelAlias("MiniMax M2.1"))
        assertEquals("Model A3B E4B A70B OSS TTS", inferModelAlias("Model A3B E4B A70B OSS TTS"))
        assertEquals("Qwen 3.8", inferModelAlias("Qwen 3.8"))
        assertEquals("Qwen 3.5 VL", inferModelAlias("  qwen__3.5--vl  "))
        assertEquals("", inferModelAlias("   "))
        assertEquals("Vendor/trailing/", inferModelAlias("vendor/trailing/"))
    }

    @Test
    fun explicitAliasWinsWhileBlankAliasFallsBackWithoutChangingModelId() {
        val model = "OpenRouter:anthropic/claude-opus-5-fast"

        assertEquals("Claude Opus 5", modelAliasDisplayName(model, emptyMap(), emptyList()))
        assertEquals(
            "Claude Opus 5",
            modelAliasDisplayName(model, mapOf(model to "   "), emptyList()),
        )
        assertEquals(
            "My Production Model",
            modelAliasDisplayName(model, mapOf(model to "My Production Model"), emptyList()),
        )
        assertEquals(model, ModelId.parse(model).prefixed)
    }

    @Test
    fun modelAliasDisplayReplacesStableIdsInsideAliasText() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val model = "$id:gemini-3.1-pro"
        val aliases = mapOf(model to "Fast via $id")
        val providers = listOf(CustomProviderConfig(name = "Relay X", id = id))

        assertEquals("Fast via Relay X", modelAliasDisplayName(model, aliases, providers))
        assertEquals("Fast via Custom", modelAliasDisplayName(model, aliases, emptyList()))
    }

    @Test
    fun bareStableModelIdUsesProviderDisplayFallback() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val providers = listOf(CustomProviderConfig(name = "Relay X", id = id))

        assertEquals("Relay X", modelApiDisplayName(id, providers))
        assertEquals("Custom", modelApiDisplayName(id, emptyList()))
    }

    @Test
    fun unresolvedStableIdentityNeverLeaksIntoDisplayText() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"

        assertEquals("Custom", providerDisplayName(id, emptyList()))
        assertEquals("Model (Custom)", modelDisplayName("$id:model", emptyMap(), emptyList(), true))
    }

    @Test
    fun citationSegmentsBypassCustomProviderDisplayReplacement() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val answer = "Claim"
        val citation = requireNotNull(
            CitationPolicy.create(
                provider = "custom",
                kind = "file",
                title = "Source",
                providerSourceId = id,
                anchors = listOf(CitationAnchor(0, answer.length, answer)),
                answerText = answer,
            ),
        )
        val segment = citation.toMessageSegment()
        val message = ChatMessage(
            text = answer,
            participant = Participant.MODEL,
            segments = listOf(segment),
        )

        val displayed = message.forDisplay(
            listOf(CustomProviderConfig(name = "Relay X", id = id)),
        )

        assertEquals(segment, displayed.segments?.single())
        assertEquals(id, displayed.citationRecords().single().providerSourceId)
    }

    @Test
    fun orphanedAliasIsNotCrossBoundWhenMultipleProvidersExposeTheSameModel() {
        val orphan = "custom-provider-00000000-0000-4000-8000-000000000001"
        val first = "custom-provider-00000000-0000-4000-8000-000000000002"
        val second = "custom-provider-00000000-0000-4000-8000-000000000003"
        val aliases = mapOf("$orphan:model" to "My Alias")

        assertEquals(
            aliases,
            repairOrphanedCustomProviderPreferenceKeys(
                aliases = aliases,
                knownModelReferences = listOf("$first:model", "$second:model"),
                activeProviderIds = setOf(first, second),
            ),
        )
    }
}
