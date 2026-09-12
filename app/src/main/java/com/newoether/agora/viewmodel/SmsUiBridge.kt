package com.newoether.agora.viewmodel

import com.newoether.agora.data.SmsDraft
import com.newoether.agora.data.SmsDraftStore
import com.newoether.agora.data.SmsPoller
import com.newoether.agora.data.SmsStore
import com.newoether.agora.data.SmsSyncState
import com.newoether.agora.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * SMS UI surface owned by [ChatViewModel]: the draft flow the review banner renders,
 * the sync/pending state the SMS settings section shows, and the user-triggered
 * actions. Extracted from ChatViewModel so the ViewModel stays within the 999-line
 * source policy instead of accumulating SMS responsibilities.
 */
class SmsUiBridge(
    private val draftStore: SmsDraftStore,
    private val smsStore: SmsStore,
    private val smsPoller: SmsPoller,
    private val smsSender: SmsSender,
    private val scope: CoroutineScope,
) {
    /** Current drafts (PENDING/SENDING/FAILED visible in the review banner). */
    val smsDrafts: Flow<List<SmsDraft>> = draftStore.drafts

    /** Pending SMS count + sync state for the SMS settings section. */
    val smsPendingCount: Flow<Int> = smsStore.pendingCount
    val smsSyncState: Flow<SmsSyncState> = smsStore.syncState

    /** User-triggered send from the review banner — the only path that dispatches SMS. */
    fun sendSmsDraft(draftId: String) {
        scope.launch { draftStore.sendDraft(draftId, smsSender) }
    }

    fun discardSmsDraft(draftId: String) {
        scope.launch { draftStore.removeDraft(draftId) }
    }

    /** One-shot poll for the "Refresh now" action in SMS settings. */
    fun refreshSmsNow() {
        scope.launch { smsPoller.poll() }
    }
}