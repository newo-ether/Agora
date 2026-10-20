package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.*
import com.newoether.agora.skills.*
import com.newoether.agora.templates.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class SkillWorkflowSubmissionTest {
    @get:Rule val folder = TemporaryFolder()
    private fun template() = InteractiveTemplate(name = "Setup", description = "Collect inputs for this workflow",
        answer = InteractiveAnswer("Setup", "Collect inputs", "Choose", html = "<button>Start</button>"))
    private inner class Fixture(val card: InteractiveTemplate) {
        val skills = SkillManager(mockk<Context> { every { filesDir } returns folder.newFolder() })
        val templates = InteractiveTemplateRepository(folder.newFolder())
        val message = ChatMessage(id = "m", participant = Participant.MODEL, text = "", runId = "local",
            segments = listOf(card.segment()))
        val path = MutableStateFlow(listOf(message.copy(segments = null)))
        val current = MutableStateFlow<String?>("c")
        val hydration = mockk<ConversationMessagePayloadHydration>()
        val vm = mockk<ChatViewModel>(relaxed = true)
        val generation = mockk<MessageGenerationController>(relaxed = true)
        val origin = mockk<ChatClient>(relaxed = true)
        val target = ForegroundSendTarget("c", "c", "fresh", false, null, "model")
        val admission = mockk<ForegroundSendAdmission>()
        init {
            val settings = mockk<SettingsRepository>()
            every { settings.accessSkills } returns MutableStateFlow(true)
            every { settings.sandboxEnabled } returns MutableStateFlow(false)
            every { settings.sandboxSharedStorageEnabled } returns MutableStateFlow(false)
            every { vm.getApplication<android.app.Application>() } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            every { vm.settings } returns settings
            every { vm.skillManager } returns skills
            every { vm.interactiveTemplates } returns templates
            every { vm.sandboxManager } returns null
            every { vm.currentConversationId } returns current
            every { vm.messages } returns path
            every { vm.messagePayloadHydration } returns hydration
            every { vm.isNewChatMode } returns MutableStateFlow(false)
            every { vm.newChatEntryId } returns MutableStateFlow(1L)
            every { vm.currentActiveModel } returns MutableStateFlow("model")
            coEvery { hydration.loadMessages("c", listOf("m"), any()) } returns listOf(message)
            every { generation.captureForegroundSendTarget(any(), any(), any(), any(), any(), any()) } returns target
            coEvery { generation.prepareForegroundSend(any(), any(), any()) } returns admission
            coEvery { generation.sendMessage(any(), any(), any(), any(), any()) } coAnswers {
                path.value += ChatMessage(id = "accepted", participant = Participant.USER, text = secondArg(), runId = "fresh")
                SendAcceptance.Direct("accepted", "c")
            }
        }
        suspend fun send(text: String, sourceFiles: List<String> = emptyList(), open: suspend (InteractiveTemplate, ForegroundSendTarget) -> LocalComposerResult = { _, _ -> error("Unexpected local open") }) =
            vm.submitTemplateFollowup("c", "m", card.id, card.answer, text, sourceFiles, generation, origin,
                { error("Must not capture another workspace") }, open)
    }
    @Test fun staleCardCannotReadAConnectedFolderOrStartGeneration() = runTest {
        val card = bindSkillTemplate(template(), SkillTemplateFlow("examiner.md", "before"))
        val f = Fixture(card)
        f.current.value = "another-conversation"
        try { f.vm.accessTemplateFile("c", "m", card.id, card.answer, TemplateFileRequest("readTextFile", "menu.json")); fail() }
        catch (_: IllegalStateException) { }
        coVerify(exactly = 0) { f.hydration.loadMessages(any(), any(), any()) }
        coVerify(exactly = 0) { f.generation.prepareForegroundSend(any(), any(), any()) }
    }
    @Test fun beforeTemplateOpensLocallyAndStartEntersTheExistingSendPathOnce() = runTest {
        val setup = template()
        val card = bindSkillTemplate(setup, SkillTemplateFlow("examiner.md", "before", "Cardiology"))
        val f = Fixture(card)
        f.templates.save(setup)
        f.skills.saveDocument(SkillDocument("examiner.md", "Act as a meticulous clinical professor. Ask one question", SkillConfiguration("osce", beforeTemplate = setup.id)), "")
        var opened: InteractiveTemplate? = null
        val result = f.vm.localComposerSubmit(f.target, ConversationComposerSnapshot(text = "/osce", loaded = true), null) { t, _ ->
            opened = t; LocalComposerResult(SendAcceptance.Direct("setup", "c"))
        }
        assertNotNull(result?.acceptance)
        assertEquals("before", opened?.skillFlow?.phase)
        coVerify(exactly = 0) { f.generation.prepareForegroundSend(any(), any(), any()) }
        assertTrue(f.send("Questions: 12"))
        coVerify(exactly = 1) { f.generation.sendMessage(f.admission, match { it.startsWith("Act as a meticulous clinical professor.") && it.contains("examiner.md") && it.contains("Questions: 12") }, emptyList(), any(), f.origin) }
        assertFalse(f.send("Questions: 12"))
        verify(exactly = 0) { f.vm.conversationComposer }
    }
    @Test fun afterContinueRunsTheConfiguredNextSkillOrOpensItsSetupWithoutCallingAi() = runTest {
        val setup = template()
        val card = bindSkillTemplate(template(), SkillTemplateFlow("examiner.md", "after", "Assessment gaps", "revision.md", "root-token", listOf("examiner.md")))
        val f = Fixture(card)
        f.templates.save(setup)
        f.skills.saveDocument(SkillDocument("revision.md", "Revise", SkillConfiguration(beforeTemplate = setup.id)), "")
        var next: InteractiveTemplate? = null
        assertTrue(f.send("Focus on examination") { t, _ -> next = t; LocalComposerResult(SendAcceptance.Direct("next-setup", "c")) })
        assertEquals("revision.md", next?.skillFlow?.skill)
        assertEquals("root-token", next?.skillFlow?.parent)
        assertTrue(next?.skillFlow?.input.orEmpty().contains("Assessment gaps"))
        coVerify(exactly = 0) { f.generation.prepareForegroundSend(any(), any(), any()) }
    }
    @Test fun afterContinuePassesCollectedDataAndActualParentToTheConfiguredSkill() = runTest {
        val card = bindSkillTemplate(template(), SkillTemplateFlow("examiner.md", "after", "Assessment gaps", "revision.md", "root-token", listOf("examiner.md")))
        val f = Fixture(card)
        f.skills.saveDocument(SkillDocument("revision.md", "Revise the selected gaps"), "")
        assertTrue(f.send("Focus on examination"))
        coVerify(exactly = 1) { f.generation.sendMessage(f.admission, match {
            it.contains("revision.md") && it.contains("root-token") && it.contains("Assessment gaps") && it.contains("Focus on examination")
        }, emptyList(), any(), f.origin) }
        assertFalse(f.send("Replay"))
    }
    @Test fun pendingDuplicateGestureAndSelectionChangeCannotSendOrRetarget() = runTest {
        val card = bindSkillTemplate(template(), SkillTemplateFlow("examiner.md", "before"))
        val f = Fixture(card)
        f.skills.saveDocument(SkillDocument("examiner.md", "Ask"), "")
        val gate = CompletableDeferred<List<ChatMessage>>()
        coEvery { f.hydration.loadMessages("c", listOf("m"), any()) } coAnswers { gate.await() }
        val pending = async { f.send("Start") }
        testScheduler.runCurrent()
        assertFalse(f.send("Start"))
        f.current.value = "other"
        gate.complete(listOf(f.message))
        assertFalse(pending.await())
        coVerify(exactly = 0) { f.generation.sendMessage(any(), any(), any(), any(), any()) }
    }
    @Test fun multipleBeforeTemplatesCollectInputLocallyThenInvokeOnce() = runTest {
        val first = template()
        val second = template().copy(name = "Second")
        val card = bindSkillTemplate(first, SkillTemplateFlow("examiner.md", "before", "Initial", remainingTemplates = listOf(second.id)))
        val f = Fixture(card)
        f.templates.save(first); f.templates.save(second)
        f.skills.saveDocument(SkillDocument("examiner.md", "Ask", SkillConfiguration(beforeTemplates = listOf(first.id, second.id))), "")
        var opened: InteractiveTemplate? = null
        assertTrue(f.send("First inputs") { t, _ -> opened = t; LocalComposerResult(SendAcceptance.Direct("second", "c")) })
        assertEquals(second.id, opened!!.id)
        assertTrue(opened!!.skillFlow!!.input.contains("First inputs"))
        assertTrue(opened!!.skillFlow!!.remainingTemplates.isEmpty())
        coVerify(exactly = 0) { f.generation.prepareForegroundSend(any(), any(), any()) }
        val last = Fixture(opened!!)
        last.skills.saveDocument(SkillDocument("examiner.md", "Ask"), "")
        assertTrue(last.send("Second inputs"))
        coVerify(exactly = 1) { last.generation.sendMessage(any(), match {
            it.contains("Initial") && it.contains("First inputs") && it.contains("Second inputs") && it.contains("examiner.md")
        }, any(), any(), any()) }
    }
    @Test fun afterSequenceFinishesAllTemplatesBeforeStartingTheNextSkill() = runTest {
        val second = template()
        val flow = SkillTemplateFlow("examiner.md", "after", "Outcome", "revision.md", "root", listOf("examiner.md"), remainingTemplates = listOf(second.id))
        val f = Fixture(bindSkillTemplate(template(), flow))
        f.templates.save(second)
        var opened: InteractiveTemplate? = null
        assertTrue(f.send("First review") { t, _ -> opened = t; LocalComposerResult(SendAcceptance.Direct("second", "c")) })
        coVerify(exactly = 0) { f.generation.sendMessage(any(), any(), any(), any(), any()) }
        val last = Fixture(opened!!)
        last.skills.saveDocument(SkillDocument("revision.md", "Revise"), "")
        assertTrue(last.send("Last review"))
        coVerify(exactly = 1) { last.generation.sendMessage(any(), match {
            it.contains("revision.md") && it.contains("root") && it.contains("First review") && it.contains("Last review")
        }, any(), any(), any()) }
    }
    @Test fun referenceSelectionsFromMultipleTemplatesReachTheFinalRequestOnly() = runTest {
        val second = template().copy(resourceFiles = listOf("two.md"))
        val first = template().copy(resourceFiles = listOf("one.md"))
        val card = bindSkillTemplate(first, SkillTemplateFlow("examiner.md", "before", remainingTemplates = listOf(second.id)))
        val f = Fixture(card)
        f.templates.save(second)
        var opened: InteractiveTemplate? = null
        assertTrue(f.send("First", listOf("one.md")) { t, _ -> opened = t; LocalComposerResult(SendAcceptance.Direct("next", "c")) })
        assertEquals(listOf(SkillTemplateSource("one.md")), opened!!.skillFlow!!.sources)
        coVerify(exactly = 0) { f.generation.sendMessage(any(), any(), any(), any(), any()) }
        val last = Fixture(opened!!)
        last.skills.saveDocument(SkillDocument("examiner.md", "Ask"), "")
        last.skills.createFile("one.md", "First reference")
        last.skills.createFile("two.md", "Second reference")
        assertTrue(last.send("Second", listOf("two.md")))
        coVerify(exactly = 1) { last.generation.sendMessage(any(), match {
            !it.contains("First reference") && !it.contains("Second reference")
        }, match { attachments ->
            attachments.map { it.fileName } == listOf("one.md", "two.md") &&
                attachments.map { it.preparedText } == listOf("First reference", "Second reference") &&
                attachments.all { java.io.File(it.localPath!!).readText() == it.preparedText }
        }, any(), any()) }
        coVerify { last.generation.prepareForegroundSend(any(), match { it.attachments.size == 2 }, any()) }
    }
    @Test fun missingIntermediateTemplateCannotFallThroughToAnAiRequest() = runTest {
        val card = bindSkillTemplate(template(), SkillTemplateFlow("a.md", "before", remainingTemplates = listOf(java.util.UUID.randomUUID().toString())))
        val f = Fixture(card)
        assertFalse(f.send("Continue"))
        coVerify(exactly = 0) { f.generation.sendMessage(any(), any(), any(), any(), any()) }
    }
    @Test fun libraryPreviewCannotStartAnAiRequest() = runTest {
        val f = Fixture(template())
        assertFalse(f.send("Start"))
        coVerify(exactly = 0) { f.generation.sendMessage(any(), any(), any(), any(), any()) }
    }
    @Test fun legacyTemplateShortcutCannotShadowTheSkillOrDispatchAForm() = runTest {
        val setup = template().copy(shortcut = "osce")
        val f = Fixture(setup)
        f.templates.save(setup)
        f.skills.saveDocument(SkillDocument("examiner.md", "Examine", SkillConfiguration("osce", beforeTemplate = setup.id)), "")
        assertEquals("examiner.md", f.vm.composerSkill("/osce")!!.first.name)
        var opened: InteractiveTemplate? = null
        assertNotNull(f.vm.localComposerSubmit(f.target, ConversationComposerSnapshot(text = "/osce", loaded = true), null) { card, _ ->
            opened = card; LocalComposerResult(SendAcceptance.Direct("setup", "c"))
        }?.acceptance)
        assertEquals("examiner.md", opened!!.skillFlow!!.skill)
    }
    @Test fun missingNextSkillNeverFallsBackToAnOrdinaryPrompt() = runTest {
        val card = bindSkillTemplate(template(), SkillTemplateFlow("examiner.md", "after", "Finished", "missing.md", "root", listOf("examiner.md")))
        val f = Fixture(card)
        assertFalse(f.send("Continue"))
        coVerify(exactly = 0) { f.generation.prepareForegroundSend(any(), any(), any()) }
        coVerify(exactly = 0) { f.generation.sendMessage(any(), any(), any(), any(), any()) }
    }
}
