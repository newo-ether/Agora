package com.newoether.agora.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticCaptureState {
    IDLE,
    RUNNING,
    PAUSED,
}

@Serializable
enum class DiagnosticExportFormat {
    REDACTED_JSON,
    SUMMARY_TEXT,
}

@Serializable
internal data class DiagnosticCaptureMetadata(
    val schemaVersion: Int = 1,
    val state: DiagnosticCaptureState = DiagnosticCaptureState.IDLE,
    val sessionId: String? = null,
    val startedAtMillis: Long? = null,
    val nextSequence: Long = 1L,
    val droppedEventCount: Long = 0L,
    val evictedEventCount: Long = 0L,
    val truncatedPayloadCount: Long = 0L,
    val capacityLimitReached: Boolean = false,
)

@Serializable
data class DiagnosticSession(
    val id: String,
    val startedAtMillis: Long,
)

@Serializable
data class CapturedDiagnosticText(
    val value: String,
    val originalLength: Int,
    val truncated: Boolean,
    /** True when a redaction policy was applied, even if no matching secret was present. */
    val redacted: Boolean,
)

@Serializable
sealed interface DiagnosticEventPayload {
    @Serializable
    data class RuntimeTransition(
        val oldState: String,
        val commandType: String,
        val newState: String,
        val effectId: String?,
        val effectTypes: List<String>,
    ) : DiagnosticEventPayload

    @Serializable
    data class HttpStage(
        val stage: String,
        val elapsedMillis: Long,
        val attributes: Map<String, String>,
    ) : DiagnosticEventPayload

    @Serializable
    data class HttpRequest(
        val method: String,
        val url: CapturedDiagnosticText,
        val headers: Map<String, String>,
        val body: CapturedDiagnosticText,
    ) : DiagnosticEventPayload

    @Serializable
    data class HttpResponseBody(
        val code: Int,
        val body: CapturedDiagnosticText,
    ) : DiagnosticEventPayload

    @Serializable
    data class WireLine(
        val lineNumber: Long,
        val line: CapturedDiagnosticText,
    ) : DiagnosticEventPayload

    @Serializable
    data class ParsedStreamEvent(
        val eventType: String,
        val attributes: Map<String, String>,
        val content: CapturedDiagnosticText?,
    ) : DiagnosticEventPayload
}

@Serializable
data class DiagnosticEvent(
    val sequence: Long,
    val timestampMillis: Long,
    val context: DiagnosticRequestContext,
    val payload: DiagnosticEventPayload,
)

data class DiagnosticSnapshot(
    val state: DiagnosticCaptureState = DiagnosticCaptureState.IDLE,
    val session: DiagnosticSession? = null,
    val events: List<DiagnosticEvent> = emptyList(),
    val nextSequence: Long = 1L,
    val retainedPayloadBytes: Long = 0L,
    val droppedEventCount: Long = 0L,
    val evictedEventCount: Long = 0L,
    val truncatedPayloadCount: Long = 0L,
    val capacityLimitReached: Boolean = false,
) {
    val isCaptureActive: Boolean get() = state == DiagnosticCaptureState.RUNNING
}
