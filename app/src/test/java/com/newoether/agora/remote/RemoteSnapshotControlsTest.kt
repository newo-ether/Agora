package com.newoether.agora.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
internal class RemoteSnapshotControlsTest : RemoteViewModelFixture() {
    private val caps = RemoteCapabilities.legacy.copy(create = false)
    private val source = RemoteAdapter("codex-desktop", "codex", "desktop", "connected",
        nativeScopeId = "scope", capabilities = caps)
    private val readCaps = RemoteCapabilities(history = true, models = true)
    private val catalogSession = session.copy(access = RemoteSessionAccess("scope", "canonical", "catalog", readCaps))
    private val idleCaps = readCaps.copy(idleSend = true, settings = listOf("model", "effort", "serviceTier"))
    private val model = RemoteModel("gpt-6.1-sol", "GPT-6.1-Sol", true, listOf("low", "high"), "high",
        listOf(RemoteServiceTier("priority", "Priority")))
    private var runtime = RemoteRuntime("idle", model = model.id, effort = "low", serviceTier = "default",
        serviceTierKnown = true)

    private fun page(grant: RemoteCapabilities? = idleCaps) = bodyPage(emptyList(), null, emptyList(), runtime).copy(
        access = grant?.let { RemoteSessionAccess("scope", "canonical", "fresh", it) })

    private fun TestScope.open(select: Boolean = true): RemoteViewModel {
        every { client.adapters } returns listOf(source)
        every { client.scoped(any()) } returns client
        coEvery { client.models() } returns listOf(model)
        coEvery { client.sessions(any()) } returns RemoteSessionPage(listOf(catalogSession), null)
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        runCurrent()
        saveAndSelect(vm)
        vm.setVisible(true)
        runCurrent()
        if (select) {
            vm.selectSession(vm.state.value.sessions.single())
            runCurrent()
        }
        return vm
    }

