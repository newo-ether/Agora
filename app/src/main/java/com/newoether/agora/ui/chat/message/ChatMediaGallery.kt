package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.ChatMessage

/**
 * Every image and video the chat displays, in message order. The full-screen viewer is opened on
 * this list so a swipe left/right reaches the conversation's other media, and the pager's
 * "n / N" capsule reports the position.
 */
internal fun chatMediaGallery(messages: List<ChatMessage>): List<String> =
    messages
        .flatMap { message ->
            val attachments = projectStoredMediaOccurrences(
                storedMediaDisplayItems(message).map { (_, path, item) -> path to item },
                message.imageArtifacts,
            ).urls
            val toolImages = message.segments.orEmpty().flatMap { it.toolImages }.map { it.path }
            val markdownImages = message.markdownImages.values.mapNotNull { it.attachment?.path }
            attachments + toolImages + markdownImages
        }
        .filter { it.isNotBlank() }
        .distinct()
