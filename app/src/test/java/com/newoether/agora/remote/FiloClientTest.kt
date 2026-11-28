package com.newoether.agora.remote

import com.newoether.agora.model.Participant
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.ui.chat.message.ToolKind
import com.newoether.agora.ui.chat.message.ToolPresentationResolver
import com.newoether.agora.ui.chat.message.ToolPresentationState
import com.newoether.agora.ui.chat.message.mergeAdjacentSegments
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.first

class FiloClientTest {
    private val token = "a".repeat(64)
    private val id = "00000000-0000-0000-0000-000000000001"

    @Test fun archiveUsesAuthenticatedNativeReceiptAndRejectsLegacyDeleteSuccess() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        var reply = """{"archived":true,"cwd":"C:/project"}"""
        server.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("{}", exchange.requestBody.reader().readText())
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            client.archiveSession(id)
            reply = """{"deleted":true}"""
            try { client.archiveSession(id); fail("Legacy deletion is not an archive receipt") }
            catch (_: IllegalArgumentException) { }
            assertEquals(List(2) { "POST /v1/sessions/$id/archive" }, requests)
        } finally { server.stop(0) }
    }

    @Test fun sessionListDecodesLightweightStatusAndOldServerFallback() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/sessions") { exchange ->
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            val bytes = """{"sessions":[
                {"id":"active","title":"Task","cwd":"C:/work","updatedAt":2,"status":"active"},
                {"id":"unknown","title":"Old","cwd":"C:/work","updatedAt":1}
            ],"nextCursor":null}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val page = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token).sessions()
            assertEquals(listOf("active", "unknown"), page.sessions.map { it.id })
            assertEquals(listOf("active", null), page.sessions.map { it.status })
        } finally { server.stop(0) }
    }

    @Test fun nativeProjectionKeepsIdentityAndReplacesEditedTail() {
        val user = RemoteMessage("u", "turn", "client", "user", "hello", 10)
        val answer = RemoteMessage("a", "turn", null, "assistant", "old", 10)
        val edited = answer.copy(text = "new")
        val messages = projectRemoteMessages(listOf(user, edited))
        assertEquals(listOf("u", "a"), messages.map { it.id })
        assertEquals("u", messages.last().parentId)
        assertEquals("turn", messages.last().runId)
        assertEquals(Participant.MODEL, messages.last().participant)
        assertEquals("new", messages.last().text)
    }

    @Test fun malformedEndpointOrTokenIsRejectedBeforeNetwork() {
        listOf("http://user:secret@localhost/", "http://localhost/?token=x", "http://localhost/path").forEach {
            assertThrows(IllegalArgumentException::class.java) { applicationFixtureClient(it, token) }
        }
        assertThrows(IllegalArgumentException::class.java) { applicationFixtureClient("http://localhost/", "short") }
    }

    @Test fun onlyNativeActiveTurnOwnsSharedStreamingPresentation() {
        val user = RemoteMessage("u", "turn", "input", "user", "hello", 1)
        val idle = projectRemoteMessages(listOf(user), RemoteRuntime("idle", model = "model"))
        assertEquals(1, idle.size)
        val pending = projectRemoteMessages(listOf(user), RemoteRuntime("active", "turn", "model"))
        assertEquals(2, pending.size)
        assertEquals(Participant.MODEL, pending.last().participant)
        assertEquals(MessageStatus.SENDING, pending.last().status)
        assertEquals("turn", pending.last().runId)
        assertEquals("u", pending.last().parentId)
        val answer = RemoteMessage("a", "turn", null, "assistant", "Hello", 1)
        val live = projectRemoteMessages(listOf(user, answer), RemoteRuntime("active", "turn", "model"))
        assertEquals(listOf("u", "a"), live.map { it.id })
        assertTrue(com.newoether.agora.ui.chat.shouldShowStreamingTailIndicator(true, false, live.last()))
        val complete = projectRemoteMessages(listOf(user, answer), RemoteRuntime("idle", model = "model"))
        assertEquals(MessageStatus.SUCCESS, complete.last().status)
        assertFalse(com.newoether.agora.ui.chat.shouldShowStreamingTailIndicator(false, false, complete.last()))
        assertEquals(1, projectRemoteMessages(listOf(user), RemoteRuntime("active")).size)
    }

    @Test fun nativeActivityUsesSharedSegmentsAndKeepsInterleaving() {
        val records = listOf(
            RemoteMessage("u", "turn", null, "user", "hello", 10),
            RemoteMessage("r", "turn", null, "assistant", "Public summary", 11, RemoteActivity("thought")),
            RemoteMessage("a", "turn", null, "assistant", "Checking", 12),
            RemoteMessage("t", "turn", null, "assistant", "", 13,
                RemoteActivity("tool", "execute_shell_command", "{\"command\":\"pwd\"}",
                    "{\"output\":\"workspace\",\"exit_code\":0}", "succeeded", 30)),
            RemoteMessage("r2", "turn", null, "assistant", "Result summary", 14, RemoteActivity("thought")),
            RemoteMessage("a2", "turn", null, "assistant", "Done", 15),
            RemoteMessage("a3", "turn", null, "assistant", "Details", 16),
        )
        val projected = projectRemoteMessages(records)
        assertEquals(listOf("u", "r"), projected.map { it.id })
        val answer = projected.last()
        assertEquals("u", answer.parentId)
        assertEquals("turn", answer.runId)
        assertEquals("Checking\n\nDone\n\nDetails", answer.text)
        assertNull(answer.thoughtTimeMs)
        assertEquals(MessageStatus.SUCCESS, answer.status)
        val segments = mergeAdjacentSegments(answer.segments!!)
        assertEquals(listOf("thought", "answer", "tool", "thought", "answer"), segments.map { it.type })
        assertEquals("Done\n\nDetails", segments.last().content)
        val tool = segments[2]
        assertEquals("t", tool.toolCallId)
        assertEquals(30L, tool.durationMs)
        val presentation = ToolPresentationResolver.resolve(tool)
        assertEquals(ToolKind.SHELL_EXECUTE, presentation.kind)
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertEquals(0, presentation.exitCode)
    }

    @Test fun userAndTurnBoundariesRemainHardEvenForActivityOnlyMessages() {
        val records = listOf(
            RemoteMessage("r", "turn1", null, "assistant", "Summary", 1, RemoteActivity("thought")),
            RemoteMessage("u", "turn1", null, "user", "Interrupt", 2),
            RemoteMessage("t", "turn1", null, "assistant", "", 3,
                RemoteActivity("tool", "file_edit", state = "stopped")),
            RemoteMessage("a", "turn2", null, "assistant", "Next turn", 4),
        )
        val projected = projectRemoteMessages(records)
        assertEquals(listOf("r", "u", "t", "a"), projected.map { it.id })
        assertEquals(listOf(null, "r", "u", "t"), projected.map { it.parentId })
        assertEquals("", projected.first().text)
        assertNull(projected[2].thoughtTimeMs)
        assertEquals(ToolPresentationState.STOPPED,
            ToolPresentationResolver.resolve(projected[2].segments!!.single()).state)
    }

    @Test fun refreshedToolPayloadKeepsSheetIdentityAndRecordedLifecycle() {
        val summary = RemoteMessage("r", "turn", null, "assistant", "Checking", 10, RemoteActivity("thought"))
        val tool = RemoteMessage("t", "turn", null, "assistant", "", 10,
            RemoteActivity("tool", "execute_shell_command", "{\"command\":\"test\"}",
                "{\"output\":\"partial\"}", "running"))
        val before = projectRemoteMessages(listOf(summary, tool)).single()
        val running = before.segments!!.last()
        assertNull(running.toolResult)
        assertEquals(tool.activity!!.result, running.toolProgress)
        assertEquals(ToolPresentationState.RUNNING, ToolPresentationResolver.resolve(running).state)
        val finished = tool.copy(activity = tool.activity.copy(
            result = "{\"output\":\"failed test\",\"exit_code\":1}", state = "failed", durationMs = 55))
        val refreshed = listOf(summary, finished,
            RemoteMessage("a", "turn", null, "assistant", "Reported", 11))
        val after = projectRemoteMessages(refreshed).single()
        assertEquals(before.id, after.id)
        val terminal = after.segments!![1]
        assertEquals(running.toolCallId, terminal.toolCallId)
        assertNull(terminal.toolProgress)
        assertEquals(55L, terminal.durationMs)
        assertEquals(ToolPresentationState.FAILED, ToolPresentationResolver.resolve(terminal).state)
        assertEquals("Reported", after.text)
        val older = RemoteMessage("older", "previous", null, "user", "Earlier", 1)
        assertEquals(after.id, projectRemoteMessages(listOf(older) + refreshed).last().id)
    }

    @Test fun blankSummariesDoNotCreateEmptyThoughtBlocksOrFakeDurations() {
        val summary = RemoteMessage("r", "turn", null, "assistant", " ", 10, RemoteActivity("thought"))
        assertTrue(projectRemoteMessages(listOf(summary)).isEmpty())
        val answer = RemoteMessage("a", "turn", null, "assistant", "Answer", 11)
        val projected = projectRemoteMessages(listOf(summary, answer)).single()
        assertEquals(listOf("answer"), mergeAdjacentSegments(projected.segments!!).map { it.type })
        assertNull(projected.thoughtTimeMs)
    }

    @Test fun historyOptsIntoActivityAndAcceptsBothLegacyAndRichPayloads() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val queries = mutableListOf<String>()
        val legacy = RemoteMessage("a", "turn", null, "assistant", "Answer", 10)
        val rich = legacy.copy(id = "t", text = "", activity = RemoteActivity(
            "tool", "mcp/tools", "{}", "{\"structuredContent\":{\"count\":2}}", "succeeded"))
        server.createContext("/v1/sessions/$id") { exchange ->
            queries.add(exchange.requestURI.query)
            val record = if (queries.size == 1) legacy else rich
            val bytes = Json.encodeToString(RemoteConversationPage(listOf(record), null, emptyList())).toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            assertNull(client.conversation(id).messages.single().activity)
            val loaded = client.conversation(id, "cursor + next").messages.single()
            assertEquals(rich, loaded)
            assertEquals("includeActivity=true&includeMetadata=true", queries.first())
            assertEquals("cursor=cursor + next&includeActivity=true&includeMetadata=true", queries.last())
            val tool = projectRemoteMessages(listOf(loaded)).single().segments!!.single()
            assertEquals(rich.activity!!.result, ToolPresentationResolver.resolve(tool).rawResult)
        } finally { server.stop(0) }
    }

    @Test fun failuresDistinguishTransportAuthenticationAndProtocol() {
        for (error in listOf(IOException("unreachable"), java.net.ConnectException("refused"),
            java.net.SocketTimeoutException("timeout"), java.net.UnknownHostException("unreachable"))) {
            assertEquals(RemoteFailure.NETWORK, classifyRemoteFailure(error))
        }
        assertEquals(RemoteFailure.AUTHENTICATION, classifyRemoteFailure(FiloHttpException(401)))
        assertEquals(RemoteFailure.AUTHENTICATION, classifyRemoteFailure(FiloHttpException(403)))
        assertEquals(RemoteFailure.SERVICE, classifyRemoteFailure(FiloHttpException(502)))
        // A wrong token on the encrypted channel is a token error, not a protocol error.
        assertEquals(RemoteFailure.AUTHENTICATION, classifyRemoteFailure(com.newoether.agora.util.ConchAuthenticationException("token")))
        assertEquals(RemoteFailure.SERVICE, classifyRemoteFailure(com.newoether.agora.util.ConchServiceException("gateway")))
        assertEquals(RemoteFailure.PROTOCOL, classifyRemoteFailure(com.newoether.agora.util.ConchChannelException("frame")))
        assertEquals(RemoteFailure.PROTOCOL, classifyRemoteFailure(SerializationException("payload")))
        assertEquals(RemoteFailure.PROTOCOL, classifyRemoteFailure(IllegalArgumentException("incompatible")))
        assertEquals(RemoteFailure.STORAGE, classifyRemoteFailure(RemoteStorageException()))
        val invalid = assertThrows(FiloConfigurationException::class.java) { applicationFixtureClient("broken", token) }
        assertEquals(RemoteFailure.CONFIGURATION, classifyRemoteFailure(invalid))
    }

    @Test fun authenticatedDirectSendUsesExactSessionAndDoesNotFollowRedirects() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        var auth = ""
        var path = ""
        var body = ""
        var query: String? = null
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            auth = exchange.requestHeaders.getFirst("Authorization")
            path = exchange.requestURI.path
            query = exchange.requestURI.query
            body = exchange.requestBody.reader().readText()
            exchange.responseHeaders.add("Location", "/must-not-retry")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            try { client.send(id, "hello", id); fail("Redirect must fail") }
            catch (error: FiloHttpException) { assertEquals(307, error.status) }
            assertEquals(1, requests.get())
            assertEquals("Bearer $token", auth)
            assertEquals("/v1/sessions/$id/messages", path)
            assertNull(query)
            assertTrue(body.contains("\"text\":\"hello\""))
            assertTrue(body.contains("\"clientId\":\"$id\""))
        } finally { server.stop(0) }
    }

    @Test fun authenticatedDirectCreationCarriesTheFirstMessageAndSettingsAndNeverCreatesEmpty() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var method = ""
        var path = ""
        var body = ""
        server.createContext("/v1/sessions") { exchange ->
            method = exchange.requestMethod
            path = exchange.requestURI.path
            body = exchange.requestBody.reader().readText()
            val bytes = """{"id":"$id","title":"New","cwd":"C:/work","updatedAt":2,"turnId":"turn-1","clientId":"$id"}""".toByteArray()
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            val created = client.create("hello", id, settings = RemoteSettings(model = "chosen", updateServiceTier = true))
            assertEquals("POST", method)
            assertEquals("/v1/sessions", path)
            assertEquals(id, created.id)
            assertTrue(body.contains("\"text\":\"hello\""))
            assertTrue(body.contains("\"clientId\":\"$id\""))
            assertTrue(body.contains("\"attachments\":[]"))
            assertTrue(body.contains("\"settings\":{\"model\":\"chosen\",\"serviceTier\":null}"))
            try { client.create("", "11111111-1111-4111-8111-111111111111"); fail("Blank first message must be refused") }
            catch (_: FiloInputException) { }
            try { client.create("hi", "11111111-1111-4111-8111-111111111111", settings = RemoteSettings()); fail("Settings without a model must be refused") }
            catch (_: FiloInputException) { }
        } finally { server.stop(0) }
        Unit
    }

    @Test fun connectionAcceptsOriginalDesktopGatewayAndRejectsRetiredOrUnknownModes() = runBlocking {
        for (mode in listOf("existing", "standalone", "unsupported")) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/info") { exchange ->
                val value = """{"protocolVersion":2,"agent":"codex","sessionMode":"$mode","messageDelivery":"native-steer","outputMode":"live-messages","supportsLazyMessages":true,"device":"quantum"}""".toByteArray()
                exchange.sendResponseHeaders(200, value.size.toLong())
                exchange.responseBody.use { it.write(value) }
            }
            server.start()
            try {
                val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
                if (mode != "existing") {
                    try { client.connect(); fail("Retired or unknown mode must be rejected") }
                    catch (_: IllegalArgumentException) { }
                } else assertEquals(RemoteDeviceStatus.IDLE, client.connect())
            } finally { server.stop(0) }
        }
    }

    @Test fun authenticatedInfoMapsOnlyExplicitDesktopStateWithoutInferringOnlineFromTheGateway() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        var desktop: String? = null
        server.createContext("/v1/info") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI}"
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            val field = desktop?.let { ""","desktop":$it""" }.orEmpty()
            val bytes = """{"protocolVersion":2,"agent":"codex","sessionMode":"existing","messageDelivery":"native-steer","outputMode":"live-messages","supportsLazyMessages":true,"device":"reachable-gateway"$field}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            val cases = listOf(
                """{"state":"connected"}""" to RemoteDeviceStatus.CONNECTED,
                """{"state":"connecting"}""" to RemoteDeviceStatus.CONNECTING,
                """{"state":"disconnected"}""" to RemoteDeviceStatus.IDLE,
                """{"state":"unavailable","error":"Native owner unavailable"}""" to RemoteDeviceStatus.ERROR,
                """{"state":"future-state","error":"Unknown native state"}""" to RemoteDeviceStatus.IDLE,
                """{"error":"No Desktop state"}""" to RemoteDeviceStatus.IDLE,
                """{"state":null}""" to RemoteDeviceStatus.IDLE,
                """{"state":true}""" to RemoteDeviceStatus.IDLE,
                """{"state":1}""" to RemoteDeviceStatus.IDLE,
                """{"state":{}}""" to RemoteDeviceStatus.IDLE,
                """{"state":[]}""" to RemoteDeviceStatus.IDLE,
                "{}" to RemoteDeviceStatus.IDLE,
                "null" to RemoteDeviceStatus.IDLE,
                null to RemoteDeviceStatus.IDLE,
            )
            for ((payload, expected) in cases) {
                desktop = payload
                assertEquals("Desktop payload: $payload", expected, client.connect())
            }
            assertEquals(List(cases.size) { "GET /v1/info" }, requests)
        } finally { server.stop(0) }
    }

    @Test fun protocolTwoAndNativeReceiptAndEventStreamUseIndependentAuthenticatedTransport() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val auth = mutableListOf<String>()
        val page = RemoteConversationPage(listOf(RemoteMessage("native-user", "turn", id, "user", "hello", 1)),
            null, emptyList(), RemoteRuntime("active", "turn", "model", 42, 256000))
        server.createContext("/") { exchange ->
            auth += exchange.requestHeaders.getFirst("Authorization")
            val value = when (exchange.requestURI.path) {
                "/v1/info" -> """{"protocolVersion":2,"agent":"codex","sessionMode":"existing","messageDelivery":"native-steer","outputMode":"live-messages","supportsLazyMessages":true,"device":"Computer"}"""
                "/v1/sessions/$id/messages" -> """{"turnId":"turn","clientId":"$id"}"""
                else -> "data: ${Json.encodeToString(page)}\n\n"
            }.toByteArray()
            exchange.sendResponseHeaders(200, value.size.toLong())
            exchange.responseBody.use { it.write(value) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            assertEquals(RemoteDeviceStatus.IDLE, client.connect())
            assertEquals("turn", client.send(id, "hello", id))
            assertEquals(page, client.events(id).first())
            assertEquals(listOf("Bearer $token", "Bearer $token", "Bearer $token"), auth)
        } finally { server.stop(0) }
    }
    @Test fun oneHttpRequestReturnsCatalogAndExactRowStatus() = runBlocking {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val page = RemoteSessionPage(
            listOf(RemoteSession(id, "Task", "/workspace", 1)), null,
            listOf(RemoteSessionStatus(id, "active", activeTurnId = "native-turn")),
        )
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            val bytes = Json.encodeToString(page).toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val result = applicationFixtureClient("http://127.0.0.1:" + server.address.port + "/", token).sessions("page-two")
            assertEquals("native-turn", result.statuses.single().activeTurnId)
            assertEquals("page-two", result.sessions.single().listCursor)
            assertEquals(listOf("/v1/sessions?cursor=page-two"), requests)
        } finally { server.stop(0) }
    }

    @Test fun activeTurnCannotShowAssistantIndicatorBeforeItsNativeUserMessage() {
        val runtime = RemoteRuntime("active", "new-turn", "model")
        assertTrue(projectRemoteMessages(emptyList(), runtime).isEmpty())
        val oldUser = RemoteMessage("old-user", "old-turn", null, "user", "old", 1)
        assertEquals(listOf("old-user"), projectRemoteMessages(listOf(oldUser), runtime).map { it.id })
        val user = RemoteMessage("new-user", "new-turn", id, "user", "new", 2)
        val visible = projectRemoteMessages(listOf(oldUser, user), runtime)
        assertEquals(listOf("old-user", "new-user", "remote-active-new-turn"), visible.map { it.id })
        assertEquals("new-user", visible.last().parentId)
    }

    @Test fun presentationRemovesOnlyTerminalLineBreaksAndPreservesNativeRecords() {
        val source = "  first  \n    code\nlast  \r\n"
        val user = RemoteMessage("u", "t", null, "user", "  input\nline  \r\n", 1)
        val answer = RemoteMessage("a", "t", null, "assistant", source, 2)
        val literal = RemoteMessage("b", "t2", null, "assistant", "literal\\n", 3)
        val projected = projectRemoteMessages(listOf(user, answer, literal))
        assertEquals("  input\nline  ", projected[0].text)
        assertEquals(source.trimEnd('\r', '\n'), projected[1].text)
        assertEquals(source.trimEnd('\r', '\n'), projected[1].segments!!.single().content)
        assertEquals("literal\\n", projected[2].text)
        assertTrue(user.text.endsWith("\r\n"))
        assertEquals(source, answer.text)
    }

    @Test fun unnamedSessionUsesLocalizedNewChatWithoutChangingNativeId() {
        val unnamed = RemoteSession("native-id", "native-id", "/workspace", 0)
        assertEquals("New Chat", unnamed.displayTitle("New Chat"))
        assertEquals("native-id", unnamed.id)
        assertEquals("Named task", unnamed.copy(title = "Named task").displayTitle("New Chat"))
    }

    @Test fun nativeStreamingTailUsesOriginalThoughtToolAndAnswerLifecycle() {
        val user = RemoteMessage("u", "turn", null, "user", "go", 1)
        val thought = RemoteMessage("r", "turn", null, "assistant", "Thinking", 2, RemoteActivity("thought"))
        val tool = RemoteMessage("t", "turn", null, "assistant", "", 3,
            RemoteActivity("tool", "shell", state = "running"))
        val answer = RemoteMessage("a", "turn", null, "assistant", "Hello", 4)
        val active = RemoteRuntime("active", "turn", "model")
        assertEquals(MessageStatus.THINKING, projectRemoteMessages(listOf(user, thought), active).last().status)
        assertEquals(MessageStatus.TOOL_CALLING, projectRemoteMessages(listOf(user, thought, tool), active).last().status)
        val streaming = projectRemoteMessages(listOf(user, thought, tool, answer), active).last()
        val grown = projectRemoteMessages(listOf(user, thought, tool, answer.copy(text = "Hello world")), active).last()
        assertEquals(streaming.id, grown.id)
        assertEquals(MessageStatus.SENDING, grown.status)
        assertEquals("Hello world", grown.text)
        assertEquals(MessageStatus.SUCCESS,
            projectRemoteMessages(listOf(user, thought, tool, answer), RemoteRuntime("idle")).last().status)
    }

}
