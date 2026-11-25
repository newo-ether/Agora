package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.mutableStateListOf

/**
 * Bounds how many preview WebViews run at once. Android WebView pages in one app share a
 * renderer, so many live previews starve each other's first frame and raise renderer OOM risk.
 * The most recently activated previews keep their slot; older ones show a manual Run button.
 * Main thread only.
 */
internal object HtmlPreviewLiveSlots {
    const val MAX_LIVE = 2

    private val live = mutableStateListOf<Any>()

    fun acquire(token: Any) {
        live.remove(token)
        live.add(token)
        while (live.size > MAX_LIVE) live.removeAt(0)
    }

    fun release(token: Any) {
        live.remove(token)
    }

    fun isLive(token: Any): Boolean = token in live
}
