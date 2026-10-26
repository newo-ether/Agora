package com.newoether.agora.webui
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.local.DrawerConversationRow
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.viewmodel.ConversationLifecycleController
import com.newoether.agora.viewmodel.ConversationStateRegistry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer row's menu commands. Pin, rename and delete all belong to the phone's one
 * conversation owner, and a delete is answered only to the connection that asked for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebUiConversationMenuTest {
    private var deleteAccepted = true
    private var deleteOutcome: Boolean? = true
    private var deleteOrigin: Any? = null
    private val list = MutableStateFlow(
        listOf(DrawerConversationRow("a", "Alpha", false, false), DrawerConversationRow("b", "Beta", false, false)),
    )
    private val conversations = mockk<ConversationRepository> {
        every { observeDrawerConversations(any()) } answers { list.map { rows -> rows.take(firstArg<Int>()) } }
        every { subagentJobs } returns MutableStateFlow(emptyMap())
        every { subagentGenerationIds } returns MutableStateFlow(emptySet())
        every { chatDao } returns mockk {
            every { observeSubagentParentIds(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }
    private val generation = mockk<com.newoether.agora.viewmodel.MessageGenerationController>(relaxed = true)
    private val generating = MutableStateFlow<Set<String>>(emptySet())
    private val registry = mockk<ConversationStateRegistry> {
        every { activeConversationIds } returns generating
    }
    private val lifecycle = mockk<ConversationLifecycleController> {
        every { setPinned(any(), any()) } just Runs
        every { rename(any(), any()) } just Runs
        every { delete(any(), any(), isNull(), any()) } answers {
            deleteOrigin = firstArg<Any>()
            deleteOutcome?.let { outcome -> lastArg<(Boolean) -> Unit>().invoke(outcome) }
            deleteAccepted
        }
    }
    private val openTarget = MutableStateFlow(WebUiChatSession.OpenTarget(null, browserSeq = 0L, movedByServer = false))
    private val transitions = com.newoether.agora.viewmodel.BranchReplacementTransitionCoordinator()
    private val session = mockk<WebUiChatSession> {
        every { openTarget } returns this@WebUiConversationMenuTest.openTarget
        every { snackbars } returns MutableSharedFlow<String>(extraBufferCapacity = 4)
        every { scrollRequests } returns MutableSharedFlow<WebSyncEvent.ScrollToBottom>(extraBufferCapacity = 4)
        every { composerState } returns MutableSharedFlow<WebUiChatSession.ComposerState>(extraBufferCapacity = 4)
        every { branchTransitions } returns transitions
        coEvery { rowCommand(any(), any(), any()) } just Runs
        every { openConversationId } answers { this@WebUiConversationMenuTest.openTarget.value.conversationId }
        coEvery { start() } just Runs
        coEvery { close() } just Runs
        coEvery { endUploads() } just Runs
    }

    @Test
    fun titleUsesTheCanonicalGeneratorWithTheNamedDrawerRowAndThisBrowserAsOrigin() = sync { send, _ ->
        send("""{"type":"conversation_title","conversationId":"b"}""")
        verify(exactly = 1) { generation.generateTitle("b", session) }
        generating.value = setOf("b")
        send("""{"type":"conversation_title","conversationId":"b"}""")
        send("""{"type":"conversation_title"}""")
        send("""{"type":"conversation_title","conversationId":" "}""")
        verify(exactly = 2) { generation.generateTitle("b", session) }
        verify(exactly = 2) { generation.generateTitle(any(), any()) }
    }

    @Test
    fun pinUsesTheConversationLifecycleOwnerThePhoneUses() = sync { send, received ->
        received()
        generating.value = setOf("b")
        send("""{"type":"pin","conversationId":"b","enabled":true}""")
        verify(exactly = 1) { lifecycle.setPinned("b", true) }
        coVerify(exactly = 0) { conversations.setConversationPinned(any(), any()) }
    }

    @Test
    fun renameUsesTheConversationLifecycleOwnerAndABlankTitleIsNotACommand() = sync { send, received ->
        received()
        generating.value = setOf("b")
        send("""{"type":"conversation_rename","conversationId":"b","text":"  Kept  "}""")
        verify(exactly = 1) { lifecycle.rename("b", "Kept") }
        send("""{"type":"conversation_rename","conversationId":"b","text":"   "}""")
        send("""{"type":"conversation_rename","conversationId":"b"}""")
        verify(exactly = 1) { lifecycle.rename(any(), any()) }
    }

    @Test
    fun deleteRunsTheConversationLifecycleOwnerWithThisConnectionAsOriginAndAnswersItsActionId() = sync { send, received ->
        received()
        generating.value = setOf("b")
        send("""{"type":"conversation_delete","conversationId":"b","actionId":7}""")
        val event = received().single { it.type == "conversation_delete" }
        assertEquals("b", event.string("conversationId"))
        assertEquals("7", event.string("actionId"))
        assertEquals("true", event.string("success"))
        assertTrue(deleteOrigin === session)
    }

    @Test
    fun aDeleteTheConversationOwnerRefusesIsAnsweredAsAFailureWithoutTouchingStorage() = sync { send, received ->
        received()
        deleteAccepted = false
        deleteOutcome = null
        send("""{"type":"conversation_delete","conversationId":"b","actionId":9}""")
        val event = received().single { it.type == "conversation_delete" }
        assertEquals("9", event.string("actionId"))
        assertEquals("false", event.string("success"))
        coVerify(exactly = 0) { conversations.deleteConversation(any()) }
    }

    @Test
    fun aDeleteWithoutAnActionIdIsNotACommand() = sync { send, received ->
        received()
        send("""{"type":"conversation_delete","conversationId":"b"}""")
        assertTrue(received().none { it.type == "conversation_delete" })
        verify(exactly = 0) { lifecycle.delete(any(), any(), any(), any()) }
    }

    @Test
    fun editWaitingForFadeDoesNotBlockTheConnectionCommandReader() = sync { send, received ->
        received()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { session.rowCommand(any(), any(), any()) } coAnswers {
            val request = transitions.begin("a", "old")!!
            entered.complete(Unit)
            transitions.awaitFade(request.id)
        }
        send("""{"type":"edit","conversationId":"a","seq":1,"messageId":"u","text":"edit"}""")
        assertTrue(entered.isCompleted)
        send("""{"type":"pin","conversationId":"b","enabled":true}""")
        verify(exactly = 1) { lifecycle.setPinned("b", true) }
        transitions.abortCurrent()
    }

    @Test
    fun recompactRowCommandReachesTheCurrentSession() = sync { send, _ ->
        send("""{"type":"recompact","conversationId":"a","seq":1,"messageId":"compact_target"}""")
        coVerify(exactly = 1) { session.rowCommand(match { it.type == "recompact" && it.messageId == "compact_target" }, any(), any()) }
    }

    private fun sync(
        block: suspend TestScope.(send: suspend (String) -> Unit, received: () -> List<JsonObject>) -> Unit,
    ) = runTest {
        val sync = WebUiSync(
            conversations = conversations,
            registry = registry,
            executionCoordinator = ConversationExecutionCoordinator(),
            hydration = mockk(relaxed = true),
            customProviders = MutableStateFlow<List<CustomProviderConfig>>(emptyList()),
            display = MutableStateFlow(
                WebDisplayContext(
                    resources = mockk(relaxed = true),
                    toolCallDisplayMode = ToolCallDisplayModes.DEFAULT,
                    thinkingSegmentDisplayMode = ThinkingSegmentDisplayModes.DEFAULT,
                    autoExpandActiveGroup = true,
                    parseInlineDollarMath = false,
                    autoWrapCodeBlocks = false,
                ),
            ),
            openChatSession = { session },
            forkShare = mockk(relaxed = true),
            conversationLifecycle = lifecycle,
            generation = generation,
            askUser = com.newoether.agora.viewmodel.AskUserController(),
            shellConfirmation = com.newoether.agora.viewmodel.ShellConfirmationController(MutableStateFlow(true), {}),
            contextAccounting = WebUiContextAccounting(
                conversations, mockk(), { mockk() }, { it }, MutableStateFlow(90), MutableStateFlow(true),
            ),
            search = { emptyList() },
            projectionDispatcher = StandardTestDispatcher(testScheduler),
        )
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

    private val JsonObject.type: String get() = string("type")

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
}
