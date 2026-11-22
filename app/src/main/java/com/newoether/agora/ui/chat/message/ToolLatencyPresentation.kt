package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageSegment

/**
 * A span under a millisecond gets no line, since "0 ms" tells the reader nothing. Display-only:
 * the value never reaches the model-facing result.
 */
internal fun toolLatencyLabel(seg: MessageSegment): String? =
    seg.durationMs?.takeIf { it > 0L }?.let(::formatLatency)
