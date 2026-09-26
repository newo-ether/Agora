package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.model.SelectedAttachment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ComposerSubmissionPhase {
    IDLE,
    WAITING,
    SUBMITTING,
    ACCEPTED_PENDING_CLEAR,
}

internal data class ConversationComposerSubmissionSnapshot(
    val phase: ComposerSubmissionPhase = ComposerSubmissionPhase.IDLE,
    val requestId: Long? = null,
    val frozenText: String = "",
    val frozenAttachmentIds: List<String> = emptyList(),
    val acceptedVersion: Long = 0L,
    val directAcceptedVersion: Long = 0L,
    val directAcceptedNewChatEntryId: Long? = null,
) {
    val isFrozen: Boolean
        get() = phase != ComposerSubmissionPhase.IDLE
    val isWaiting: Boolean
        get() = phase == ComposerSubmissionPhase.WAITING
    val isSubmitting: Boolean
        get() = phase == ComposerSubmissionPhase.SUBMITTING
    val isAcceptedPendingClear: Boolean
        get() = phase == ComposerSubmissionPhase.ACCEPTED_PENDING_CLEAR
}

internal typealias ComposerSubmissionTargetCapture =
    (ownerId: String) -> ForegroundSendTarget?
internal typealias ComposerSubmissionPrepare =
    suspend (ForegroundSendTarget, ConversationComposerSnapshot) -> ForegroundSendAdmission?
internal typealias ComposerSubmissionSend = suspend (
    admission: ForegroundSendAdmission,
    text: String,
    attachments: List<SelectedAttachment>,
    onAccepted: suspend (SendAcceptance) -> Unit,
) -> SendAcceptance?

