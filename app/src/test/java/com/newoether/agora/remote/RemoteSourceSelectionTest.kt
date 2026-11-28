package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class RemoteSourceSelectionTest : RemoteViewModelFixture() {
    private val desktop = RemoteAdapter("codex-desktop", "codex", "desktop", "connected",
        nativeScopeId = "account-a", capabilities = RemoteCapabilities.legacy)
    private val cli = desktop.copy(id = "codex-cli", origin = "cli")
    private val claude = RemoteAdapter("claude-code", "claude", "cli", "disconnected",
        nativeScopeId = "claude-a", capabilities = RemoteCapabilities(history = true))
    private val claudeDesktop = claude.copy(id = "claude-desktop", origin = "desktop",
        nativeScopeId = "claude-desktop-a", state = "unavailable")
    private var advertised = listOf(desktop, cli, claude, claudeDesktop)
    private val sourceClients = mutableMapOf<String, FiloClient>()

    private fun scoped(source: RemoteAdapter): FiloClient = sourceClients.getOrPut(source.id) {
        mockk<FiloClient>().also { scoped ->
            coEvery { scoped.models() } returns listOf(RemoteModel(source.id, source.id, true))
            coEvery { scoped.sessions(any()) } returns RemoteSessionPage(listOf(row(source)), null)
            coEvery { scoped.conversation(any(), any()) } returns bodyPage(emptyList(), null, emptyList(),
                RemoteRuntime("idle", model = source.id)).copy(access = row(source).access)
            every { scoped.events(any()) } answers { flow {
                emit(scoped.conversation("session"))
                awaitCancellation()
            } }
        }
    }
    private fun row(source: RemoteAdapter, scope: String = source.nativeScopeId) = session.copy(
        access = RemoteSessionAccess(scope, "shared-canonical", "${source.id}-binding", source.capabilities))

    private fun TestScope.open(): RemoteViewModel {
        every { client.adapters } answers { advertised }
        every { client.scoped(any()) } answers { scoped(advertised.first { it.id == firstArg<RemoteAccessPath>().adapterId }) }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        runCurrent()
        saveAndSelect(vm)
        vm.setVisible(true)
        runCurrent()
        return vm
    }

    @Test fun fourInstalledSourcesSurviveFailedObservationWithoutSwitchingOrNativeLaunch() = runTest(dispatcher) {
        advertised = listOf(desktop, cli.copy(state = "connecting", capabilities = RemoteCapabilities(history = true)),
            claude, claudeDesktop)
        val installed = advertised
        val vm = open()
        assertEquals(installed, vm.state.value.devices.single().adapters)
        assertEquals(listOf(RemoteDeviceStatus.CONNECTED, RemoteDeviceStatus.CONNECTING,
            RemoteDeviceStatus.IDLE, RemoteDeviceStatus.ERROR), vm.state.value.devices.single().adapters.map { it.status })
        for (source in installed.drop(1)) {
            vm.selectAdapter(source.id)
            runCurrent()
            assertEquals(source.id, vm.state.value.selectedAdapter.id)
            assertFalse(vm.state.value.canCreate)
            assertFalse(vm.state.value.canSend)
            assertFalse(vm.state.value.canStop)
        }
        vm.selectSession(vm.state.value.sessions.single())
        runCurrent()
        assertEquals(claudeDesktop.id, vm.state.value.selectedAdapter.id)
        assertFalse(vm.state.value.canCreate)
        assertFalse(vm.state.value.canSend)
        assertFalse(vm.state.value.canStop)
        val loaded = vm.state.value.sessions
        coEvery { client.connect() } throws java.io.IOException("offline")
        advanceTimeBy(3000)
        runCurrent()
        val offline = vm.state.value
        assertEquals(installed.map { it.copy(state = "disconnected") }, offline.devices.single().adapters)
        assertEquals(RemoteDeviceStatus.CONNECTED, offline.devices.single().status)
        assertEquals(claudeDesktop.id, offline.selectedAdapter.id)
        assertEquals(loaded, offline.sessions)
        assertNotNull(offline.session)
        coEvery { client.connect() } returns RemoteDeviceStatus.CONNECTED
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(installed, vm.state.value.devices.single().adapters)
        assertEquals(claudeDesktop.id, vm.state.value.selectedAdapter.id)
        for (native in listOf(client) + sourceClients.values) {
            coVerify(exactly = 0) { native.create(any(), any(), any(), any()) }
            coVerify(exactly = 0) { native.send(any(), any(), any(), any()) }
            coVerify(exactly = 0) { native.stop(any(), any()) }
        }
        io.mockk.verify(exactly = 0) { scoped(claudeDesktop).events(any()) }
        vm.setVisible(false)
    }

    @Test fun offlineSelectionIsPreservedAndFreshRemovalNeverFallsBack() = runTest(dispatcher) {
        val vm = open()
        assertEquals(desktop.id, vm.state.value.selectedAdapter.id)
        vm.selectAdapter(claude.id)
        runCurrent()
        assertEquals(claude.id, vm.state.value.selectedAdapter.id)
        assertFalse(vm.state.value.canCreate)
        assertEquals(listOf("session"), vm.state.value.sessions.map { it.id })
        assertTrue(vm.state.value.models.isEmpty())
        coVerify(exactly = 0) { scoped(claude).models() }
        advertised = listOf(desktop, cli)
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(claude.id, vm.state.value.selectedAdapter.id)
        assertEquals("unavailable", vm.state.value.selectedAdapter.state)
        assertTrue(vm.state.value.devices.single().adapters.none { it.id == claude.id })
        assertTrue(vm.state.value.sessions.isEmpty())
        assertFalse(vm.state.value.canRead)
        vm.setVisible(false)
    }

    @Test fun switchingSourcesRejectsOldCatalogAndModelsAndReusesVerifiedDraftOnly() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<RemoteModel>>()
        val history = CompletableDeferred<RemoteSessionPage>()
        val desktopClient = scoped(desktop)
        coEvery { desktopClient.models() } coAnswers { withContext(NonCancellable) { gate.await() } }
        coEvery { desktopClient.sessions(any()) } coAnswers { withContext(NonCancellable) { history.await() } }
        val vm = open()
        vm.selectAdapter(cli.id)
        runCurrent()
        assertEquals(cli.id, vm.state.value.models.single().id)
        vm.selectSession(vm.state.value.sessions.single())
        runCurrent()
        val owner = vm.state.value.owner!!
        val access = vm.state.value.payloadOwner
        vm.editDraft(owner, "canonical draft")
        gate.complete(listOf(RemoteModel("stale", "Stale", true)))
        history.complete(RemoteSessionPage(listOf(session.copy(id = "stale")), null))
        runCurrent()
        assertEquals(cli.id, vm.state.value.models.single().id)
        assertEquals("session", vm.state.value.session?.id)
        coEvery { scoped(desktop).sessions(any()) } returns RemoteSessionPage(listOf(row(desktop)), null)
        vm.selectSession(null)
        vm.selectAdapter(desktop.id)
        runCurrent()
        vm.selectSession(vm.state.value.sessions.single())
        runCurrent()
        assertEquals(owner, vm.state.value.owner)
        assertEquals("canonical draft", vm.state.value.drafts[owner])
        assertNotEquals(access, vm.state.value.payloadOwner)
        vm.setVisible(false)
    }

    @Test fun freshInfoAccountSwapDropsOldAccessRuntimeAndModelsBeforeReread() = runTest(dispatcher) {
        val vm = open()
        vm.selectSession(vm.state.value.sessions.single())
        runCurrent()
        assertEquals("account-a", vm.state.value.session?.access?.nativeScopeId)
        advertised = listOf(desktop.copy(nativeScopeId = "account-b"), cli, claude)
        coEvery { scoped(desktop).sessions(any()) } returns RemoteSessionPage(listOf(row(desktop, "account-b")), null)
        val models = CompletableDeferred<List<RemoteModel>>()
        coEvery { scoped(desktop).models() } coAnswers { models.await() }
        advanceTimeBy(3000)
        runCurrent()
        assertNull(vm.state.value.session)
        assertNull(vm.state.value.runtime)
        assertEquals("account-b", vm.state.value.selectedAdapter.nativeScopeId)
        assertTrue(vm.state.value.models.isEmpty())
        assertEquals("account-b", vm.state.value.sessions.single().access?.nativeScopeId)
        models.complete(emptyList())
        runCurrent()
        vm.setVisible(false)
    }

    @Test fun automaticModelFailuresStayQuietAndExplicitRetriesStillReport() = runTest(dispatcher) {
        val unavailable = claude.copy(state = "unavailable", capabilities = RemoteCapabilities(
            history = true, models = true, create = true, settings = listOf("model")))
        advertised = listOf(desktop, cli, unavailable, claudeDesktop)
        val unavailableClient = scoped(unavailable)
        coEvery { unavailableClient.models() } throws FiloHttpException(503)
        val vm = open()
        val notices = mutableListOf<RemoteNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        vm.selectAdapter(unavailable.id)
        runCurrent()
        repeat(3) { vm.refresh(); runCurrent() }
        assertEquals(unavailable.id, vm.state.value.selectedAdapter.id)
        assertEquals("unavailable", vm.state.value.selectedAdapter.state)
        assertEquals(RemoteDeviceStatus.CONNECTED, vm.state.value.devices.single().status)
        assertEquals(listOf("session"), vm.state.value.sessions.map { it.id })
        assertTrue(vm.state.value.models.isEmpty())
        assertFalse(vm.state.value.modelsLoading)
        assertTrue(notices.none { it.stage == "models_failed" })
        vm.refreshModels()
        runCurrent()
        assertEquals(1, notices.count { it.stage == "models_failed" })
        coEvery { unavailableClient.models() } returns listOf(RemoteModel("native", "Native", true))
        vm.refreshModels()
        runCurrent()
        assertEquals("native", vm.state.value.models.single().id)
        vm.newSession()
        runCurrent()
        assertFalse(vm.state.value.isDraft)
        assertEquals("native", vm.state.value.models.single().id)
        assertFalse(vm.state.value.canCreate)
        assertEquals(1, notices.count { it.stage == "models_failed" })
        coVerify(exactly = 0) { unavailableClient.create(any(), any(), any(), any()) }
        coVerify(exactly = 0) { unavailableClient.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun readonlyHistoryNeverStartsAnEventSubscriptionOrBorrowsModelCapabilities() = runTest(dispatcher) {
        val vm = open()
        vm.selectAdapter(claude.id)
        runCurrent()
        vm.selectSession(vm.state.value.sessions.single())
        runCurrent()
        coVerify(exactly = 1) { scoped(claude).conversation("session", null) }
        io.mockk.verify(exactly = 0) { scoped(claude).events(any()) }
        assertFalse(vm.state.value.canSend)
        assertFalse(vm.state.value.canStop)
        assertFalse(vm.state.value.canChooseModel)
        vm.setVisible(false)
    }
}
