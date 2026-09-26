package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.data.PromptItemType
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationRequestBuilderProviderDisplayTest {
    @Test
    fun foregroundExistingAdmissionWaitsForSettingsThenUsesCapturedConversation() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val gate = CompletableDeferred<Unit>()
        coEvery { fixture.settings.awaitInitialLoad() } coAnswers { gate.await() }
        coEvery { fixture.conversations.getConversation("conversation") } returns
            ChatEntity("conversation", "Existing")
        val target = ForegroundSendTarget(
            "conversation", "conversation", "run", false, null, fixture.modelId,
        )
        val pending = async {
            fixture.builder.prepareForegroundSend(target, ConversationComposerSnapshot(), fixture.appContext)
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        coVerify(exactly = 0) { fixture.providerRegistry.awaitInitialSync() }
        coVerify(exactly = 0) { fixture.conversations.getConversation(any()) }
        gate.complete(Unit)
        val admission = requireNotNull(pending.await())
        assertEquals(target, admission.target)
        assertEquals("run", admission.generationSnapshot.runId)
        assertEquals("conversation", admission.generationSnapshot.conversationId)
        assertNull(admission.newConversation)
        assertNull(admission.newChatPersistSnapshot)
        coVerify(exactly = 1) { fixture.conversations.getConversation("conversation") }
    }

    @Test
    fun foregroundNewAdmissionWaitsForCapturedWorkspaceAndKeepsFrozenDraft() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val gate = CompletableDeferred<NewChatPersistEntity?>()
        val capturedSettings = ConversationSettings(codeExecutionEnabled = false)
        val persisted = NewChatPersistEntity(
            modelId = "older:model",
            draftText = "older draft",
            conversationSettingsJson = Json.encodeToString(capturedSettings),
        )
        val target = ForegroundSendTarget(
            NEW_CHAT_WORKSPACE_ID, "new-conversation", "new-run", true, 7L, fixture.modelId,
            NewChatWorkspaceSnapshot.pending(null, gate),
        )
        val pending = async {
            fixture.builder.prepareForegroundSend(
                target, ConversationComposerSnapshot(text = "frozen draft"), fixture.appContext,
            )
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        coVerify(exactly = 0) { fixture.settings.awaitActiveKey(any()) }
        gate.complete(persisted)
        val admission = requireNotNull(pending.await())
        assertEquals(target, admission.target)
        assertEquals(fixture.modelId, admission.newConversation?.modelId)
        assertEquals("frozen draft", admission.newConversation?.title)
        assertEquals(capturedSettings, admission.newConversationSettings)
        assertEquals("frozen draft", admission.newChatPersistSnapshot?.draftText)
        assertEquals("older:model", admission.newChatPersistSnapshot?.modelId)
        assertFalse(admission.generationSnapshot.config.codeExecutionEnabled)
        coVerify(exactly = 0) { fixture.conversations.getConversation(any()) }
    }

    @Test
    fun foregroundAdmissionCancellationDuringProviderWaitDoesNotReadConversation() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val gate = CompletableDeferred<Unit>()
        coEvery { fixture.providerRegistry.awaitInitialSync() } coAnswers { gate.await() }
        val target = ForegroundSendTarget(
            "conversation", "conversation", "run", false, null, fixture.modelId,
        )
        val pending = async {
            fixture.builder.prepareForegroundSend(target, ConversationComposerSnapshot(), fixture.appContext)
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        pending.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertTrue(pending.isCancelled)
        coVerify(exactly = 0) { fixture.conversations.getConversation(any()) }
        coVerify(exactly = 0) { fixture.settings.awaitActiveKey(any()) }
    }

    @Test
    fun foregroundBlankModelUsesOriginalValidationContextAndRejectsBeforeProvider() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val validationContext = mockk<Context>()
        every { validationContext.getString(R.string.no_model_selected) } returns "Select a model"
        val target = ForegroundSendTarget("conversation", "conversation", "run", false, null, "")
        assertNull(fixture.builder.prepareForegroundSend(target, ConversationComposerSnapshot(), validationContext))
        assertEquals(listOf("Select a model"), fixture.snackbars)
        coVerify(exactly = 0) { fixture.providerRegistry.awaitInitialSync() }
    }

    @Test
    fun selectedCompactAndTranscriptionProvidersHaveIndependentFrozenCacheFields() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val custom = CustomProviderConfig(
            "Relay", CustomEndpointProtocol.ANTHROPIC,
            id = "custom-provider-00000000-0000-4000-8000-000000000001",
            anthropicCacheEnabled = false, anthropicCacheTtl = "5m",
        )
        val providers = MutableStateFlow(listOf(custom))
        every { fixture.settings.customProviders } returns providers
        every { fixture.settings.contextCompactModel } returns MutableStateFlow("${custom.providerId}:model")
        every { fixture.settings.imageTranscriptionModel } returns MutableStateFlow("${custom.providerId}:vision")
        every { fixture.providerRegistry.providerForModel(any()) } answers { firstArg<String>().substringBefore(':') }
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        providers.value = listOf(custom.copy(anthropicCacheEnabled = true, anthropicCacheTtl = "1h"))
        assertFalse(snapshot.config.anthropicCacheEnabled)
        assertEquals("1h", snapshot.config.anthropicCacheTtl)
        assertFalse(snapshot.automaticCompact.generationConfig.anthropicCacheEnabled)
        assertEquals("5m", snapshot.automaticCompact.generationConfig.anthropicCacheTtl)
        assertFalse(snapshot.context.transcriptionAnthropicCacheEnabled)
        assertEquals("5m", snapshot.context.transcriptionAnthropicCacheTtl)
    }
    @Test
    fun cacheChoiceIsFrozenForGenerationCompactAndTranscription() = runTest {
        val fixture = RequestBuilderFixture(providerName = Constants.PROVIDER_ANTHROPIC, lowContextModeEnabled = false)
        val enabled = MutableStateFlow(false)
        val ttl = MutableStateFlow("5m")
        every { fixture.settings.anthropicCacheEnabled } returns enabled
        every { fixture.settings.anthropicCacheTtl } returns ttl
        every { fixture.settings.imageTranscriptionModel } returns MutableStateFlow(fixture.modelId)
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        enabled.value = true
        ttl.value = "1h"
        assertFalse(snapshot.config.anthropicCacheEnabled)
        assertEquals("5m", snapshot.config.anthropicCacheTtl)
        assertFalse(snapshot.automaticCompact.generationConfig.anthropicCacheEnabled)
        assertEquals("5m", snapshot.automaticCompact.generationConfig.anthropicCacheTtl)
        assertFalse(snapshot.context.transcriptionAnthropicCacheEnabled)
        assertEquals("5m", snapshot.context.transcriptionAnthropicCacheTtl)
    }
    @Test
    fun `preserve mode resolves the ordinary system prompt for the compact request`() = runTest {
        val fixture = RequestBuilderFixture(
            providerName = Constants.PROVIDER_OPENAI,
            lowContextModeEnabled = false,
            compactPreserveSystemPrompt = true,
        )

        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)

        assertTrue(snapshot.automaticCompact.request.preserveSystemPrompt)
        assertEquals(
            RequestBuilderFixture.COMPACT_PROMPT,
            snapshot.automaticCompact.request.prompt,
        )
        assertEquals(
            RequestBuilderFixture.RESOLVED_SYSTEM_PROMPT,
            snapshot.automaticCompact.generationConfig.effectiveSystemPrompt,
        )
    }
    @Test
    fun `legacy mode keeps the compact prompt as the compact system prompt`() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)

        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)

        assertFalse(snapshot.automaticCompact.request.preserveSystemPrompt)
        assertEquals(
            RequestBuilderFixture.COMPACT_PROMPT,
            snapshot.automaticCompact.generationConfig.effectiveSystemPrompt,
        )
    }
    @Test
    fun `conversation tool overrides are reflected immediately in effective settings`() {
        val settings = mockk<SettingsRepository>()
        every { settings.conversationSettings } returns MutableStateFlow(
            mapOf(
                "conversation" to ConversationSettings(
                    webSearchEnabled = false,
                    shellEnabled = false,
                    openAiWebSearchEnabled = false,
                    lowContextModeEnabled = false,
                )
            )
        )
        every { settings.maxContextWindow } returns MutableStateFlow(128_000)
        every { settings.defaultTemperature } returns MutableStateFlow(null)
        every { settings.defaultMaxTokens } returns MutableStateFlow(null)
        every { settings.defaultTopP } returns MutableStateFlow(null)
        every { settings.defaultFrequencyPenalty } returns MutableStateFlow(null)
        every { settings.defaultPresencePenalty } returns MutableStateFlow(null)
        every { settings.codeExecutionEnabled } returns MutableStateFlow(false)
        every { settings.googleSearchEnabled } returns MutableStateFlow(false)
        every { settings.thinkingEnabled } returns MutableStateFlow(true)
        every { settings.thinkingLevel } returns MutableStateFlow("medium")
        every { settings.thinkingBudgetEnabled } returns MutableStateFlow(false)
        every { settings.thinkingBudgetTokens } returns MutableStateFlow(4096)
        every { settings.openAiServiceTierEnabled } returns MutableStateFlow(false)
        every { settings.openAiServiceTier } returns MutableStateFlow("auto")
        every { settings.webSearchEnabled } returns MutableStateFlow(true)
        every { settings.shellEnabled } returns MutableStateFlow(true)
        every { settings.localLowContextModeEnabled } returns MutableStateFlow(true)
        val builder = GenerationRequestBuilder(
            settings = settings,
            convRepo = mockk<ConversationRepository>(),
            memoryManager = mockk<MemoryManager>(),
            skillManager = mockk<SkillManager>(),
            providerRegistry = mockk<ProviderRegistry>(),
            ragManager = mockk<RagManager>(),
            appContext = mockk<Context>(),
            pendingConversationSettings = MutableStateFlow<ConversationSettings?>(null),
            onSnackbar = {},
        )

        val effective = builder.buildEffectiveConversationSettings("conversation")

        assertEquals(false, effective.webSearchEnabled)
        assertEquals(false, effective.shellEnabled)
        assertEquals(false, effective.openAiWebSearchEnabled)
        assertEquals(false, effective.lowContextModeEnabled)
    }

    @Test
    fun `local low context admission omits structured prompt work and preserves compact prompt`() = runTest {
        val fixture = RequestBuilderFixture(
            providerName = Constants.PROVIDER_LOCAL,
            lowContextModeEnabled = true,
        )

        val snapshot = fixture.builder.captureAdmissionSnapshot(
            conversationId = "conversation",
            runId = "run",
            modelId = fixture.modelId,
            resolvedPromptOverride = GenerationRequestBuilder.ResolvedPrompt(
                systemPrompt = "override system",
                userPrepend = "override user prepend",
                userPostpend = "override user postpend",
                assistantPrepend = "override assistant prepend",
                assistantPostpend = "override assistant postpend",
            ),
        )

        assertTrue(snapshot.config.lowContextModeEnabled)
        assertNull(snapshot.config.effectiveSystemPrompt)
        assertNull(snapshot.config.userPrepend)
        assertNull(snapshot.config.userPostpend)
        assertNull(snapshot.config.assistantPrepend)
        assertNull(snapshot.config.assistantPostpend)
        assertNull(snapshot.config.promptTemplate)
        assertNull(snapshot.config.requestResolver)
        assertFalse(snapshot.config.codeExecutionEnabled)
        assertFalse(snapshot.config.googleSearchEnabled)
        assertFalse(snapshot.config.openAiWebSearchEnabled)
        assertFalse(snapshot.context.skillReadAccess)
        assertFalse(snapshot.context.skillModifyAccess)
        assertEquals("", snapshot.context.skillCatalog)
        coVerify(exactly = 0) { fixture.conversations.getConversation(any()) }
        verify(exactly = 0) { fixture.memoryManager.getActiveMemory() }
        verify(exactly = 0) { fixture.skillManager.catalog() }

        assertFalse(snapshot.automaticCompact.generationConfig.lowContextModeEnabled)
        assertEquals(
            RequestBuilderFixture.COMPACT_PROMPT,
            snapshot.automaticCompact.generationConfig.effectiveSystemPrompt,
        )
        assertFalse(snapshot.automaticCompact.generationContext.skillReadAccess)
        assertEquals("", snapshot.automaticCompact.generationContext.skillCatalog)
    }

    @Test
    fun `local low context disabled preserves structured prompt projection`() = runTest {
        val fixture = RequestBuilderFixture(
            providerName = Constants.PROVIDER_LOCAL,
            lowContextModeEnabled = false,
        )

        val snapshot = fixture.builder.captureContextProjectionSnapshot(
            conversationId = "conversation",
            modelId = fixture.modelId,
        )

        assertFalse(snapshot.config.lowContextModeEnabled)
        assertEquals(RequestBuilderFixture.RESOLVED_SYSTEM_PROMPT, snapshot.config.effectiveSystemPrompt)
        assertEquals("user-before|", snapshot.config.userPrepend)
        assertEquals("|user-after", snapshot.config.userPostpend)
        assertEquals("assistant-before|", snapshot.config.assistantPrepend)
        assertEquals("|assistant-after", snapshot.config.assistantPostpend)
        assertTrue(snapshot.config.promptTemplate != null)
        assertTrue(snapshot.context.skillReadAccess)
        assertEquals(RequestBuilderFixture.SKILL_CATALOG, snapshot.context.skillCatalog)
        verify { fixture.memoryManager.getActiveMemory() }
        verify { fixture.skillManager.catalog() }
    }

    @Test
    fun `remote and ollama ignore a true low context conversation override`() = runTest {
        listOf(Constants.PROVIDER_OPENAI, Constants.PROVIDER_OLLAMA).forEach { providerName ->
            val fixture = RequestBuilderFixture(
                providerName = providerName,
                lowContextModeEnabled = true,
            )

            val snapshot = fixture.builder.captureContextProjectionSnapshot(
                conversationId = "conversation",
                modelId = fixture.modelId,
            )

            assertFalse(providerName, snapshot.config.lowContextModeEnabled)
            assertEquals(
                providerName,
                RequestBuilderFixture.RESOLVED_SYSTEM_PROMPT,
                snapshot.config.effectiveSystemPrompt,
            )
            assertEquals(providerName, "user-before|", snapshot.config.userPrepend)
            assertEquals(providerName, "|user-after", snapshot.config.userPostpend)
            assertTrue(providerName, snapshot.config.promptTemplate != null)
        }
    }

    @Test
    fun `generation provider admission waits for initial registry sync before resolving`() = runTest {
        val modelId = "custom-provider-00000000-0000-4000-8000-000000000001:model"
        val providerName = "Relay X"
        val settings = mockk<SettingsRepository>()
        val providerRegistry = mockk<ProviderRegistry>()
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        coEvery { providerRegistry.awaitInitialSync() } coAnswers {
            events += "await"
            gate.await()
            events += "synced"
        }
        every { providerRegistry.providerForModel(modelId) } answers {
            events += "resolve"
            providerName
        }
        every { settings.resolveActiveKey(providerName) } returns "active-key"
        every { providerRegistry.isConfigured(providerName, "active-key") } returns true

        val builder = GenerationRequestBuilder(
            settings = settings,
            convRepo = mockk<ConversationRepository>(),
            memoryManager = mockk<MemoryManager>(),
            skillManager = mockk<SkillManager>(),
            providerRegistry = providerRegistry,
            ragManager = mockk<RagManager>(),
            appContext = mockk<Context>(),
            pendingConversationSettings = MutableStateFlow<ConversationSettings?>(null),
            onSnackbar = {},
        )

        val result = async { builder.awaitProviderKey(modelId) }
        runCurrent()

        assertEquals(listOf("await"), events)
        assertFalse(result.isCompleted)

        gate.complete(Unit)
        runCurrent()

        assertEquals(
            GenerationRequestBuilder.ProviderKey(providerName, "active-key"),
            result.await(),
        )
        assertEquals(listOf("await", "synced", "resolve"), events)
    }

    @Test
    fun `missing custom provider credentials show alias instead of stable id`() {
        val providerId = "custom-provider-00000000-0000-4000-8000-000000000001"
        val providerAlias = "Relay X"
        val modelId = "$providerId:model"
        val settings = mockk<SettingsRepository>()
        val providerRegistry = mockk<ProviderRegistry>()
        val appContext = mockk<Context>()
        val snackbars = mutableListOf<String>()

        every { settings.resolveActiveKey(providerId) } returns null
        every { settings.customProviders } returns MutableStateFlow(
            listOf(CustomProviderConfig(name = providerAlias, id = providerId))
        )
        every { providerRegistry.providerForModel(modelId) } returns providerId
        every { providerRegistry.isConfigured(providerId, "") } returns false
        every {
            appContext.getString(R.string.no_api_key_for_provider, providerAlias)
        } returns "No credentials configured for $providerAlias."

        val builder = GenerationRequestBuilder(
            settings = settings,
            convRepo = mockk<ConversationRepository>(),
            memoryManager = mockk<MemoryManager>(),
            skillManager = mockk<SkillManager>(),
            providerRegistry = providerRegistry,
            ragManager = mockk<RagManager>(),
            appContext = appContext,
            pendingConversationSettings = MutableStateFlow<ConversationSettings?>(null),
            onSnackbar = snackbars::add,
        )

        assertNull(builder.resolveProviderKey(modelId))
        assertEquals(1, snackbars.size)
        assertTrue(snackbars.single().contains(providerAlias))
        assertFalse(snackbars.single().contains(providerId))
    }
}

