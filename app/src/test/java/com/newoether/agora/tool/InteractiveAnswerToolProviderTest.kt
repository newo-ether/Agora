package com.newoether.agora.tool

import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InteractiveAnswerToolProviderTest {
    private val provider = InteractiveAnswerToolProvider()
    private val enabled = GenerationContext(interactiveAnswersEnabled = true)
    private val answer = InteractiveAnswer("Calculator", "Double a number", "Three doubled is six", html = "<output>6</output>")

    @Test fun `tool is opt in and does not require another provider`() {
        assertTrue(provider.definitions(GenerationContext()).isEmpty())
        assertEquals(InteractiveAnswer.TOOL_NAME, provider.definitions(enabled).single().function.name)
    }
    @Test fun `disabled execution refuses without returning executable content`() = runTest {
        assertFails { provider.executeOneShotResult(InteractiveAnswer.TOOL_NAME, answer.encode(), GenerationContext()) }
    }
    @Test fun `completed result separates model text from structured artifact`() = runTest {
        val result = provider.executeOneShotResult(InteractiveAnswer.TOOL_NAME, answer.encode(), enabled)
        assertEquals(answer, InteractiveAnswer.decode(result.structuredContent!!))
        assertEquals(answer.fallback, result.displayText)
        assertFalse(result.text.contains("<output>"))
        assertTrue(result.text.contains(answer.fallback))
        assertFalse(result.isError)
    }
    @Test fun `large artifacts fail before the generic result can clip their code`() = runTest {
        assertFails { provider.executeOneShotResult(InteractiveAnswer.TOOL_NAME,
            answer.copy(html = "x".repeat(80000)).encode(), enabled) }
    }
    @Test fun `partial tool arguments never create an artifact`() = runTest {
        assertFails { provider.executeOneShotResult(InteractiveAnswer.TOOL_NAME, "{\"html\":", enabled) }
    }
    @Test fun `missing fallback malformed code fields and unknown format fail closed`() {
        for (value in listOf(
            """{"title":"x","description":"x","html":"x"}""",
            """{"title":"x","description":"x","fallback":"x","html":12}""",
            answer.copy(version = 2).encode(), answer.copy(kind = "foreign").encode(),
            answer.copy(fallback = " ").encode(), answer.copy(jsExpressions = List(101) { "0" }).encode(),
        )) assertThrows(IllegalArgumentException::class.java) { InteractiveAnswer.decode(value) }
    }
    @Test fun `size budget measures utf8 and the persisted encoding`() {
        assertThrows(IllegalArgumentException::class.java) { InteractiveAnswer.decode("x".repeat(InteractiveAnswer.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { answer.copy(html = "漢".repeat(400000)).validate() }
        assertThrows(IllegalArgumentException::class.java) { answer.copy(html = "\\".repeat(600000)).validate() }
    }
    @Test fun `eink guidance is conditional and awareness is stamped from admission not model arguments`() = runTest {
        val ordinary = provider.definitions(enabled).single().function.description
        val eink = provider.definitions(enabled.copy(eInkMode = true)).single().function.description
        // PR #154 head 0cf872e: preserve the approved default instructions exactly.
        val prDefault = javaClass.getResource("/fixtures/interactive-answer-pr154-description.txt")!!
            .readText().removeSuffix("\n")
        assertEquals(prDefault, ordinary)
        assertEquals(prDefault + INTERACTIVE_ANSWER_EINK_GUIDANCE, eink)
        assertTrue("e-ink addendum should stay concise", INTERACTIVE_ANSWER_EINK_GUIDANCE.trim().split(Regex("\\s+")).size <= 160)
        assertFalse(ordinary.contains("E-ink adaptation"))
        assertFalse(provider.definitions(enabled.copy(eInkMode = true)).single().function.parameters.properties.containsKey("eInkAware"))
        val normal = provider.executeOneShotResult(InteractiveAnswer.TOOL_NAME, answer.copy(eInkAware = true).encode(), enabled)
        assertEquals(answer.encode(), normal.structuredContent)
        val paper = provider.executeOneShotResult(InteractiveAnswer.TOOL_NAME, answer.encode(), enabled.copy(eInkMode = true))
        assertEquals(answer.copy(eInkAware = true), InteractiveAnswer.decode(paper.structuredContent!!))
    }
    @Test fun `legacy artifact awareness defaults off without changing its format`() {
        assertFalse(InteractiveAnswer.decode(answer.encode()).eInkAware)
        assertFalse(answer.encode().contains("eInkAware"))
        val aware = InteractiveAnswer.decode(answer.copy(eInkAware = true).encode())
        assertEquals(1, aware.version)
        assertEquals(answer.fallback, aware.fallback)
    }
    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block(); fail("Expected refusal") } catch (_: IllegalArgumentException) { }
    }
}
