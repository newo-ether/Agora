package com.newoether.agora.webui

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.tool.SessionToolGroup
import com.newoether.agora.viewmodel.MessageGenerationController
import com.newoether.agora.viewmodel.SessionToolCatalog
import com.newoether.agora.viewmodel.SessionToolEntry
import com.newoether.agora.viewmodel.SessionToolGroupTools
import com.newoether.agora.viewmodel.SessionToolUnavailable
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebUiSessionToolsTest {
    @Test
    fun catalogMappingPreservesCostsReasonsAndNewChatIdentity() {
        val catalog = catalog()
        val event = catalog.toWebSessionTools(
            connectionId = "browser",
            target = WebUiChatSession.OpenTarget(null, browserSeq = 7, movedByServer = false),
            revision = 9,
        )
        assertEquals("browser", event.connectionId)
        assertNull(event.conversationId)
        assertEquals(7, event.seq)
        assertEquals(9, event.revision)
        assertEquals(catalog.groups.map { it.group.id }, event.groups.map { it.id })
        assertEquals(catalog.enabledGroups.map(SessionToolGroup::id), event.enabledGroups)
        assertEquals(23, event.toolSetTokens)
        val memory = event.groups.first()
        assertEquals("remember", memory.tools.single().name)
        assertEquals("Remember one fact", memory.tools.single().description)
        assertEquals(11, memory.tools.single().tokens)
        assertEquals(12, memory.tokens)
        assertNull(memory.unavailable)

        val unavailable = catalog.copy(
            groups = catalog.groups + group(SessionToolGroup.MCP, SessionToolUnavailable.NO_TOOLS),
        ).toWebSessionTools("browser", target("c"), 10)
        assertEquals("no_tools", unavailable.groups.last().unavailable)
        val encoded = WebUiSync.json.encodeToString(WebSyncEvent.serializer(), event)
        val body = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("session_tools", body.getValue("type").jsonPrimitive.content)
        assertFalse("conversationId" in body)
        assertEquals(event, WebUiSync.json.decodeFromString(WebSyncEvent.serializer(), encoded))
        val failed = null.toWebSessionTools("browser", target("c"), 11, failed = true)
        assertTrue(failed.failed)
        assertTrue(failed.groups.isEmpty())
        assertTrue(failed.enabledGroups.isEmpty())
        assertEquals(0, failed.toolSetTokens)
    }

    @Test
    fun newChatCaptureUsesBrowserOverridesAndWritesOnlyBrowserSettings() = runTest {
        val original = ConversationSettings(
            temperature = 0.4f,
            disabledToolGroups = setOf("legacy", "web", "terminal"),
        )
        val fixture = Fixture(
            initialTarget = WebUiChatSession.OpenTarget(null, 4, false),
            initialNewChatSettings = original,
        )
        fixture.newChatPrompt = "browser-prompt"
        coEvery {
            fixture.generation.captureSessionToolCatalog(any(), any(), any(), any())
        } returns catalog()
        val event = fixture.controller.capture(fixture.command(revision = 1), "browser")
        assertEquals(catalog().groups.map { it.group.id }, event?.groups?.map { it.id })
        coVerify(exactly = 1) {
            fixture.generation.captureSessionToolCatalog(
                conversationId = null,
                modelId = "model",
                systemPromptIdOverride = "browser-prompt",
                conversationSettingsOverride = original,
            )
        }

        val toggled = fixture.controller.toggle(
            fixture.command(type = "session_tool_toggle", revision = 1, toolGroup = "memory", enabled = false),
            "browser",
        )
        assertFalse("memory" in toggled!!.enabledGroups)
        assertEquals(setOf("legacy", "memory"), fixture.newChatSettings?.disabledToolGroups)
        assertEquals(0.4f, fixture.newChatSettings?.temperature)
        assertEquals(ConversationSettings(), fixture.storedSettings)

        coEvery {
            fixture.generation.captureSessionToolCatalog(any(), any(), any(), any())
        } throws IllegalStateException("capture failed")
        val failed = fixture.controller.capture(fixture.command(revision = 2), "browser")
        assertTrue(failed!!.failed)
        assertTrue(failed.groups.isEmpty())
        assertNull(fixture.controller.toggle(
            fixture.command(type = "session_tool_toggle", revision = 2, toolGroup = "skills", enabled = false),
            "browser",
        ))
    }

    @Test
    fun consecutiveAndLinkedTogglesRetainIndependentSettings() = runTest {
        val original = ConversationSettings(
            temperature = 0.8f,
            webSearchEnabled = true,
            shellEnabled = true,
            disabledToolGroups = setOf("legacy", "web", "terminal"),
        )
        val fixture = Fixture(initialSettings = original)
        coEvery {
            fixture.generation.captureSessionToolCatalog(any(), any(), any(), any())
        } returns catalog()
        fixture.controller.capture(fixture.command(revision = 3), "browser")

        val memoryOff = fixture.toggle("memory", false, revision = 3)!!
        val skillsOff = fixture.toggle("skills", false, revision = 3)!!
        assertFalse("memory" in memoryOff.enabledGroups)
        assertFalse("memory" in skillsOff.enabledGroups)
        assertFalse("skills" in skillsOff.enabledGroups)
        assertEquals(
            setOf("legacy", "memory", "skills"),
            fixture.storedSettings.disabledToolGroups,
        )
        assertEquals(0.8f, fixture.storedSettings.temperature)

        fixture.toggle("web", false, revision = 3)
        assertEquals(false, fixture.storedSettings.webSearchEnabled)
        assertEquals(true, fixture.storedSettings.shellEnabled)
        assertEquals(setOf("legacy", "memory", "skills"), fixture.storedSettings.disabledToolGroups)
        fixture.toggle("terminal", false, revision = 3)
        assertEquals(false, fixture.storedSettings.webSearchEnabled)
        assertEquals(false, fixture.storedSettings.shellEnabled)
        val webOn = fixture.toggle("web", true, revision = 3)!!
        assertTrue("web" in webOn.enabledGroups)
        assertFalse("terminal" in webOn.enabledGroups)
        assertEquals(true, fixture.storedSettings.webSearchEnabled)
        assertEquals(false, fixture.storedSettings.shellEnabled)
        assertEquals(setOf("legacy", "memory", "skills"), fixture.storedSettings.disabledToolGroups)
        assertEquals(5, fixture.writes.size)
    }

    @Test
    fun staleIdentityUnavailableGroupsAndCloseCannotWrite() = runTest {
        val fixture = Fixture()
        coEvery {
            fixture.generation.captureSessionToolCatalog(any(), any(), any(), any())
        } returns catalog()
        assertEquals(3L, fixture.controller.capture(fixture.command(revision = 3), "browser")?.revision)
        listOf(
            fixture.command(revision = 3),
            fixture.command(revision = 2),
            fixture.command(revision = 4, seq = 6),
            fixture.command(revision = 4, conversationId = "other"),
        ).forEach { assertNull(fixture.controller.capture(it, "browser")) }
        coVerify(exactly = 1) {
            fixture.generation.captureSessionToolCatalog(any(), any(), any(), any())
        }
        listOf(
            fixture.command(type = "session_tool_toggle", revision = 2, toolGroup = "memory", enabled = false),
            fixture.command(type = "session_tool_toggle", revision = 3, seq = 6, toolGroup = "memory", enabled = false),
            fixture.command(type = "session_tool_toggle", revision = 3, conversationId = "other", toolGroup = "memory", enabled = false),
            fixture.command(type = "session_tool_toggle", revision = 3, toolGroup = "unknown", enabled = false),
        ).forEach { assertNull(fixture.controller.toggle(it, "browser")) }
        assertTrue(fixture.writes.isEmpty())

        fixture.closed = true
        assertNull(fixture.toggle("memory", false, revision = 3))
        assertNull(fixture.controller.capture(fixture.command(revision = 4), "browser"))
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun unavailableGroupCannotBeToggled() = runTest {
        val fixture = Fixture()
        val unavailable = catalog().copy(
            groups = catalog().groups.map {
                if (it.group == SessionToolGroup.MEMORY) it.copy(unavailable = SessionToolUnavailable.LOW_CONTEXT) else it
            },
        )
        coEvery {
            fixture.generation.captureSessionToolCatalog(any(), any(), any(), any())
        } returns unavailable
        fixture.controller.capture(fixture.command(revision = 1), "browser")
        assertNull(fixture.toggle("memory", false, revision = 1))
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun newerRevisionAndTargetChangeInvalidateInFlightCaptures() = runTest {
        val first = Fixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        coEvery {
            first.generation.captureSessionToolCatalog(any(), any(), any(), any())
        } coAnswers {
            calls += 1
            if (calls == 1) {
                started.complete(Unit)
                release.await()
            }
            catalog()
        }
        val stale = async { first.controller.capture(first.command(revision = 1), "browser") }
        started.await()
        val current = first.controller.capture(first.command(revision = 2), "browser")
        release.complete(Unit)
        assertNull(stale.await())
        assertEquals(2L, current?.revision)
        assertTrue(first.controller.isCurrent(current!!))

        val second = Fixture()
        val targetStarted = CompletableDeferred<Unit>()
        val targetRelease = CompletableDeferred<Unit>()
        coEvery {
            second.generation.captureSessionToolCatalog(any(), any(), any(), any())
        } coAnswers {
            targetStarted.complete(Unit)
            targetRelease.await()
            catalog()
        }
        val moved = async { second.controller.capture(second.command(revision = 1), "browser") }
        targetStarted.await()
        second.target.value = WebUiChatSession.OpenTarget("other", 8, false)
        targetRelease.complete(Unit)
        assertNull(moved.await())
    }

    @Test
    fun rapidToggleRepliesStayInConnectionCommandOrder() = runTest {
        val fixture = Fixture()
        coEvery {
            fixture.generation.captureSessionToolCatalog(any(), any(), any(), any())
        } returns catalog()
        val session = mockk<WebUiChatSession> { every { sessionTools } returns fixture.controller }
        val outbound = Channel<WebSyncEvent>(Channel.UNLIMITED)
        val connectionJob = Job()
        dispatchWebUiSessionToolsCommand(
            fixture.command(revision = 1), "browser", session, connectionJob, outbound,
        )
        runCurrent()
        outbound.receive()

        val first = fixture.command(
            type = "session_tool_toggle", revision = 1, toolGroup = "memory", enabled = false,
        )
        dispatchWebUiSessionToolsCommand(first, "browser", session, connectionJob, outbound)
        // No scheduler pass: the ordered connection loop emits this reply before admitting the next.
        val memoryOff = outbound.receive() as WebSyncEvent.SessionTools
        assertFalse("memory" in memoryOff.enabledGroups)
        assertTrue("skills" in memoryOff.enabledGroups)

        val second = first.copy(toolGroup = "skills")
        dispatchWebUiSessionToolsCommand(second, "browser", session, connectionJob, outbound)
        val bothOff = outbound.receive() as WebSyncEvent.SessionTools
        assertFalse("memory" in bothOff.enabledGroups)
        assertFalse("skills" in bothOff.enabledGroups)
        assertTrue(outbound.tryReceive().isFailure)
    }

    @Test
    fun dispatchRoutesCommandsAndDropsWrongConnectionOrStaleEvents() = runTest {
        val tools = mockk<WebUiSessionToolsController>()
        val session = mockk<WebUiChatSession> { every { sessionTools } returns tools }
        val outbound = Channel<WebSyncEvent>(Channel.UNLIMITED)
        val connectionJob = Job()
        val capture = WebSyncCommand(
            type = "session_tools", connectionId = "browser", conversationId = "c", seq = 7, revision = 4,
        )
        val event = catalog().toWebSessionTools("browser", target("c"), 4)
        coEvery { tools.capture(capture, "browser") } returns event
        every { tools.isCurrent(event) } returns true

        dispatchWebUiSessionToolsCommand(
            capture.copy(connectionId = "other"), "browser", session, connectionJob, outbound,
        )
        runCurrent()
        coVerify(exactly = 0) { tools.capture(any(), any()) }
        assertTrue(outbound.tryReceive().isFailure)

        dispatchWebUiSessionToolsCommand(capture, "browser", session, connectionJob, outbound)
        runCurrent()
        assertSame(event, outbound.receive())
        coVerify(exactly = 1) { tools.capture(capture, "browser") }

        val toggle = capture.copy(type = "session_tool_toggle", revision = 5, toolGroup = "memory", enabled = false)
        val stale = event.copy(revision = 5)
        coEvery { tools.toggle(toggle, "browser") } returns stale
        every { tools.isCurrent(stale) } returns false
        dispatchWebUiSessionToolsCommand(toggle, "browser", session, connectionJob, outbound)
        runCurrent()
        coVerify(exactly = 1) { tools.toggle(toggle, "browser") }
        assertTrue(outbound.tryReceive().isFailure)
    }

    private class Fixture(
        initialTarget: WebUiChatSession.OpenTarget = target("c"),
        initialSettings: ConversationSettings = ConversationSettings(),
        initialNewChatSettings: ConversationSettings? = null,
    ) {
        val generation = mockk<MessageGenerationController>()
        val target = MutableStateFlow(initialTarget)
        val activeModel = MutableStateFlow<Pair<String?, String>?>(initialTarget.conversationId to "model")
        var closed = false
        var newChatPrompt: String? = null
        var newChatSettings: ConversationSettings? = initialNewChatSettings
        var storedSettings: ConversationSettings = initialSettings
        val writes = mutableListOf<ConversationSettings>()
        val controller = WebUiSessionToolsController(
            generation = generation,
            ownerMutex = kotlinx.coroutines.sync.Mutex(),
            target = target,
            activeModel = activeModel,
            isClosed = { closed },
            newChatSystemPromptId = { newChatPrompt },
            newChatSettings = { newChatSettings },
            updateSettings = { id, update ->
                val previous = if (id == null) newChatSettings ?: ConversationSettings() else storedSettings
                val updated = update(previous)
                writes += updated
                if (id == null) newChatSettings = updated else storedSettings = updated
            },
        )

        fun command(
            type: String = "session_tools",
            revision: Long,
            seq: Long = target.value.browserSeq,
            conversationId: String? = target.value.conversationId,
            toolGroup: String? = null,
            enabled: Boolean? = null,
        ) = WebSyncCommand(
            type = type,
            connectionId = "browser",
            conversationId = conversationId,
            seq = seq,
            revision = revision,
            toolGroup = toolGroup,
            enabled = enabled,
        )

        suspend fun toggle(group: String, enabled: Boolean, revision: Long) = controller.toggle(
            command(type = "session_tool_toggle", revision = revision, toolGroup = group, enabled = enabled),
            "browser",
        )
    }

    companion object {
        private fun target(id: String?) = WebUiChatSession.OpenTarget(id, 7, false)

        private fun group(
            group: SessionToolGroup,
            unavailable: SessionToolUnavailable? = null,
        ) = SessionToolGroupTools(
            group = group,
            tools = listOf(SessionToolEntry("remember", "Remember one fact", 11)),
            tokens = 12,
            unavailable = unavailable,
        )

        private fun catalog(): SessionToolCatalog {
            val groups = listOf(
                SessionToolGroup.MEMORY,
                SessionToolGroup.SKILLS,
                SessionToolGroup.WEB,
                SessionToolGroup.TERMINAL,
                SessionToolGroup.TODO,
            ).map(::group)
            return SessionToolCatalog(
                groups = groups,
                enabledGroups = groups.mapTo(mutableSetOf()) { it.group },
                storedDisabledIds = setOf("legacy", "web", "terminal"),
                toolSetTokens = 23,
            )
        }
    }
}
