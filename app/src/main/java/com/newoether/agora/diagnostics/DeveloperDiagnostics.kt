package com.newoether.agora.diagnostics

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ConversationRuntimeTrace
import com.newoether.agora.model.ConversationRuntimeTraceEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Process-wide producer facade for the credential-sanitized persistent diagnostic capture. */
object DeveloperDiagnostics {
    private val buffer = DiagnosticEventBuffer()

    val snapshots: StateFlow<DiagnosticSnapshot> = buffer.snapshots
    val isCaptureActive: Boolean get() = buffer.isCaptureActive

    suspend fun initialize(
        noBackupFilesDirectory: File,
        scope: CoroutineScope,
    ) {
        buffer.initialize(
            store = DiagnosticCaptureStore(File(noBackupFilesDirectory, CAPTURE_DIRECTORY)),
            scope = scope,
        )
    }

    suspend fun startCapture(): DiagnosticSession? = buffer.start().session

    suspend fun pauseCapture() = buffer.pause()

    suspend fun clear() = buffer.clear()

    suspend fun disableAndClear() = buffer.disableAndClear()

    suspend fun flush(): DiagnosticSnapshot = buffer.flush()

    fun newRequestContext(
        requestId: String,
        conversationId: String,
        runId: String?,
        pass: Int?,
        provider: String,
        model: String,
        requestKind: String,
    ): DiagnosticRequestContext? {
        if (!buffer.isCaptureActive) return null
        return DiagnosticRequestContext(
            requestId = DiagnosticRedactor.safeIdentifier(requestId).take(MAX_IDENTIFIER_LENGTH),
            conversationIdHash = ConversationRuntimeTrace.hashConversationId(conversationId),
            runId = runId?.let(DiagnosticRedactor::safeIdentifier)?.take(MAX_IDENTIFIER_LENGTH),
            pass = pass,
            provider = DiagnosticRedactor.safeIdentifier(provider).take(MAX_IDENTIFIER_LENGTH),
            model = DiagnosticRedactor.safeIdentifier(model).take(MAX_IDENTIFIER_LENGTH),
            requestKind = DiagnosticRedactor.safeIdentifier(requestKind).take(MAX_IDENTIFIER_LENGTH),
        )
    }

