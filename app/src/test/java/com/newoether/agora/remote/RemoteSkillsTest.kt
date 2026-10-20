package com.newoether.agora.remote

import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class RemoteSkillsTest : RemoteViewModelFixture() {
    private val skills = listOf(RemoteSkill("clinical-osce", "Practice a focused clinical scenario", "/skill:clinical-osce"))

    @Test fun duplicateNamesCommandsAndUnsafeNativeInputsAreOmitted() {
        val source = skills + listOf(skills.single().copy(name = "Clinical OSCE.md", command = "/osce"),
            skills.single().copy(name = "different"), RemoteSkill("bad", "", "/osce;rm"),
            RemoteSkill("bad-arguments", "", "/osce start"))
        assertEquals(skills, distinctRemoteSkills(source))
        assertEquals(512, distinctRemoteSkills((0..600).map { RemoteSkill("s$it", "", "/s$it") }).size)
    }

    @Test fun sessionSwitchHideAndDeviceDisconnectRevokeTheNativeCatalog() = runTest(dispatcher) {
        coEvery { client.conversation("session", any()) } returns bodyPage(emptyList(), null, emptyList(), RemoteRuntime("idle", skills = skills))
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        saveAndSelect(vm); vm.setVisible(true); vm.selectSession(session); runCurrent()
        assertEquals(skills, vm.state.value.runtime?.skills)
        vm.selectSession(session.copy(id = "other")); runCurrent()
        assertTrue(vm.state.value.runtime?.skills.orEmpty().isEmpty())
        vm.selectSession(session); runCurrent()
        assertEquals(skills, vm.state.value.runtime?.skills)
        vm.setVisible(false); runCurrent()
        assertNull(vm.state.value.runtime)
        vm.selectDevice(null); runCurrent()
        assertNull(vm.state.value.runtime)
        coVerify(exactly = 0) { client.send(any(), any(), any(), any()) }
    }

    @Test fun readOnlyFallbackCannotRestoreNativeSkillSuggestions() = runTest(dispatcher) {
        coEvery { client.conversation("session", any()) } returns bodyPage(emptyList(), null, emptyList(), RemoteRuntime("idle", skills = skills))
        io.mockk.every { client.events("session") } returns kotlinx.coroutines.flow.flow { throw java.io.IOException("disconnected") }
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        saveAndSelect(vm); vm.setVisible(true); vm.selectSession(session); runCurrent()
        assertNull(vm.state.value.runtime)
        vm.setVisible(false)
    }

    @Test fun nativeCommandUsesTheExistingRemoteSendWithoutLocalExpansion() = runTest(dispatcher) {
        coEvery { client.send(any(), any(), any(), any()) } returns "native-turn"
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }; runCurrent()
        saveAndSelect(vm); vm.setVisible(true); vm.selectSession(session); runCurrent()
        val command = "/skill:clinical-osce Cardiology"
        vm.editDraft(vm.state.value.owner!!, command); vm.send(); runCurrent()
        coVerify(exactly = 1) { client.send("session", command, any(), any()) }
        vm.setVisible(false)
    }

    @Test fun optionalRuntimeDiscoveryUsesTheExistingAuthenticatedSessionRead() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var reads = 0
        server.createContext("/") { exchange ->
            reads++
            assertEquals("Bearer " + "a".repeat(64), exchange.requestHeaders.getFirst("Authorization"))
            val body = """{"messages":[],"nextCursor":null,"queued":[],"runtime":{"status":"idle","skills":[{"name":"osce","description":"Practice a focused clinical scenario","command":"/skill:osce"},{"name":"OSCE.md","command":"/osce"},{"name":"incomplete"}]}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}", "a".repeat(64))
            assertEquals(0, reads)
            val page = client.conversation("11111111-1111-4111-8111-111111111111")
            assertEquals(listOf("/skill:osce"), page.runtime?.skills?.map { it.command })
            assertEquals(1, reads)
            assertTrue(Json.decodeFromString<RemoteRuntime>("""{"status":"idle"}""").skills.isEmpty())
        } finally { server.stop(0) }
    }
}
