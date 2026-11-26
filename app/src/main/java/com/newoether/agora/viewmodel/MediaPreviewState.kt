package com.newoether.agora.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Transient preview state for the in-chat PDF page viewer and text-file viewer.
 *
 * Extracted from [ChatViewModel] so the preview lifecycle (open / replace / clear)
 * is a single self-contained responsibility, independent of chat and generation
 * concerns. The two viewers are mutually exclusive in the UI but tracked separately
 * so each can be cleared without disturbing the other's last-shown content.
 *
 * Text shown in the viewer is capped at [TEXT_PREVIEW_MAX_CHARS]; laying out more than that in
 * Compose text crashes or stalls the app. [onTextTruncated] reports each cut so the UI can say so.
 */
class MediaPreviewState(private val onTextTruncated: (limit: Int) -> Unit = {}) {
    private val _pdfPages = MutableStateFlow<List<String>>(emptyList())
    val pdfPages: StateFlow<List<String>> = _pdfPages.asStateFlow()
    private val _pdfIndex = MutableStateFlow(0)
    val pdfIndex: StateFlow<Int> = _pdfIndex.asStateFlow()

    private val _fileContent = MutableStateFlow<String?>(null)
    val fileContent: StateFlow<String?> = _fileContent.asStateFlow()
    private val _fileName = MutableStateFlow<String?>(null)
    val fileName: StateFlow<String?> = _fileName.asStateFlow()

    fun showPdf(pages: List<String>, startIndex: Int) {
        _pdfPages.value = pages
        _pdfIndex.value = startIndex
    }

    fun showFile(fileName: String, content: String) {
        val capped = capTextPreview(content)
        _fileName.value = fileName
        _fileContent.value = capped
        if (capped.length < content.length) onTextTruncated(TEXT_PREVIEW_MAX_CHARS)
    }

    fun clear() {
        _pdfPages.value = emptyList()
        _fileContent.value = null
    }

    companion object {
        const val TEXT_PREVIEW_MAX_CHARS = 100_000
    }
}

/**
 * Cuts [content] to at most [limit] chars, preferring the last line break in the final tenth so
 * the viewer does not end mid-line, and never splitting a surrogate pair.
 */
internal fun capTextPreview(content: String, limit: Int = MediaPreviewState.TEXT_PREVIEW_MAX_CHARS): String {
    if (content.length <= limit) return content
    val lineEnd = content.lastIndexOf('\n', limit - 1)
    var end = if (lineEnd >= limit - limit / 10) lineEnd else limit
    if (end > 0 && Character.isHighSurrogate(content[end - 1])) end--
    return content.substring(0, end)
}
