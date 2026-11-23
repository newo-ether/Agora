package com.newoether.agora.api.util

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ProviderControlRecoveryTest {

    private fun tool() = ToolDefinition(
        function = ToolFunction("shell_exec", "d", ToolParameters(properties = emptyMap())),
    )

    private fun request() = OpenAiChatRequest(
        model = "m",
        messages = emptyList(),
        tools = listOf(tool()),
        reasoningEffort = "high",
    )

    private val toolRejectionBody =
        """{"error":{"message":"No endpoints found that support tool use"}}"""
    private val reasoningRejectionBody =
        """{"error":{"message":"Unknown parameter: reasoning_effort"}}"""

    @Test
    fun `fresh model keeps tools and reasoning`() {
        val recovery = ProviderControlRecovery("model-${UUID.randomUUID()}")

        val applied = recovery.apply(request())

        assertNotNull(applied.tools)
        assertNotNull(applied.reasoningEffort)
    }

    @Test
    fun `tool rejection drops tools once and is remembered for the model`() {
        val modelId = "model-${UUID.randomUUID()}"
        val recovery = ProviderControlRecovery(modelId)

        assertTrue(recovery.recoveredByDropping(404, toolRejectionBody, "openrouter"))
        assertTrue(recovery.omitTools)
        assertNull(recovery.apply(request()).tools)
        assertNotNull(recovery.apply(request()).reasoningEffort)

        // One recovery per control per request stream; the rejection is not re-consumed.
        assertFalse(recovery.recoveredByDropping(404, toolRejectionBody, "openrouter"))

        // The same model keeps the suppression while the TTL is fresh.
        assertTrue(ProviderControlRecovery(modelId).omitTools)
        assertNull(ProviderControlRecovery(modelId).apply(request()).tools)
    }

    @Test
    fun `reasoning rejection strips every thinking control`() {
        val recovery = ProviderControlRecovery("model-${UUID.randomUUID()}")

        assertTrue(recovery.recoveredByDropping(400, reasoningRejectionBody, "test"))
        assertTrue(recovery.omitReasoning)

        val applied = recovery.apply(request())
        assertNull(applied.reasoningEffort)
        assertNull(applied.thinking)
        assertNull(applied.enableThinking)
        assertNull(applied.thinkingBudget)
        assertNull(applied.chatTemplateKwargs)
        assertNull(applied.reasoning)
        assertNull(applied.includeReasoning)
        assertNotNull(applied.tools)
    }

    @Test
    fun `unrelated failures recover nothing`() {
        val recovery = ProviderControlRecovery("model-${UUID.randomUUID()}")

        assertFalse(recovery.recoveredByDropping(500, toolRejectionBody, "test"))
        assertFalse(recovery.recoveredByDropping(401, "invalid api key", "test"))
        assertFalse(recovery.recoveredByDropping(400, """{"error":{"message":"context length exceeded"}}""", "test"))
        assertFalse(recovery.omitTools)
        assertFalse(recovery.omitReasoning)
    }
}
