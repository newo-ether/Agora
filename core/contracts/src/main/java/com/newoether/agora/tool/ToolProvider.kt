package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface ToolExecutionEvent {
    /** Incremental user-visible output. It is never sent to the model as a partial result. */
    data class OutputDelta(val text: String) : ToolExecutionEvent

    /** Bounded full output as of now. Consumers replace the visible body instead of appending. */
    data class OutputSnapshot(val text: String) : ToolExecutionEvent

    /** The concrete device selected after resolving optional tool arguments. */
    data class TargetResolved(val target: String, val displayName: String? = null) : ToolExecutionEvent

    /** A low-volume lifecycle update. It is not command output. */
    data class Progress(val message: String) : ToolExecutionEvent

    /** Exactly one authoritative model-facing result. */
    data class Completed(val result: ToolExecutionResult) : ToolExecutionEvent {
        constructor(text: String) : this(ToolExecutionResult(text = text))
    }
}

/**
 * Provider-neutral tool output. Text remains the protocol-facing result while images are
 * persisted as private files and projected into a normal user multimodal turn for the next model
 * round. Structured JSON stays distinct so arbitrary text is never classified by its spelling.
 */
data class ToolExecutionResult(
    val text: String,
    val images: List<ToolImageAttachment> = emptyList(),
    val structuredContent: String? = null,
    /** Human-readable content for UI display when [text] also carries protocol JSON/attachments. */
    val displayText: String? = null,
    val isError: Boolean = false,
    /**
     * Declares that [images] are INPUT the model must understand (e.g. file_view_image), so the tool
     * batch executor runs the per-generation image transcription flow over them and appends the
     * description to [text]. Output images (image generation) never set this.
     */
    val transcribeImages: Boolean = false,
)

/** Provider-owned presentation metadata resolved without exposing protocol routing IDs to the UI. */
data class ToolPresentationMetadata(
    val displayName: String,
    val target: String? = null,
)

/**
 * Interface for tool providers that supply tool definitions and execution
 * logic to the LLM generation pipeline. Each implementation manages a
 * specific category of tools (memory, web search, RAG, shell, etc.).
 */
interface ToolProvider {
    /** The tool definitions this provider exposes for the given context.
     *  Returns empty list when the provider is disabled. */
    fun definitions(ctx: GenerationContext): List<ToolDefinition>

    /** Execute a named tool with the given JSON arguments string.
     *  Returns the result string (usually JSON). */
    suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String

    /** One-shot result metadata; arbitrary successful text must not imply an error. */
    suspend fun executeOneShotResult(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult = ToolExecutionResult(execute(name, arguments, ctx))

    /**
     * Streaming execution contract. One-shot providers inherit the adapter; streaming providers
     * emit progress/deltas and finish with exactly one [ToolExecutionEvent.Completed].
     */
    fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        emit(ToolExecutionEvent.Completed(executeOneShotResult(name, arguments, ctx)))
    }

    /** Whether this provider can execute the given tool name. */
    fun handles(name: String): Boolean

    /**
     * Resolve stable UI metadata from the protocol name and optional raw arguments. Streaming
     * callers may supply an unfinished JSON prefix; providers must bound that inspection.
     * Built-in tools keep their localized presentation path by default.
     */
    fun presentationMetadata(name: String, arguments: String? = null): ToolPresentationMetadata? = null
}
