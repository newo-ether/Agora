package com.newoether.agora.viewmodel

import com.newoether.agora.util.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationRequestBuilderLowContextDefaultTest {
    @Test
    fun `local follows the global low context default without an override`() = runTest {
        val fixture = RequestBuilderFixture(
            providerName = Constants.PROVIDER_LOCAL,
            lowContextModeEnabled = null,
            localLowContextDefault = true,
        )

        val snapshot = fixture.builder.captureContextProjectionSnapshot(
            conversationId = "conversation",
            modelId = fixture.modelId,
        )

        assertTrue(snapshot.config.lowContextModeEnabled)
        assertNull(snapshot.config.effectiveSystemPrompt)
    }

    @Test
    fun `remote and ollama ignore the local low context default`() = runTest {
        listOf(Constants.PROVIDER_OPENAI, Constants.PROVIDER_OLLAMA).forEach { providerName ->
            val fixture = RequestBuilderFixture(
                providerName = providerName,
                lowContextModeEnabled = null,
                localLowContextDefault = true,
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
}
