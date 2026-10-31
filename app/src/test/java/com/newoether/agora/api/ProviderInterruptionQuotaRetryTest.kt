package com.newoether.agora.api

import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.api.util.ProviderRetryPolicy
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.viewmodel.ProviderPassRunner
import com.newoether.agora.util.DebugLog
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderInterruptionQuotaRetryTest {
    @Before
    fun useImmediateRetriesAndJvmLogging() {
        mockkObject(DebugLog)
        mockkObject(ProviderRetryPolicy)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.e(any(), any()) } just Runs
        every { DebugLog.w(any(), any()) } just Runs
        every { ProviderRetryPolicy.delayMillis(any()) } returns 1L
        every { ProviderRetryPolicy.delayMillis(any(), any()) } returns 1L
    }

    @After
    fun restoreObjects() {
        unmockkObject(HttpClient)
        unmockkObject(ProviderRetryPolicy)
        unmockkObject(DebugLog)
    }

    @Test
    fun prematureEofBeforeAndAfterOutputUsesEveryRemoteProviderBoundary() {
        cases().forEach { case ->
            listOf(
                EOFException(),
                IOException("unexpected end of stream on https://example.invalid/..."),
                IOException("read failed", IOException("Unexpected end of file")),
                IOException("read failed", EOFException("premature close")),
            ).forEach { failure ->
                val before = collect(case, reads = listOf(failure) + case.success)
                assertRecovered(case, before, resetCurrentAttempt = true)

                val after = collect(case, reads = case.partial + failure + case.success)
                assertRecoveredPartial(case, after) { it is StreamEvent.TextChunk && it.text == "partial" }
            }
        }
    }

    @Test
    fun formattingOnlyChunksBeforeParseReadAndEofFailuresUseExistingRetry() {
        cases().forEach { case ->
            case.formattingOnlyLines().forEach { prefix ->
                listOf<Any>(
                    if (case.name == "Ollama") "not-json" else "data: not-json",
                    IOException("The server response could not be read."),
                    IOException("read failed", IOException("The server response could not be read.")),
                    EOFException(),
                ).forEach { failure ->
                    val result = collect(case, reads = prefix + failure + case.success, throughConsumer = true)
                    assertEquals(case.name, 2, result.second)
                    assertEquals(case.name, listOf(StreamEvent.Retrying(1, 5, resetCurrentAttempt = true)), result.first.filterIsInstance<StreamEvent.Retrying>())
                    assertEquals(case.name, "ok", answer(result.first))
                    assertEquals(case.name, "", result.first.filterIsInstance<StreamEvent.ThoughtChunk>().joinToString("") { it.thought })
                    assertTrue(case.name, result.first.none { it is StreamEvent.Error })
                }
            }
        }
    }

    @Test
    fun unreadableApiEnvelopeRetriesPartialButRejectsPermanentFields() {
        cases().forEach { case ->
            val message = "The server response could not be read."
            assertRecovered(
                case,
                collect(case, reads = listOf(case.errorLine("provider_error", "provider_error", message)) + case.success),
                resetCurrentAttempt = true,
            )
            val partial = collect(
                case,
                reads = case.partial + case.errorLine("provider_error", "provider_error", message, sequenceNumber = 2) + case.success,
            )
            assertRecoveredPartial(case, partial) { it is StreamEvent.TextChunk && it.text == "partial" }
            case.formattingOnlyLines().forEach { prefix ->
                val withPermanentFields = collect(
                    case,
                    reads = prefix + case.errorLine(
                        "insufficient_quota", "insufficient_quota", "$message Insufficient quota", sequenceNumber = 2,
                    ) + case.success,
                    throughConsumer = true,
                )
                assertTerminalApi(case, withPermanentFields, "$message Insufficient quota")
            }
        }
    }

    @Test
    fun socketDiagnosticsInsideApiEnvelopesRetryAfterPartialOutput() {
        cases().forEach { case ->
            listOf("Unexpected end of stream", "Software caused connection abort", "Socket is closed").forEach { message ->
                val result = collect(case, reads = case.partial + case.errorLine("503", "server_error", message, 2) + case.success)
                assertRecoveredPartial(case, result) { it is StreamEvent.TextChunk && it.text == "partial" }
            }
        }
    }

    @Test
    fun parseFailureAfterPartialOutputRetriesAndKeepsRawAttemptEventsOrdered() {
        cases().forEach { case ->
            val malformed = if (case.name == "Ollama") "not-json" else "data: not-json"
            val result = collect(case, reads = case.partial + malformed + case.success)
            assertRecoveredPartial(case, result) { it is StreamEvent.TextChunk && it.text == "partial" }
        }
    }

    @Test
    fun completedToolHistoryAndSendingPlaceholderDoNotCountAsCurrentPassOutput() {
        val call = ToolCallData("file_read", "{}", "", toolCallId = "call_previous")
        val history = listOf(
            ChatMessage(id = "user", text = "hello", participant = Participant.USER),
            ChatMessage(id = "tool", text = "Previous pass", participant = Participant.MODEL, toolCall = call),
            ChatMessage(id = "result", text = "done", participant = Participant.USER, toolCall = call.copy(result = "done")),
            ChatMessage(id = "sending", text = "", participant = Participant.MODEL, status = MessageStatus.SENDING),
        )
        cases().forEach { case ->
            listOf<Any>(
                if (case.name == "Ollama") "not-json" else "data: not-json",
                IOException("The server response could not be read."),
                EOFException(),
            ).forEach { failure ->
                val prefix = case.formattingOnlyLines().first()
                val result = collect(case, reads = prefix + failure + case.success, messages = history, throughConsumer = true)
                assertEquals(case.name, 2, result.second)
                assertEquals(case.name, listOf(StreamEvent.Retrying(1, 5, resetCurrentAttempt = true)), result.first.filterIsInstance<StreamEvent.Retrying>())
                assertEquals(case.name, "ok", answer(result.first))
                assertTrue(case.name, result.first.none { it is StreamEvent.Error })
            }
        }
    }

    @Test
    fun eofWhileOpeningRetriesThroughEveryExistingProviderLoop() {
        cases().forEach { case ->
            listOf(EOFException(), IOException("request failed", EOFException())).forEach { failure ->
                assertRecovered(case, collect(case, reads = case.success, openingFailure = failure))
            }
        }
    }

    @Test
    fun exhaustedQuotaHttpDoesNotRetryOrTryAnotherOpenAiEndpoint() {
        cases().forEach { case ->
            listOf(429, 503).forEach { status ->
                val result = collect(
                    case,
                    status = status,
                    errorBody = """{"error":{"code":"insufficient_quota","type":"rate_limit_error","message":"Failed to generate: account blocked"}}""",
                    // Missing /v1 gives OpenAI a second endpoint candidate; it must not be attempted.
                    config = case.config.copy(baseUrl = "https://example.invalid"),
                )
                assertTerminalApi(case, result, "Failed to generate: account blocked")
                val error = result.first.filterIsInstance<StreamEvent.Error>().single().error as GenerationError.Api
                assertEquals(case.name, "insufficient_quota", error.code)
                assertEquals(case.name, "rate_limit_error", error.type)
            }
        }
    }

    @Test
    fun permanentHttpAuthAndRequestFailuresRetainFieldsWithoutEndpointFallback() {
        cases().forEach { case ->
            listOf(
                Triple(401, "authentication_error", "Failed to generate: key rejected"),
                Triple(403, "permission_error", "Failed to generate: forbidden"),
                Triple(503, "invalid_request_error", "Invalid request: unsupported parameter"),
            ).forEach { (status, type, message) ->
                val result = collect(
                    case,
                    status = status,
                    errorBody = """{"error":{"code":"request_rejected","type":"$type","message":"$message"}}""",
                    config = case.config.copy(baseUrl = "https://example.invalid"),
                )
                assertTerminalApi(case, result, message)
                val error = result.first.filterIsInstance<StreamEvent.Error>().single().error as GenerationError.Api
                assertEquals(case.name, "request_rejected", error.code)
                assertEquals(case.name, type, error.type)
            }
        }
    }

    @Test
    fun quotaAndDeterministicStreamErrorsCannotUseMissingTerminalToRetry() {
        cases().forEach { case ->
            listOf(
                Triple("insufficient_quota", "insufficient_quota", "Failed to generate: insufficient quota"),
                Triple("invalid_api_key", "authentication_error", "Failed to generate: invalid API key"),
                Triple("invalid_request_error", "invalid_request_error", "Invalid request: unsupported parameter"),
                Triple("provider_rejected", "provider_rejected", "Request rejected by provider"),
            ).forEach { (code, type, message) ->
                val result = collect(case, reads = listOf(case.errorLine(code, type, message)))
                assertTerminalApi(case, result, message)
            }
        }
    }

    @Test
    fun structuredQuotaFieldsWinOverTransientTextWithoutQuotaInMessage() {
        cases().filter { it.name != "Ollama" && it.name != "Gemini" }.forEach { case ->
            val message = "Failed to generate: account blocked"
            assertTerminalApi(
                case,
                collect(case, reads = listOf(case.errorLine("insufficient_quota", "insufficient_quota", message))),
                message,
            )
        }
        val gemini = cases().single { it.name == "Gemini" }
        assertTerminalApi(
            gemini,
            collect(gemini, reads = listOf(gemini.errorLine("429", "RESOURCE_EXHAUSTED", "Credit balance is too low"))),
            "Credit balance is too low",
        )
    }

    @Test
    fun responsesExplicitFailureRetriesOnlyWithTransientEvidence() {
        val case = cases().single { it.name == "OpenAI Responses" }
        fun failed(code: String, type: String, message: String): String =
            """data: {"type":"response.failed","sequence_number":1,"response":{"status":"failed","error":{"code":"$code","type":"$type","message":"$message"}}}"""

        listOf(
            failed("server_error", "server_error", "Try again"),
            failed("failed_to_generate", "failed_to_generate", "Failed to generate"),
        ).forEach { line -> assertRecovered(case, collect(case, reads = listOf(line) + case.success)) }

        listOf(
            Triple("insufficient_quota", "server_error", "Failed to generate"),
            Triple("unsupported_parameter", "invalid_request_error", "Unsupported parameter: service_tier"),
            Triple("provider_rejected", "responses_error", "Request rejected"),
        ).forEach { (code, type, message) ->
            val result = collect(case, reads = listOf(failed(code, type, message)))
            assertTerminalApi(case, result, message)
            val error = result.first.filterIsInstance<StreamEvent.Error>().single().error as GenerationError.Api
            assertEquals(code, error.code)
            assertEquals(type, error.type)
        }
    }

    @Test
    fun rateLimitAfterPartialOutputRetriesAcrossProviders() {
        cases().forEach { case ->
            val result = collect(
                case,
                reads = case.partial + case.errorLine(
                    "rate_limit_exceeded", "rate_limit_error", "Rate limit exceeded", sequenceNumber = 2,
                ) + case.success,
            )
            assertRecoveredPartial(case, result) { it is StreamEvent.TextChunk && it.text == "partial" }
        }
    }

    @Test
    fun temporaryPerMinuteQuotaHttpAndStreamErrorsStillRetry() {
        cases().forEach { case ->
            val message = "Rate limit: per-minute quota exceeded, retry later"
            val http = collect(
                case,
                reads = case.success,
                status = 429,
                errorBody = """{"error":{"code":"rate_limit_exceeded","message":"$message"}}""",
                recoverHttp = true,
            )
            assertRecovered(case, http)
            val stream = collect(
                case,
                reads = listOf(case.errorLine("rate_limit_exceeded", "rate_limit_error", message)) + case.success,
            )
            assertRecovered(case, stream)
        }
    }

    @Test
    fun eofAndRateLimitExhaustOnlyTheExistingFiveRetriesAndReportOnce() {
        cases().forEach { case ->
            listOf(
                collect(case, reads = List(ProviderRetryPolicy.MAX_ATTEMPTS) { EOFException() }),
                collect(
                    case,
                    reads = List(ProviderRetryPolicy.MAX_ATTEMPTS) {
                        case.formattingOnlyLines().first() +
                            (if (case.name == "Ollama") "not-json" else "data: not-json")
                    }.flatten(),
                ),
                collect(case, status = 429, errorBody = "Too many requests, retry later"),
                collect(
                    case,
                    reads = List(ProviderRetryPolicy.MAX_ATTEMPTS) {
                        case.errorLine("rate_limit_exceeded", "rate_limit_error", "Rate limit exceeded")
                    },
                ),
            ).forEachIndexed { failureIndex, result ->
                assertEquals(case.name, ProviderRetryPolicy.MAX_ATTEMPTS, result.second)
                assertEquals(
                    case.name,
                    (1..ProviderRetryPolicy.MAX_RETRIES).map { StreamEvent.Retrying(it, 5, resetCurrentAttempt = failureIndex <= 1) },
                    result.first.filterIsInstance<StreamEvent.Retrying>(),
                )
                assertEquals(case.name, 1, result.first.filterIsInstance<StreamEvent.Error>().size)
                assertTrue(case.name, answer(result.first).isBlank())
            }
        }
    }

    @Test
    fun partialTextThoughtAndToolFailuresRetryAcrossEveryRemoteProtocol() {
        cases().forEach { case ->
            case.partialOutputLines().forEach { (kind, prefix) ->
                listOf<Any>(
                    if (case.name == "Ollama") "not-json" else "data: not-json",
                    IOException("The server response could not be read."),
                    IOException("read failed", IOException("The server response could not be read.")),
                    EOFException(),
                    java.net.SocketException("Software caused connection abort"),
                    java.net.SocketException("Socket is closed"),
                ).forEach { failure ->
                    val result = collect(case, reads = prefix + failure + case.success)
                    assertRecoveredPartial(case, result) { event ->
                        when (kind) {
                            "text" -> event is StreamEvent.TextChunk && event.text == "partial"
                            "thought" -> event is StreamEvent.ThoughtChunk && event.thought == "partial thought"
                            else -> event is StreamEvent.ToolCallUpdate && event.name == "file_read"
                        }
                    }
                    val retryIndex = result.first.indexOfFirst { it is StreamEvent.Retrying }
                    assertTrue(case.name, result.first.drop(retryIndex + 1).none {
                        it is StreamEvent.ToolCallRequest || it is StreamEvent.ToolCallsRequest
                    })
                }
            }
        }
    }

    @Test
    fun partialResponseReadFailuresExhaustSixAttemptsBeforeOneFinalError() {
        cases().forEach { case ->
            listOf<Any>(
                if (case.name == "Ollama") "not-json" else "data: not-json",
                IOException("The server response could not be read."),
                java.net.SocketException("Connection reset"),
            ).forEach { failure ->
                val result = collect(case, reads = List(6) { case.partial + failure }.flatten())
                assertEquals(case.name, 6, result.second)
                assertEquals(
                    case.name,
                    (1..5).map { StreamEvent.Retrying(it, 5, resetCurrentAttempt = true) },
                    result.first.filterIsInstance<StreamEvent.Retrying>(),
                )
                assertEquals(case.name, List(6) { "partial" }, result.first.filterIsInstance<StreamEvent.TextChunk>().map { it.text })
                val error = result.first.filterIsInstance<StreamEvent.Error>().single().error
                when (failure) {
                    is IOException -> assertTrue(case.name, error is GenerationError.Network)
                    else -> assertTrue(case.name, if (failure.toString().contains("not-json")) error is GenerationError.SseParse else error is GenerationError.Api)
                }
                assertTrue(case.name, result.first.last() is StreamEvent.Error)
                assertTrue(case.name, result.first.indexOfLast { it is StreamEvent.Retrying } < result.first.indexOfFirst { it is StreamEvent.Error })
            }
        }
    }

    @Test
    fun exactUnreadableSocketTimeoutAfterPartialRetriesAndExhaustsAsNetworkError() {
        val message = "The server response could not be read."
        cases().forEach { case ->
            listOf(
                java.net.SocketTimeoutException(message),
                java.net.SocketTimeoutException("read timed out").apply { initCause(IOException(message)) },
                IOException("read failed", java.net.SocketTimeoutException(message)),
            ).forEach { failure ->
                val recovered = collect(case, reads = case.partial + failure + case.success)
                assertRecoveredPartial(case, recovered) { it is StreamEvent.TextChunk && it.text == "partial" }
                val exhausted = collect(case, reads = List(6) { case.partial + failure }.flatten())
                assertEquals(case.name, 6, exhausted.second)
                assertEquals(
                    case.name,
                    (1..5).map { StreamEvent.Retrying(it, 5, resetCurrentAttempt = true) },
                    exhausted.first.filterIsInstance<StreamEvent.Retrying>(),
                )
                val error = exhausted.first.filterIsInstance<StreamEvent.Error>().single().error
                assertTrue(case.name, error is GenerationError.Network)
                assertEquals(case.name, message, (error as GenerationError.Network).message)
                assertEquals(case.name, 0, error.statusCode)
                assertTrue(case.name, exhausted.first.last() is StreamEvent.Error)
            }
        }
    }

    @Test
    fun unreadableHttpFailuresPreservePermanentFieldsWithoutEndpointFallback() {
        cases().forEach { case ->
            listOf(401 to "authentication_error", 403 to "permission_error", 503 to "invalid_request_error").forEach { (status, type) ->
                val urls = mutableListOf<String>()
                val body = """{"error":{"code":"insufficient_quota","type":"$type","message":"The server response could not be read."}}"""
                val result = collect(
                    case, status = status, errorBody = body,
                    config = case.config.copy(baseUrl = "https://example.invalid"), requestUrls = urls,
                )
                assertEquals(case.name, 1, result.second)
                assertEquals(case.name, 1, urls.distinct().size)
                assertTrue(case.name, result.first.filterIsInstance<StreamEvent.Retrying>().isEmpty())
                val error = result.first.filterIsInstance<StreamEvent.Error>().single().error as GenerationError.Api
                assertEquals(case.name, "The server response could not be read.", error.message)
                assertEquals(case.name, "insufficient_quota", error.code)
                assertEquals(case.name, type, error.type)
            }
        }
    }

    @Test
    fun encodedUnreadableHttpResponsePreservesPermanentClassificationAndDecodedMessage() {
        val body = """{"error":{"type":"invalid_request_error","message":"The server response could not be \u0072ead."}}"""
        assertFalse(body.contains("The server response could not be read."))
        cases().forEach { case ->
            listOf(400, 401, 403, 429, 503).forEach { status ->
                val urls = mutableListOf<String>()
                val result = collect(
                    case, status = status, errorBody = body,
                    config = case.config.copy(baseUrl = "https://example.invalid"), requestUrls = urls,
                )
                assertEquals(case.name, 1, result.second)
                assertEquals(case.name, 1, urls.size)
                assertEquals(case.name, 1, urls.distinct().size)
                assertTrue(case.name, result.first.filterIsInstance<StreamEvent.Retrying>().isEmpty())
                val error = result.first.filterIsInstance<StreamEvent.Error>().single().error as GenerationError.Api
                assertEquals(case.name, "The server response could not be read.", error.message)
                assertEquals(case.name, "invalid_request_error", error.type)
                assertTrue(case.name, result.first.last() is StreamEvent.Error)
            }
        }
    }

    @Test
    fun ordinaryQuotaAndAuthenticationErrorsAfterPartialOutputRemainTerminal() {
        cases().forEach { case ->
            listOf(
                Triple("insufficient_quota", "insufficient_quota", "Insufficient quota"),
                Triple("invalid_api_key", "authentication_error", "Invalid API key"),
                Triple("invalid_request_error", "invalid_request_error", "Invalid request"),
            ).forEach { (code, type, message) ->
                val result = collect(case, reads = case.partial + case.errorLine(code, type, message, 2))
                assertEquals(case.name, 1, result.second)
                assertEquals(case.name, "partial", answer(result.first))
                assertTrue(case.name, result.first.none { it is StreamEvent.Retrying })
                assertEquals(case.name, message, (result.first.filterIsInstance<StreamEvent.Error>().single().error as GenerationError.Api).message)
            }
        }
    }

    @Test
    fun cancellationDuringPartialReadOrRetryDoesNotStartAnotherRequest() {
        cases().forEach { case ->
            listOf(false, true).forEach { cancelOnRetry ->
                // Keep the producer in backoff until the buffered retry reaches the collector.
                val retryDelay = if (cancelOnRetry) Long.MAX_VALUE else 1L
                every { ProviderRetryPolicy.delayMillis(any()) } returns retryDelay
                every { ProviderRetryPolicy.delayMillis(any(), any()) } returns retryDelay
                val urls = mutableListOf<String>()
                val events = mutableListOf<StreamEvent>()
                val failure = if (cancelOnRetry) IOException("The server response could not be read.")
                    else kotlinx.coroutines.CancellationException("The server response could not be read.")
                val result = runCatching {
                    collect(case, reads = case.partial + failure + case.success, requestUrls = urls, onEvent = { event ->
                        events += event
                        if (cancelOnRetry && event is StreamEvent.Retrying) throw kotlinx.coroutines.CancellationException("stopped")
                    })
                }
                assertTrue(case.name, result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                assertEquals(case.name, 1, urls.size)
                assertTrue(case.name, events.none { it is StreamEvent.Error })
                assertEquals(case.name, if (cancelOnRetry) 1 else 0, events.filterIsInstance<StreamEvent.Retrying>().size)
            }
        }
    }

    @Test
    fun anthropicUnnamedOpenToolRetriesBeforeAnyTerminalError() {
        val case = cases().single { it.name == "Anthropic" }
        val prefix = listOf("""data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_incomplete"}}""")
        val recovered = collect(case, reads = prefix + listOf(null) + case.success)
        assertRecoveredPartial(case, recovered) { it is StreamEvent.ToolCallUpdate }
        assertTrue(recovered.first.none { it is StreamEvent.ToolCallRequest || it is StreamEvent.ToolCallsRequest })
        val exhausted = collect(case, reads = List(6) { prefix + listOf(null) }.flatten())
        assertEquals(6, exhausted.second)
        assertEquals((1..5).map { StreamEvent.Retrying(it, 5, resetCurrentAttempt = true) }, exhausted.first.filterIsInstance<StreamEvent.Retrying>())
        assertTrue(exhausted.first.filterIsInstance<StreamEvent.Error>().single().error is GenerationError.SseParse)
        assertTrue(exhausted.first.last() is StreamEvent.Error)
    }

    private fun assertRecoveredPartial(
        case: Case,
        result: Pair<List<StreamEvent>, Int>,
        failedOutput: (StreamEvent) -> Boolean,
    ) {
        assertEquals(case.name, 2, result.second)
        assertEquals(case.name, listOf(StreamEvent.Retrying(1, 5, resetCurrentAttempt = true)), result.first.filterIsInstance<StreamEvent.Retrying>())
        val retryIndex = result.first.indexOfFirst { it is StreamEvent.Retrying }
        assertTrue(case.name, result.first.take(retryIndex).any(failedOutput))
        assertEquals(case.name, "ok", answer(result.first.drop(retryIndex + 1)))
        assertTrue(case.name, result.first.none { it is StreamEvent.Error })
    }

    private fun assertRecovered(
        case: Case,
        result: Pair<List<StreamEvent>, Int>,
        resetCurrentAttempt: Boolean = false,
    ) {
        assertEquals(case.name, 2, result.second)
        assertEquals(case.name, listOf(StreamEvent.Retrying(1, 5, resetCurrentAttempt)), result.first.filterIsInstance<StreamEvent.Retrying>())
        assertEquals(case.name, "ok", answer(result.first))
        assertTrue(case.name, result.first.none { it is StreamEvent.Error })
    }

    private fun assertTerminalApi(case: Case, result: Pair<List<StreamEvent>, Int>, message: String) {
        assertEquals(case.name, 1, result.second)
        assertFalse(case.name, result.first.any { it is StreamEvent.Retrying })
        assertTrue(case.name, answer(result.first).isBlank())
        val error = result.first.filterIsInstance<StreamEvent.Error>().single().error as GenerationError.Api
        assertEquals(case.name, message, error.message)
    }

    private fun answer(events: List<StreamEvent>): String =
        events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text }

    private fun collect(
        case: Case,
        reads: List<Any?> = emptyList(),
        status: Int = 200,
        errorBody: String = "",
        openingFailure: Throwable? = null,
        recoverHttp: Boolean = false,
        config: ProviderConfig = case.config,
        messages: List<ChatMessage> = listOf(ChatMessage(id = "user", text = "hello", participant = Participant.USER)),
        throughConsumer: Boolean = false,
        requestUrls: MutableList<String>? = null,
        onEvent: (StreamEvent) -> Unit = {},
    ): Pair<List<StreamEvent>, Int> {
        val opens = AtomicInteger()
        val readIndex = AtomicInteger()
        val handle = mockk<HttpClient.StreamHandle>(relaxed = true)
        every { handle.code } answers { if (recoverHttp && opens.get() > 1) 200 else status }
        every { handle.errorBody } returns errorBody
        every { handle.readLine() } answers {
            reads.getOrNull(readIndex.getAndIncrement()).let { read ->
                if (read is Throwable) throw read else read as String?
            }
        }
        mockkObject(HttpClient)
        every { HttpClient.streamPost(any(), any(), any()) } answers {
            requestUrls?.add(firstArg<String>())
            if (opens.incrementAndGet() == 1 && openingFailure != null) throw openingFailure
            handle
        }
        return try {
            val events = runBlocking {
                withTimeout(3_000L) {
                    if (throughConsumer) {
                        val forwarded = mutableListOf<StreamEvent>()
                        ProviderPassRunner().run(
                            RunEffectIdentity("conversation", 1L, "run", 1, "provider-1-0"),
                            case.provider, messages, config, forwarded::add,
                        )
                        forwarded
                    } else {
                        case.provider.generateResponse(messages, config).onEach { onEvent(it) }.toList()
                    }
                }
            }
            events to opens.get()
        } finally {
            unmockkObject(HttpClient)
        }
    }

    private data class Case(
        val name: String,
        val provider: LlmProvider,
        val config: ProviderConfig,
        val success: List<String>,
        val partial: List<String>,
    ) {
        fun errorLine(code: String, type: String, message: String, sequenceNumber: Int = 1): String = when (name) {
            "OpenAI Chat" -> """data: {"error":{"code":"$code","type":"$type","message":"$message"}}"""
            "OpenAI Responses" -> """data: {"type":"error","sequence_number":$sequenceNumber,"error":{"code":"$code","type":"$type","message":"$message"}}"""
            "Anthropic" -> """data: {"type":"error","error":{"type":"$type","message":"$message"}}"""
            "Gemini" -> {
                val status = code.toIntOrNull() ?: when {
                    code == "rate_limit_exceeded" -> 429
                    type == "provider_error" -> 500
                    else -> 400
                }
                """data: {"error":{"code":$status,"status":"$type","message":"$message"}}"""
            }
            else -> """{"error":"$message"}"""
        }
    }

    private fun Case.formattingOnlyLines(): List<List<String>> = when (name) {
        "OpenAI Chat" -> listOf(
            listOf("""data: {"choices":[{"index":0,"delta":{"content":" \t\n"}}]}"""),
            listOf("""data: {"choices":[{"index":0,"delta":{"reasoning_content":" \t\n"}}]}"""),
        )
        "OpenAI Responses" -> listOf(
            listOf("""data: {"type":"response.output_text.delta","sequence_number":1,"delta":" \t\n"}"""),
            listOf("""data: {"type":"response.reasoning_text.delta","sequence_number":1,"delta":" \t\n"}"""),
        )
        "Anthropic" -> listOf(
            listOf(
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" \t\n"}}""",
            ),
            listOf(
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":" \t\n"}}""",
            ),
        )
        "Gemini" -> listOf(
            listOf("""data: {"candidates":[{"content":{"parts":[{"text":" \t\n"}]}}]}"""),
            listOf("""data: {"candidates":[{"content":{"parts":[{"text":" \t\n","thought":true}]}}]}"""),
        )
        else -> listOf(
            listOf("""{"model":"test","message":{"role":"assistant","content":" \t\n"},"done":false}"""),
            listOf("""{"model":"test","message":{"role":"assistant","content":"","thinking":" \t\n"},"done":false}"""),
        )
    }

    private fun Case.partialOutputLines(): List<Pair<String, List<String>>> {
        val thought = when (name) {
            "OpenAI Chat" -> listOf("""data: {"choices":[{"index":0,"delta":{"reasoning_content":"partial thought"}}]}""")
            "OpenAI Responses" -> listOf("""data: {"type":"response.reasoning_text.delta","sequence_number":1,"delta":"partial thought"}""")
            "Anthropic" -> listOf(
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"partial thought"}}""",
            )
            "Gemini" -> listOf("""data: {"candidates":[{"content":{"parts":[{"text":"partial thought","thought":true}]}}]}""")
            else -> listOf("""{"model":"test","message":{"role":"assistant","content":"","thinking":"partial thought"},"done":false}""")
        }
        val tool = when (name) {
            "OpenAI Chat" -> listOf("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_partial","type":"function","function":{"name":"file_read","arguments":"{"}}]}}]}""")
            "OpenAI Responses" -> listOf("""data: {"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"item_partial","type":"function_call","call_id":"call_partial","name":"file_read"}}""")
            "Anthropic" -> listOf("""data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_partial","name":"file_read"}}""")
            "Gemini" -> listOf("""data: {"candidates":[{"content":{"parts":[{"functionCall":{"id":"call_partial","name":"file_read","args":{}}}]}}]}""")
            else -> listOf("""{"model":"test","message":{"role":"assistant","content":"","tool_calls":[{"id":"call_partial","type":"function","function":{"name":"file_read","arguments":{}}}]},"done":false}""")
        }
        return listOf("text" to partial, "thought" to thought, "tool" to tool)
    }

    private fun config(model: String, responses: Boolean = false) = ProviderConfig(
        apiKey = "",
        modelId = model,
        baseUrl = "https://example.invalid/v1",
        thinkingEnabled = false,
        responsesApiEnabled = responses,
    )

    private fun cases(): List<Case> = listOf(
        Case(
            "OpenAI Chat", CustomOpenAiProvider("Custom OpenAI", "https://example.invalid/v1"), config("gpt-test"),
            listOf(
                """data: {"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "data: [DONE]",
            ),
            listOf("""data: {"choices":[{"index":0,"delta":{"content":"partial"}}]}"""),
        ),
        Case(
            "OpenAI Responses", CustomOpenAiProvider("Custom OpenAI", "https://example.invalid/v1"), config("gpt-test", true),
            listOf(
                """data: {"type":"response.output_text.delta","sequence_number":1,"delta":"ok"}""",
                """data: {"type":"response.completed","sequence_number":2,"response":{"status":"completed"}}""",
            ),
            listOf("""data: {"type":"response.output_text.delta","sequence_number":1,"delta":"partial"}"""),
        ),
        Case(
            "Anthropic", AnthropicProvider(), config("claude-3-5-sonnet-20240620"),
            listOf(
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                """data: {"type":"content_block_stop","index":0}""",
                """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                """data: {"type":"message_stop"}""",
            ),
            listOf(
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}""",
                """data: {"type":"content_block_stop","index":0}""",
            ),
        ),
        Case(
            "Gemini", GeminiProvider(), config("gemini-test"),
            listOf("""data: {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""),
            listOf("""data: {"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}"""),
        ),
        Case(
            "Ollama", OllamaProvider(), config("llama3"),
            listOf(
                """{"model":"test","message":{"role":"assistant","content":"ok"},"done":false}""",
                """{"model":"test","done":true,"done_reason":"stop"}""",
            ),
            listOf("""{"model":"test","message":{"role":"assistant","content":"partial"},"done":false}"""),
        ),
    )
}
