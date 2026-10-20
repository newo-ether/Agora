package com.newoether.agora.skills

import android.content.Context
import com.newoether.agora.data.SkillManager
import com.newoether.agora.model.*
import com.newoether.agora.templates.*
import com.newoether.agora.tool.*
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class SkillWorkflowTest {
    @get:Rule val folder = TemporaryFolder()
    private fun manager(): SkillManager = SkillManager(mockk<Context> { every { filesDir } returns folder.root })
    private fun template(name: String) = InteractiveTemplate(name = name, description = "Collect inputs for this workflow",
        answer = InteractiveAnswer(name, "Collect inputs for this workflow", "Choose and Continue", html = "<button>Continue</button>"))
    @Test fun workflowConfigurationRoundTripsAndCyclicSettingsCannotBeSaved() {
        val manager = manager()
        val after = template("Review")
        val b = SkillDocument("b.md", "B")
        manager.saveDocument(b, "")
        val a = SkillDocument("a.md", "A", SkillConfiguration("example", after.id, after.id, "b.md"))
        manager.saveDocument(a, "")
        assertEquals(a, manager.document("a.md"))
        assertThrows(IllegalArgumentException::class.java) {
            manager.saveDocument(b.copy(configuration = SkillConfiguration(afterTemplate = after.id, nextSkill = "a.md")), "", "b.md", manager.readFile("b.md"))
        }
        assertEquals(b, manager.document("b.md"))
        assertThrows(IllegalArgumentException::class.java) { SkillConfiguration(nextSkill = "b.md").validate() }
    }
    @Test fun completionUsesItsInvocationSnapshotAndPresentsAReusableInterfaceWithoutRoutingInHtml() = runTest {
        val manager = manager()
        val repo = InteractiveTemplateRepository(folder.newFolder())
        val review = template("Review"); repo.save(review)
        val doc = SkillDocument("a.md", "Finish the exercise", SkillConfiguration(afterTemplate = review.id, nextSkill = "next.md"))
        manager.saveDocument(SkillDocument("next.md", "Next"), ""); manager.saveDocument(doc, "")
        val invocation = SkillInvocationToolProvider(manager).invoke("""{"skill":"a.md","input":"task"}""", GenerationContext(), "actual-call", emptyList(), "run")
        val calls = listOf(ToolCallData("invoke_skill", "{}", invocation.text, toolCallId = "actual-call", structuredResult = invocation.structuredContent))
        manager.saveDocument(doc.copy(configuration = SkillConfiguration()), "", "a.md", manager.readFile("a.md"))
        val result = SkillCompletionToolProvider(repo, null).complete("""{"token":"actual-call","summary":"Focus on assessment"}""", GenerationContext(), calls)
        val opened = InteractiveTemplate.decode(result.structuredContent!!)
        assertEquals(review.id, opened.id)
        assertEquals("next.md", opened.skillFlow?.nextSkill)
        assertEquals("actual-call", opened.skillFlow?.parent)
        assertEquals(listOf("a.md"), opened.skillFlow?.ancestors)
        assertFalse(opened.answer.encode().contains("next.md"))
        assertTrue(opened.answer.jsExpressions.first().contains("Focus on assessment"))
        assertEquals(review, repo.get(review.id))
        assertThrows(IllegalArgumentException::class.java) { repo.save(opened, replace = true) }
        val history = calls + ToolCallData("complete_skill", "{}", result.text, toolCallId = "completed", structuredResult = result.structuredContent)
        try { SkillCompletionToolProvider(repo, null).complete("""{"token":"actual-call","summary":"Again"}""", GenerationContext(), history); fail() }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("already")) }
        val segment = MessageSegment("tool", toolName = "complete_skill", toolState = ToolExecutionStates.SUCCEEDED, toolStructuredResult = result.structuredContent)
        val entity = com.newoether.agora.data.local.MessageEntity(id = "completion", conversationId = "c",
            text = "", status = MessageStatus.SUCCESS, participant = Participant.MODEL, timestamp = 1L, runId = "run",
            toolCallJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(MessageSegment.serializer()), listOf(segment)))
        val projected = com.newoether.agora.viewmodel.projectProviderMessages(listOf(entity), false).single().toolCall!!
        assertEquals("actual-call", SkillCompletionReceipt.token(projected.structuredResult))
        assertFalse(projected.structuredResult!!.contains("<button>"))
        assertFalse(projected.structuredResult!!.contains(review.id))
        try { SkillCompletionToolProvider(repo, null).complete("""{"token":"actual-call","summary":"Again"}""", GenerationContext(), calls + projected); fail() }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("already")) }
        assertNotNull(segment.interactiveAnswer())
        assertNull(segment.copy(toolState = ToolExecutionStates.STOPPED).interactiveAnswer())
    }
    @Test fun missingTokensDisabledAccessAndAncestryCyclesCannotAdvance() = runTest {
        val provider = SkillCompletionToolProvider(InteractiveTemplateRepository(folder.root), null)
        assertTrue(provider.definitions(GenerationContext(skillReadAccess = false)).isEmpty())
        try { provider.complete("""{"token":"foreign","summary":"Finished"}""", GenerationContext(), emptyList()); fail() }
        catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("Unknown")) }
        val flow = SkillTemplateFlow("a.md", "after", nextSkill = "a.md", parent = "token", ancestors = listOf("a.md"))
        assertThrows(IllegalArgumentException::class.java) { flow.checkTarget("a.md") }
        assertThrows(IllegalArgumentException::class.java) { flow.copy(phase = "during").validate() }
    }
    @Test fun orderedTemplatesRoundTripAndLegacySingleStepsRemainReadable() {
        val first = template("First").id
        val second = template("Second").id
        val config = SkillConfiguration(beforeTemplates = listOf(first, second), afterTemplates = listOf(second, first), nextSkill = "b.md")
        val doc = SkillDocument("a.md", "Instructions", config)
        assertEquals(config, SkillDocument.decode(doc.name, doc.encode()).configuration)
        assertEquals(listOf(first), SkillConfiguration(beforeTemplate = first).beforeSteps)
        assertNull(config.withTemplates(listOf(first), emptyList()).nextSkill)
        assertThrows(IllegalArgumentException::class.java) { config.copy(beforeTemplates = List(17) { first }).validate() }
        assertThrows(IllegalArgumentException::class.java) { config.copy(afterTemplates = listOf("bad-path")).validate() }
    }
    @Test fun completionSnapshotsTheOrderedAfterSequence() = runTest {
        val manager = manager()
        val repo = InteractiveTemplateRepository(folder.newFolder())
        val first = template("First"); val second = template("Second")
        repo.save(first); repo.save(second)
        manager.saveDocument(SkillDocument("a.md", "Finish", SkillConfiguration(afterTemplates = listOf(first.id, second.id))), "")
        val invocation = SkillInvocationToolProvider(manager).invoke("""{"skill":"a.md"}""", GenerationContext(), "call", emptyList(), "run")
        val calls = listOf(ToolCallData("invoke_skill", "{}", invocation.text, toolCallId = "call", structuredResult = invocation.structuredContent))
        val result = SkillCompletionToolProvider(repo, null).complete("""{"token":"call","summary":"Finished"}""", GenerationContext(), calls)
        val card = InteractiveTemplate.decode(result.structuredContent!!)
        assertEquals(first.id, card.id)
        assertEquals(listOf(second.id), card.skillFlow!!.remainingTemplates)
    }
    @Test fun onlyNewTemplateWritesEnforceTheShortDescriptionCap() {
        val repo = InteractiveTemplateRepository(folder.root)
        val legacy = template("Old").copy(description = (1..11).joinToString(" "))
        legacy.validate()
        assertThrows(IllegalArgumentException::class.java) { repo.save(legacy) }
        repo.restoreCatalogue(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(InteractiveTemplate.serializer()), listOf(legacy)), true)
        assertEquals(legacy, repo.list().single())
    }
}
