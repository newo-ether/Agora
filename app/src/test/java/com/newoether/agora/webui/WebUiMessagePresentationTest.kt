package com.newoether.agora.webui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.chat.message.toolSummary
import com.newoether.agora.util.appLanguageResources
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The web receives the app's own layout decisions and card text, in the phone's language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebUiMessagePresentationTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun repositoryForConversationOpen() =
        io.mockk.mockk<com.newoether.agora.data.repository.ConversationRepository>(relaxed = true) {
            io.mockk.every { subagentJobs } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
            io.mockk.every { subagentGenerationIds } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
            io.mockk.every { chatDao } returns io.mockk.mockk {
                io.mockk.every { observeConversationTodo(any()) } returns kotlinx.coroutines.flow.flowOf(null)
                io.mockk.every { observeParentConversationId(any()) } returns kotlinx.coroutines.flow.flowOf(null)
                io.mockk.every { observeSubagents(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
                io.mockk.coEvery { getSubagentFamilyRootId(any()) } answers { firstArg<String>() }
                io.mockk.coEvery { getSubagentIds(any()) } returns emptyList()
                io.mockk.coEvery { getLiveRun(any()) } returns null
                io.mockk.coEvery { getValidatedSubagentStatus(any()) } returns null
            }
            io.mockk.coEvery { recoverConversationRuntime(any(), any()) } returns 0
            io.mockk.coEvery { withProviderContextSnapshot<Unit>(any()) } coAnswers {
                firstArg<suspend () -> Unit>().invoke()
            }
        }

    private fun display(
        language: String = "en",
        toolMode: String = ToolCallDisplayModes.DEFAULT,
        thinkingMode: String = ThinkingSegmentDisplayModes.DEFAULT,
    ) = WebDisplayContext(
        resources = context.appLanguageResources(language),
        toolCallDisplayMode = toolMode,
        thinkingSegmentDisplayMode = thinkingMode,
        autoExpandActiveGroup = true,
        parseInlineDollarMath = false,
        autoWrapCodeBlocks = true,
    )

    private fun model(status: MessageStatus, vararg segments: MessageSegment, text: String = "") =
        ChatMessage(
            id = "m",
            text = text,
            participant = Participant.MODEL,
            status = status,
            segments = segments.toList(),
        )

    @Test
    fun phoneStickToBottomSettingIsAlwaysPublishedIncludingItsDefault() {
        for (enabled in listOf(true, false)) {
            val event = display().copy(stickToBottom = enabled).toEvent()
            assertEquals(enabled, event.stickToBottom)
            val encoded = kotlinx.serialization.json.Json.encodeToString(
                WebSyncEvent.Display.serializer(), event,
            )
            assertTrue(encoded.contains("\"stickToBottom\":$enabled"))
        }
        assertTrue(display().toEvent().stickToBottom)
    }
    @Test
    fun completionSourceProjectsCompactMetadataAndKeepsTheFullOriginalResult() {
        val raw = "Worker (child) [failed]\nFull original result\nError: Connection closed"
        val result = com.newoether.agora.model.MessageSource.SubagentResult(
            "Worker", "failed", "Connection closed", "connection_closed")
        val source = com.newoether.agora.model.MessageSource(
            com.newoether.agora.model.MessageSource.Kind.SUBAGENT,
            subagentResults = mapOf("child" to "result"), subagentResult = result)
        val message = ChatMessage(id = "receipt", text = raw, participant = Participant.USER, source = source)
        val wire = message.toWeb(null)
        assertEquals(raw, wire.text.markdown)
        assertEquals(result, wire.source?.subagentResult)
        val encoded = WebUiSync.json.encodeToString(WebMessage.serializer(), wire)
        assertEquals(wire, WebUiSync.json.decodeFromString(WebMessage.serializer(), encoded))
        assertEquals(com.newoether.agora.model.MessageSource.SubagentResult("Worker", "failed"),
            message.copy(source = source.copy(subagentResult = null)).toWeb(null).source?.subagentResult)
        assertNull(message.copy(source = com.newoether.agora.model.MessageSource(
            com.newoether.agora.model.MessageSource.Kind.SUBAGENT)).toWeb(null).source?.subagentResult)
    }

    @Test
    fun todoAndSubagentCardsUseTheAppLocaleInBothDisplayModes() {
        val names = listOf("read_todo", "update_todo", "spawn_agent", "send_agent_message", "wait_agents", "list_agents", "stop_agent")
        val titles = listOf("读取待办清单", "更新待办清单", "启动子代理", "向子代理发送消息", "等待子代理", "列出子代理", "停止子代理")
        for (mode in listOf(ToolCallDisplayModes.GROUPED_TIMELINE, ToolCallDisplayModes.TIMELINE)) {
            names.forEachIndexed { index, name ->
                val segment = MessageSegment(type = "tool", toolName = name, toolArgs = "{\"agent_id\":\"CaseSensitive\"}")
                val projected = webPresentation(model(MessageStatus.SENDING, segment), true, display(language = "zh", toolMode = mode))!!
                val item = projected.thinkingCard?.items?.single()
                    ?: (projected.blocks.single() as? WebTimelineBlock.Group)?.group?.items?.single()
                    ?: (projected.blocks.single() as WebTimelineBlock.Card).item
                assertEquals(titles[index], item.title)
                assertEquals(com.newoether.agora.ui.chat.message.ToolPresentationResolver.kindForToolName(name).name, item.toolDetail!!.kind)
                assertEquals(display(language = "zh").resources.toolSummary(segment), item.summary)
                assertEquals(item.summary, (item.toolDetail.body as WebToolBody.Active).text)
                val encoded = WebUiSync.json.encodeToString(WebPresentation.serializer(), projected)
                assertEquals(projected, WebUiSync.json.decodeFromString(WebPresentation.serializer(), encoded))
                assertEquals("{\"agent_id\":\"CaseSensitive\"}", segment.toolArgs)
            }
        }
    }

    @Test
    fun citationWireUsesCanonicalGroupsAndPreservesOriginalSearchOffsetsAndCopy() {
        val answer = "Alpha beta after \\(x^2\\) tail"
        val citations = listOf("https://first.example/a", "https://second.example/b").map { url ->
            requireNotNull(com.newoether.agora.model.CitationPolicy.create(
                provider = "openai", kind = "url", title = "Source $url", url = url,
                anchors = listOf(com.newoether.agora.model.CitationAnchor(0, 5, "Alpha")), answerText = answer,
            ))
        }
        val segments = listOf(MessageSegment("answer", answer)) + citations.map {
            MessageSegment("citation", com.newoether.agora.model.CitationPolicy.encode(it))
        }
        for (mode in listOf(ToolCallDisplayModes.TIMELINE, ToolCallDisplayModes.GROUPED_TIMELINE)) {
            for (status in listOf(MessageStatus.SUCCESS, MessageStatus.ERROR, MessageStatus.STOPPED)) {
                val message = model(status, *segments.toTypedArray(), text = answer)
                val wire = webPresentation(message, false, display(toolMode = mode))!!
                val text = wire.answer ?: (wire.blocks.single() as WebTimelineBlock.Answer).text
                assertEquals(2, text.citations.single().sources.size)
                assertEquals("first.example", text.citations.single().label)
                assertEquals(2, wire.sources.size)
                assertEquals("2 Sources", wire.sourcesTitle)
                assertTrue(wire.copyText!!.contains("## Sources"))
                assertTrue(wire.copyText!!.contains("https://second.example/b"))
                for (word in listOf("beta", "after", "tail")) {
                    val index = text.markdown.indexOf(word)
                    val mapping = text.sourceMap.lastOrNull { it[0] <= index }
                    assertEquals(word, answer.indexOf(word), index + (mapping?.let { it[1] - it[0] } ?: 0))
                }
                val encoded = WebUiSync.json.encodeToString(WebPresentation.serializer(), wire)
                assertEquals(wire, WebUiSync.json.decodeFromString(WebPresentation.serializer(), encoded))
                val live = webPresentation(message.copy(status = MessageStatus.SENDING), true, display(toolMode = mode))!!
                val liveText = live.answer ?: (live.blocks.single() as WebTimelineBlock.Answer).text
                assertTrue(liveText.citations.isEmpty())
                assertEquals(answer, message.text)
            }
        }
    }

    @Test
    fun compactDetailErrorsUseTheNativeResolverForEveryParticipantAndKeepSummarySeparate() {
        for (participant in Participant.entries) {
            for (body in listOf("", "Partial summary")) {
                val message = model(MessageStatus.ERROR, MessageSegment("error", "Network failure"), text = body)
                    .copy(id = "compact_test", participant = participant)
                val projected = webPresentation(message, false, display())!!
                assertEquals("Network failure", projected.compactDetailErrorText)
                assertEquals(body, message.toWeb(projected).text.markdown)
                val encoded = WebUiSync.json.encodeToString(WebPresentation.serializer(), projected)
                assertEquals(projected, WebUiSync.json.decodeFromString(WebPresentation.serializer(), encoded))
            }
            val empty = model(MessageStatus.ERROR).copy(id = "compact_test", participant = participant)
            assertEquals(display().resources.getString(R.string.failed_to_generate),
                webPresentation(empty, false, display())!!.compactDetailErrorText)
            for (status in listOf(MessageStatus.SENDING, MessageStatus.SUCCESS, MessageStatus.STOPPED)) {
                assertNull(webPresentation(empty.copy(status = status, participant = Participant.MODEL),
                    status == MessageStatus.SENDING, display())!!.compactDetailErrorText)
            }
        }
        assertNull(webPresentation(model(MessageStatus.ERROR), false, display())!!.compactDetailErrorText)
    }

    @Test
    fun branchWireUsesTheCanonicalUserEditAndAssistantRegenerationProjection() {
        val user = ChatMessage(id = "u", text = "input", participant = Participant.USER, timestamp = 1, runId = "r1")
        val edited = user.copy(id = "edited", timestamp = 2, runId = "r2")
        val first = user.copy(id = "m", parentId = "u", participant = Participant.MODEL, timestamp = 3)
        val regenerated = first.copy(id = "regen", timestamp = 4, runId = "r3")
        val final = first.copy(id = "final", parentId = "m", timestamp = 5)
        val path = listOf(user, first, final)
        val graph = listOf(user, edited, first, regenerated, final)
        val projected = com.newoether.agora.model.RunUiProjection.project(path, graph)
        val wire = path.map { it.toWebPathEntry(projected[it.id]) }
        assertTrue(wire[0].showBranchSelector)
        assertEquals(2, wire[0].totalBranches)
        assertEquals("u", wire[0].branchAnchorMessageId)
        assertTrue(!wire[1].showBranchSelector)
        assertTrue(wire[2].showBranchSelector)
        assertEquals("m", wire[2].branchAnchorMessageId)
        assertEquals("u", wire[2].branchAnchorParentId)
        assertEquals(2, wire[2].totalBranches)
        val encoded = WebUiSync.json.encodeToString(WebPathEntry.serializer(), wire[2])
        assertEquals(wire[2], WebUiSync.json.decodeFromString(WebPathEntry.serializer(), encoded))
        val fields = WebUiSync.json.parseToJsonElement(encoded) as kotlinx.serialization.json.JsonObject
        assertEquals("0", fields.getValue("branchIndex").toString())
        assertEquals("2", fields.getValue("totalBranches").toString())
        val last = WebUiSync.json.encodeToString(WebPathEntry.serializer(), wire[2].copy(branchIndex = 1))
        assertEquals("1", (WebUiSync.json.parseToJsonElement(last) as kotlinx.serialization.json.JsonObject)
            .getValue("branchIndex").toString())
    }

    @Test
    fun `live thinking group carries a timer base and loading icon`() {
        val message = model(
            MessageStatus.THINKING,
            MessageSegment(type = "thought", content = "plan", durationMs = 5_000),
        )
        val presentation = webPresentation(message, isStreaming = true, display())!!
        val group = (presentation.blocks.single() as WebTimelineBlock.Group).group
        assertEquals(5_000L, group.liveBaseMs)
        assertEquals("LOADING", group.icon)
        assertEquals(
            context.appLanguageResources("en").getString(R.string.thinking_for_seconds_ellipsis, 5),
            group.title,
        )
        assertTrue(group.items.single().streaming)
    }

    @Test
    fun `finished message uses static title then answer block`() {
        val resources = context.appLanguageResources("en")
        val message = model(
            MessageStatus.SUCCESS,
            MessageSegment(type = "thought", content = "plan", durationMs = 12_000),
            MessageSegment(type = "answer", content = "hi"),
            text = "hi",
        )
        val presentation = webPresentation(message, isStreaming = false, display())!!
        assertTrue(presentation.useTimeline)
        val group = (presentation.blocks[0] as WebTimelineBlock.Group).group
        assertNull(group.liveBaseMs)
        assertEquals(resources.getString(R.string.thought_for_seconds, 12), group.title)
        assertEquals(resources.getString(R.string.tool_thinking), group.items.single().title)
        assertEquals("hi", (presentation.blocks[1] as WebTimelineBlock.Answer).text.markdown)
        assertNull(presentation.inlineTerminalText)
    }

    @Test
    fun `grouped bottom sheet retains the shared thinking card and answer body`() {
        val message = model(
            MessageStatus.SUCCESS,
            MessageSegment(type = "thought", content = "plan", durationMs = 2_000),
            MessageSegment(type = "answer", content = "hi"),
            text = "hi",
        )
        val presentation =
            webPresentation(message, isStreaming = false,
                display(thinkingMode = ThinkingSegmentDisplayModes.BOTTOM_SHEET))!!
        assertTrue(!presentation.useTimeline)
        assertTrue(presentation.blocks.isEmpty())
        assertEquals("thinking", presentation.thinkingCard!!.key)
        assertEquals("hi", presentation.answer!!.markdown)
    }

    @Test
    fun legacyCompactMatchesGroupedOnTheWireAndPublishesOnlyTheCurrentMode() {
        val message = model(MessageStatus.SUCCESS,
            MessageSegment("thought", "first"), MessageSegment("answer", "middle"),
            MessageSegment(type = "tool", toolName = "file_read"),
            MessageSegment("answer", "last"), text = "middlelast")
        val grouped = webPresentation(message, false, display())!!
        val legacy = webPresentation(message, false, display(toolMode = "compact"))!!
        assertEquals(grouped, legacy)
        assertEquals(ToolCallDisplayModes.GROUPED_TIMELINE, display(toolMode = "compact").toEvent().toolCallDisplayMode)
        assertEquals(listOf("middle", "last"),
            legacy.blocks.filterIsInstance<WebTimelineBlock.Answer>().map { it.text.markdown })
        assertEquals(2, legacy.blocks.filterIsInstance<WebTimelineBlock.Group>().size)
        assertNull(legacy.thinkingCard)
    }

    @Test
    fun `stopped message without answer shows localized inline text`() {
        val message = model(MessageStatus.STOPPED)
        val zh = display(language = "zh")
        val presentation = webPresentation(message, isStreaming = false, zh)!!
        assertEquals(zh.resources.getString(R.string.generation_stopped), presentation.inlineTerminalText)
        assertNull(webPresentation(message.copy(participant = Participant.USER), false, zh))
    }

    @Test
    fun toolDetailsUseTheExistingMessageProjectionForStreamingAndFinalPayloads() {
        val active = model(MessageStatus.SENDING,
            MessageSegment(type = "tool", toolName = "file_read", toolArgs = "{\"path\":\"/file\"}"),
        )
        fun item(message: ChatMessage, streaming: Boolean, mode: String): WebInfoItem {
            val projected = webPresentation(message, streaming, display(toolMode = mode))!!
            return projected.thinkingCard?.items?.single()
                ?: (projected.blocks.single() as? WebTimelineBlock.Group)?.group?.items?.single()
                    ?: (projected.blocks.single() as WebTimelineBlock.Card).item
        }
        for (mode in listOf(ToolCallDisplayModes.GROUPED_TIMELINE, ToolCallDisplayModes.TIMELINE)) {
            assertTrue(item(active, true, mode).toolDetail!!.body is WebToolBody.Active)
            val done = active.copy(status = MessageStatus.SUCCESS, segments = active.segments.orEmpty().map {
                it.copy(toolResult = "{\"path\":\"/file\",\"content\":\"result\"}")
            })
            assertEquals("result", (item(done, false, mode).toolDetail!!.body as WebToolBody.FileContent).content)
            val encoded = WebUiSync.json.encodeToString(WebPresentation.serializer(), webPresentation(done, false, display(toolMode = mode))!!)
            assertTrue(encoded.contains("\"toolDetail\""))
            assertTrue(encoded.contains("\"type\":\"file\""))
        }
        val thought = model(MessageStatus.SUCCESS, MessageSegment(type = "thought", content = "plan"))
        assertNull(item(thought, false, ToolCallDisplayModes.GROUPED_TIMELINE).toolDetail)
    }

    @Test
    fun appearanceFlagsPreserveBothExplicitValues() {
        for ((blur, reduceMotion) in listOf(false to true, true to false)) {
            val event = display().copy(blurEffectsEnabled = blur, reduceMotion = reduceMotion).toEvent()
            assertEquals(blur, event.blurEffectsEnabled)
            assertEquals(reduceMotion, event.reduceMotion)
            val encoded = WebUiSync.json.encodeToString(WebSyncEvent.serializer(), event)
            val decoded = WebUiSync.json.decodeFromString(WebSyncEvent.serializer(), encoded) as WebSyncEvent.Display
            assertEquals(event, decoded)
        }
    }

    @Test
    fun activityUsesTheComposeLastVisibleSegmentPredicate() {
        val answer = MessageSegment("answer", "partial")
        val thought = MessageSegment("thought", "plan")
        val tool = MessageSegment(type = "tool", toolName = "file_read", toolArgs = "{}")
        val cases = listOf(
            model(MessageStatus.SENDING) to false,
            model(MessageStatus.THINKING, thought) to false,
            model(MessageStatus.SENDING, answer, text = "partial") to true,
            model(MessageStatus.TOOL_CALLING, answer, tool, text = "partial") to false,
            model(MessageStatus.SENDING, tool, answer, text = "partial") to true,
            model(MessageStatus.SENDING, answer, text = "partial").copy(retryText = "Retry") to false,
            model(MessageStatus.SUCCESS, answer, text = "partial") to false,
            model(MessageStatus.STOPPED, answer, text = "partial") to false,
            model(MessageStatus.ERROR, answer, text = "partial") to false,
        )
        for ((message, expected) in cases) {
            for (mode in listOf(ToolCallDisplayModes.GROUPED_TIMELINE, ToolCallDisplayModes.TIMELINE)) {
                val projected = webPresentation(message, true, display(toolMode = mode))!!
                assertEquals("${message.status} ${message.segments} $mode", expected, projected.answerTailVisible)
                assertTrue(!projected.answerTailVisible || projected.inlineActivity == null)
            }
        }
    }
    @Test
    fun loadBudgetCountsSourceAndExtraDetailsWithoutMirroredAnswersOrUsage() {
        val text = "source body"
        val message = model(MessageStatus.SUCCESS,
            MessageSegment("answer", text), MessageSegment("thought", "reason"),
            MessageSegment("transcription", "description"),
            MessageSegment("tool", toolArgs = "arguments", toolResult = "result"), text = text)
        val expected = listOf(text, "reason", "description", "arguments", "result")
            .sumOf(com.newoether.agora.api.util.ContextTokenEstimator::estimateText)
        assertEquals(expected, message.webLoadTokens())
        assertEquals(expected, message.copy(tokenCount = Int.MAX_VALUE).webLoadTokens())
        assertEquals(expected, message.toWeb(null).loadTokens)
        assertEquals(1, model(MessageStatus.SUCCESS).webLoadTokens())
    }

    @Test
    fun wireContainsOneRenderedBodyAndPreservesOriginalCopySource() {
        val source = "x \\(a^2\\) y".repeat(1000)
        val message = model(MessageStatus.SENDING, MessageSegment("answer", source), text = source)
        val projection = message.toWeb(webPresentation(message, true, display(thinkingMode = ThinkingSegmentDisplayModes.BOTTOM_SHEET)))
        assertEquals(source, projection.text.markdown)
        assertEquals(emptyList<WebMath>(), projection.text.math)
        assertTrue(projection.presentation!!.answer!!.math.isNotEmpty())
        val encoded = WebUiSync.json.encodeToString(WebMessage.serializer(), projection)
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(encoded) as kotlinx.serialization.json.JsonObject
        assertTrue("segments" !in keys && "thoughts" !in keys)
    }
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun liveTailSkipsDurableHydrationAndTextDeltasSkipPathUntilTerminalHandoff() = kotlinx.coroutines.test.runTest {
        val terminal = model(MessageStatus.SUCCESS, MessageSegment("answer", "complete"), text = "complete")
            .copy(parentId = null, timestamp = 1, runId = "run", runSequence = 1)
        val snapshot = kotlinx.coroutines.flow.MutableStateFlow(com.newoether.agora.viewmodel.ConversationGenerationSnapshot(
            conversationId = "c", streamingMessage = terminal.copy(status = MessageStatus.SENDING, text = "partial"),
            isLoading = true, isGenerating = true))
        val topology = kotlinx.coroutines.flow.MutableStateFlow(listOf(com.newoether.agora.data.local.MessageContextTopology(
            id = "m", conversationId = "c", parentId = null, status = MessageStatus.SENDING,
            participant = Participant.MODEL, timestamp = 1, modelName = null, runId = "run", runSequence = 1, consumedAtPass = null)))
        val repository = repositoryForConversationOpen()
        io.mockk.every { repository.observeConversation("c") } returns kotlinx.coroutines.flow.flowOf(com.newoether.agora.model.ChatConversation("c", "Test"))
        io.mockk.every { repository.observeMessageTopology("c") } returns topology
        io.mockk.every { repository.observeDrawerConversations(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val state = io.mockk.mockk<com.newoether.agora.viewmodel.ConversationGenerationState>()
        io.mockk.every { state.generationSnapshot } returns snapshot
        val registry = io.mockk.mockk<com.newoether.agora.viewmodel.ConversationStateRegistry>()
        io.mockk.every { registry.activeConversationIds } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        io.mockk.every { registry.getOrCreate("c") } returns state
        val hydration = io.mockk.mockk<com.newoether.agora.viewmodel.ConversationMessagePayloadHydration>()
        io.mockk.every { hydration.observeMessages("c", listOf("m"), any()) } returns kotlinx.coroutines.flow.flowOf(listOf(terminal))
        val session = io.mockk.mockk<WebUiChatSession>(relaxed = true)
        io.mockk.every { session.openTarget } returns kotlinx.coroutines.flow.MutableStateFlow(WebUiChatSession.OpenTarget("c", 0, false))
        io.mockk.every { session.composerState } returns kotlinx.coroutines.flow.emptyFlow()
        io.mockk.every { session.branchTransitions } returns com.newoether.agora.viewmodel.BranchReplacementTransitionCoordinator()
        io.mockk.every { session.snackbars } returns kotlinx.coroutines.flow.MutableSharedFlow<String>()
        io.mockk.every { session.scrollRequests } returns kotlinx.coroutines.flow.MutableSharedFlow<WebSyncEvent.ScrollToBottom>()
        val providers = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.newoether.agora.data.CustomProviderConfig>())
        val accounting = WebUiContextAccounting(repository, io.mockk.mockk(), { io.mockk.mockk() }, { it },
            kotlinx.coroutines.flow.MutableStateFlow(90), kotlinx.coroutines.flow.MutableStateFlow(true))
        val owner = WebUiSync(repository, registry, com.newoether.agora.automation.ConversationExecutionCoordinator(),
            hydration, providers, kotlinx.coroutines.flow.flowOf(display()), { session },
            io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), com.newoether.agora.viewmodel.AskUserController(),
            com.newoether.agora.viewmodel.ShellConfirmationController(kotlinx.coroutines.flow.MutableStateFlow(true), {}),
            accounting, { emptyList() }, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val incoming = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val events = mutableListOf<WebSyncEvent>()
        backgroundScope.launch {
            owner.serve("test", incoming) { events += WebUiSync.json.decodeFromString(WebSyncEvent.serializer(), it) }
        }
        runCurrent()
        incoming.send("""{"type":"watch","messageIds":["m"]}""")
        runCurrent()
        assertTrue(events.any { it is WebSyncEvent.Streaming })
        io.mockk.verify(exactly = 0) { hydration.observeMessages("c", listOf("m"), any()) }
        events.clear()
        repeat(20) { index ->
            snapshot.value = snapshot.value.copy(streamingMessage = snapshot.value.streamingMessage!!.copy(text = "delta-$index"))
            topology.value = topology.value.map { it.copy(tokenCount = index + 1) }
            runCurrent()
        }
        // Later frames of the same live message travel as patches against the first full frame.
        assertEquals(20, events.count { it is WebSyncEvent.StreamingPatch })
        assertTrue(events.none { it is WebSyncEvent.Streaming })
        assertTrue(events.none { it is WebSyncEvent.Path || it is WebSyncEvent.Payload || it is WebSyncEvent.Context })
        snapshot.value = snapshot.value.copy(streamingMessage = terminal, isLoading = false, isGenerating = false)
        runCurrent()
        assertEquals(terminal.text, (events.single { it is WebSyncEvent.Payload } as WebSyncEvent.Payload).message.text.markdown)
        io.mockk.verify(exactly = 1) { hydration.observeMessages("c", listOf("m"), any()) }
    }
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun tokenPageHydratesMoreThan48RowsAndSuppressesUnchangedRows() = kotlinx.coroutines.test.runTest {
        val rows = (0 until 120).map { index ->
            ChatMessage(id = "m$index", parentId = if (index == 0) null else "m${index - 1}", text = "body-$index",
                participant = if (index == 0) Participant.USER else Participant.MODEL,
                status = MessageStatus.SUCCESS, timestamp = index.toLong(), runId = "run", runSequence = index.toLong())
        }
        val bodies = kotlinx.coroutines.flow.MutableStateFlow(rows)
        val repository = repositoryForConversationOpen()
        io.mockk.every { repository.observeConversation("c") } returns kotlinx.coroutines.flow.flowOf(com.newoether.agora.model.ChatConversation("c", "Test"))
        io.mockk.every { repository.observeMessageTopology("c") } returns kotlinx.coroutines.flow.flowOf(rows.map {
            com.newoether.agora.data.local.MessageContextTopology(
                id = it.id, conversationId = "c", parentId = it.parentId, participant = it.participant,
                status = it.status, timestamp = it.timestamp, modelName = null, runId = "run",
                runSequence = requireNotNull(it.runSequence), consumedAtPass = null)
        })
        io.mockk.every { repository.observeDrawerConversations(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val runtime = io.mockk.mockk<com.newoether.agora.viewmodel.ConversationGenerationState>()
        io.mockk.every { runtime.generationSnapshot } returns kotlinx.coroutines.flow.MutableStateFlow(com.newoether.agora.viewmodel.ConversationGenerationSnapshot())
        val registry = io.mockk.mockk<com.newoether.agora.viewmodel.ConversationStateRegistry>()
        io.mockk.every { registry.activeConversationIds } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        io.mockk.every { registry.getOrCreate("c") } returns runtime
        val hydration = io.mockk.mockk<com.newoether.agora.viewmodel.ConversationMessagePayloadHydration>()
        val batches = mutableListOf<List<String>>()
        io.mockk.every { hydration.observeMessages("c", any(), any()) } answers {
            val ids = secondArg<List<String>>()
            batches += ids
            bodies.map { all -> all.filter { it.id in ids } }
        }
        val session = io.mockk.mockk<WebUiChatSession>(relaxed = true)
        io.mockk.every { session.openTarget } returns kotlinx.coroutines.flow.MutableStateFlow(WebUiChatSession.OpenTarget("c", 0, false))
        io.mockk.every { session.composerState } returns kotlinx.coroutines.flow.emptyFlow()
        io.mockk.every { session.branchTransitions } returns com.newoether.agora.viewmodel.BranchReplacementTransitionCoordinator()
        io.mockk.every { session.snackbars } returns kotlinx.coroutines.flow.MutableSharedFlow<String>()
        io.mockk.every { session.scrollRequests } returns kotlinx.coroutines.flow.MutableSharedFlow<WebSyncEvent.ScrollToBottom>()
        val providers = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.newoether.agora.data.CustomProviderConfig>())
        val accounting = WebUiContextAccounting(repository, io.mockk.mockk(), { io.mockk.mockk() }, { it },
            kotlinx.coroutines.flow.MutableStateFlow(90), kotlinx.coroutines.flow.MutableStateFlow(true))
        val owner = WebUiSync(repository, registry, com.newoether.agora.automation.ConversationExecutionCoordinator(),
            hydration, providers, kotlinx.coroutines.flow.flowOf(display()), { session },
            io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), com.newoether.agora.viewmodel.AskUserController(),
            com.newoether.agora.viewmodel.ShellConfirmationController(kotlinx.coroutines.flow.MutableStateFlow(true), {}),
            accounting, { emptyList() }, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val incoming = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val events = mutableListOf<WebSyncEvent>()
        val job = backgroundScope.launch {
            owner.serve("test", incoming) { events += WebUiSync.json.decodeFromString(WebSyncEvent.serializer(), it) }
        }
        runCurrent()
        var previousCount = 0
        for (count in listOf(48, 96, 120)) {
            events.clear()
            incoming.send("""{"type":"watch","messageIds":[${rows.takeLast(count).joinToString(",") { "\"${it.id}\"" }},"off-path"]}""")
            runCurrent()
            assertEquals(count - previousCount, events.filterIsInstance<WebSyncEvent.Payload>().size)
            previousCount = count
        }
        assertEquals(listOf(48, 48, 24), batches.map { it.size })
        assertEquals(120, batches.flatten().toSet().size)
        events.clear()
        val retained = rows.drop(1)
        incoming.send("""{"type":"watch","messageIds":[${retained.joinToString(",") { "\"${it.id}\"" }}]}""")
        runCurrent()
        assertTrue(events.none { it is WebSyncEvent.Payload })
        val incremental = batches.size
        bodies.value = rows.map { if (it.id == "m60") it.copy(text = "changed") else it }
        runCurrent()
        assertEquals(incremental, batches.size)
        assertEquals(listOf("m60"), events.filterIsInstance<WebSyncEvent.Payload>().map { it.message.id })
        incoming.send("""{"type":"watch","messageIds":[]}""")
        runCurrent()
        events.clear()
        bodies.value = rows
        runCurrent()
        assertTrue(events.none { it is WebSyncEvent.Payload })
        io.mockk.every { hydration.observeMessages("c", any(), any()) } answers {
            kotlinx.coroutines.flow.flow {
                kotlinx.coroutines.delay(100)
                emit(rows.take(3))
            }
        }
        incoming.send("""{"type":"watch","messageIds":["m0","m1","m2"]}""")
        runCurrent()
        incoming.send("""{"type":"watch","messageIds":[]}""")
        runCurrent()
        testScheduler.advanceTimeBy(101)
        runCurrent()
        assertTrue(events.none { it is WebSyncEvent.Payload })
        job.cancel()
    }

    @Test
    fun capsuleDisplayModeProjectsAndRoundTripsInDisplayEvents() {
        val original = display()
        assertEquals("grouped", original.toEvent().subagentCapsuleDisplayMode)
        val defaultEvent = original.toEvent()
        assertEquals("grouped", WebSyncEvent.Display(
            toolCallDisplayMode = defaultEvent.toolCallDisplayMode,
            thinkingSegmentDisplayMode = defaultEvent.thinkingSegmentDisplayMode,
            autoExpandActiveGroup = defaultEvent.autoExpandActiveGroup,
            autoWrapCodeBlocks = defaultEvent.autoWrapCodeBlocks,
            blurEffectsEnabled = defaultEvent.blurEffectsEnabled,
            reduceMotion = defaultEvent.reduceMotion,
            liveThinking = defaultEvent.liveThinking,
        ).subagentCapsuleDisplayMode)
        for (mode in listOf("individual", "grouped")) {
            val event = original.copy(subagentCapsuleDisplayMode = mode).toEvent()
            assertEquals(mode, event.subagentCapsuleDisplayMode)
            val encoded = WebUiSync.json.encodeToString(WebSyncEvent.serializer(), event)
            assertTrue(encoded.contains("\"subagentCapsuleDisplayMode\":\"$mode\""))
            assertEquals(event, WebUiSync.json.decodeFromString(WebSyncEvent.serializer(), encoded))
        }
    }

    @Test
    fun `display event follows the app language and serializes`() {
        val en = display().toEvent()
        val zh = display(language = "zh").toEvent()
        assertTrue(en.liveThinking.seconds.contains("%1\$d"))
        assertNotEquals(en.liveThinking.seconds, zh.liveThinking.seconds)
        val encoded = WebUiSync.json.encodeToString(WebSyncEvent.serializer(), zh)
        assertTrue(encoded.contains("\"type\":\"display\""))
    }
}
