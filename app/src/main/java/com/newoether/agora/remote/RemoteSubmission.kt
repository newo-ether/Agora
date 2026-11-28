package com.newoether.agora.remote

import com.newoether.agora.viewmodel.ScrollRequestCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** The existing send/receipt lifecycle, bound to the admitted access path and canonical draft. */
internal class RemoteSubmission(
    private val scope: CoroutineScope,
    private val mutableState: MutableStateFlow<RemoteState>,
    private val clientFor: (RemoteState) -> FiloClient?,
    private val sameSource: (RemoteState, FiloClient) -> Boolean,
    private val selectionEpoch: () -> Long,
    private val visible: () -> Boolean,
    private val refresh: () -> Unit,
    private val attachmentStore: RemoteAttachmentStore?,
    private val scrollRequests: ScrollRequestCoordinator,
    private val report: (String, Exception, Boolean) -> RemoteFailure?,
    private val notify: (RemoteNotice) -> Unit,
    private val settingsConflict: (RemoteState) -> Boolean,
) {
    fun send() {
        val snapshot = mutableState.value
        if (!visible() || snapshot.controlling || snapshot.controlPending || snapshot.isStopping || snapshot.settingsPending ||
            settingsConflict(snapshot) || !snapshot.canSend) return
        val owner = snapshot.owner ?: return
        val client = clientFor(snapshot) ?: return
        val text = snapshot.drafts[owner].orEmpty()
        val attachments = snapshot.attachments[owner].orEmpty()
        val previous = snapshot.attempts[owner]
        if ((text.isBlank() && attachments.isEmpty()) || previous?.delivery in
            setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED) ||
            previous?.delivery == RemoteDelivery.UNKNOWN && (previous.receiptKind == "submitted_observed" || previous.accessKey != snapshot.attemptAccessKey)) return
        if (snapshot.isDraft && snapshot.capabilities.models && snapshot.settingsModel == null) {
            report("models_failed", FiloInputException(), true)
            return
        }
        if (attachments.isNotEmpty() && !snapshot.capabilities.uploads) return
        if (attachments.any { it.importState != com.newoether.agora.model.AttachmentImportState.READY }) {
            report("send_failed", RemoteAttachmentException("Finish or remove pending attachments before sending"), true)
            return
        }
        val selected = selectionEpoch()
        val attempt = RemoteAttempt(UUID.randomUUID().toString(), text, RemoteDelivery.SUBMITTING,
            accessKey = snapshot.attemptAccessKey,
            receiptKind = if (snapshot.selectedAdapter.family == "claude" && !snapshot.isDraft) "submitted_observed" else "")
        mutableState.value = snapshot.copy(attempts = snapshot.attempts + (owner to attempt))
        scope.launch {
            var uploadComplete = false
            try {
                if (!sameSource(snapshot, client)) return@launch
                val uploads = attachments.map { client.upload(it) }
                uploadComplete = true
                if (!sameSource(snapshot, client)) throw FiloInputException()
                var acceptedTurnId: String?
                var sendReceipt: RemoteSendReceipt? = null
                if (snapshot.isDraft) {
                    val settings = snapshot.settingsModel?.let {
                        RemoteSettings(it.id, snapshot.selectedEffort, snapshot.selectedServiceTier,
                            updateServiceTier = it.serviceTiers != null)
                    }
                    val created = client.create(text, attempt.clientId, uploads.map { it.id }, settings)
                    if (!sameSource(snapshot, client)) return@launch
                    if (!snapshot.selectedAdapter.legacy) {
                        val access = requireNotNull(created.access)
                        require(access.nativeScopeId == snapshot.selectedAdapter.nativeScopeId &&
                            access.canonicalSessionRef.isNotBlank())
                    }
                    acceptedTurnId = created.acceptedTurnId
                    val createdState = snapshot.copy(session = created, draftSessionId = null)
                    val key = snapshot.sessionKey(created.id)
                    val canonical = createdState.canonicalOwner(created)
                    mutableState.value = mutableState.value.copy(sessionOwners = mutableState.value.sessionOwners +
                        (key to owner) + (canonical to owner))
                    if (selected == selectionEpoch() && mutableState.value.accessOwner == snapshot.accessOwner &&
                        mutableState.value.attempts[owner]?.clientId == attempt.clientId) {
                        mutableState.value = mutableState.value.copy(session = created, lastKnownModel = snapshot.selectedModel)
                        refresh()
                    }
                } else {
                    // Navigation never transfers an admitted write to a different native route.
                    sendReceipt = if (snapshot.selectedAdapter.family == "claude") {
                        client.sendReceipt(snapshot.session!!.id, text, attempt.clientId, uploads.map { it.id })
                    } else RemoteSendReceipt(client.send(snapshot.session!!.id, text, attempt.clientId, uploads.map { it.id }), attempt.clientId)
                    acceptedTurnId = sendReceipt.turnId.takeIf { it.isNotBlank() }
                }
                if (!sameSource(snapshot, client)) return@launch
                val current = mutableState.value.attempts[owner]
                if (current?.clientId == attempt.clientId && current.delivery == RemoteDelivery.SUBMITTING) {
                    mutableState.value = mutableState.value.copy(attempts = mutableState.value.attempts +
                        (owner to current.copy(delivery = if (sendReceipt?.state == "unconfirmed") RemoteDelivery.UNKNOWN else RemoteDelivery.ACCEPTED,
                            turnId = acceptedTurnId, receiptKind = sendReceipt?.kind.orEmpty())))
                    confirm(owner, attempt, sendReceipt?.let { listOf(RemoteInputReceipt(it.clientId, it.turnId, it.messageId, it.kind, it.state)) }.orEmpty())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val rejected = !uploadComplete || error is FiloInputException ||
                    error is FiloHttpException && error.status in setOf(400, 401, 403, 404, 409, 413, 415, 429)
                val failure = report("send_failed", error, false)
                if (!sameSource(snapshot, client)) return@launch
                val current = mutableState.value.attempts[owner]
                if (current?.clientId == attempt.clientId && current.delivery == RemoteDelivery.SUBMITTING) {
                    if (failure != null) notify(RemoteNotice("send_failed", failure, selected,
                        remoteErrorDetail(error).takeIf { rejected }, if (rejected) remoteErrorCode(error) else "delivery_unconfirmed"))
                    mutableState.value = mutableState.value.copy(
                        failure = if (mutableState.value.owner == owner && mutableState.value.accessOwner == snapshot.accessOwner &&
                            failure == RemoteFailure.SESSION_BUSY) failure else mutableState.value.failure,
                        attempts = mutableState.value.attempts +
                            (owner to current.copy(delivery = if (rejected) RemoteDelivery.REJECTED else RemoteDelivery.UNKNOWN)))
                }
            }
        }
    }

    fun confirm(owner: String, attempt: RemoteAttempt, receipts: List<RemoteInputReceipt> = emptyList()) {
        val snapshot = mutableState.value
        val current = snapshot.attempts[owner]?.takeIf { it.clientId == attempt.clientId } ?: return
        if (current.delivery == RemoteDelivery.DELIVERED || snapshot.owner != owner ||
            current.accessKey != snapshot.confirmationPath) return
        val observation = receipts.firstOrNull { it.clientId == current.clientId && it.kind == "submitted_observed" }
        if (observation != null && observation.state != "observed") {
            val delivery = when {
                current.delivery == RemoteDelivery.UNKNOWN && observation.state == "submitted" -> RemoteDelivery.UNKNOWN
                observation.state == "submitted" -> RemoteDelivery.ACCEPTED
                observation.state == "rejected" -> RemoteDelivery.REJECTED
                else -> RemoteDelivery.UNKNOWN
            }
            mutableState.value = snapshot.copy(attempts = snapshot.attempts +
                (owner to current.copy(delivery = delivery, receiptKind = observation.kind)))
            return
        }
        val message = snapshot.nodes.firstOrNull { it.role == "user" && it.clientId == attempt.clientId &&
            (current.turnId == null || current.turnId == it.turnId) }
        val receipt = receipts.firstOrNull { it.clientId == current.clientId &&
            (it.kind == "submitted_observed" && it.state == "observed" && (it.turnId.isNotBlank() || it.messageId.isNotBlank()) ||
                it.kind == "" && it.turnId.isNotBlank() && it.messageId.isNotBlank() && (current.turnId == null || current.turnId == it.turnId)) }
        if (message == null && receipt == null) return
        snapshot.attachments[owner].orEmpty().forEach { attachmentStore?.remove(it) }
        mutableState.value = snapshot.copy(attachments = snapshot.attachments - owner,
            drafts = if (snapshot.drafts[owner] == attempt.text) snapshot.drafts - owner else snapshot.drafts,
            attempts = snapshot.attempts + (owner to current.copy(delivery = RemoteDelivery.DELIVERED, receiptKind = receipt?.kind ?: current.receiptKind)))
        val target = message?.let { it.nativeId ?: it.id } ?: snapshot.messageGroups.lastOrNull()?.stub?.id
        if (target != null) scrollRequests.requestAbsoluteBottomAfter(owner, target)
    }
}
