package com.newoether.agora.assistant

import android.content.Context
import android.util.Log
import com.newoether.agora.AgoraApplication
import com.newoether.agora.automation.TaskExecutionEngine

/**
 * Bridges the system-assistant surfaces (assist session, overlay, and later
 * AppFunctions) to Agora's existing headless agent engine.
 *
 * This is the single controller every assistant front end calls — no tool or
 * model logic is duplicated here. It runs a turn through
 * [TaskExecutionEngine.runOnce], which already has MCP tools, automation tools,
 * sub-agents, and document search wired in, so the assistant inherits Agora's
 * full capability set for free.
 *
 * Design note: Agora uses manual DI via [AgoraApplication.awaitContainer]; the
 * engine and repositories are process-scoped singletons on the container.
 */
class AssistantController(private val app: AgoraApplication) {

    /**
     * Run one assistant turn. [screenContext] is optional text captured from the
     * foreground app (via the assist AssistStructure); [userIntent] is what the
     * user asked. Returns the assistant's final text, or a short error string.
     *
     * Call from a coroutine on a foreground Service/Worker (so the engine's
     * foreground-service importance gate is satisfied).
     */
    suspend fun ask(
        screenContext: String,
        userIntent: String,
        modelId: String? = null,
    ): String {
        val container = app.awaitContainer()
        if (container == null) {
            Log.w(TAG, "container not ready (db gate)")
            return "Agora is not ready yet."
        }

        val userText = buildString {
            val ctx = screenContext.trim()
            if (ctx.isNotEmpty()) {
                append("[Screen context]\n")
                append(ctx.take(MAX_SCREEN_CONTEXT))
                append("\n\n")
            }
            append(userIntent.trim())
        }

        return try {
            // A dedicated, throwaway conversation per invocation keeps assistant
            // turns out of the user's chat history. Reusing a pinned "assistant"
            // conversation is a future option.
            val conversationId = container.conversationRepository.createConversation(
                title = ASSISTANT_TITLE,
            )
            when (val r = container.taskExecutionEngine.runOnce(
                conversationId = conversationId,
                userText = userText,
                modelId = modelId,
                requestKind = "assistant",
            )) {
                is TaskExecutionEngine.Result.Success -> r.text
                is TaskExecutionEngine.Result.Busy -> "Agora is busy: ${r.reason}"
                is TaskExecutionEngine.Result.Failure -> "Agora error: ${r.reason}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "assistant turn failed: ${e.message}", e)
            "Agora error: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "AgoraAssistant"
        private const val MAX_SCREEN_CONTEXT = 4000
        private const val ASSISTANT_TITLE = "Assistant"

        /** Convenience accessor from any Context. */
        fun from(context: Context): AssistantController =
            AssistantController(context.applicationContext as AgoraApplication)
    }
}