/** Owns every pre-acceptance Composer submission independently of the visible composition. */
internal class ConversationComposerSubmissionController(
    private val scope: CoroutineScope,
    private val composers: ConversationComposerController,
    private val drafts: ComposerDraftController,
    private val captureTarget: ComposerSubmissionTargetCapture,
    private val prepare: ComposerSubmissionPrepare,
    private val send: ComposerSubmissionSend,
    private val onAcceptedClearFailed: (
        ownerId: String,
        retry: () -> Unit,
    ) -> Unit = { _, _ -> },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private class OwnerSubmission {
        val state = MutableStateFlow(ConversationComposerSubmissionSnapshot())
        var request: FrozenRequest? = null
        var job: Job? = null
        var cancelWaitingRequested = false
        var observerCount = 0
        var wasObserved = false
    }

    private class FrozenRequest(
        val id: Long,
        val target: ForegroundSendTarget,
        val text: String,
        val attachmentIds: List<String>,
    ) {
        val ownerId: String get() = target.ownerId
        val acceptanceStarted = AtomicBoolean(false)
        val acceptedAndCleared = AtomicBoolean(false)
        var accepted: SendAcceptance? = null
        var frozenRevision: Long? = null
        var expectedNewChatWorkspace: NewChatPersistEntity? = null
        var durableAttachmentIds: Set<String> = emptySet()
    }

    private val ownersLock = Any()
    private val owners = mutableMapOf<String, OwnerSubmission>()
    private val nextRequestId = AtomicLong(0L)
    private val _activeOwnerIds = MutableStateFlow<Set<String>>(emptySet())
    val activeOwnerIds: StateFlow<Set<String>> = _activeOwnerIds.asStateFlow()

    fun state(ownerId: String): StateFlow<ConversationComposerSubmissionSnapshot> =
        owner(ownerId).state

    fun observeState(ownerId: String): StateFlow<ConversationComposerSubmissionSnapshot> =
        synchronized(ownersLock) {
            owners.getOrPut(ownerId, ::OwnerSubmission).also { owner ->
                owner.observerCount += 1
                owner.wasObserved = true
            }.state
        }

    fun releaseState(ownerId: String) {
        synchronized(ownersLock) {
            val owner = owners[ownerId] ?: return
            owner.observerCount = (owner.observerCount - 1).coerceAtLeast(0)
            if (
                owner.wasObserved &&
                owner.observerCount == 0 &&
                owner.state.value.phase == ComposerSubmissionPhase.IDLE
            ) {
                owners.remove(ownerId, owner)
            }
        }
    }

    fun snapshot(ownerId: String): ConversationComposerSubmissionSnapshot =
        synchronized(ownersLock) { owners[ownerId]?.state?.value }
            ?: ConversationComposerSubmissionSnapshot()

    fun isFrozen(ownerId: String): Boolean {
        val owner = synchronized(ownersLock) { owners[ownerId] } ?: return false
        return owner.state.value.isFrozen
    }

    fun submit(
        ownerId: String,
        text: String,
        attachmentIds: List<String>,
    ): Boolean {
        val target = captureTarget(ownerId) ?: return false
        val owner = owner(ownerId)
        val request = synchronized(owner) {
            if (owner.request != null) return false
            FrozenRequest(
                id = nextRequestId.incrementAndGet(),
                target = target,
                text = text,
                attachmentIds = attachmentIds.toList(),
            ).also { frozen ->
                owner.request = frozen
                owner.cancelWaitingRequested = false
                owner.state.value = owner.state.value.copy(
                    phase = ComposerSubmissionPhase.WAITING,
                    requestId = frozen.id,
                    frozenText = frozen.text,
                    frozenAttachmentIds = frozen.attachmentIds,
                )
            }
        }
        setOwnerActive(ownerId, active = true)
        val job = scope.launch { runSubmission(owner, request) }
        job.invokeOnCompletion { completeTerminalState(owner, request) }
        synchronized(owner) {
            if (owner.request === request) {
                owner.job = job
                if (owner.cancelWaitingRequested) job.cancel()
            } else {
                job.cancel()
            }
        }
        return true
    }

    fun cancelWaiting(ownerId: String): Boolean {
        val owner = synchronized(ownersLock) { owners[ownerId] } ?: return false
        val job = synchronized(owner) {
            if (owner.state.value.phase != ComposerSubmissionPhase.WAITING) return false
            owner.cancelWaitingRequested = true
            owner.job
        }
        job?.cancel()
        return true
    }

    fun retryAcceptedClear(ownerId: String): Boolean {
        val owner = synchronized(ownersLock) { owners[ownerId] } ?: return false
        val request = synchronized(owner) {
            if (
                owner.state.value.phase != ComposerSubmissionPhase.ACCEPTED_PENDING_CLEAR ||
                owner.job != null
            ) {
                return false
            }
            owner.state.value = owner.state.value.copy(
                phase = ComposerSubmissionPhase.SUBMITTING,
            )
            checkNotNull(owner.request)
        }
        val job = scope.launch {
            var retained = false
            try {
                composers.load(request.ownerId)
                retained = true
                clearAccepted(owner, request)
            } finally {
                if (retained) {
                    withContext(NonCancellable) { composers.release(request.ownerId) }
                }
            }
        }
        job.invokeOnCompletion { completeTerminalState(owner, request) }
        synchronized(owner) {
            if (owner.request !== request) {
                job.cancel()
            } else if (!job.isCompleted) {
                owner.job = job
            }
        }
        return true
    }

    private suspend fun runSubmission(owner: OwnerSubmission, request: FrozenRequest) {
        val startedNs = System.nanoTime()
        var stageNs = startedNs
        var previousStage = "begin"
        fun markStage(name: String) {
            val now = System.nanoTime()
            com.newoether.agora.util.DebugLog.sendStage(
                runId = request.target.runId,
                component = "composer",
                stage = name,
                elapsedMs = (now - startedNs) / 1_000_000L,
                previous = previousStage,
                previousMs = (now - stageNs) / 1_000_000L,
            )
            previousStage = name
            stageNs = now
        }
        var retained = false
        try {
            markStage("load-composer")
            composers.load(request.ownerId)
            retained = true
            markStage("freeze-draft")
            val frozen = composers.freezeSubmission(
                request.ownerId,
                request.id,
                request.text,
                request.attachmentIds,
            ) ?: return
            request.frozenRevision = frozen.revision
            markStage("prepare-admission")
            val admission = prepare(request.target, frozen) ?: return
            markStage("await-attachments")
            composers.awaitProcessing(request.ownerId, request.attachmentIds.toSet())
            markStage("resolve-ready-attachments")
            val settledComposer = composers.state(request.ownerId).value
            val acceptedAdmission = admission.withSettledComposerDraft(
                acceptedText = request.text,
                settledAttachments = settledComposer.attachments,
            )
            request.expectedNewChatWorkspace = acceptedAdmission.newChatPersistSnapshot
            val readyAttachments = settledComposer.attachments
                .associateBy(SelectedAttachment::localId)
                .let { currentById ->
                    request.attachmentIds.mapNotNull { id ->
                        currentById[id]?.takeIf(
                            SelectedAttachment::hasCanonicalReadyArtifact,
                        )
                    }
                }
                .map { attachment ->
                    attachment.copy(storage = attachment.storage.transferForSend())
                }
            if (request.text.isBlank() && readyAttachments.isEmpty()) return
            if (!startSubmitting(owner, request)) return
            // Every canonical artifact passed to Send becomes message/queue-owned at acceptance.
            // It must never re-enter abandoned-draft reclamation after that ownership transfer.
            request.durableAttachmentIds = readyAttachments
                .mapTo(hashSetOf(), SelectedAttachment::localId)
            markStage("send")
            send(acceptedAdmission, request.text, readyAttachments) { acceptance ->
                if (!request.acceptanceStarted.compareAndSet(false, true)) return@send
                markStage(if (acceptance is SendAcceptance.Direct) "accepted-direct" else "accepted-queued")
                markStage("clear-accepted-draft")
                request.accepted = acceptance
                clearAccepted(owner, request)
                markStage(if (request.acceptedAndCleared.get()) "draft-cleared" else "draft-clear-pending")
            }
            markStage("send-returned")
        } catch (cancelled: CancellationException) {
            markStage("cancelled")
            throw cancelled
        } catch (failure: Exception) {
            markStage("failed")
            com.newoether.agora.util.DebugLog.w(
                "ChatViewModel",
                "Composer submission failed for ${request.ownerId}",
                failure,
            )
        } finally {
            markStage("release-submission")
            if (retained) {
                withContext(NonCancellable) {
                    try {
                        composers.releaseSubmission(request.ownerId, request.id)
                    } finally {
                        composers.release(request.ownerId)
                    }
                }
            }
            markStage("finished")
        }
    }

    private suspend fun clearAccepted(
        owner: OwnerSubmission,
        request: FrozenRequest,
    ) {
        val acceptance = request.accepted ?: return
        val clearResult = withContext(NonCancellable) {
            composers.clearAccepted(
                ownerId = request.ownerId,
                reclaimAttachments = false,
                submissionId = request.id,
                acceptedRevision = request.frozenRevision,
                acceptedText = request.text,
                acceptedAttachmentIds = request.attachmentIds.toSet(),
                expectedWorkspace = request.expectedNewChatWorkspace,
            )
        }
        if (!clearResult.succeeded) {
            markAcceptedPendingClear(owner, request)
            onAcceptedClearFailed(request.ownerId) {
                retryAcceptedClear(request.ownerId)
            }
            return
        }
        request.acceptedAndCleared.set(true)
        val reclaimable = clearResult.attachments.filterNot { attachment ->
            attachment.localId in request.durableAttachmentIds
        }
        if (reclaimable.isNotEmpty() && acceptance.hasDurableAttachmentOwner()) {
            scope.launch(ioDispatcher) { drafts.reclaimAttachments(reclaimable) }
        }
    }

    private fun markAcceptedPendingClear(owner: OwnerSubmission, request: FrozenRequest) {
        synchronized(owner) {
            if (owner.request !== request) return
            owner.state.value = owner.state.value.copy(
                phase = ComposerSubmissionPhase.ACCEPTED_PENDING_CLEAR,
            )
        }
    }

    private fun startSubmitting(owner: OwnerSubmission, request: FrozenRequest): Boolean =
        synchronized(owner) {
            if (
                owner.request !== request ||
                owner.state.value.phase != ComposerSubmissionPhase.WAITING
            ) {
                return@synchronized false
            }
            owner.state.value = owner.state.value.copy(
                phase = ComposerSubmissionPhase.SUBMITTING,
            )
            true
        }

    private fun completeTerminalState(owner: OwnerSubmission, request: FrozenRequest) {
        synchronized(owner) {
            if (owner.request !== request) return
            owner.job = null
            owner.cancelWaitingRequested = false
            when {
                request.acceptedAndCleared.get() -> completeAcceptedLocked(owner, request)
                request.accepted != null -> owner.state.value = owner.state.value.copy(
                    phase = ComposerSubmissionPhase.ACCEPTED_PENDING_CLEAR,
                )
                else -> completeIdleLocked(owner, request)
            }
        }
    }

    private fun completeAcceptedLocked(owner: OwnerSubmission, request: FrozenRequest) {
        val current = owner.state.value
        owner.request = null
        owner.state.value = current.toIdle().copy(
            acceptedVersion = current.acceptedVersion + 1L,
            directAcceptedVersion = current.directAcceptedVersion +
                if (request.accepted is SendAcceptance.Direct) 1L else 0L,
            directAcceptedNewChatEntryId = request.target.newChatEntryId
                .takeIf { request.accepted is SendAcceptance.Direct },
        )
        setOwnerActive(request.ownerId, active = false)
        removeReleasedOwner(request.ownerId, owner)
    }

    private fun completeIdleLocked(owner: OwnerSubmission, request: FrozenRequest) {
        owner.request = null
        owner.state.value = owner.state.value.toIdle()
        setOwnerActive(request.ownerId, active = false)
        removeReleasedOwner(request.ownerId, owner)
    }

    private fun removeReleasedOwner(ownerId: String, owner: OwnerSubmission) {
        synchronized(ownersLock) {
            if (
                owners[ownerId] === owner &&
                owner.wasObserved &&
                owner.observerCount == 0 &&
                owner.state.value.phase == ComposerSubmissionPhase.IDLE
            ) {
                owners.remove(ownerId)
            }
        }
    }

    private fun setOwnerActive(ownerId: String, active: Boolean) {
        synchronized(ownersLock) {
            _activeOwnerIds.value = if (active) {
                _activeOwnerIds.value + ownerId
            } else {
                _activeOwnerIds.value - ownerId
            }
        }
    }

    private fun owner(ownerId: String): OwnerSubmission = synchronized(ownersLock) {
        owners.getOrPut(ownerId, ::OwnerSubmission)
    }

    private fun ConversationComposerSubmissionSnapshot.toIdle() = copy(
        phase = ComposerSubmissionPhase.IDLE,
        requestId = null,
        frozenText = "",
        frozenAttachmentIds = emptyList(),
    )
}
