package com.newoether.agora

import android.content.Intent
import android.os.Bundle
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One selected-text invocation. Equal selections remain separate user actions. */
data class ProcessTextRequest(val id: String, val text: String)

/** Pending UI ingress only; durable text remains owned by the conversation Composer. */
class ProcessTextInbox {
    private val pending = MutableStateFlow<List<ProcessTextRequest>>(emptyList())
    val requests: StateFlow<List<ProcessTextRequest>> = pending.asStateFlow()

    fun receive(intent: Intent?): Boolean {
        val text = selectedProcessText(intent) ?: return false
        pending.value = pending.value + ProcessTextRequest(UUID.randomUUID().toString(), text)
        return true
    }

    fun consume(requestId: String) {
        pending.value = pending.value.filterNot { it.id == requestId }
    }

    fun save(): Bundle = Bundle().apply {
        putStringArrayList(REQUEST_IDS, ArrayList(pending.value.map { it.id }))
        putStringArrayList(REQUEST_TEXTS, ArrayList(pending.value.map { it.text }))
    }

    fun restore(saved: Bundle?) {
        val ids = saved?.getStringArrayList(REQUEST_IDS).orEmpty()
        val texts = saved?.getStringArrayList(REQUEST_TEXTS).orEmpty()
        pending.value = ids.zip(texts) { id, text -> ProcessTextRequest(id, text) }
    }

    companion object {
        internal const val SAVED_STATE_KEY = "agora.processTextInbox"
        private const val REQUEST_IDS = "ids"
        private const val REQUEST_TEXTS = "texts"
    }
}

internal fun selectedProcessText(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_PROCESS_TEXT || intent.type != "text/plain") return null
    // Android may supply a styled CharSequence. Preserve its exact plain text, not its spans.
    return runCatching { intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() }
        .getOrNull()?.takeIf { it.isNotBlank() }
}
