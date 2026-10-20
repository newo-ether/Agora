package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.util.Constants
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationRequestBuilderInteractionPreferencesTest {
    @Test
    fun `ordinary prompt without catalog opt in never enumerates skill files`() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false, catalogOptIn = false)
        val snapshot = fixture.builder.captureContextProjectionSnapshot("conversation", fixture.modelId)
        assertEquals("system|active memory|", snapshot.config.effectiveSystemPrompt)
        assertEquals("", snapshot.context.skillCatalog)
        verify(exactly = 0) { fixture.skillManager.catalog() }
    }

    @Test
    fun `interactive answer preference is frozen in the admitted generation`() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val enabled = MutableStateFlow(true)
        every { fixture.settings.interactiveAnswersEnabled } returns enabled
        val eink = MutableStateFlow(true)
        every { fixture.settings.eInkMode } returns eink
        val snapshot = fixture.builder.captureAdmissionSnapshot(
            conversationId = "conversation", runId = "run", modelId = fixture.modelId,
            conversationOverride = ChatEntity("conversation", "Chat"),
        )
        enabled.value = false
        eink.value = false
        assertTrue(snapshot.context.eInkMode)
        assertTrue(snapshot.context.interactiveAnswersEnabled)
        assertEquals("render_interactive_answer", com.newoether.agora.tool.InteractiveAnswerToolProvider()
            .definitions(snapshot.context).single().function.name)
    }

    @Test
    fun askUserTimeoutIsFrozenForTheAdmittedContextAndChangesForTheNextRun() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val timeoutMinutes = MutableStateFlow(1)
        every { fixture.settings.askUserTimeoutMinutes } returns timeoutMinutes
        val first = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        assertEquals(1, first.context.askUserTimeoutMinutes)

        timeoutMinutes.value = 60
        val next = fixture.builder.captureAdmissionSnapshot("conversation", "next-run", fixture.modelId)

        assertEquals(60, next.context.askUserTimeoutMinutes)
        assertEquals(1, first.context.askUserTimeoutMinutes)
    }

}