    private fun assertNoExecution() {
        coVerify(exactly = 0) { client.create(any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.stop(any(), any()) }
    }

    @Test fun explicitIdleSettingsGrantPreservesRuntimeAndAllowsChangesWithoutSSE() = runTest(dispatcher) {
        coEvery { client.conversation(any(), any()) } answers { page() }
        coEvery { client.updateSettings(any(), any()) } answers {
            val patch = secondArg<RemoteSettings>()
            runtime = runtime.copy(effort = patch.effort ?: runtime.effort,
                serviceTier = if (patch.updateServiceTier) patch.serviceTier ?: "default" else runtime.serviceTier)
        }
        val vm = open()
        assertEquals(runtime, vm.state.value.runtime)
        assertEquals("low", vm.state.value.selectedEffort)
        assertTrue(vm.state.value.serviceTierKnown)
        assertNull(vm.state.value.selectedServiceTier)
        assertTrue(vm.state.value.canEditSettings)
        assertTrue(vm.state.value.canSend)
        assertFalse(vm.state.value.capabilities.live)
        assertEquals(listOf("priority"), vm.state.value.settingsModel?.serviceTiers?.map { it.id })
        coVerify(exactly = 0) { client.updateSettings(any(), any()) }
        vm.setThinkingLevel("high")
        runCurrent()
        assertEquals("high", vm.state.value.selectedEffort)
        vm.setServiceTier("priority")
        runCurrent()
        assertEquals("priority", vm.state.value.selectedServiceTier)
        assertEquals(runtime, vm.state.value.runtime)
        coVerify(exactly = 1) { client.updateSettings("session", RemoteSettings(effort = "high")) }
        coVerify(exactly = 1) { client.updateSettings("session", RemoteSettings(serviceTier = "priority", updateServiceTier = true)) }
        advanceTimeBy(6000)
        runCurrent()
        verify(exactly = 0) { client.events(any()) }
        assertNoExecution()
        vm.setVisible(false)
    }

    @Test fun readonlyAndMissingDetailedGrantsCannotAdmitControl() = runTest(dispatcher) {
        val vm = open(select = false)
        for (grant in listOf(readCaps, null)) {
            coEvery { client.conversation(any(), any()) } returns page(grant)
            vm.selectSession(catalogSession)
            runCurrent()
            assertNull(vm.state.value.runtime)
            assertEquals(model.id, vm.state.value.selectedModel)
            assertFalse(vm.state.value.canEditSettings)
            assertFalse(vm.state.value.canSend)
            assertFalse(vm.state.value.canStop)
            vm.setThinkingLevel("high")
            vm.setServiceTier("priority")
            runCurrent()
            coVerify(exactly = 0) { client.updateSettings(any(), any()) }
            vm.selectSession(null)
            runCurrent()
        }
        verify(exactly = 0) { client.events(any()) }
        assertNoExecution()
        vm.setVisible(false)
    }

    @Test fun detailedLiveGrantUpgradesTheCatalogReadIntoTheExistingSubscription() = runTest(dispatcher) {
        coEvery { client.conversation(any(), any()) } returns page(idleCaps.copy(live = true))
        every { client.events(any()) } returns flow {
            emit(page(idleCaps.copy(live = true)).copy(runtime = runtime.copy(effort = "high")))
            awaitCancellation()
        }
        val vm = open()
        assertEquals("high", vm.state.value.selectedEffort)
        assertTrue(vm.state.value.capabilities.live)
        assertTrue(vm.state.value.canEditSettings)
        verify(exactly = 1) { client.events("session") }
        coVerify(exactly = 1) { client.conversation("session", null) }
        assertNoExecution()
        vm.setVisible(false)
    }

    @Test fun failedSubscriptionFallbackNeverRestoresControlEvenWithANewIdleGrant() = runTest(dispatcher) {
        coEvery { client.conversation(any(), any()) } returns page(idleCaps.copy(live = true))
        every { client.events(any()) } returns flow { throw IOException("stream unavailable") }
        val vm = open()
        assertNull(vm.state.value.runtime)
        assertFalse(vm.state.value.canEditSettings)
        coEvery { client.conversation(any(), any()) } returns page()
        advanceTimeBy(3000)
        runCurrent()
        assertFalse(vm.state.value.capabilities.live)
        assertNull(vm.state.value.runtime)
        assertFalse(vm.state.value.canEditSettings)
        assertEquals(model.id, vm.state.value.selectedModel)
        verify(exactly = 2) { client.events("session") }
        assertNoExecution()
        vm.setVisible(false)
    }

    @Test fun invalidDetailedIdentityCannotAdmitRuntimeOrSettings() = runTest(dispatcher) {
        val vm = open(select = false)
        for (access in listOf(
            page().access!!.copy(nativeScopeId = "another-scope"),
            page().access!!.copy(canonicalSessionRef = "another-session"),
            page().access!!.copy(binding = ""),
        )) {
            coEvery { client.conversation(any(), any()) } returns page().copy(access = access)
            vm.selectSession(catalogSession)
            runCurrent()
            assertNull(vm.state.value.runtime)
            assertFalse(vm.state.value.canEditSettings)
            assertEquals(RemoteFailure.PROTOCOL, vm.state.value.failure)
            vm.selectSession(null)
            runCurrent()
        }
        verify(exactly = 0) { client.events(any()) }
        coVerify(exactly = 0) { client.updateSettings(any(), any()) }
        assertNoExecution()
        vm.setVisible(false)
    }

    @Test fun lateDetailedGrantCannotEnableAnotherSelectedSession() = runTest(dispatcher) {
        val delayed = CompletableDeferred<RemoteConversationPage>()
        coEvery { client.conversation("session", null) } coAnswers {
            withContext(NonCancellable) { delayed.await() }
        }
        val vm = open()
        val another = catalogSession.copy(id = "another", access = catalogSession.access!!.copy(
            canonicalSessionRef = "another-canonical", binding = "another-catalog"))
        coEvery { client.conversation("another", null) } returns page(readCaps).copy(access = another.access)
        vm.selectSession(another)
        runCurrent()
        delayed.complete(page(idleCaps.copy(live = true)))
        runCurrent()
        assertEquals("another", vm.state.value.session?.id)
        assertEquals("another-canonical", vm.state.value.session?.access?.canonicalSessionRef)
        assertNull(vm.state.value.runtime)
        assertFalse(vm.state.value.canEditSettings)
        verify(exactly = 0) { client.events(any()) }
        assertNoExecution()
        vm.setVisible(false)
    }
}
