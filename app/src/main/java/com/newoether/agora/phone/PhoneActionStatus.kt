package com.newoether.agora.phone

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.tool.BuiltInToolNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Main-thread projection of the existing tool segments, with one coalescing display deadline. */
internal class PhoneActionStatus(
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    internal data class Activity(
        val runId: String,
        val callId: String,
        val toolName: String,
        val label: String,
        val running: Boolean,
    ) {
        fun sameCall(other: Activity?) = other?.runId == runId && other.callId == callId
    }

    private val current = MutableStateFlow<String?>(null)
    val name = current.asStateFlow()
    private var requested: Activity? = null
    private var displayed: Activity? = null
    private var shownAt: Long? = null
    private var completedAt: Long? = null
    private var pending: Job? = null
    private var idle: Job? = null

    /** Consecutive snapshots update one target; they never enqueue display events or device work. */
    fun present(activity: Activity?) {
        val next = activity ?: requested?.copy(running = false) ?: return
        val previous = requested
        if (next == previous && (next.sameCall(displayed) || pending?.isActive == true || !next.running)) return
        val sameCall = next.sameCall(previous)
        requested = next
        if (!sameCall || next.running) completedAt = null
        if (!next.running && completedAt == null) completedAt = now()
        idle?.cancel()
        idle = null
        if (sameCall && next.sameCall(displayed)) {
            displayed = next
            current.value = next.label
            scheduleIdle()
            return
        }
        if (sameCall && pending?.isActive == true) return
        pending?.cancel()
        pending = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            shownAt?.let { delay((200 - (now() - it)).coerceAtLeast(0)) }
            val latest = requested?.takeIf { next.sameCall(it) } ?: return@launch
            displayed = latest
            current.value = latest.label
            shownAt = now()
            scheduleIdle()
        }
    }

    /** Phone effects retain the minimum dwell before execution; other tools only update the UI. */
    suspend fun awaitVisible(
        activity: Activity,
        isCurrent: () -> Boolean = { activity.sameCall(requested) },
    ) {
        present(activity)
        while (true) {
            pending?.join()
            if (!isCurrent()) throw PhoneFailure("stale_identity")
            if (activity.sameCall(displayed) && activity.sameCall(requested)) return
            // A display task can be replaced without withdrawing the actual tool call.
            present(activity)
        }
    }

    private fun scheduleIdle() {
        val completion = completedAt ?: return
        val action = requested ?: return
        if (action.running || !action.sameCall(displayed)) return
        idle = scope.launch {
            val grace = 1000 - (now() - completion)
            val dwell = 200 - (now() - checkNotNull(shownAt))
            delay(maxOf(grace, dwell, 0))
            current.value = null
            displayed = null
            shownAt = null
        }
    }

    fun reset() {
        pending?.cancel()
        pending = null
        idle?.cancel()
        idle = null
        requested = null
        displayed = null
        shownAt = null
        completedAt = null
        current.value = null
    }

    companion object {
        /** All client, MCP and provider-hosted tools share the ordinary streaming segment owner. */
        fun activity(message: ChatMessage?, runId: String, toolName: String? = null): Activity? {
            if (message?.runId != runId) return null
            val tools = message.segments.orEmpty().withIndex().filter {
                it.value.type == "tool" && !it.value.toolName.isNullOrBlank() &&
                    (toolName == null || BuiltInToolNames.canonical(checkNotNull(it.value.toolName)) == toolName)
            }
            val selected = tools.lastOrNull { it.value.toolState == ToolExecutionStates.RUNNING }
                ?: tools.lastOrNull { it.value.toolState == ToolExecutionStates.CALLING }
                ?: tools.lastOrNull() ?: return null
            val segment = selected.value
            val name = BuiltInToolNames.canonical(checkNotNull(segment.toolName))
            return Activity(
                runId = runId,
                callId = segment.toolCallId ?: "${message.id}:${selected.index}",
                toolName = name,
                label = segment.toolDisplayName?.takeIf(String::isNotBlank) ?: name,
                running = segment.isRunning(),
            )
        }

        /** Validate the exact executing call, even while another tool is selected for display. */
        fun isRunning(message: ChatMessage?, activity: Activity): Boolean {
            if (message == null || message.runId != activity.runId) return false
            return message.segments.orEmpty().withIndex().any { (index, segment) ->
                segment.type == "tool" && segment.isRunning() &&
                    segment.toolName?.let(BuiltInToolNames::canonical) == activity.toolName &&
                    (segment.toolCallId ?: "${message.id}:$index") == activity.callId
            }
        }

        private fun MessageSegment.isRunning() = toolState == ToolExecutionStates.CALLING ||
            toolState == ToolExecutionStates.RUNNING
    }
}
