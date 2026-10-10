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
import com.newoether.agora.util.appLanguageResources
import kotlinx.coroutines.launch
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

    private fun display(
        language: String = "en",
        toolMode: String = ToolCallDisplayModes.DEFAULT,
    ) = WebDisplayContext(
        resources = context.appLanguageResources(language),
        toolCallDisplayMode = toolMode,
        thinkingSegmentDisplayMode = ThinkingSegmentDisplayModes.DEFAULT,
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
    fun `compact mode sends one block and the answer body`() {
        val message = model(
            MessageStatus.SUCCESS,
            MessageSegment(type = "thought", content = "plan", durationMs = 2_000),
            MessageSegment(type = "answer", content = "hi"),
            text = "hi",
        )
        val presentation =
            webPresentation(message, isStreaming = false, display(toolMode = ToolCallDisplayModes.COMPACT))!!
        assertTrue(!presentation.useTimeline)
        assertTrue(presentation.blocks.isEmpty())
        assertEquals("compact", presentation.compact!!.key)
        assertEquals("hi", presentation.answer!!.markdown)
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
            return projected.compact?.items?.single()
                ?: (projected.blocks.single() as WebTimelineBlock.Group).group.items.single()
        }
        for (mode in listOf(ToolCallDisplayModes.GROUPED_TIMELINE, ToolCallDisplayModes.COMPACT)) {
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
            for (mode in listOf(ToolCallDisplayModes.COMPACT, ToolCallDisplayModes.GROUPED_TIMELINE, ToolCallDisplayModes.TIMELINE)) {
                val projected = webPresentation(message, true, display(toolMode = mode))!!
                assertEquals("${message.status} ${message.segments} $mode", expected, projected.answerTailVisible)
                assertTrue(!projected.answerTailVisible || projected.inlineActivity == null)
            }
        }
    }
    @Test
    fun wireContainsOneRenderedBodyAndPreservesOriginalCopySource() {
        val source = "x \\(a^2\\) y".repeat(1000)
        val message = model(MessageStatus.SENDING, MessageSegment("answer", source), text = source)
        val projection = message.toWeb(webPresentation(message, true, display(toolMode = ToolCallDisplayModes.COMPACT)))
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
        val repository = io.mockk.mockk<com.newoether.agora.data.repository.ConversationRepository>(relaxed = true)
        io.mockk.every { repository.observeConversation("c") } returns kotlinx.coroutines.flow.flowOf(com.newoether.agora.model.ChatConversation("c", "Test"))
        io.mockk.every { repository.observeMessageTopology("c") } returns topology
        io.mockk.every { repository.observeDrawerConversations(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val state = io.mockk.mockk<com.newoether.agora.viewmodel.ConversationGenerationState>()
        io.mockk.every { state.generationSnapshot } returns snapshot
        val registry = io.mockk.mockk<com.newoether.agora.viewmodel.ConversationStateRegistry>()
        io.mockk.every { registry.activeConversationIds } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        io.mockk.every { registry.getOrCreate("c") } returns state
        val hydration = io.mockk.mockk<com.newoether.agora.viewmodel.ConversationMessagePayloadHydration>()
        io.mockk.every { hydration.observeMessage("m", any()) } returns kotlinx.coroutines.flow.flowOf(terminal)
        val session = io.mockk.mockk<WebUiChatSession>(relaxed = true)
        io.mockk.every { session.openTarget } returns kotlinx.coroutines.flow.MutableStateFlow(WebUiChatSession.OpenTarget("c", 0, false))
        io.mockk.every { session.composerState } returns kotlinx.coroutines.flow.emptyFlow()
        io.mockk.every { session.snackbars } returns kotlinx.coroutines.flow.MutableSharedFlow<String>()
        io.mockk.every { session.scrollRequests } returns kotlinx.coroutines.flow.MutableSharedFlow<WebSyncEvent.ScrollToBottom>()
        val providers = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.newoether.agora.data.CustomProviderConfig>())
        val accounting = WebUiContextAccounting(repository, io.mockk.mockk(), { io.mockk.mockk() }, { it },
            kotlinx.coroutines.flow.MutableStateFlow(90), kotlinx.coroutines.flow.MutableStateFlow(true))
        val owner = WebUiSync(repository, registry, com.newoether.agora.automation.ConversationExecutionCoordinator(),
            hydration, providers, kotlinx.coroutines.flow.flowOf(display(toolMode = ToolCallDisplayModes.COMPACT)), { session },
            io.mockk.mockk(), com.newoether.agora.viewmodel.AskUserController(),
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
        io.mockk.verify(exactly = 0) { hydration.observeMessage("m", any()) }
        events.clear()
        repeat(20) { index ->
            snapshot.value = snapshot.value.copy(streamingMessage = snapshot.value.streamingMessage!!.copy(text = "delta-$index"))
            topology.value = topology.value.map { it.copy(tokenCount = index + 1) }
            runCurrent()
        }
        assertEquals(20, events.count { it is WebSyncEvent.Streaming })
        assertTrue(events.none { it is WebSyncEvent.Path || it is WebSyncEvent.Payload || it is WebSyncEvent.Context })
        snapshot.value = snapshot.value.copy(streamingMessage = terminal, isLoading = false, isGenerating = false)
        runCurrent()
        assertEquals(terminal.text, (events.single { it is WebSyncEvent.Payload } as WebSyncEvent.Payload).message.text.markdown)
        io.mockk.verify(exactly = 1) { hydration.observeMessage("m", any()) }
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
