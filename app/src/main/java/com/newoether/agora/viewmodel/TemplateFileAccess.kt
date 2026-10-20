package com.newoether.agora.viewmodel

import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.templates.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/** File operations are authorized against the current, native-opened card, not a JS-supplied grant. */
internal suspend fun ChatViewModel.accessTemplateFile(conversationId: String, messageId: String, callId: String,
    answer: InteractiveAnswer, request: TemplateFileRequest): JsonElement {
    check(verifyInteractiveAnswer(conversationId, messageId, callId, answer)) { "This template is no longer active" }
    val template = messagePayloadHydration.loadMessages(conversationId, listOf(messageId)).singleOrNull()
        ?.segments?.firstOrNull { it.toolCallId == callId }?.let(::interactiveTemplateOf)
        ?: error("File access requires a saved template")
    val result = withContext(Dispatchers.IO) {
        checkNotNull(interactiveTemplates.androidSources).execute(template, request)
    }
    check(verifyInteractiveAnswer(conversationId, messageId, callId, answer)) { "This template is no longer active" }
    return result
}
