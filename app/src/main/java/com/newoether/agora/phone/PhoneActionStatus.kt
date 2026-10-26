package com.newoether.agora.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Main-thread presentation pacing; no queued device work or lease ownership. */
internal class PhoneActionStatus(
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val current = MutableStateFlow<String?>(null)
    val name = current.asStateFlow()
    private var shownAt: Long? = null
    private var idle: Job? = null
    private var revision = 0L

    suspend fun update(action: String?) {
        idle?.cancel()
        idle = null
        if (action == null) {
            if (current.value != null) idle = scope.launch {
                delay(1000)
                current.value = null
                shownAt = null
            }
            return
        }
        val admittedRevision = revision
        shownAt?.let { delay((200 - (now() - it)).coerceAtLeast(0)) }
        if (revision != admittedRevision) throw PhoneFailure("overlay_unavailable")
        current.value = action
        // Repeated identical actions also renew their minimum dwell and idle grace.
        shownAt = now()
    }

    fun reset() {
        revision++
        idle?.cancel()
        idle = null
        shownAt = null
        current.value = null
    }
}
