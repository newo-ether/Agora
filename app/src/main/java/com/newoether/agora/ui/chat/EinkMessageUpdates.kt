package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

internal const val E_INK_UPDATE_INTERVAL_MS = 1_000L

/** Identity/lifecycle changes are not growing text and must never wait behind a presentation tick. */
internal fun sameEinkStreamingUpdate(before: List<ChatMessage>, after: List<ChatMessage>): Boolean {
    if (!sameStreamingRenderStructure(before, after)) return false
    return before.indices.all { index ->
        val a = before[index]
        val b = after[index]
        a.runId == b.runId && a.runSequence == b.runSequence &&
            a.consumedAtPass == b.consumedAtPass &&
            (a == b || b.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING,
                MessageStatus.TOOL_CALLING, MessageStatus.TRANSCRIBING))
    }
}

/**
 * One pending latest snapshot, one cancellable timer. This is disposable presentation only;
 * neither the controller nor Room reads it. Collection cancellation drops the pending snapshot.
 */
internal fun Flow<List<ChatMessage>>.coalesceEinkMessages(): Flow<List<ChatMessage>> = channelFlow {
    val events = Channel<List<ChatMessage>>(Channel.CONFLATED)
    var shown: List<ChatMessage>? = null
    var pending: List<ChatMessage>? = null
    var timer: Job? = null
    val collector = launch { collect { events.send(it) }; events.close() }
    try {
        for (next in events) {
            val previous = shown
            if (previous == null || !sameEinkStreamingUpdate(previous, next)) {
                timer?.cancel()
                timer = null
                pending = null
                shown = next
                send(next)
            } else if (next != previous) {
                pending = next
                if (timer == null) {
                    timer = launch {
                        delay(E_INK_UPDATE_INTERVAL_MS)
                        pending?.let { latest -> shown = latest; pending = null; send(latest) }
                        timer = null
                    }
                }
            }
        }
        timer?.cancel()
        pending?.let { send(it) }
    } finally {
        timer?.cancel()
        collector.cancel()
        events.cancel()
    }
}
