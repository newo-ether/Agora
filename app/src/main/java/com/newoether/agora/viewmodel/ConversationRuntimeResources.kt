package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunRecoveryPolicy
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RuntimeRunIdentity
import com.newoether.agora.model.Transition
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

internal data class RuntimeResourceEvents(
    val activated: Boolean = false,
    val released: Boolean = false,
)

data class ConversationGenerationSnapshot(
    val conversationId: String? = null,
    val streamingMessage: ChatMessage? = null,
    val isLoading: Boolean = false,
    val isGenerating: Boolean = false,
)

private fun ChatMessage.toStoppedRuntimeSnapshot(): ChatMessage = copy(
    status = MessageStatus.STOPPED,
    segments = segments?.let(RunRecoveryPolicy::stopIncompleteTools),
)

/**
 * Sole owner of one conversation's process resources and UI projection.
 *
 * The runtime host serializes calls under its generation lock. This owner never calls the reducer,
 * changes [RunState], releases a slot, or chooses a subsequent effect. It only applies resource
 * consequences of an already accepted [Transition] and manages handles installed by the host.
 */
internal class ConversationRuntimeResources {
    val streamScope = StreamScope()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    private val _generationSnapshot = MutableStateFlow(ConversationGenerationSnapshot())
    val generationSnapshot: StateFlow<ConversationGenerationSnapshot> =
        _generationSnapshot.asStateFlow()
    private var generationJob: Job? = null
    private var uiGenToken = 0L
    private val persistId = AtomicLong(0L)
    private var suppressedQueueDrainCount = 0

    fun captureUiToken(): Long = uiGenToken

    fun nextUiToken(): Long = uiGenToken + 1

    fun isCurrentToken(token: Long): Boolean = uiGenToken == token

    fun nextPersistId(): Long = persistId.incrementAndGet()

    fun isLatestPersist(id: Long): Boolean = persistId.get() == id

    fun currentGenerationJob(): Job? = generationJob

    fun installGenerationJob(job: Job): Boolean {
        if (generationJob != null) return false
        generationJob = job
        return true
    }

    fun activate(identity: RuntimeRunIdentity, loading: Boolean) {
        uiGenToken = identity.ownerToken
        // A drain suppression belongs to the run that armed it and is answered by that run's slot
        // release. A newly activated run means that release either already happened or never will
        // (its terminal effect failed), so a leftover suppression can no longer be satisfied.
        // Keeping it armed would make THIS run's release silently skip its queue drain, leaving
        // queued sends stranded with nothing left to trigger them.
        suppressedQueueDrainCount = 0
        _isLoading.value = loading
        _generating.value = true
        _stopping.value = false
    }

    fun applyMailboxEffects(
        transition: Transition,
        currentState: RunState,
    ): RuntimeResourceEvents {
        check(transition.accepted) { "Rejected transitions cannot mutate runtime resources" }
        var activated = false
        var released = false


        transition.effects.filterIsInstance<RunEffect.PersistAcceptedInput>()
            .singleOrNull()
            ?.let { effect ->
                val preparing = currentState as? RunState.Preparing
                    ?: error("Accepted input persistence must enter Preparing")
                check(preparing.inputEffectIdentity == effect.identity)
                activate(preparing.ownerIdentity, loading = false)
                activated = true
            }

        transition.effects.filterIsInstance<RunEffect.CancelProviderPass>()
            .singleOrNull()
            ?.let { effect ->
                check(uiGenToken == effect.identity.ownerToken)
                val stopped = _streamingMessage.value?.toStoppedRuntimeSnapshot()
                check(stopped == null || effect.identity.runId != null) {
                    "A streaming Stop effect requires a bound Run"
                }
                // Revoke DB/UI ownership before cancellation can unwind provider finally blocks.
                persistId.incrementAndGet()
                uiGenToken += 1
                _streamingMessage.value = stopped
                if (currentState is RunState.Stopping) {
                    _isLoading.value = true
                    _generating.value = true
                    _stopping.value = true
                }
            }

        transition.effects.filterIsInstance<RunEffect.ReleaseSlot>()
            .singleOrNull()
            ?.let { release ->
                check(currentState is RunState.Idle) {
                    "ReleaseSlot resource cleanup requires an Idle runtime state"
                }
                release()
                released = true
            }

        return RuntimeResourceEvents(activated = activated, released = released)
    }

    fun stoppableOverlay(currentState: RunState): ChatMessage? = _streamingMessage.value
        ?.takeUnless {
            currentState is RunState.Idle ||
                currentState is RunState.Finalizing && !currentState.persistenceFailureReported
        }
        ?.toStoppedRuntimeSnapshot()

    fun streamUpdate(uiToken: Long, message: ChatMessage) {
        if (this.uiGenToken == uiToken) _streamingMessage.value = message
    }

    fun loadingChange(uiToken: Long, value: Boolean) {
        if (this.uiGenToken == uiToken) _isLoading.value = value
    }

    fun streamMessageForClear(uiToken: Long): ChatMessage? = _streamingMessage.value
        ?.takeIf { this.uiGenToken == uiToken && it.status != MessageStatus.STOPPED }

    fun clearStreamingMessage() {
        _streamingMessage.value = null
    }

    fun clearStoppedOverlay() {
        if (_streamingMessage.value?.status == MessageStatus.STOPPED) {
            _streamingMessage.value = null
        }
    }

    fun deferNextQueueDrain() {
        check(suppressedQueueDrainCount < Int.MAX_VALUE)
        suppressedQueueDrainCount += 1
    }

    fun cancelDeferredQueueDrain() {
        if (suppressedQueueDrainCount > 0) suppressedQueueDrainCount -= 1
    }

    fun consumeQueueDrainPermission(): Boolean {
        if (suppressedQueueDrainCount == 0) return true
        suppressedQueueDrainCount -= 1
        return false
    }

    fun cancelStreamsAnd(job: Job?) {
        streamScope.cancelAll()
        job?.cancel()
    }

    fun publishGenerationSnapshot(currentState: RunState) {
        _generationSnapshot.value = ConversationGenerationSnapshot(
            conversationId = currentState.conversationId,
            streamingMessage = _streamingMessage.value,
            isLoading = _isLoading.value,
            isGenerating = _generating.value,
        )
    }

    private fun release() {
        generationJob = null
        _isLoading.value = false
        _generating.value = false
        _stopping.value = false
    }
}
