package com.newoether.agora.skills

import com.newoether.agora.model.*
import com.newoether.agora.tool.SkillDraftToolProvider
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SkillDraftTest {
    private val provider = SkillDraftToolProvider()
    private val ctx = GenerationContext(skillModifyAccess = true, interactiveAnswersEnabled = true)
    private fun args(instructions: String = "Use <script> literally; read /home/agora/notes with terminal tools.") = buildJsonObject {
        put("name", "discussion-workflow.md"); put("description", "Turn this discussion into a repeatable workflow")
        put("shortcut", "workflow"); put("instructions", instructions)
    }.toString()
    @Test fun draftProducesAnEscapedReviewArtifactAndNoStorageMutation() = runBlocking {
        val result = provider.executeOneShotResult("propose_skill", args(), ctx)
        val answer = InteractiveAnswer.decode(checkNotNull(result.structuredContent))
        assertEquals("discussion-workflow.md", answer.skillDraft?.name)
        assertTrue(answer.html.contains("&lt;script&gt;"))
        assertFalse(answer.html.contains("<script>"))
        assertTrue(answer.fallback.contains("/home/agora/notes"))
        assertEquals("workflow", answer.skillDraft?.configuration?.shortcut)
    }
    @Test fun onlyCompletedDraftToolArtifactsReceiveTheReviewAction() = runBlocking {
        val result = provider.executeOneShotResult("propose_skill", args(), ctx)
        val segment = MessageSegment(type = "tool", toolName = "propose_skill", toolCallId = "draft",
            toolState = ToolExecutionStates.SUCCEEDED, toolStructuredResult = result.structuredContent)
        assertNotNull(segment.interactiveAnswer())
        assertNull(segment.copy(toolState = ToolExecutionStates.RUNNING).interactiveAnswer())
        assertNull(segment.copy(toolName = "foreign").interactiveAnswer())
        assertNull(segment.copy(toolName = InteractiveAnswer.TOOL_NAME).interactiveAnswer())
        assertNull(segment.copy(toolStructuredResult = "{}").interactiveAnswer())
    }
    @Test fun disabledAccessAndOversizedDraftsFailBeforeProducingAnArtifact() = runBlocking {
        assertTrue(provider.definitions(ctx.copy(skillModifyAccess = false)).isEmpty())
        assertFalse(provider.definitions(ctx.copy(interactiveAnswersEnabled = false)).isEmpty())
        try { provider.executeOneShotResult("propose_skill", args(), ctx.copy(skillModifyAccess = false)); fail() }
        catch (_: IllegalArgumentException) { }
        try { provider.executeOneShotResult("propose_skill", args("x".repeat(80000)), ctx); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun authoringInstructionsAreLoadedOnlyByTheirFilename() {
        assertEquals("create-skill.md", SkillAuthoring.document.name)
        assertTrue(SkillAuthoring.document.instructions.contains("propose_skill"))
        assertFalse(SkillAuthoring.document.instructions.contains("UUID"))
    }
}
