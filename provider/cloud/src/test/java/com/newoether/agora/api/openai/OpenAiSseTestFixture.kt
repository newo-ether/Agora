package com.newoether.agora.api.openai

import android.content.Context
import android.content.pm.ApplicationInfo
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.CloudDebugLog as DebugLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import okio.buffer
import okio.source
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

abstract class OpenAiSseTestFixture {

    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    protected fun collectWithMockedStream(
        reads: List<Any?>,
        messages: List<ChatMessage> = messages(),
        responsesApiEnabled: Boolean = false,
    ): List<StreamEvent> {
        val readIndex = java.util.concurrent.atomic.AtomicInteger()
        val handle = mockk<HttpClient.StreamHandle>(relaxed = true)
        every { handle.code } returns 200
        every { handle.readLine() } answers {
            reads.getOrNull(readIndex.getAndIncrement()).let { read ->
                if (read is Throwable) throw read else read as String?
            }
        }
        mockkObject(HttpClient)
        every { HttpClient.streamPost(any(), any(), any()) } returns handle
        return try {
            collect(
                object : BaseOpenAiProvider() {
                    override val name = "test"
                    override val defaultBaseUrl = "https://example.invalid/v1"
                    override fun retryDelayMillis(attempt: Int) = 1L
                },
                ProviderConfig(
                    apiKey = "",
                    modelId = "test-model",
                    responsesApiEnabled = responsesApiEnabled,
                ),
                messages = messages,
            )
        } finally {
            unmockkObject(HttpClient)
        }
    }
    protected fun collect(
        provider: BaseOpenAiProvider,
        config: ProviderConfig,
        timeoutMillis: Long = 2_000L,
        messages: List<ChatMessage> = messages(),
    ): List<StreamEvent> = runBlocking {
        withTimeout(timeoutMillis) { provider.generateResponse(messages, config).toList() }
    }

    protected fun messages() = listOf(
        ChatMessage(
            text = "hello",
            participant = Participant.USER,
        )
    )

    protected fun ProviderConfig.withTools() = copy(
        tools = listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "file_edit",
                    description = "Edit a file",
                    parameters = ToolParameters(
                        type = "object",
                        properties = emptyMap(),
                    ),
                )
            )
        )
    )

    protected fun withServer(
        terminalGraceMillis: Long,
        responsesApiEnabled: Boolean = false,
        connectionCount: Int = 1,
        statusCode: Int = 200,
        errorBody: String? = null,
        providerFactory: ((String) -> BaseOpenAiProvider)? = null,
        response: (Socket, CountDownLatch) -> Unit,
        test: (BaseOpenAiProvider, ProviderConfig, SseServer) -> Unit,
    ) {
        SseServer(connectionCount, statusCode, errorBody, response).use { server ->
            val provider = providerFactory?.invoke(server.baseUrl) ?: object : BaseOpenAiProvider() {
                override val name: String = "test"
                override val defaultBaseUrl: String = server.baseUrl
                override val terminalSseGraceMillis: Long = terminalGraceMillis
                override fun retryDelayMillis(attempt: Int): Long = 1L
            }
            try {
                test(
                    provider,
                    ProviderConfig(
                        apiKey = "",
                        modelId = "test-model",
                        baseUrl = server.baseUrl,
                        thinkingEnabled = false,
                        responsesApiEnabled = responsesApiEnabled,
                    ),
                    server,
                )
            } finally {
                server.throwIfFailed()
            }
        }
    }

    protected data class CapturedRequest(val requestLine: String, val body: String)

    protected class SseServer(
        private val connectionCount: Int,
        private val statusCode: Int,
        private val errorBody: String?,
        private val response: (Socket, CountDownLatch) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        private val release = CountDownLatch(1)
        private val accepted = CountDownLatch(connectionCount)
        private val failure = AtomicReference<Throwable?>(null)
        private val clients = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<CapturedRequest>()
        private val worker = thread(
            name = "openai-sse-test-server",
            isDaemon = true,
        ) {
            try {
                repeat(connectionCount) {
                    server.accept().use { socket ->
                        clients += socket
                        socket.tcpNoDelay = true
                        requests += readRequest(socket)
                        accepted.countDown()
                        val output = socket.getOutputStream()
                        if (statusCode == 200) {
                            val headers = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: text/event-stream\r\n")
                                append("Cache-Control: no-cache\r\n")
                                append("Transfer-Encoding: chunked\r\n")
                                append("Connection: close\r\n")
                                append("\r\n")
                            }
                            output.write(headers.toByteArray(StandardCharsets.US_ASCII))
                            output.flush()
                            response(socket, release)
                            try {
                                output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                                output.flush()
                            } catch (_: SocketException) {
                                // A terminal SSE event lets the client close before this optional chunk terminator.
                            }
                        } else {
                            val payload = (errorBody ?: "Unknown error").toByteArray(StandardCharsets.UTF_8)
                            val headers = buildString {
                                append("HTTP/1.1 $statusCode Error\r\n")
                                append("Content-Type: application/json\r\n")
                                append("Content-Length: ${payload.size}\r\n")
                                append("Connection: close\r\n")
                                append("\r\n")
                            }
                            output.write(headers.toByteArray(StandardCharsets.US_ASCII))
                            output.write(payload)
                            output.flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!server.isClosed) failure.set(error)
            }
        }

        val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("SSE test server failed", it) }
            check(accepted.await(1L, TimeUnit.SECONDS)) {
                "SSE test server received ${requests.size} of $connectionCount requests"
            }
        }

        override fun close() {
            release.countDown()
            clients.forEach { runCatching { it.close() } }
            runCatching { server.close() }
            worker.join(1_000L)
        }

        private fun readRequest(socket: Socket): CapturedRequest {
            val input = socket.getInputStream().source().buffer()
            val requestLine = input.readUtf8LineStrict()
            var contentLength = 0
            while (true) {
                val line = input.readUtf8LineStrict()
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toInt()
                }
            }
            val body = input.readUtf8(contentLength.toLong())
            return CapturedRequest(requestLine, body)
        }
    }

    protected fun Socket.writeSse(data: String) {
        val payload = "data: $data\n\n".toByteArray(StandardCharsets.UTF_8)
        val output = getOutputStream()
        output.write("${payload.size.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(payload)
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    protected fun Socket.writeContentSse(content: String, finishReason: String? = null) {
        writeSse(
            WIRE_JSON.encodeToString(
                com.newoether.agora.api.OpenAiStreamResponse(
                    choices = listOf(
                        com.newoether.agora.api.OpenAiChoice(
                            index = 0,
                            delta = com.newoether.agora.api.OpenAiDelta(content = content),
                            finishReason = finishReason,
                        )
                    )
                )
            )
        )
    }

    protected companion object {
        val WIRE_JSON = Json { explicitNulls = false }
    }
}
