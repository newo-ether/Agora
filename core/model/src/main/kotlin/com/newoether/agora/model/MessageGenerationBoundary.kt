package com.newoether.agora.model

/**
 * One visible generation group.
 *
 * Every row participates in Run grouping. A real USER is always a hard boundary, while rows with
 * one nonblank durable Run id remain indivisible. This policy locates Regenerate/UI scope only; it
 * has no authority over Provider context assembly.
 */
data class MessageGenerationBoundary(
    val messages: List<ChatMessage>,
) {
    val input: ChatMessage? =
        messages.firstOrNull(MessageGenerationBoundaryResolver::isRealUser)
    val firstAssistant: ChatMessage? =
        messages.firstOrNull(MessageGenerationBoundaryResolver::isOrdinaryAssistant)
    val lastAssistant: ChatMessage? =
        messages.lastOrNull(MessageGenerationBoundaryResolver::isOrdinaryAssistant)
}

object MessageGenerationBoundaryResolver {
    fun resolve(visibleMessages: List<ChatMessage>): List<MessageGenerationBoundary> {
        val boundaries = mutableListOf<MessageGenerationBoundary>()
        val current = mutableListOf<ChatMessage>()
        var currentRunId: String? = null

        fun finishBoundary() {
            if (current.isNotEmpty()) {
                boundaries += MessageGenerationBoundary(current.toList())
                current.clear()
                currentRunId = null
            }
        }

        visibleMessages.distinctBy(ChatMessage::id).forEach { message ->
            val messageRunId = message.runId.orEmpty().takeIf(String::isNotBlank)
            if (isRealUser(message)) {
                finishBoundary()
            } else if (
                current.isNotEmpty() &&
                currentRunId != null &&
                messageRunId != null &&
                currentRunId != messageRunId
            ) {
                finishBoundary()
            }
            current += message
            if (currentRunId == null) currentRunId = messageRunId
        }
        finishBoundary()
        return boundaries
    }

    fun containing(
        visibleMessages: List<ChatMessage>,
        messageId: String,
    ): MessageGenerationBoundary? = resolve(visibleMessages).firstOrNull { boundary ->
        boundary.messages.any { it.id == messageId }
    }

    fun isRealUser(message: ChatMessage): Boolean =
        message.participant == Participant.USER &&
            !isProtocolRow(message) &&
            !message.isContextCompact()

    fun isOrdinaryAssistant(message: ChatMessage): Boolean =
        message.participant == Participant.MODEL &&
            !isProtocolRow(message) &&
            !message.isContextCompact()

    private fun isProtocolRow(message: ChatMessage): Boolean =
        message.id.startsWith(ModelConstants.TOOL_MSG_PREFIX) ||
            message.id.startsWith(ModelConstants.RESULT_MSG_PREFIX)
}
