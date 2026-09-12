package com.newoether.agora.data

/**
 * Polls for new SMS messages and saves them to the store.
 *
 * Runs inside the HeartbeatScheduler's loop. The first poll (or a poll after the
 * sync state was reset) seeds the high-water mark to the current maximum inbox id
 * so existing history is never dumped into Room or the pending queue. Poll interval
 * gating lives in the scheduler and uses the persisted last-attempt/last-sync
 * timestamps, so a failing poll backs off at the configured interval instead of
 * retrying every scheduler tick.
 */
class SmsPoller(
    private val smsStore: SmsStore,
    private val smsReader: SmsReader,
) {
    /**
     * Performs a single poll for new SMS messages.
     * Called by HeartbeatScheduler on its loop, gated by the configured interval.
     */
    suspend fun poll() {
        if (!smsReader.isSupported()) return
        val attemptAt = System.currentTimeMillis()
        try {
            if (!smsReader.hasPermission()) {
                recordFailure(attemptAt, "Permission not granted")
                return
            }

            val state = smsStore.getSyncStateOnce()

            // First enable (or reset): record the current max id as the high-water
            // mark and skip the read — everything already in the inbox is history.
            if (state.lastSeenId == 0L) {
                smsStore.updateSyncState(
                    state.copy(
                        lastSyncEpochMs = attemptAt,
                        lastAttemptEpochMs = attemptAt,
                        lastError = null,
                        lastSeenId = smsReader.currentMaxInboxId(),
                    ),
                )
                return
            }

            val newMessages = smsReader.readNewMessages(state.lastSeenId, MAX_FETCH_PER_POLL)
            if (newMessages.isNotEmpty()) {
                val updated = state.copy(
                    lastSeenId = newMessages.maxOf { it.id },
                    lastSyncEpochMs = attemptAt,
                    lastAttemptEpochMs = attemptAt,
                    unreadCount = newMessages.count { !it.read },
                    lastError = null,
                )
                smsStore.saveMessages(newMessages, updated)
            } else {
                smsStore.updateSyncState(
                    state.copy(
                        lastSyncEpochMs = attemptAt,
                        lastAttemptEpochMs = attemptAt,
                        unreadCount = 0,
                        lastError = null,
                    ),
                )
            }
        } catch (e: Exception) {
            recordFailure(attemptAt, e.message ?: e::class.simpleName ?: "Poll failed")
        }
    }

    private suspend fun recordFailure(attemptAt: Long, error: String) {
        val state = smsStore.getSyncStateOnce()
        smsStore.updateSyncState(
            state.copy(
                lastAttemptEpochMs = attemptAt,
                lastError = error,
            ),
        )
    }

    companion object {
        const val MAX_FETCH_PER_POLL = 50
    }
}