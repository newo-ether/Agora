package com.newoether.agora.remote

import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.model.ToolExecutionStates

/** Foreign, unfinished and malformed tool data never becomes executable content. */
internal fun remoteInteractiveResult(activity: RemoteActivity): String? {
    if (activity.toolName != InteractiveAnswer.TOOL_NAME || activity.state != ToolExecutionStates.SUCCEEDED) return null
    val payload = activity.structuredResult?.toString() ?: return null
    if (payload.toByteArray(Charsets.UTF_8).size > 80_000) return null
    return runCatching { InteractiveAnswer.decode(payload).encode() }.getOrNull()
}