private class RequestBuilderFixture(
    providerName: String,
    lowContextModeEnabled: Boolean,
    compactPreserveSystemPrompt: Boolean = false,
) {
    companion object {
        const val COMPACT_PROMPT = "compact prompt"
        const val SKILL_CATALOG = "skill catalog"
        const val RESOLVED_SYSTEM_PROMPT = "system|active memory|skill catalog"
        private const val PROMPT_ID = "prompt-id"
    }

    val modelId = "$providerName:model"
    val appContext = mockk<Context>()
    val snackbars = mutableListOf<String>()
    val settings = mockk<SettingsRepository>()
    val conversations = mockk<ConversationRepository>()
    val memoryManager = mockk<MemoryManager>()
    val skillManager = mockk<SkillManager>()
    val providerRegistry = mockk<ProviderRegistry>()
    private val ragManager = mockk<RagManager>()
    private val provider = mockk<com.newoether.agora.api.LlmProvider>()

    val builder: GenerationRequestBuilder

    init {
        val prompt = SystemPromptEntry(
            id = PROMPT_ID,
            title = "Prompt",
            systemItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "system|"),
                PromptTemplateItem(
                    type = PromptItemType.PREDEFINED,
                    value = PredefinedVariables.ACTIVE_MEMORY,
                ),
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "|"),
                PromptTemplateItem(
                    type = PromptItemType.PREDEFINED,
                    value = PredefinedVariables.SKILL_CATALOG,
                ),
            ),
            userItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "user-before|"),
                PredefinedVariables.promptItem(),
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "|user-after"),
            ),
            assistantItems = listOf(
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "assistant-before|"),
                PredefinedVariables.promptItem(),
                PromptTemplateItem(type = PromptItemType.CUSTOM, value = "|assistant-after"),
            ),
        )
        every { settings.conversationSettings } returns MutableStateFlow(
            mapOf(
                "conversation" to ConversationSettings(
                    codeExecutionEnabled = true,
                    googleSearchEnabled = true,
                    openAiWebSearchEnabled = true,
                    lowContextModeEnabled = lowContextModeEnabled,
                )
            )
        )
        every { settings.maxContextWindow } returns MutableStateFlow(32_768)
        every { settings.defaultTemperature } returns MutableStateFlow(null)
        every { settings.defaultMaxTokens } returns MutableStateFlow(null)
        every { settings.defaultTopP } returns MutableStateFlow(null)
        every { settings.defaultFrequencyPenalty } returns MutableStateFlow(null)
        every { settings.defaultPresencePenalty } returns MutableStateFlow(null)
        every { settings.codeExecutionEnabled } returns MutableStateFlow(true)
        every { settings.googleSearchEnabled } returns MutableStateFlow(true)
        every { settings.thinkingEnabled } returns MutableStateFlow(true)
        every { settings.thinkingLevel } returns MutableStateFlow("medium")
        every { settings.thinkingBudgetEnabled } returns MutableStateFlow(false)
        every { settings.thinkingBudgetTokens } returns MutableStateFlow(4096)
        every { settings.openAiServiceTierEnabled } returns MutableStateFlow(false)
        every { settings.openAiServiceTier } returns MutableStateFlow("auto")
        every { settings.webSearchEnabled } returns MutableStateFlow(true)
        every { settings.shellEnabled } returns MutableStateFlow(true)
        every { settings.localLowContextModeEnabled } returns MutableStateFlow(false)
        every { settings.contextCompactModel } returns MutableStateFlow(null)
        every { settings.contextCompactPrompt } returns MutableStateFlow(COMPACT_PROMPT)
        every { settings.contextCompactEnabled } returns MutableStateFlow(true)
        every { settings.contextCompactThresholdPercent } returns MutableStateFlow(80)
        every { settings.contextCompactRetainCount } returns MutableStateFlow(8)
        every { settings.contextCompactPreserveSystemPrompt } returns
            MutableStateFlow(compactPreserveSystemPrompt)
        every { settings.openAiResponsesApiEnabled } returns MutableStateFlow(false)
        every { settings.anthropicCacheEnabled } returns MutableStateFlow(true)
        every { settings.anthropicCacheTtl } returns MutableStateFlow("1h")
        every { settings.customProviders } returns MutableStateFlow(emptyList())
        every { settings.titleGenerationEnabled } returns MutableStateFlow(true)
        every { settings.imageGenModel } returns MutableStateFlow(null)
        every { settings.imageTranscriptionModel } returns MutableStateFlow(null)
        every { settings.accessSkills } returns MutableStateFlow(true)
        every { settings.accessSkillsModify } returns MutableStateFlow(true)
        every { settings.accessSavedMemories } returns MutableStateFlow(true)
        every { settings.accessActiveMemory } returns MutableStateFlow(true)
        every { settings.accessPastConversations } returns MutableStateFlow(true)
        every { settings.modelSearchMethod } returns MutableStateFlow("keyword")
        every { settings.ragThreshold } returns MutableStateFlow(0.5f)
        every { settings.searchMatchLimit } returns MutableStateFlow(10)
        every { settings.searchContextWindow } returns MutableStateFlow(8)
        every { settings.webSearchApiKeys } returns MutableStateFlow(emptyMap())
        every { settings.webSearchProvider } returns MutableStateFlow("duckduckgo")
        every { settings.webSearchNumResults } returns MutableStateFlow(5)
        every { settings.webSearchBaseUrl } returns MutableStateFlow("")
        every { settings.imageGenEnabled } returns MutableStateFlow(false)
        every { settings.imageGenSize } returns MutableStateFlow("1024x1024")
        every { settings.automationToolsEnabled } returns MutableStateFlow(true)
        every { settings.shellDevices } returns MutableStateFlow(emptyList())
        every { settings.sandboxEnabled } returns MutableStateFlow(true)
        every { settings.sandboxSharedStorageEnabled } returns MutableStateFlow(true)
        every { settings.imageTranscriptionEnabled } returns MutableStateFlow(false)
        every { settings.imageTranscriptionEnabledModels } returns MutableStateFlow(emptySet())
        every { settings.imageTranscriptionBatchSize } returns MutableStateFlow(3)
        every { settings.imageTranscriptionPrompt } returns MutableStateFlow("transcribe")
        every { settings.activeSystemPromptId } returns MutableStateFlow(PROMPT_ID)
        every { settings.systemPrompts } returns MutableStateFlow(listOf(prompt))
        coEvery { settings.awaitActiveKey(any()) } returns "key"
        coEvery { settings.awaitInitialLoad() } returns Unit
        every { appContext.getString(R.string.new_chat) } returns "New chat"
        every { settings.resolveActiveKey(any()) } returns "key"

        coEvery { providerRegistry.awaitInitialSync() } returns Unit
        every { providerRegistry.canonicalModelId(any()) } answers { firstArg() }
        every { providerRegistry.providerForModel(any()) } returns providerName
        every { providerRegistry.isConfigured(any(), any()) } returns true
        every { providerRegistry.generationSnapshot() } returns mapOf(providerName to provider)
        every { providerRegistry.getEffectiveBaseUrl(any()) } returns null

        every { ragManager.activeEmbeddingModel } returns MutableStateFlow(null)
        every { ragManager.resolveEmbeddingApiKey() } returns null
        coEvery { conversations.getConversation(any()) } returns null
        every { memoryManager.getActiveMemory() } returns "active memory"
        every { skillManager.catalog() } returns SKILL_CATALOG

        builder = GenerationRequestBuilder(
            settings = settings,
            convRepo = conversations,
            memoryManager = memoryManager,
            skillManager = skillManager,
            providerRegistry = providerRegistry,
            ragManager = ragManager,
            appContext = appContext,
            pendingConversationSettings = MutableStateFlow(null),
            onSnackbar = snackbars::add,
        )
    }
}
