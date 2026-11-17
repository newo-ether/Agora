package com.newoether.agora.viewmodel

import com.newoether.agora.model.SelectedAttachment

internal data class ConversationComposerSnapshot(
    val text: String = "",
    val attachments: List<SelectedAttachment> = emptyList(),
    val pdfPreviewProgress: Map<String, Pair<Int, Int>> = emptyMap(),
    val revision: Long = 0L,
    val textProjectionVersion: Long = 0L,
    val textProjectionExpectedText: String? = null,
    val loaded: Boolean = false,
    val ingressRetryVersion: Long = 0L,
)
