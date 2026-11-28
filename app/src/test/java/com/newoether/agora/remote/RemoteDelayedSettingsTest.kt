package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class RemoteDelayedSettingsTest : RemoteViewModelFixture() {
    private val caps = RemoteCapabilities(history = true, live = true, idleSend = true,
        activeSend = "native-steer", stop = true, settings = listOf("effort"))
    private var source = RemoteAdapter("claude-desktop", "claude", "desktop", "connected",
        nativeScopeId = "scope", capabilities = caps)
    private val nativeSession = session.copy(access = RemoteSessionAccess("scope", "canonical", "binding", caps))
    private val updates = MutableSharedFlow<RemoteConversationPage>(extraBufferCapacity = 8)
    private var runtime = RemoteRuntime("idle", model = "actual-model", effort = "high")
    private val pending = RemoteSettingsChange("change", "pending", RemoteSettings(effort = "low"))
    private fun page(id: String = nativeSession.id) = bodyPage(emptyList(), null, emptyList(), runtime).copy(
        access = nativeSession.access?.let { access -> if (id == nativeSession.id) access else access.copy(
            canonicalSessionRef = "another-canonical", binding = "another-binding") })

    private fun TestScope.open(): Pair<RemoteViewModel, MutableList<RemoteNotice>> {
        every { client.adapters } returns listOf(source)
        every { client.scoped(any()) } returns client
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(nativeSession), null)
        coEvery { client.conversation(any(), any()) } answers { page(firstArg()) }
        every { client.events(any()) } answers {
            val id = firstArg<String>()
            flow {
                emit(page(id))
                updates.collect { emit(it) }
            }
        }
        coEvery { client.updateSettings(any(), any()) } answers {
            runtime = runtime.copy(settingsChange = pending)
        }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        runCurrent()
        saveAndSelect(vm)
        vm.setVisible(true)
        runCurrent()
        vm.selectSession(nativeSession)
        runCurrent()
        val notices = mutableListOf<RemoteNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        return vm to notices
    }

    @Test fun unknownDesktopEffortDispatchesAndKeepsPendingSelectionWithoutCatalog() = runTest(dispatcher) {
        assertUnknownEffortDispatch()
    }

    @Test fun unknownTerminalEffortDispatchesAndKeepsPendingSelectionWithoutCatalog() = runTest(dispatcher) {
        source = source.copy(id = "claude-code", origin = "cli")
        assertUnknownEffortDispatch()
    }

    private fun TestScope.assertUnknownEffortDispatch() {
        runtime = RemoteRuntime("unknown", model = "claude-sonnet-5")
        coEvery { client.models() } returns emptyList()
        val (vm, notices) = open()
        assertTrue(vm.state.value.canEditSettings)
        assertNull(vm.state.value.selectedEffort)
        vm.setThinkingLevel("low")
        runCurrent()
        coVerify(exactly = 1) { client.updateSettings("session", RemoteSettings(effort = "low")) }
        assertEquals(pending, vm.state.value.runtime?.settingsChange)
        assertEquals("low", vm.state.value.selectedEffort)
        assertFalse(vm.state.value.settingsPending)
        assertTrue(notices.isEmpty())
        vm.refresh()
        runCurrent()
        assertEquals("low", vm.state.value.selectedEffort)
        coVerify(exactly = 1) { client.updateSettings(any(), any()) }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun pendingThenFailedNotifiesOnceThroughTheExistingNoticeAndKeepsActualValues() = runTest(dispatcher) {
        val (vm, notices) = open()
        assertTrue(vm.state.value.models.isEmpty())
        vm.setThinkingLevel("low")
        runCurrent()
        assertEquals(pending, vm.state.value.runtime?.settingsChange)
        assertFalse(vm.state.value.settingsPending)
        assertTrue(notices.isEmpty())
        runtime = runtime.copy(settingsChange = pending.copy(state = "failed", actualModel = "actual-model", actualEffort = "high"))
        assertTrue(updates.tryEmit(page()))
        runCurrent()
        assertEquals("high", vm.state.value.selectedEffort)
        assertEquals("actual-model", vm.state.value.selectedModel)
        assertEquals("control_failed", notices.single().stage)
        assertEquals("settings_unavailable", notices.single().code)
        assertNull(notices.single().detail)
        assertTrue(updates.tryEmit(page()))
        runCurrent()
        vm.refresh()
        runCurrent()
        assertEquals(1, notices.size)
        coVerify(exactly = 1) { client.updateSettings("session", RemoteSettings(effort = "low")) }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun confirmedSettingsAndUnrelatedFailedChangesDoNotNotify() = runTest(dispatcher) {
        val (vm, notices) = open()
        vm.setThinkingLevel("low")
        runCurrent()
        runtime = runtime.copy(settingsChange = pending.copy(id = "unrelated", state = "failed"))
        assertTrue(updates.tryEmit(page()))
        runCurrent()
        assertTrue(notices.isEmpty())
        runtime = runtime.copy(effort = "low", settingsChange = pending.copy(state = "confirmed"))
        assertTrue(updates.tryEmit(page()))
        runCurrent()
        assertEquals("low", vm.state.value.selectedEffort)
        runtime = runtime.copy(settingsChange = pending.copy(state = "failed"))
        assertTrue(updates.tryEmit(page()))
        runCurrent()
        assertTrue(notices.isEmpty())
        vm.setVisible(false)
    }

    @Test fun anotherSessionCannotReceiveThePreviousOwnersDelayedFailure() = runTest(dispatcher) {
        val (vm, notices) = open()
        vm.setThinkingLevel("low")
        runCurrent()
        vm.selectSession(nativeSession.copy(id = "another", access = nativeSession.access?.copy(
            canonicalSessionRef = "another-canonical", binding = "another-binding")))
        runCurrent()
        runtime = runtime.copy(settingsChange = pending.copy(state = "failed"))
        assertTrue(updates.tryEmit(page("another")))
        runCurrent()
        assertEquals("another", vm.state.value.session?.id)
        assertTrue(notices.isEmpty())
        coVerify(exactly = 1) { client.updateSettings(any(), any()) }
        vm.setVisible(false)
    }
}
