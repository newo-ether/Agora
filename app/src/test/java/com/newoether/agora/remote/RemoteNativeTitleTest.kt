package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class RemoteNativeTitleTest : RemoteViewModelFixture() {
    @Test fun liveTitleUpdatesSelectedSessionAndCatalogWithoutRefresh() = runTest(dispatcher) {
        val events = MutableSharedFlow<RemoteConversationPage>()
        every { client.events(any()) } returns events
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        runCurrent()
        saveAndSelect(vm)
        vm.setVisible(true); runCurrent()
        vm.selectSession(session); runCurrent()
        val page = bodyPage(emptyList(), null, emptyList(), RemoteRuntime("active", "turn", title = "Generated title"))
        events.emit(page); runCurrent()
        assertEquals("Generated title", vm.state.value.session?.title)
        assertEquals("Generated title", vm.state.value.sessions.single().title)
        events.emit(page.copy(runtime = RemoteRuntime("idle", title = "Renamed natively"))); runCurrent()
        assertEquals("Renamed natively", vm.state.value.session?.title)
        assertEquals("Renamed natively", vm.state.value.sessions.single().title)
        events.emit(page.copy(runtime = RemoteRuntime("idle"))); runCurrent()
        assertEquals("Renamed natively", vm.state.value.session?.title)
        vm.setVisible(false)
    }

    @Test fun listPollingRefreshesNativeTitle() = runTest(dispatcher) {
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        runCurrent(); saveAndSelect(vm)
        vm.setVisible(true); runCurrent()
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(session.copy(title = "New name")), null)
        vm.observeSessions("http://computer/", listOf(session.id))
        advanceTimeBy(3000); runCurrent()
        assertEquals("New name", vm.state.value.sessions.single().title)
        vm.setVisible(false)
    }
}
