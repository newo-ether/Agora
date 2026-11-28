package com.newoether.agora.remote

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionCatalogTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<FiloClient>(relaxed = true)
    private val store = mockk<RemoteConnectionStore>(relaxed = true)
    private fun row(id: String, cursor: String? = null) = RemoteSession(id, id, "/fixture", 1, listCursor = cursor)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { store.load() } returns emptyList()
        every { client.adapters } returns null
        every { client.address } returns "http://fixture/"
        coEvery { client.connect() } returns RemoteDeviceStatus.CONNECTED
        coEvery { client.models() } returns emptyList()
        coEvery { client.sessions(null) } returns RemoteSessionPage(listOf(row("first")), "next")
        coEvery { client.sessions("next") } returns RemoteSessionPage(listOf(row("later", "next")), null)
        every { client.events(any()) } returns flow { awaitCancellation() }
    }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun TestScope.open(): RemoteViewModel {
        val vm = RemoteViewModel(store, projectionDispatcher = dispatcher) { _, _ -> client }
        runCurrent()
        vm.setVisible(true)
        vm.saveDevice("http://fixture/", "fixture-token"); runCurrent()
        vm.selectDevice("http://fixture/"); runCurrent()
        return vm
    }

    @Test fun returnUsesAllCachedPagesImmediatelyAndRefreshesWithoutTruncating() = runTest(dispatcher) {
        val vm = open()
        vm.loadMore(); runCurrent()
        val cached = vm.state.value.sessions
        assertEquals(listOf("first", "later"), cached.map { it.id })
        vm.selectSession(cached.last()); runCurrent()
        val pending = CompletableDeferred<RemoteSessionPage>()
        coEvery { client.sessions(null) } coAnswers { pending.await() }
        vm.selectSession(null)
        assertEquals(cached, vm.state.value.sessions)
        runCurrent()
        assertTrue(vm.state.value.loading)
        assertEquals(cached, vm.state.value.sessions)
        pending.complete(RemoteSessionPage(listOf(row("fresh")), "next")); runCurrent()
        assertEquals(listOf("fresh", "later"), vm.state.value.sessions.map { it.id })
        assertNull(vm.state.value.sessionCursor)
        assertFalse(vm.state.value.loading)
        vm.setVisible(false)
    }

    @Test fun nativeArchiveDisappearsFromVisiblePageWithoutErasingOtherPages() = runTest(dispatcher) {
        val vm = open()
        vm.loadMore(); runCurrent()
        coEvery { client.sessions(null) } returns RemoteSessionPage(emptyList(), "next")
        vm.observeSessions("http://fixture/", listOf("first"))
        runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(listOf("later"), vm.state.value.sessions.map { it.id })
        vm.setVisible(false)
    }

    @Test fun failedBackgroundRefreshRetainsEntireCachedCatalog() = runTest(dispatcher) {
        val vm = open()
        vm.loadMore(); runCurrent()
        val cached = vm.state.value.sessions
        vm.selectSession(cached.first()); runCurrent()
        coEvery { client.sessions("next") } throws FiloHttpException(503, "unavailable")
        vm.selectSession(null); runCurrent()
        assertEquals(cached, vm.state.value.sessions)
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.failure)
        vm.setVisible(false)
    }

    @Test fun repeatedCursorFailsInsteadOfPublishingPartialRefresh() = runTest(dispatcher) {
        val cached = listOf(row("first"), row("later", "next"))
        coEvery { client.sessions("next") } returns RemoteSessionPage(listOf(row("later", "next")), "next")
        try {
            client.refreshSessionCatalog(cached)
            fail("Repeated cursor accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun pageReplacementUpdatesMembershipAndAvoidsDuplicates() {
        val cached = listOf(row("archived"), row("moved", "next"), row("later", "next"))
        val page = RemoteSessionPage(listOf(row("moved"), row("new")), "next")
        assertEquals(listOf("moved", "new", "later"), cached.replaceCatalogPage(null, page).map { it.id })
    }
}
