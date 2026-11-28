package com.newoether.agora.model

/** Terminal detail is separate from generated answer text in current persisted messages. */
fun terminalErrorSegment(
    status: MessageStatus,
    participant: Participant,
    text: String,
    segments: List<MessageSegment>,
): MessageSegment? {
    if (status != MessageStatus.ERROR && participant != Participant.ERROR) return null
    segments.lastOrNull { it.type == "error" && it.content.isNotBlank() }?.let { return it }
    // Legacy error-only rows used text for the failure, but partial answers must not become errors.
    return text.takeIf { it.isNotBlank() && segments.none { it.type == "answer" && it.content.isNotBlank() } }
        ?.let { MessageSegment(type = "error", content = it) }
}
