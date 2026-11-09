package com.newoether.agora.viewmodel

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** One cancellable operation registered with a conversation-owned [StreamScope]. */
fun interface GenerationCancelHandle {
    fun cancel()
}

/**
 * Per-conversation collection of in-flight HTTP streaming handles. [cancelAll] severs only the
 * streams opened under this scope, so stopping one conversation cannot cancel another one.
 */
class StreamScope {
    private val handles = Collections.newSetFromMap(
        ConcurrentHashMap<GenerationCancelHandle, Boolean>(),
    )

    fun register(handle: GenerationCancelHandle) {
        handles.add(handle)
    }

    fun unregister(handle: GenerationCancelHandle) {
        handles.remove(handle)
    }

    fun cancelAll() {
        handles.toList().forEach { runCatching { it.cancel() } }
        handles.clear()
    }
}
