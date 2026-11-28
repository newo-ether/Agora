package com.newoether.agora

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class TopLevelPresentation {
    CHAT,
    SETTINGS,
    TASKS,
    REMOTE,
    MEDIA_PREVIEW,
    TEXT_PREVIEW,
}

/** Single main-thread owner for the top-level surface currently covering Chat. */
@Stable
internal class TopLevelPresentationState(
    initialOwner: TopLevelPresentation = TopLevelPresentation.CHAT,
    private val onOwnerChanged: (TopLevelPresentation) -> Unit = {},
) {
    private val presentations = mutableListOf(initialOwner).apply {
        remove(TopLevelPresentation.CHAT)
    }
    var owner by mutableStateOf(initialOwner)
        private set

    init {
        onOwnerChanged(owner)
    }

    fun present(presentation: TopLevelPresentation) {
        // Chat is the base surface, not an overlay. Dismissed overlays retain ownership
        // until their exit callbacks release them, even when navigation requests Chat.
        if (presentation == TopLevelPresentation.CHAT) return
        presentations.remove(presentation)
        presentations.add(presentation)
        owner = presentation
        onOwnerChanged(owner)
    }

    /** A stale exiting surface cannot return ownership after a newer surface was presented. */
    fun release(presentation: TopLevelPresentation): Boolean {
        // An underlying surface may finish exiting while a preview still covers it.
        presentations.remove(presentation)
        if (owner != presentation) return false
        owner = presentations.lastOrNull() ?: TopLevelPresentation.CHAT
        onOwnerChanged(owner)
        return true
    }
}
