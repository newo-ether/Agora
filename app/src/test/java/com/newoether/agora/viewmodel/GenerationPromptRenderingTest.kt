package com.newoether.agora.viewmodel

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.resolveRequest
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.data.DefaultSystemPrompt
import com.newoether.agora.data.HtmlPreviewGuidance
import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.data.PromptItemType
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.util.Constants
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GenerationPromptRenderingTest {
    @Test
    fun staticDefaultAndCapturedMemorySurviveToolChangesContinuationsAndRetries() = runTest {
        val fixture = fixture()
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val config = ProviderConfig(
            apiKey = "key", modelId = "model", requestResolver = snapshot.config.requestResolver,
        )
        val first = config.resolveRequest(emptyList()).systemPrompt.orEmpty()
        listOf("Memory", "Skills", "Past conversations", "Web", "Devices",
            "Subagents and automation", "Asking the user").forEach {
            assertTrue(first.contains("## $it"))
        }
        assertTrue(first.contains("<active_memory>\nactive memory\n</active_memory>"))
        assertTrue(first.contains("<skill_catalog>\nskill catalog\n</skill_catalog>"))
        every { fixture.memoryManager.getActiveMemory() } returns "edited during run"
        every { fixture.skillManager.catalog() } returns "edited during run"
        listOf(emptyList(), listOf(tool("web_search")), listOf(tool("active_memory_update"))).forEach {
            repeat(3) { _ ->
                assertEquals(first, config.copy(tools = it).resolveRequest(emptyList()).systemPrompt)
            }
        }
        assertFalse(first.contains("edited during run"))
    }

    @Test
    fun previewAndDispatchUseIdenticalPromptAndFixedCostWithOrWithoutTools() = runTest {
        val fixture = fixture()
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val preview = fixture.builder.captureContextProjectionSnapshot("conversation", fixture.modelId)
        val tools = listOf(tool("memory_read"), tool("skill_read"), tool("file_read"), tool("ask_user"))
        val accounting = GenerationContextAccounting { tools }
        val dispatched = ProviderConfig(
            apiKey = "key", modelId = "model", tools = tools,
            requestResolver = snapshot.config.requestResolver,
        ).resolveRequest(emptyList())
        assertEquals(dispatched.systemPrompt, preview.config.effectiveSystemPrompt)
        assertEquals(
            ContextTokenEstimator.forModel("model").estimateFixed(
                systemPrompt = dispatched.systemPrompt,
                tools = tools,
                codeExecutionEnabled = preview.config.codeExecutionEnabled,
                googleSearchEnabled = preview.config.googleSearchEnabled,
                openAiWebSearchEnabled = preview.config.openAiWebSearchEnabled,
            ),
            accounting.fixedContextTokenCost(preview.config, preview.context),
        )
        assertEquals(
            accounting.fixedContextTokenCost(preview.config, preview.context),
            accounting.resolvedFixedContextTokenCost(snapshot.config, snapshot.context),
        )
        val noTools = ProviderConfig(
            apiKey = "key", modelId = "model", requestResolver = snapshot.config.requestResolver,
        ).resolveRequest(emptyList())
        assertEquals(dispatched.systemPrompt, noTools.systemPrompt)
        val emptyAccounting = GenerationContextAccounting { emptyList() }
        assertTrue(accounting.fixedContextTokenCost(preview.config, preview.context) >
            emptyAccounting.fixedContextTokenCost(preview.config, preview.context))
        assertEquals(
            emptyAccounting.fixedContextTokenCost(preview.config, preview.context),
            emptyAccounting.resolvedFixedContextTokenCost(snapshot.config, snapshot.context),
        )
    }

    @Test
    fun contextProjectorPricesTheSharedCapturedStaticPrompt() = runTest {
        val fixture = fixture()
        val tools = listOf(tool("web_fetch"), tool("shell_exec"))
        val accounting = GenerationContextAccounting { tools }
        val manager = mockk<GenerationManager>()
        every { manager.fixedContextTokenCost(any(), any()) } answers {
            accounting.fixedContextTokenCost(firstArg(), secondArg())
        }
        every { manager.fixedContextComposition(any(), any()) } answers {
            accounting.fixedContextComposition(firstArg(), secondArg())
        }
        every { manager.includesAssistantReasoning(any(), any()) } returns false
        val projector = ConversationContextProjector(
            conversations = fixture.conversations,
            requestBuilder = fixture.builder,
            generationManager = { manager },
            generationErrorFormatter = { it },
            newChatSystemPromptId = { "prompt-id" },
        )
        val projection = projector.project(null, null, fixture.modelId, 32_768)
        assertFalse(projection.failed)
        assertTrue(projection.completed)
        val captured = fixture.builder.captureContextProjectionSnapshot(
            "context-preview-conversation", fixture.modelId, "prompt-id",
        )
        assertEquals(
            accounting.fixedContextTokenCost(captured.config, captured.context),
            requireNotNull(projection.usage).estimatedTokenCount,
        )
    }

    @Test
    fun toolsNeverInjectGuidanceIntoCustomText() = runTest {
        val fixture = fixture()
        val custom = "custom\n\n\nexact text"
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH).copy(
            id = "prompt-id",
            systemItems = listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = custom)),
        )
        every { fixture.settings.systemPrompts } returns MutableStateFlow(listOf(entry))
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val result = ProviderConfig(
            apiKey = "key", modelId = "model", tools = listOf(tool("memory_read"), tool("web_search")),
            requestResolver = snapshot.config.requestResolver,
        ).resolveRequest(emptyList())
        assertEquals(custom, result.systemPrompt)
    }

    @Test
    fun htmlGuidanceIsPresentInDispatchAndPreviewOnlyWhenStoredInTheTemplate() = runTest {
        val fixture = fixture()
        val original = DefaultSystemPrompt.create(Locale.ENGLISH).copy(id = "prompt-id")
        val stored = MutableStateFlow(listOf(original))
        every { fixture.settings.systemPrompts } returns stored
        val values = mapOf("active_memory" to "active memory", "skill_catalog" to "skill catalog")
        val removed = original.copy(systemItems = original.systemItems.filterNot { it.value == HtmlPreviewGuidance.text })
        val edited = original.copy(systemItems = original.systemItems.map {
            if (it.value == HtmlPreviewGuidance.text) it.copy(value = "\n\nMy edited HTML guidance") else it
        })
        listOf(original, removed, edited).forEachIndexed { index, entry ->
            stored.value = listOf(entry)
            val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run-$index", fixture.modelId)
            val preview = fixture.builder.captureContextProjectionSnapshot("conversation", fixture.modelId)
            val expected = PredefinedVariables.compile(entry.systemItems, values)
            listOf(emptyList(), listOf(tool("file_read"), tool("web_search"))).forEach { tools ->
                val request = ProviderConfig(
                    apiKey = "key", modelId = "model", tools = tools,
                    requestResolver = snapshot.config.requestResolver,
                ).resolveRequest(emptyList())
                assertEquals(expected, request.systemPrompt)
                assertEquals(expected, preview.config.effectiveSystemPrompt)
                assertEquals(index == 0, request.systemPrompt.orEmpty().contains(HtmlPreviewGuidance.text))
            }
            assertEquals(entry.systemItems, snapshot.config.promptTemplate?.systemItems)
        }
    }

    @Test
    fun retiredItemsAreEmptyInActualSystemAndMessageTemplatesAndPreview() = runTest {
        val fixture = fixture()
        val retired = DefaultSystemPrompt.versionWithToolGuidesForMigration(Locale.ENGLISH)
            .systemItems.filter { it.value.startsWith("tool_guide_") }
        val custom = DefaultSystemPrompt.create(Locale.ENGLISH).copy(
            id = "prompt-id", title = "My custom prompt",
            systemItems = retired,
            userItems = retired + PredefinedVariables.promptItem(),
            assistantItems = listOf(PredefinedVariables.promptItem()) + retired,
        )
        every { fixture.settings.systemPrompts } returns MutableStateFlow(listOf(custom))
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val preview = fixture.builder.captureContextProjectionSnapshot("conversation", fixture.modelId)
        val result = ProviderConfig(
            apiKey = "key", modelId = "model", tools = listOf(tool("memory_read")),
            requestResolver = snapshot.config.requestResolver,
        ).resolveRequest(emptyList())
        assertEquals(null, result.systemPrompt)
        assertEquals(null, preview.config.effectiveSystemPrompt)
        assertEquals(null, preview.config.userPrepend)
        assertEquals(null, preview.config.assistantPostpend)
        assertEquals(retired, snapshot.config.promptTemplate?.systemItems)
    }

    @Test
    fun preservedCompactRetainsTheCompleteOrdinaryStaticPrompt() = runTest {
        val fixture = fixture(compactPreserveSystemPrompt = true)
        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        val prompt = snapshot.automaticCompact.generationConfig.effectiveSystemPrompt.orEmpty()
        val ordinary = ProviderConfig(
            apiKey = "key", modelId = "model", requestResolver = snapshot.config.requestResolver,
        ).resolveRequest(emptyList()).systemPrompt
        assertEquals(ordinary, prompt)
        assertTrue(prompt.contains("## Memory"))
        assertTrue(prompt.contains("## Devices"))
        assertTrue(prompt.contains("<active_memory>\nactive memory\n</active_memory>"))
    }

    private fun fixture(compactPreserveSystemPrompt: Boolean = false): RequestBuilderFixture {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false, compactPreserveSystemPrompt)
        every { fixture.settings.systemPrompts } returns MutableStateFlow(
            listOf(DefaultSystemPrompt.create(Locale.ENGLISH).copy(id = "prompt-id")),
        )
        return fixture
    }

    private fun tool(name: String) = ToolDefinition(
        function = ToolFunction(name, "test tool", ToolParameters(properties = emptyMap())),
    )
}