    fun recordRuntimeTransition(entry: ConversationRuntimeTraceEntry) {
        if (!buffer.isCaptureActive) return
        val context = DiagnosticRequestContext(
            conversationIdHash = entry.conversationIdHash,
            runId = DiagnosticRedactor.safeIdentifier(entry.runId.orEmpty())
                .take(MAX_IDENTIFIER_LENGTH)
                .ifEmpty { null },
            pass = entry.pass,
        )
        val payload = DiagnosticEventPayload.RuntimeTransition(
            oldState = DiagnosticRedactor.safeIdentifier(entry.oldState),
            commandType = DiagnosticRedactor.safeIdentifier(entry.commandType),
            newState = DiagnosticRedactor.safeIdentifier(entry.newState),
            effectId = entry.effectId?.let(DiagnosticRedactor::safeIdentifier),
            effectTypes = entry.effectTypes.map(DiagnosticRedactor::safeIdentifier),
        )
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(sequence, timestampMillis, context, payload)
        }
    }

    fun recordHttpStage(
        context: DiagnosticRequestContext?,
        stage: String,
        elapsedMillis: Long,
        detail: String,
    ) {
        if (context == null || !buffer.isCaptureActive) return
        val payload = DiagnosticEventPayload.HttpStage(
            stage = DiagnosticRedactor.safeIdentifier(stage).take(MAX_STAGE_LENGTH),
            elapsedMillis = elapsedMillis.coerceAtLeast(0L),
            attributes = safeHttpAttributes(detail),
        )
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(sequence, timestampMillis, context, payload)
        }
    }

    fun recordHttpRequest(
        context: DiagnosticRequestContext?,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
    ) {
        if (context == null || !buffer.isCaptureActive) return
        val payload = DiagnosticEventPayload.HttpRequest(
            method = DiagnosticRedactor.safeIdentifier(method).take(16),
            url = DiagnosticRedactor.captureUrl(url),
            headers = DiagnosticRedactor.captureHeaders(headers),
            body = DiagnosticRedactor.captureJson(
                body,
                DiagnosticRedactor.credentialValues(headers),
            ),
        )
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(sequence, timestampMillis, context, payload)
        }
    }

    fun recordHttpResponseBody(
        context: DiagnosticRequestContext?,
        code: Int,
        body: String,
    ) {
        if (context == null || !buffer.isCaptureActive) return
        val payload = DiagnosticEventPayload.HttpResponseBody(
            code = code,
            body = DiagnosticRedactor.captureJson(body),
        )
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(sequence, timestampMillis, context, payload)
        }
    }

    fun recordWireLine(
        context: DiagnosticRequestContext?,
        lineNumber: Long,
        line: String,
    ) {
        if (context == null || !buffer.isCaptureActive) return
        val payload = DiagnosticEventPayload.WireLine(
            lineNumber = lineNumber,
            line = DiagnosticRedactor.captureWireLine(line),
        )
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(sequence, timestampMillis, context, payload)
        }
    }

    fun recordParsedStreamEvent(
        context: DiagnosticRequestContext?,
        event: StreamEvent,
    ) {
        if (context == null || !buffer.isCaptureActive) return
        val details = event.diagnosticDetails()
        val payload = DiagnosticEventPayload.ParsedStreamEvent(
            eventType = details.eventType,
            attributes = details.attributes,
            content = details.content,
        )
        buffer.record { sequence, timestampMillis ->
            DiagnosticEvent(sequence, timestampMillis, context, payload)
        }
    }

    /** Unknown transport detail keys are discarded before a diagnostic command is queued. */
    internal fun safeHttpAttributes(detail: String): Map<String, String> {
        if (detail.isBlank()) return emptyMap()
        return DETAIL_PAIR.findAll(detail)
            .mapNotNull { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                if (key !in SAFE_HTTP_ATTRIBUTE_KEYS) {
                    null
                } else {
                    key to DiagnosticRedactor.safeIdentifier(value).take(MAX_ATTRIBUTE_LENGTH)
                }
            }
            .toMap()
    }

    private fun StreamEvent.diagnosticDetails(): ParsedEventDetails = when (this) {
        is StreamEvent.TextChunk -> ParsedEventDetails(
            eventType = "TextChunk",
            attributes = mapOf("chars" to text.length.toString()),
            content = DiagnosticRedactor.captureContent(text),
        )
        is StreamEvent.ImageChunk -> ParsedEventDetails(
            eventType = "ImageChunk",
            attributes = mapOf("bytes" to bytes.size.toString()),
        )
        is StreamEvent.CitationUpdate -> ParsedEventDetails(
            eventType = "CitationUpdate",
            attributes = mapOf(
                "provider" to DiagnosticRedactor.safeIdentifier(citation.provider),
                "kind" to DiagnosticRedactor.safeIdentifier(citation.kind),
                "anchors" to citation.anchors.size.toString(),
            ),
        )
        is StreamEvent.ThoughtChunk -> ParsedEventDetails(
            eventType = "ThoughtChunk",
            attributes = mapOf(
                "chars" to thought.length.toString(),
                "titleChars" to (title?.length ?: 0).toString(),
                "hasSignature" to (signature != null).toString(),
            ),
            content = DiagnosticRedactor.captureContent(thought),
        )
        is StreamEvent.UsageUpdate -> ParsedEventDetails(
            eventType = "UsageUpdate",
            attributes = buildMap {
                put("totalTokens", usage.totalTokenCount.toString())
                usage.inputTokenCount?.let { put("inputTokens", it.toString()) }
                usage.outputTokenCount?.let { put("outputTokens", it.toString()) }
                usage.reasoningTokenCount?.let { put("reasoningTokens", it.toString()) }
            },
        )
        is StreamEvent.Error -> ParsedEventDetails(
            eventType = "Error",
            attributes = mapOf("errorType" to error.javaClass.simpleName),
            content = DiagnosticRedactor.captureContent(message),
        )
        is StreamEvent.HostedToolCallUpdate -> ParsedEventDetails(
            eventType = "HostedToolCallUpdate",
            attributes = mapOf(
                "streamKey" to DiagnosticRedactor.safeIdentifier(streamKey),
                "name" to DiagnosticRedactor.safeIdentifier(name),
                "argumentChars" to arguments.length.toString(),
                "resultChars" to (result?.length ?: 0).toString(),
                "isError" to isError.toString(),
            ),
            content = DiagnosticRedactor.captureContent(result ?: arguments),
        )
        is StreamEvent.ToolCallUpdate -> ParsedEventDetails(
            eventType = "ToolCallUpdate",
            attributes = mapOf(
                "streamKey" to DiagnosticRedactor.safeIdentifier(streamKey),
                "id" to DiagnosticRedactor.safeIdentifier(id.orEmpty()),
                "name" to DiagnosticRedactor.safeIdentifier(name),
                "argumentChars" to arguments.length.toString(),
            ),
            content = DiagnosticRedactor.captureContent(arguments),
        )
        is StreamEvent.ToolCallRequest -> ParsedEventDetails(
            eventType = "ToolCallRequest",
            attributes = mapOf(
                "streamKey" to DiagnosticRedactor.safeIdentifier(streamKey),
                "id" to DiagnosticRedactor.safeIdentifier(id),
                "name" to DiagnosticRedactor.safeIdentifier(name),
                "argumentChars" to arguments.length.toString(),
            ),
            content = DiagnosticRedactor.captureContent(arguments),
        )
        is StreamEvent.ToolCallsRequest -> ParsedEventDetails(
            eventType = "ToolCallsRequest",
            attributes = mapOf("calls" to calls.size.toString()),
        )
        is StreamEvent.Retrying -> ParsedEventDetails(
            eventType = "Retrying",
            attributes = mapOf(
                "attempt" to attempt.toString(),
                "maxAttempts" to maxAttempts.toString(),
            ),
        )
    }

    private data class ParsedEventDetails(
        val eventType: String,
        val attributes: Map<String, String>,
        val content: CapturedDiagnosticText? = null,
    )

    private val SAFE_HTTP_ATTRIBUTE_KEYS = setOf(
        "acceptedDelayMs",
        "addresses",
        "bodyBytes",
        "bytes",
        "chars",
        "code",
        "messages",
        "protocol",
        "proxy",
        "tools",
        "version",
    )
    private val DETAIL_PAIR = Regex("""([A-Za-z][A-Za-z0-9_]*)=([^\s]+)""")
    private const val CAPTURE_DIRECTORY = "diagnostic-capture"
    private const val MAX_IDENTIFIER_LENGTH = 160
    private const val MAX_STAGE_LENGTH = 80
    private const val MAX_ATTRIBUTE_LENGTH = 80
}
