package com.newoether.agora.remote

import com.newoether.agora.model.ToolImageAttachment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSourceIsolationTest {
    private val caps = RemoteCapabilities.legacy
    private val desktop = RemoteAdapter("codex-desktop", "codex", "desktop", "connected",
        nativeScopeId = "scope", capabilities = caps)
    private val cli = desktop.copy(id = "codex-cli", origin = "cli")
    private val model = RemoteModel("model", "Model", true, listOf("low", "high", "ultra"), "low")
    private fun snapshot(source: RemoteAdapter = desktop, binding: String = "binding-a"): RemoteState = RemoteState(
        devices = listOf(RemoteDevice("device", "Computer", "address", RemoteDeviceStatus.CONNECTED,
            adapters = listOf(desktop, cli), multiAdapter = true)), deviceId = "device",
        adapterSelections = mapOf("device" to source.id), restoring = false,
        session = RemoteSession("session", "Task", "", 1, access = RemoteSessionAccess("scope", "canonical", binding, caps)),
        runtime = RemoteRuntime("active", "turn", "model", effort = "low"), models = listOf(model), hydrationEnabled = true,
    )

    @Test fun settingsMergeOnlyOnOneBindingAndRejectOtherCanonicalAccessWhilePending() = runTest {
        val state = MutableStateFlow(snapshot())
        val connection = mockk<FiloClient>()
        val native = mockk<FiloClient>()
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<RemoteSettings>()
        var runtime = state.value.runtime!!
        coEvery { native.updateSettings(any(), any()) } coAnswers {
            val settings = secondArg<RemoteSettings>()
            writes += settings
            if (writes.size == 1) gate.await()
            runtime = runtime.copy(effort = settings.effort)
        }
        coEvery { native.conversation(any(), any()) } answers {
            bodyPage(emptyList(), null, emptyList(), runtime)
        }
        val mutations = RemoteSettingsMutation(backgroundScope, state, { native }, { connection }, { _, _ -> true },
            { 1 }, { 1 }, { _, _, _, _ -> }, {}, { null })
        mutations.setThinkingLevel("high")
        runCurrent()
        mutations.setThinkingLevel("ultra")
        assertEquals("ultra", state.value.selectedEffort)
        val admitted = state.value
        state.value = admitted.copy(adapterSelections = mapOf("device" to cli.id))
        assertTrue(mutations.hasConflict(state.value))
        mutations.setThinkingLevel("high")
        assertNull(state.value.optimisticSettings[state.value.settingsKey])
        val admittedSession = requireNotNull(admitted.session)
        state.value = admitted.copy(session = admittedSession.copy(
            access = requireNotNull(admittedSession.access).copy(binding = "binding-b")))
        assertTrue(mutations.hasConflict(state.value))
        mutations.setThinkingLevel("high")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("high", "ultra"), writes.map { it.effort })
        assertTrue(state.value.optimisticSettings.isEmpty())
        assertFalse(mutations.hasConflict(state.value))
        coVerify(exactly = 2) { native.updateSettings("session", any()) }
    }

    @Test fun stoppedCanonicalTaskKeepsBarrierUntilOriginalSourceReportsEndEvenAfterNavigation() = runTest {
        val state = MutableStateFlow(snapshot())
        val client = mockk<FiloClient>()
        var selected = 1L
        var stopCalls = 0
        val control = RemoteControl(backgroundScope, state, { client }, { _, _ -> true }, { false }, { selected }, { null })
        control.run(stoppingTurnId = "turn") { stopCalls++ }
        runCurrent()
        assertEquals(1, stopCalls)
        assertTrue(state.value.controlPending)
        val admitted = state.value
        selected++
        state.value = admitted.copy(adapterSelections = mapOf("device" to cli.id), controlling = false,
            stoppingOwner = null, stoppingTurnId = null)
        control.settle(RemoteRuntime("idle", completedTurnId = "turn"))
        assertTrue(state.value.controlPending)
        control.run { fail("An alternate path must not bypass the pending Stop") }
        state.value = state.value.copy(adapterSelections = mapOf("device" to desktop.id))
        control.settle(RemoteRuntime("active", "turn"))
        assertTrue(state.value.isStopping)
        control.settle(RemoteRuntime("idle", completedTurnId = "turn"))
        assertFalse(state.value.controlPending)
        assertFalse(state.value.isStopping)
    }

    @Test fun sharedCanonicalTextCacheNeverReturnsAnotherSourcesBodyAndLateImageIsCancelled() = runTest {
        val node = RemoteMessageNode("answer", "turn", null, "assistant", 1, "revision", 4, groupId = "group")
        val groups = projectRemoteTopology(listOf(node), RemoteRuntime("idle"))
        val state = MutableStateFlow(snapshot().copy(messageGroups = groups))
        val image = CompletableDeferred<ToolImageAttachment>()
        val hydration = RemoteMessageHydration(state, { _, _ -> error("Unexpected read") }, { throw it },
            { _, _ -> image.await() }, projectionDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val owner = state.value.owner
        val original = state.value.payloadOwner!!
        val page = RemoteConversationPage(listOf(RemoteMessage("answer", "turn", null, "assistant", "body", 1,
            groupId = "group")), null, emptyList(), nodes = listOf(node))
        hydration.accept(original, page, groups, live = false)
        assertEquals("body", hydration.cachedMessage(original, groups.single())?.text)
        state.value = state.value.copy(adapterSelections = mapOf("device" to cli.id))
        assertEquals(owner, state.value.owner)
        assertNotEquals(original, state.value.payloadOwner)
        assertNull(hydration.cachedMessage(state.value.payloadOwner!!, groups.single()))
        val imageNode = node.copy(activity = RemoteNodeActivity("tool", hasImage = true))
        state.value = snapshot().copy(messageGroups = projectRemoteTopology(listOf(imageNode), RemoteRuntime("idle")))
        val loading = async { hydration.loadToolImage(original, "answer", "revision") }
        runCurrent()
        state.value = state.value.copy(adapterSelections = mapOf("device" to cli.id))
        image.complete(ToolImageAttachment("unused", "image/png", 1, 1, 1))
        runCurrent()
        assertTrue(loading.isCancelled)
    }

    @Test fun wrappedBindingAndClaudeRunningRemainValidWithoutGrantingUnsupportedSteer() {
        RemoteAccessPath("claude-code", "x".repeat(5000), "scope")
        val state = snapshot().copy(runtime = RemoteRuntime("running", "native-turn"))
        assertTrue(state.runtime!!.isRunning)
        assertTrue(state.canStop)
        val readonly = state.copy(devices = listOf(state.devices.single().copy(adapters = listOf(
            desktop.copy(capabilities = RemoteCapabilities(history = true))))))
        assertFalse(readonly.canSend)
        assertFalse(readonly.canStop)
        assertFalse(readonly.canChooseModel)
    }
}
