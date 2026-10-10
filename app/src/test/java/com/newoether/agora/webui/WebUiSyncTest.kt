package com.newoether.agora.webui
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.CustomEndpointProtocol

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.viewmodel.ComposerSubmissionPhase
import com.newoether.agora.viewmodel.ConversationComposerSubmissionSnapshot
import com.newoether.agora.viewmodel.ConversationGenerationSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import com.newoether.agora.viewmodel.ConversationGenerationState
import com.newoether.agora.viewmodel.ConversationMessagePayloadHydration
import com.newoether.agora.viewmodel.ConversationStateRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import com.newoether.agora.util.DebugLog
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.cancelAndJoin
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.AttachmentImportState
import org.junit.Assert.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class WebUiSyncTest {
    private val list = MutableStateFlow(
        listOf(
            ChatConversation(id = "a", title = "Alpha", selectedBranchesJson = """{"null":"root","root":"m1"}"""),
            ChatConversation(id = "b", title = "Beta", hasUnreadGeneration = true),
        ),
    )
    private val topology = MutableStateFlow(
        listOf(
            topology("root", parent = null, Participant.USER, timestamp = 1),
            topology("m1", parent = "root", Participant.MODEL, timestamp = 2),
            // Newer sibling: without the saved selection the path would take it.
            topology("m2", parent = "root", Participant.MODEL, timestamp = 3),
        ),
    )
    private val snapshot = MutableStateFlow(ConversationGenerationSnapshot())
    private val bodies = mapOf(
        "root" to message("root", null, Participant.USER, "question"),
        "m1" to message("m1", "root", Participant.MODEL, "x \\(a^2\\) y"),
        "m2" to message("m2", "root", Participant.MODEL, "other"),
    )
    private val conversations = mockk<ConversationRepository> {
        every { getAllConversations() } returns list
        every { observeMessageTopology("a") } returns topology
        coEvery { recoverConversationRuntime(any(), any()) } returns 0
    }
    private val state = mockk<ConversationGenerationState> {
        every { generationSnapshot } returns snapshot
    }
    private val registry = mockk<ConversationStateRegistry> {
        every { activeConversationIds } returns MutableStateFlow(setOf("b"))
        every { getOrCreate("a") } returns state
    }
    private val hydration = mockk<ConversationMessagePayloadHydration> {
        every { observeMessage(any(), any()) } answers { flowOf(bodies[firstArg<String>()]) }
    }
    private val display = MutableStateFlow(
        WebDisplayContext(
            resources = mockk(relaxed = true),
            toolCallDisplayMode = ToolCallDisplayModes.DEFAULT,
            thinkingSegmentDisplayMode = ThinkingSegmentDisplayModes.DEFAULT,
            autoExpandActiveGroup = true,
            parseInlineDollarMath = false,
            autoWrapCodeBlocks = false,
        ),
    )

    private val openTarget = MutableStateFlow(WebUiChatSession.OpenTarget(null, browserSeq = 0L, movedByServer = false))
    private val snackbars = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val scrollRequests = MutableSharedFlow<WebSyncEvent.ScrollToBottom>(extraBufferCapacity = 4)
    private val composerState = MutableSharedFlow<WebUiChatSession.ComposerState>(extraBufferCapacity = 4)
    private val customProviders = MutableStateFlow<List<CustomProviderConfig>>(emptyList())
    private val session = mockk<WebUiChatSession> {
        every { openTarget } returns this@WebUiSyncTest.openTarget
        every { snackbars } returns this@WebUiSyncTest.snackbars
        every { scrollRequests } returns this@WebUiSyncTest.scrollRequests
        every { composerState } returns this@WebUiSyncTest.composerState
        coEvery { start() } just Runs
        coEvery { close() } just Runs
        coEvery { endUploads() } just Runs
        coEvery { send(any(), any(), any()) } just Runs
        coEvery { edit(any(), any(), any()) } just Runs
        coEvery { cancelWaiting(any(), any()) } just Runs
        coEvery { selectModel(any(), any(), any()) } just Runs
        coEvery { removeQueued(any(), any()) } just Runs
        coEvery { sendQueued(any(), any()) } just Runs
        coEvery { attachmentCommand(any()) } just Runs
        coEvery { settingCommand(any()) } just Runs
        coEvery { editorCommand(any()) } just Runs
        every { stop(any()) } just Runs
        coEvery { open(any(), any()) } answers {
            this@WebUiSyncTest.openTarget.value =
                WebUiChatSession.OpenTarget(firstArg(), secondArg(), movedByServer = false)
        }
    }

    @Test
    fun sendAndStopCommandsGoToTheConnectionSession() = sync { send, _ ->
        send("""{"type":"send","text":"hello"}""")
        send("""{"type":"stop"}""")
        coVerify(exactly = 1) { session.start() }
        coVerify(exactly = 1) { session.send("hello", 0L, 0L) }
        verify(exactly = 1) { session.stop(0L) }
    }
    @Test
    fun draftAndCancelCommandsKeepTheirExactTargetAndActionIdentity() = sync { send, _ ->
        send("""{"type":"draft","text":"later edit","revision":3,"seq":7}""")
        send("""{"type":"cancel_waiting","seq":7,"actionId":9}""")
        coVerify(exactly = 1) { session.edit("later edit", 3L, 7L) }
        coVerify(exactly = 1) { session.cancelWaiting(7L, 9L) }
    }
    @Test
    fun modelAndQueueCommandsKeepTheirExactTargetIdentity() = sync { send, _ ->
        send("""{"type":"model","modelId":"provider:model","seq":4,"actionId":7}""")
        send("""{"type":"remove_queued","queuedId":"queue-id","seq":4}""")
        send("""{"type":"send_queued","seq":4,"actionId":8}""")
        coVerify(exactly = 1) { session.selectModel("provider:model", 4L, 7L) }
        coVerify(exactly = 1) { session.removeQueued("queue-id", 4L) }
        coVerify(exactly = 1) { session.sendQueued(4L, 8L) }
    }

    @Test
    fun editorCommandsAndProjectionUseTheExistingSessionAndStructuredSettings() = sync { send, received ->
        send("""{"type":"advanced","seq":7,"actionId":9,"parameters":{"temperature":1.2}}""")
        send("""{"type":"compact","seq":7,"actionId":10,"modelId":"m","text":"summary","retainCount":0}""")
        coVerify(exactly = 1) { session.editorCommand(WebSyncCommand("advanced", seq = 7, actionId = 9, parameters = com.newoether.agora.data.ConversationSettings(temperature = 1.2f))) }
        coVerify(exactly = 1) { session.editorCommand(WebSyncCommand("compact", seq = 7, actionId = 10, modelId = "m", text = "summary", retainCount = 0)) }
        received()
        composerState.emit(WebUiChatSession.ComposerState(null, ConversationComposerSubmissionSnapshot(),
            generationParameters = com.newoether.agora.data.ConversationSettings(temperature = 1.2f),
            compactDefaults = com.newoether.agora.viewmodel.CompactRequest("m", "summary", 4)))
        val projected = received().single { it.type == "composer" }
        assertEquals("1.2", projected["advanced"]!!.jsonObject["overrides"]!!.jsonObject.string("temperature"))
        assertEquals("4096", projected["advanced"]!!.jsonObject["contextPresets"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals("4K", projected["advanced"]!!.jsonObject["contextLabels"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals("m", projected["compact"]!!.jsonObject.string("modelId"))
    }
    @Test
    fun settingsCommandAndEffectiveControlsKeepCanonicalIdentityAndAvailability() = sync { send, received ->
        send("""{"type":"setting","seq":7,"actionId":9,"setting":"webSearchEnabled","enabled":false}""")
        coVerify(exactly = 1) { session.settingCommand(WebSyncCommand("setting", seq = 7, actionId = 9, setting = "webSearchEnabled", enabled = false)) }
        received()
        val global = com.newoether.agora.data.ConversationSettings(contextWindow = 32768, codeExecutionEnabled = false,
            googleSearchEnabled = false, thinkingEnabled = true, thinkingLevel = "medium", thinkingBudgetEnabled = false,
            thinkingBudgetTokens = 4096, openAiServiceTierEnabled = true, openAiServiceTier = "priority",
            webSearchEnabled = true, shellEnabled = false, lowContextModeEnabled = true)
        val controls = com.newoether.agora.ui.chat.resolveEffectiveConversationControls(null, null, global, com.newoether.agora.util.Constants.PROVIDER_OPENAI, true, emptyList())
        composerState.emit(WebUiChatSession.ComposerState(null, ConversationComposerSubmissionSnapshot(), controls = controls))
        val projected = received().single { it.type == "composer" }["controls"]!!.jsonObject
        assertEquals("true", projected.string("openAiWebSearchAvailable"))
        assertEquals("fast", projected.string("openAiServiceTier"))
        assertEquals("false", projected.string("showLowContextMode"))
        assertEquals("false", projected.string("shellAvailable"))
        assertEquals("32768", projected.string("contextWindow"))
        send("""{"type":"setting","seq":7,"actionId":10,"setting":"thinkingLevel","value":"high"}""")
        coVerify { session.settingCommand(WebSyncCommand("setting", seq = 7, actionId = 10, setting = "thinkingLevel", value = "high")) }
        received()
        composerState.emit(WebUiChatSession.ComposerState(null, ConversationComposerSubmissionSnapshot(),
            modelValid = true, modelId = "OpenAI:gpt-6-astra", controls = controls.copy(thinkingEnabled = false, thinkingLevel = "minimal", openAiServiceTierState = controls.openAiServiceTierState.copy(tier = "ultrafast"))))
        val resolved = received().single { it.type == "composer" }["controls"]!!.jsonObject
        assertEquals("false", resolved.string("thinkingEnabled"))
        assertEquals("true", resolved.string("displayedThinkingEnabled"))
        assertEquals("low", resolved.string("displayedThinkingLevel"))
        assertEquals("false", resolved.string("thinkingCanDisable"))
        assertEquals("false", resolved.string("thinkingSupportsBudget"))
        assertEquals("fast", resolved.string("displayedServiceTier"))
        assertEquals(listOf("auto", "default", "flex", "fast"), resolved["serviceTiers"]!!.jsonArray.map { it.jsonPrimitive.content })
        val customId = "custom-provider-12345678-1234-4234-8234-123456789abc"
        customProviders.value = listOf(CustomProviderConfig(name = "Relay", id = customId, responsesApiEnabled = true))
        received()
        composerState.emit(WebUiChatSession.ComposerState(null, ConversationComposerSubmissionSnapshot(),
            modelValid = true, modelId = "$customId:unlisted", controls = controls))
        assertEquals("true", received().single { it.type == "composer" }["controls"]!!.jsonObject.string("thinkingSupportsBudget"))
        composerState.emit(WebUiChatSession.ComposerState(null, ConversationComposerSubmissionSnapshot(),
            modelValid = true, modelId = "$customId:claude-opus-4-6", controls = controls))
        assertEquals(listOf("minimal", "low", "medium", "high", "xhigh", "max"), received().single { it.type == "composer" }["controls"]!!.jsonObject["thinkingEfforts"]!!.jsonArray.map { it.jsonPrimitive.content })
        customProviders.value = listOf(customProviders.value.single().copy(protocol = CustomEndpointProtocol.ANTHROPIC))
        val updated = received().single { it.type == "composer" }["controls"]!!.jsonObject
        assertEquals("true", updated.string("thinkingSupportsBudget"))
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), updated["thinkingEfforts"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
    @Test
    fun attachmentCommandsKeepTheExactIdSelectionAndConfiguration() = sync { send, _ ->
        for (type in listOf("attachment_remove", "attachment_retry", "attachment_pdf", "attachment_video")) {
            send("""{"type":"$type","seq":7,"attachmentId":"pick","actionId":9,"pages":[0,2],"frameCount":3,"intervalMs":5000}""")
            coVerify(exactly = 1) { session.attachmentCommand(WebSyncCommand(type, seq = 7, actionId = 9, attachmentId = "pick", pages = listOf(0, 2), frameCount = 3, intervalMs = 5000)) }
        }
    }

    @Test
    fun attachmentProjectionIncludesStatusAndProgressButNoPrivatePaths() = sync { _, received ->
        received()
        composerState.emit(WebUiChatSession.ComposerState(null, ConversationComposerSubmissionSnapshot(),
            attachments = listOf(SelectedAttachment(localId = "pdf", uri = "private-uri", type = "pdf", localPath = "private-source",
                importState = AttachmentImportState.PROCESSING, pageCount = 3, preRenderedPaths = listOf("private-page")),
                SelectedAttachment(uri = "private-video", type = "video", videoDurationMs = 15_000)),
            pdfProgress = mapOf("pdf" to (1 to 3))))
        val event = received().single { it.type == "composer" }
        val pdf = event["attachments"]!!.jsonArray.first().jsonObject
        assertEquals("pdf", pdf.string("id"))
        assertEquals("PROCESSING", pdf.string("state"))
        assertEquals("1", pdf.string("previewDone"))
        assertEquals("3", pdf.string("previewTotal"))
        assertEquals("1", pdf.string("pagePreviewCount"))
        assertEquals("5", event["attachments"]!!.jsonArray.last().jsonObject.string("defaultFrameCount"))
        assertFalse(event.toString().contains("private-"))
    }

    @Test
    fun fileProjectionUsesOnlyCanonicalPreparedTextAndNeverSandboxOrUnavailableContent() = sync { _, received ->
        received()
        val file = SelectedAttachment(uri = "private-uri", type = "file", preparedText = "prepared text")
        composerState.emit(WebUiChatSession.ComposerState(null, ConversationComposerSubmissionSnapshot(), attachments = listOf(
            file, file.copy(unavailable = true, preparedText = "secret-unavailable"),
            file.copy(storage = com.newoether.agora.model.AttachmentStorage.LOCAL_SANDBOX_RUNTIME, preparedText = "secret-sandbox"))))
        val event = received().single { it.type == "composer" }
        assertEquals("prepared text", event["attachments"]!!.jsonArray.first().jsonObject.string("text"))
        assertFalse(event.toString().contains("secret-"))
        assertFalse(event.toString().contains("private-"))
    }

    @Test
    fun aRuntimeMoveIsAnnouncedBeforeTheMovedConversationsEvents() = sync { send, received ->
        send("""{"type":"open","conversationId":null,"seq":4}""")
        received()
        openTarget.value = WebUiChatSession.OpenTarget("a", browserSeq = 4L, movedByServer = true)
        val events = received()
        val opened = events.indexOfFirst { it.type == "opened" }
        assertEquals("a", events[opened].string("conversationId"))
        assertEquals("4", events[opened].string("seq"))
        assertTrue(opened < events.indexOfFirst { it.type == "path" })
    }

    @Test
    fun browserOpensAreNotEchoedAsMoves() = sync { send, received ->
        send("""{"type":"open","conversationId":"a","seq":1}""")
        val events = received()
        assertTrue(events.none { it.type == "opened" })
        assertEquals(listOf("root", "m1"), events.single { it.type == "path" }.ids())
    }

    @Test
    fun snackbarsAndComposerPhasesAreForwarded() = sync { _, received ->
        received()
        snackbars.emit("No model selected")
        composerState.emit(
            WebUiChatSession.ComposerState(
                conversationId = null,
                snapshot = ConversationComposerSubmissionSnapshot(
                    phase = ComposerSubmissionPhase.WAITING,
                    acceptedVersion = 2L,
                ),
                text = "canonical draft",
                editRevision = 3L,
                actionId = 9L,
            ),
        )
        val events = received()
        assertEquals("No model selected", events.single { it.type == "snackbar" }.string("message"))
        val composer = events.single { it.type == "composer" }
        assertEquals("WAITING", composer.string("phase"))
        assertEquals("2", composer.string("acceptedVersion"))
        assertEquals("canonical draft", composer.string("text"))
        assertEquals("3", composer.string("editRevision"))
        assertEquals("9", composer.string("actionId"))
    }

    @Test
    fun closingTheConnectionClosesItsSession() = runTest {
        val incoming = Channel<String>(Channel.UNLIMITED)
        incoming.close()
        webUiSync(StandardTestDispatcher(testScheduler)).serve("login", incoming) { }
        coVerify(exactly = 1) { session.close() }
    }
    @Test
    fun uploadsRequireTheExactLoginAndLiveConnection() = runTest {
        val sync = webUiSync(StandardTestDispatcher(testScheduler))
        val incoming = Channel<String>()
        val events = mutableListOf<JsonObject>()
        val job = backgroundScope.launch { sync.serve("login", incoming) { events += Json.parseToJsonElement(it).jsonObject } }
        runCurrent()
        val id = events.single { it.type == "connection" }.string("connectionId")
        coEvery { session.upload(any(), any(), any(), any(), any(), any()) } returns HttpStatusCode.Accepted
        val bytes = ByteReadChannel(byteArrayOf(1))
        assertEquals(HttpStatusCode.NotFound, sync.upload("other-login", id, 2, "f.txt", "text/plain", null, 1, bytes))
        assertEquals(HttpStatusCode.NotFound, sync.upload("login", "other-connection", 2, "f.txt", "text/plain", null, 1, bytes))
        assertEquals(HttpStatusCode.Accepted, sync.upload("login", id, 2, "f.txt", "text/plain", null, 1, bytes))
        coVerify(exactly = 1) { session.upload(2, "f.txt", "text/plain", null, 1, bytes) }
        coEvery { session.previewAttachment(any(), any(), any(), any(), any()) } returns true
        val consume: suspend (java.io.File, String) -> Unit = { _, _ -> }
        assertFalse(sync.previewAttachment("other-login", id, 2, "pick", "source", 0, consume))
        assertFalse(sync.previewAttachment("login", "other-connection", 2, "pick", "source", 0, consume))
        assertTrue(sync.previewAttachment("login", id, 2, "pick", "source", 0, consume))
        coVerify(exactly = 1) { session.previewAttachment(2, "pick", "source", 0, consume) }
        job.cancelAndJoin()
        assertEquals(HttpStatusCode.NotFound, sync.upload("login", id, 2, "f.txt", "text/plain", null, 1, bytes))
        assertFalse(sync.previewAttachment("login", id, 2, "pick", "source", 0, consume))
    }
    @Test
    fun acceptedScrollRequestsKeepTheMessageAndBrowserTarget() = sync { _, received ->
        received()
        scrollRequests.emit(WebSyncEvent.ScrollToBottom("a", "accepted-message", 7L))
        val event = received().single { it.type == "scroll_to_bottom" }
        assertEquals("a", event.string("conversationId"))
        assertEquals("accepted-message", event.string("messageId"))
        assertEquals("7", event.string("seq"))
    }

    @Test
    fun listThenOpenSendsTheSavedBranchAfterRecovery() = sync { send, received ->
        val first = received()
        assertEquals("false", first.single { it.type == "display" }.string("autoWrapCodeBlocks"))
        assertEquals("true", first.single { it.type == "display" }.string("blurEffectsEnabled"))
        assertEquals("false", first.single { it.type == "display" }.string("reduceMotion"))
        val listed = first.single { it.type == "conversations" }
        val items = listed["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b"), items.map { it.string("id") })
        assertEquals(listOf("false", "true"), items.map { it.string("generating") })
        assertEquals("true", items[1].string("unread"))

        send("""{"type":"open","conversationId":"a"}""")
        val path = received().single { it.type == "path" }
        assertEquals(listOf("root", "m1"), path.ids())
        coVerify(exactly = 1) { conversations.recoverConversationRuntime("a", any()) }
    }

    @Test
    fun onlyWatchedRowsOnThePathGetBodiesWithMathSplitOut() = sync { send, received ->
        send("""{"type":"open","conversationId":"a"}""")
        received()
        send("""{"type":"watch","messageIds":["m1","m2"]}""")
        val payloads = received().filter { it.type == "payload" }.map { it["message"]!!.jsonObject }
        assertEquals(listOf("m1"), payloads.map { it.string("id") })
        val text = payloads.single()["presentation"]!!.jsonObject["answer"]!!.jsonObject
        // parseLatexSpans moves the spaces around inline math into the formula's source.
        assertEquals("x${MATH_OPEN}0${MATH_CLOSE}y", text.string("markdown"))
        val math = text["math"]!!.jsonArray.single().jsonObject
        assertEquals("a^2", math.string("tex"))
        assertEquals("false", math.string("display"))
        verify(exactly = 0) { hydration.observeMessage("m2", any()) }
    }

    @Test
    fun streamingMessageJoinsThePathAndIsSentWithItsBody() = sync { send, received ->
        send("""{"type":"open","conversationId":"a"}""")
        received()
        snapshot.value = ConversationGenerationSnapshot(
            conversationId = "a",
            streamingMessage = message("m3", "m1", Participant.MODEL, "partial", MessageStatus.SENDING),
            isLoading = true,
            isGenerating = true,
        )
        val events = received()
        val path = events.single { it.type == "path" }
        assertEquals(listOf("root", "m1", "m3"), path.ids())
        assertEquals("true", path.string("generating"))
        val streaming = events.single { it.type == "streaming" }["message"]!!.jsonObject
        assertEquals("partial", streaming["text"]!!.jsonObject.string("markdown"))
    }

    @Test
    fun openingAConversationThatIsGoneReportsItDeletedWithoutRecovery() = sync { send, received ->
        send("""{"type":"open","conversationId":"gone"}""")
        assertEquals("gone", received().single { it.type == "deleted" }.string("conversationId"))
        coVerify(exactly = 0) { conversations.recoverConversationRuntime(any(), any()) }
    }

    @Test
    fun rowRemovedFromTheListWhileOpenIsReportedDeleted() = sync { send, received ->
        send("""{"type":"open","conversationId":"a"}""")
        received()
        list.value = list.value.filterNot { it.id == "a" }
        assertTrue(received().any { it.type == "deleted" && it.string("conversationId") == "a" })
    }

    @Test
    fun aFailedLoadEndsThatConversationButNotTheConnection() {
        every { conversations.observeMessageTopology("a") } returns flow { error("broken graph") }
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } just Runs
        try {
            sync { send, received ->
                send("""{"type":"open","conversationId":"a"}""")
                assertTrue(received().any { it.type == "load_failed" })
                list.value = list.value.map { it.copy(title = it.title + "!") }
                val titles = received().single { it.type == "conversations" }["items"]!!.jsonArray
                    .map { it.jsonObject.string("title") }
                assertEquals(listOf("Alpha!", "Beta!"), titles)
            }
        } finally {
            unmockkObject(DebugLog)
        }
    }

    @Test
    fun appearanceChangesKeepTheOpenConversationAndExistingPayloadSubscription() = sync { send, received ->
        received()
        send("""{"type":"open","conversationId":"a"}""")
        received()
        send("""{"type":"watch","messageIds":["m1"]}""")
        received()
        for ((blur, reduceMotion) in listOf(false to true, false to false, true to true, true to false)) {
            display.value = display.value.copy(blurEffectsEnabled = blur, reduceMotion = reduceMotion)
            val events = received()
            val appearance = events.single { it.type == "display" }
            assertEquals(blur.toString(), appearance.string("blurEffectsEnabled"))
            assertEquals(reduceMotion.toString(), appearance.string("reduceMotion"))
            assertEquals("m1", events.single { it.type == "payload" }["message"]!!.jsonObject.string("id"))
        }
        coVerify(exactly = 1) { conversations.recoverConversationRuntime("a", any()) }
        verify(exactly = 1) { hydration.observeMessage("m1", any()) }
        verify(exactly = 0) { hydration.observeMessage("m2", any()) }
    }

    private fun sync(
        block: suspend TestScope.(send: suspend (String) -> Unit, received: () -> List<JsonObject>) -> Unit,
    ) = runTest {
        val sync = webUiSync(StandardTestDispatcher(testScheduler))
        val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch { sync.serve("login", incoming) { sent.send(it) } }
        runCurrent()
        block(
            { text -> incoming.send(text); runCurrent() },
            {
                runCurrent()
                generateSequence { sent.tryReceive().getOrNull() }
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .toList()
            },
        )
    }

    private fun webUiSync(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = WebUiSync(
        conversations = conversations,
        registry = registry,
        executionCoordinator = ConversationExecutionCoordinator(),
        hydration = hydration,
        customProviders = customProviders,
        display = display,
        openChatSession = { session },
        projectionDispatcher = dispatcher,
    )

    private val JsonObject.type: String get() = string("type")

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.ids(): List<String> =
        this["messages"]!!.jsonArray.map { it.jsonObject.string("id") }

    private fun topology(id: String, parent: String?, participant: Participant, timestamp: Long) =
        MessageContextTopology(
            id = id,
            conversationId = "a",
            parentId = parent,
            status = MessageStatus.SUCCESS,
            participant = participant,
            timestamp = timestamp,
            modelName = null,
            runId = "run-$id",
            runSequence = timestamp,
            consumedAtPass = null,
        )

    private fun message(
        id: String,
        parent: String?,
        participant: Participant,
        text: String,
        status: MessageStatus = MessageStatus.SUCCESS,
    ) = ChatMessage(
        id = id,
        parentId = parent,
        text = text,
        participant = participant,
        status = status,
        timestamp = 10,
    )
}
