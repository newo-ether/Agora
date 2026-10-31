package com.newoether.agora.viewmodel

import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.ConversationRuntimeReducer
import com.newoether.agora.model.ConversationRuntimeTrace
import com.newoether.agora.model.ConversationRuntimeTraceEntry
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RuntimeRunIdentity
import com.newoether.agora.model.SlotReleaseReason
import com.newoether.agora.model.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One runtime host per conversation. It exclusively owns [runState], applies reducer transitions,
 * and coordinates the mailbox with the separately owned process resources and guidance leases, so
 * two conversations can generate in parallel without their state clobbering each other.
 *
 * Replaces the process-global single-slot generation state that predated per-conversation parallelism.
 * The global StateFlows ChatViewModel exposes to the UI are now a mirror of whichever
 * conversation is currently open (see [ConversationStateRegistry]); background conversations
 * mutate only their own private flows here and write the DB, so they stay invisible until the
 * user switches back.
 *
 * ## Ownership tokens (unchanged semantics, scoped per conversation)
 *
 *  • The resource owner's UI token owns the shared UI mirror (isLoading/streamingMessage
 *    as seen through the registry). Advanced on EVERY stop and captured by each new generation.
 *    Token-gated mutators below only touch state while their captured token is current.
 *
 *  • The resource owner's persistence token owns the model message's DB row. It advances when a
 *    new generation starts and when
 *    Stop transfers terminal-write ownership to [GenerationFinalizer], so the cancelled provider
 *    coroutine cannot race the dedicated STOPPED transaction.
 *
 * ## Slot lifecycle (requestSend / replacement compatibility / endGeneration / stop)
 *
 * Ordinary foreground Send, queued-guidance placement, and headless Task/Loop Send enter
 * [commands]' sequential mailbox port. [acquireForSend] remains only as a legacy test adapter;
 * [tryAcquireForReplacement] is the idle-only ordinary continuation/replacement adapter.
 * [endGeneration] and
 * [stop] submit through the same mailbox. [endGeneration] releases token-gated ownership; Stop
 * establishes the terminal cutoff, then cancels only this conversation's owned generation Job and
 * in-flight HTTP streams (via [streamScope]).
 */
class ConversationGenerationState(
    val conversationId: String,
    private val onRegistryActive: (String) -> Unit = {},
    private val onRegistryIdle: (String) -> Unit = {},
    private val onRunStateChanged: (ConversationGenerationState, RunState, RunState?) -> Unit = { _, _, _ -> },
    private val onRunHandoffFinished: (ConversationGenerationState, Long) -> Unit = { _, _ -> },
    reclaimQueuedAttachments: (List<com.newoether.agora.model.SelectedAttachment>) -> Unit,
) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val resources = ConversationRuntimeResources()
    private val guidanceLeases = GuidanceLeaseStore(reclaimQueuedAttachments)

    /** Read-only process-resource projection observed by the UI and effect runners. */
    val streamScope = resources.streamScope
    val streamingMessage = resources.streamingMessage
    val isLoading = resources.isLoading
    val generating = resources.generating
    val stopping = resources.stopping
    val generationSnapshot = resources.generationSnapshot
    internal val queuedSends = guidanceLeases.queuedSends

    /** Serializes durable intervention acceptance against slot release/queue drain. */
    val queueMutationMutex = Mutex()

    // ── Ownership tokens ──
    private val genLock = Any()
    /** Authoritative process-slot state. All mutations go through [ConversationRuntimeReducer]. */
    private var runState: RunState = RunState.Idle(conversationId)
    /** Wait-only projection of reducer ownership; Stop's UI token revocation does not settle it. */
    private val generationOwner = MutableStateFlow<Long?>(null)
    private val runtimeTrace = ConversationRuntimeTrace()
    /** Foreground/headless Send, Stop, and tool-effect lifecycle commands enter here. */
    private val commandMailbox = ConversationCommandMailbox(scope, ::reduceMailboxCommand)
    internal val commands = ConversationRuntimeCommandPort(
        conversationId = conversationId,
        mailbox = commandMailbox,
        nextOwnerToken = resources::nextUiToken,
    )

    /** Captures the current UI-ownership token right after a stop, under the lock. */
    fun captureUiToken(): Long = synchronized(genLock) { resources.captureUiToken() }
    /** Claims DB-row ownership for a freshly-started generation. */
    fun nextPersistId(): Long = resources.nextPersistId()
    /** True while the persistence token still belongs to the generation that captured [id]. */
    fun isLatestPersist(id: Long): Boolean = resources.isLatestPersist(id)
    /** True while [uiToken] is still the current UI-ownership token (nothing stopped/superseded us). */
    fun isCurrentToken(uiToken: Long): Boolean = synchronized(genLock) {
        resources.isCurrentToken(uiToken)
    }

    fun bindPersistedRun(
        uiToken: Long,
        runId: String,
        pass: Int = 0,
    ): RunBindingOutcome = synchronized(genLock) {
        require(runId.isNotBlank())
        require(pass >= 0)
        val transition = reduceLocked(
            ConversationCommand.BindRun(
                RuntimeRunIdentity(
                    conversationId = conversationId,
                    ownerToken = uiToken,
                    runId = runId,
                    pass = pass,
                ),
            ),
        )
        if (!transition.accepted) return@synchronized RunBindingOutcome.Rejected
        applyMailboxEffectsLocked(transition)
        transition.bindingOutcome()
    }

    fun tryBindRun(uiToken: Long, runId: String, pass: Int = 0): Boolean =
        bindPersistedRun(uiToken, runId, pass) is RunBindingOutcome.Active

    fun bindRun(uiToken: Long, runId: String, pass: Int = 0) {
        check(tryBindRun(uiToken, runId, pass)) {
            "Only the active slot owner can bind Run $runId"
        }
    }

    fun currentRunId(): String? = synchronized(genLock) { runState.identityOrNull()?.runId }
    /** Exact mailbox-owned batch authority. A Run ID alone cannot authorize device effects. */
    fun acceptsToolEffect(identity: RunEffectIdentity): Boolean = synchronized(genLock) {
        val active = runState as? RunState.Active ?: return@synchronized false
        scope.isActive && !active.coroutineSettled &&
            (active.toolPhase as? com.newoether.agora.model.RunToolPhase.Executing)?.batchIdentity == identity
    }
    fun controlsRun(runId: String): Boolean = synchronized(genLock) {
        val active = runState as? RunState.Active
        scope.isActive && active?.identity?.runId == runId && !active.coroutineSettled
    }

    /** Echo the exact Room acceptance effect back through the mailbox. */
    suspend fun finishInputPersistence(identity: RunEffectIdentity): RunBindingOutcome =
        commands.finishInputPersistence(identity).bindingOutcome()

    suspend fun inputPersisted(identity: RunEffectIdentity): Boolean =
        finishInputPersistence(identity) is RunBindingOutcome.Active

    /** Echo the exact Room finalization result; release requires both durable and Job barriers. */
    suspend fun finishRunFinalization(
        identity: RunEffectIdentity,
        success: Boolean,
    ): RunFinalizationOutcome = withContext(NonCancellable) {
        val transition = commands.finishRunFinalization(identity, success)
        if (!transition.accepted) return@withContext RunFinalizationOutcome.REJECTED
        if (!success) return@withContext RunFinalizationOutcome.FAILED
        val release = transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().singleOrNull()
            ?: return@withContext RunFinalizationOutcome.RECORDED
        check(release.reason == SlotReleaseReason.NORMAL_FINALIZATION_SETTLED)
        // Fire after mailbox handling and outside [genLock].
        onQueueDrainRequested?.invoke(this@ConversationGenerationState)
        RunFinalizationOutcome.SETTLED
    }

    /** Wait for this owner's reducer release, even if a successor has already claimed the slot. */
    suspend fun awaitGenerationSettled(uiToken: Long) {
        require(uiToken > 0)
        generationOwner.first { owner -> owner != uiToken }
    }

    /** Wait until the conversation's one ordinary generation slot is available. */
    suspend fun awaitSendAvailable() {
        generating.first { isGenerating -> !isGenerating }
    }


    // ── Generation slot (single source of truth: [runState] under [genLock]) ─────────────
    // The reducer-backed slot is the atomic decision point for "launch now vs enqueue": exactly
    // one generation owns a conversation's tree at a time.

    /**
     * Legacy test/setup claim. Production Send/Task/Loop paths use [commands]. If the slot is
     * free, marks this conversation
     * generating (advancing the UI token so any just-finished generation's late callbacks are gated
     * out), flips it active in the registry, and returns the captured token. If a generation is
     * already running, returns null → the caller must enqueue instead of launching (fixes the
     * silent-drop / same-conversation-parallel window: [generating] is now set synchronously here,
     * not deep inside the coroutine).
    */
    fun acquireForSend(): Long? = synchronized(genLock) {
        val nextToken = resources.nextUiToken()
        val transition = reduceLocked(
            ConversationCommand.AcquireSlot(
                RuntimeRunIdentity(conversationId = conversationId, ownerToken = nextToken),
            ),
        )
        if (!transition.accepted) return null
        check(transition.effects.singleOrNull() is RunEffect.SlotActivated)
        applyActivatedSlotLocked(transition.newState.identityOrNull()!!, loading = false)
        nextToken
    }

    /**
     * Atomic idle-only claim for an ordinary continuation/replacement. The UI disables actions
     * while this
     * conversation is generating, but that visual gate can lag by a frame during a conversation
     * switch; enforcing the same rule here makes the state machine authoritative.
    */
    fun tryAcquireForReplacement(): Long? = synchronized(genLock) {
        val nextToken = resources.nextUiToken()
        val transition = reduceLocked(
            ConversationCommand.AcquireSlot(
                RuntimeRunIdentity(conversationId = conversationId, ownerToken = nextToken),
            ),
        )
        if (!transition.accepted) return null
        check(transition.effects.singleOrNull() is RunEffect.SlotActivated)
        applyActivatedSlotLocked(transition.newState.identityOrNull()!!, loading = true)
        nextToken
    }

    /** Retained by this lifecycle owner until both durable settlement and installed Job cleanup. */
    private val generationAdmissionReleases = mutableMapOf<Long, Pair<Job, () -> Unit>>()

    internal suspend fun acquireGenerationAdmission(
        uiToken: Long,
        coordinator: com.newoether.agora.automation.ConversationExecutionCoordinator,
    ): Boolean {
        val release = coordinator.acquireGenerationAdmission(conversationId)
        val retained = synchronized(genLock) {
            if (!runState.isLaunchableOwner(uiToken)) {
                false
            } else {
                val job = checkNotNull(resources.currentGenerationJob())
                check(uiToken !in generationAdmissionReleases)
                generationAdmissionReleases[uiToken] = job to release
                true
            }
        }
        if (!retained) release()
        return retained
    }

    private fun releaseSettledGenerationAdmissions() = synchronized(genLock) {
        generationAdmissionReleases.entries.removeAll { (token, admission) ->
            if (runState.identityOrNull()?.ownerToken == token || !admission.first.isCompleted) {
                false
            } else {
                admission.second()
                true
            }
        }
    }

    /**
     * Installs the generation Job before it can execute. This closes the launch-assignment race:
     * Stop either sees and cancels this exact Job, or marks the pre-launch slot STOPPING and this
     * method refuses to start it. A completion hook is a final safety net for cancellation that
     * lands after installation but before the LAZY body gets its first instruction. Optional queue
     * suppression is counted and installed under the same lock before the Job can start.
     */
    fun launchGenerationJob(
        uiToken: Long,
        suppressAutomaticQueueDrain: Boolean = false,
        retainHandoff: Boolean = false,
        block: suspend CoroutineScope.() -> Unit,
    ): Job? {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        val installed = AtomicBoolean(false)
        job.invokeOnCompletion {
            if (installed.get()) settleCoroutineAsync(uiToken)
        }

        val accepted = synchronized(genLock) {
            if (
                !runState.isLaunchableOwner(uiToken) ||
                resources.currentGenerationJob() != null
            ) {
                false
            } else {
                resources.installGenerationJob(job).also { didInstall ->
                    installed.set(didInstall)
                    if (didInstall && retainHandoff) resources.retainRunHandoff(uiToken)
                    if (didInstall && suppressAutomaticQueueDrain) {
                        resources.deferNextQueueDrain()
                    }
                }
            }
        }
        if (!accepted) {
            job.cancel()
            val abandonedStoppingLaunch = synchronized(genLock) {
                runState.isStoppingOwner(uiToken) && resources.currentGenerationJob() == null
            }
            if (abandonedStoppingLaunch) settleCoroutineAsync(uiToken)
            return null
        }
        job.start()
        return job
    }

    /**
     * Attaches a generation coroutine owned by an external process-scoped runner. Background
     * Task/Loop execution cannot be launched in [scope] because its caller must suspend until the
     * durable result is known, but Stop still has to cancel the exact worker coroutine and its
     * streams. The same install-before-work invariant as [launchGenerationJob] applies.
     */
    fun attachGenerationJob(uiToken: Long, job: Job): Boolean {
        val installed = AtomicBoolean(false)
        job.invokeOnCompletion {
            if (installed.get()) settleCoroutineAsync(uiToken)
        }
        val accepted = synchronized(genLock) {
            if (
                !runState.isLaunchableOwner(uiToken) ||
                resources.currentGenerationJob() != null
            ) {
                false
            } else {
                resources.installGenerationJob(job).also(installed::set)
            }
        }
        // An external Job can complete between hook registration and installation. The hook sees
        // installed=false in that race, so this post-install check supplies the result. A duplicate
        // delivery from the opposite race is harmless because reducer identity rejects it.
        if (accepted && job.isCompleted) settleCoroutineAsync(uiToken)
        return accepted
    }

    /**
     * Owner-token-gated release of the slot when a generation coroutine finishes (normally OR
     * after a Stop — [stop] deliberately does not free the slot, see there). Only the installed
     * Job's completion hook reports settlement, so a coroutine superseded in an earlier era is a
     * no-op.
     * A bound durable Run cannot release from this signal alone: normal finalization or Stop must
     * also settle. Returns true only if this command actually emitted the release effect (i.e. the
     * caller may now drain the queue).
     */
    suspend fun endGeneration(uiToken: Long): Boolean = withContext(NonCancellable) {
        require(uiToken > 0)
        val transition = commandMailbox.submit(
            ConversationCommandFactory {
                check(resources.currentGenerationJob()?.isCompleted != false) {
                    "CoroutineSettled requires the installed generation Job to be completed"
                }
                val currentIdentity = runState.identityOrNull()
                val commandIdentity = currentIdentity
                    ?.takeIf { it.ownerToken == uiToken }
                    ?: RuntimeRunIdentity(conversationId = conversationId, ownerToken = uiToken)
                ConversationCommand.CoroutineSettled(commandIdentity)
            },
        )
        releaseSettledGenerationAdmissions()
        if (!transition.accepted) return@withContext false
        when (
            transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().singleOrNull()?.reason
        ) {
            SlotReleaseReason.NORMAL_COMPLETION -> true
            SlotReleaseReason.NORMAL_FINALIZATION_SETTLED -> {
                // The durable callback owns queue drain when it wins the barrier race. If the Job
                // completion wins, this hook owns it instead.
                true
            }
            SlotReleaseReason.STOP_BARRIERS_SETTLED -> {
                // Every terminal release uses one process-owned queue-drain signal. Keeping Stop
                // on the shared path lets registry handoff/rebinding preserve the obligation.
                onQueueDrainRequested?.invoke(this@ConversationGenerationState)
                false
            }
            SlotReleaseReason.EMPTY_STOP -> error("Coroutine settlement cannot emit EMPTY_STOP")
            SlotReleaseReason.SEND_LAUNCH_ABANDONED ->
                error("Coroutine settlement cannot abandon an unlaunched Send")
            null -> false
        }
    }

    /** Completion hooks cannot suspend; enqueue their identified result on the owned scope. */
    private fun settleCoroutineAsync(uiToken: Long) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (endGeneration(uiToken)) onQueueDrainRequested?.invoke(this@ConversationGenerationState)
        }
    }

    /**
     * Defers exactly the next automatic queue drain. A boundary send that failed before its batch
     * became durable keeps the guidance for a later boundary without immediately retrying itself
     * from the current generation's finally block.
     */
    fun deferNextQueueDrain() = synchronized(genLock) {
        resources.deferNextQueueDrain()
    }

    fun cancelDeferredQueueDrain() = synchronized(genLock) {
        resources.cancelDeferredQueueDrain()
    }

    fun consumeQueueDrainPermission(): Boolean = synchronized(genLock) {
        resources.consumeQueueDrainPermission()
    }

    // ── Token-gated UI mutators ───────────────────────────────────────────
    fun streamUpdate(uiToken: Long, msg: ChatMessage) {
        synchronized(genLock) {
            if (resources.isCurrentToken(uiToken)) {
                resources.streamUpdate(uiToken, msg)
                resources.publishGenerationSnapshot(runState)
            }
        }
    }
    fun loadingChange(uiToken: Long, value: Boolean) {
        synchronized(genLock) {
            if (resources.isCurrentToken(uiToken)) {
                resources.loadingChange(uiToken, value)
                resources.publishGenerationSnapshot(runState)
            }
        }
    }
    fun streamClear(uiToken: Long) {
        synchronized(genLock) {
            val message = resources.streamMessageForClear(uiToken) ?: return
            // Commit before removing the overlay so the UI never exposes the empty placeholder.
            onStreamCommit?.invoke(conversationId, message)
            resources.clearStreamingMessage()
            resources.publishGenerationSnapshot(runState)
        }
    }

    /** Wired by ChatViewModel to mark this conversation active/idle in the registry and to commit
     * the final streaming message into the currently open conversation before overlay removal. */
    @Volatile var onActive: ((String) -> Unit)? = null
    @Volatile var onIdle: ((String) -> Unit)? = null
    @Volatile var onStreamCommit: ((String, ChatMessage) -> Unit)? = null
    /** Fired after any normal or STOPPED terminal release so pending guidance can claim a fresh Run. */
    @Volatile var onQueueDrainRequested: ((ConversationGenerationState) -> Unit)? = null
    internal var onQueuedSendDismissed: (suspend (QueuedSend) -> Unit)? = null

    /** Builds the token-gated callbacks for one generation, writing ONLY to this conversation's
     *  private state. The ChatViewModel mirror pipes private→global when this conversation is
     *  open, so the callbacks need no knowledge of the current conversation id. */
    internal fun callbacksFor(uiToken: Long, persistId: Long): GenerationCallbacks = GenerationCallbacks(
        onStreamUpdate = { streamUpdate(uiToken, it) },
        onLoadingChange = { loadingChange(uiToken, it) },
        onStreamClear = { streamClear(uiToken) },
        isLatestPersist = { isLatestPersist(persistId) },
        onProviderPassRequested = commands::requestProviderPass,
        onProviderPassCompleted = commands::finishProviderPass,
        onRunFinalizationRequested = commands::requestRunFinalization,
        onRunFinalizationCompleted = { identity, success ->
            finishRunFinalization(identity, success).accepted
        },
        // Steering: lets the tool loop see a mid-generation queued send and end at the next
        // round boundary, so the queue flushes without waiting out the whole tool loop.
        hasQueuedSends = { queuedSends.value.isNotEmpty() },
        onToolBatchRequested = commands::requestToolBatch,
        onToolBatchCompleted = commands::completeToolBatch,
        onToolRoundCommitted = commands::finishToolRoundCommit,
    )

    // ── Stop / finalization ───────────────────────────────────────────────
    /**
     * Terminal user Stop request. Cancels ONLY this conversation's job + in-flight HTTP streams,
     * advances the UI token, and commits STOPPED to the streaming snapshot. The cancelled
     * coroutine retains the slot until its installed Job completion hook reports settlement.
     * Regenerate/edit never call Stop; they can claim only an idle slot through
     * [tryAcquireForReplacement].
     */
    internal suspend fun stop(): StopResult = withContext(NonCancellable) {
        val previousJob = AtomicReference<Job?>()
        val requestedIdentity = AtomicReference<RuntimeRunIdentity>()
        val stoppedMessage = AtomicReference<ChatMessage?>()
        val duplicateStoppingRequest = AtomicBoolean(false)
        val transition = commandMailbox.submit(
            ConversationCommandFactory {
                val currentState = runState
                val identity = currentState.identityOrNull()
                    ?: RuntimeRunIdentity(
                        conversationId = conversationId,
                        ownerToken = resources.nextUiToken().coerceAtLeast(1),
                    )
                previousJob.set(resources.currentGenerationJob())
                requestedIdentity.set(identity)
                duplicateStoppingRequest.set(currentState is RunState.Stopping)
                stoppedMessage.set(resources.stoppableOverlay(currentState))
                val requiresPersistence = identity.runId != null
                val effectId = when {
                    !requiresPersistence -> null
                    currentState is RunState.Stopping -> currentState.finalizationEffectId
                    else -> "stop-${identity.ownerToken}"
                }
                ConversationCommand.StopRequested(
                    identity = identity,
                    coroutineAlreadySettled = previousJob.get()?.isCompleted != false,
                    requiresPersistence = requiresPersistence,
                    effectId = effectId,
                )
            },
        )
        val identity = checkNotNull(requestedIdentity.get())
        if (transition.accepted || duplicateStoppingRequest.get()) {
            // Hard kill after the mailbox-owned cutoff: synchronous handle cancellation wakes
            // blocking HTTP/native reads, then Job cancellation unwinds every remaining child.
            resources.cancelStreamsAnd(previousJob.get())
        }
        StopResult(
            stoppedMessage = stoppedMessage.get(),
            conversationId = conversationId,
            runId = identity.runId,
            finalizationEffect = transition.effects
                .filterIsInstance<RunEffect.FinalizeStop>()
                .singleOrNull(),
        )
    }

    /** Enqueue Stop immediately, but keep accepted-effect handling on the conversation scope. */
    internal fun requestStop(onResult: (StopResult) -> Unit): Job = scope.launch(
        start = CoroutineStart.UNDISPATCHED,
    ) {
        withContext(NonCancellable) {
            onResult(stop())
        }
    }

    /**
     * Completes the durable half of the Stop barrier. A failed terminal write deliberately keeps
     * STOPPING occupied: the unique live-Run slot is still unavailable, so reporting IDLE would
     * make the next Send fail or attach to the doomed Run.
     */
    internal suspend fun finishStopFinalization(
        command: ConversationCommand.PersistenceSettled,
    ): StopFinalizationOutcome = withContext(NonCancellable) {
        val transition = commandMailbox.submit(
            ConversationCommandFactory { command },
        )
        if (!transition.accepted) return@withContext StopFinalizationOutcome.REJECTED
        if (!command.success) return@withContext StopFinalizationOutcome.FAILED
        val release = transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().singleOrNull()
            ?: return@withContext StopFinalizationOutcome.RECORDED
        check(release.reason == SlotReleaseReason.STOP_BARRIERS_SETTLED)
        // Fire after mailbox handling and outside [genLock].
        onQueueDrainRequested?.invoke(this@ConversationGenerationState)
        StopFinalizationOutcome.SETTLED
    }

    /**
     * Clears a lingering STOPPED streaming snapshot. [stop] deliberately leaves the STOPPED
     * overlay in place until Room has persisted it (see [streamClear]); this is the matching
     * release, invoked once stop-finalization has written the row. Without it the stale overlay
     * survives indefinitely and [ConversationUiState.resolvePath] can re-append it as a ghost
     * after the persisted message is deleted.
     */
    fun clearStoppedOverlay() {
        synchronized(genLock) {
            resources.clearStoppedOverlay()
            resources.publishGenerationSnapshot(runState)
        }
    }

    /** Cancel this conversation's scope (called when the conversation is deleted). */
    private fun cancelScope() {
        scope.coroutineContext[Job]?.cancel()
    }

    /** Runtime disposal is not a user Stop and therefore does not create a durable Stop effect. */
    internal fun dispose(): List<QueuedSend> {
        val pendingGuidance = guidanceLeases.disposePending()
        val job = synchronized(genLock) {
            onRunStateChanged(this, runState, null)
            resources.currentGenerationJob()
        }
        resources.cancelStreamsAnd(job)
        cancelScope()
        return pendingGuidance
    }

    /** Append a queued send (generation in progress → enqueue instead of launching). */
    internal fun enqueueSend(send: QueuedSend) = guidanceLeases.enqueue(send)

    /** Stable evidence for orchestration that a queued batch already crossed into generation. */
    fun guidanceClaimRevision(): Long = guidanceLeases.currentClaimRevision()

    fun hasPendingOrClaimedGuidanceSince(revision: Long): Boolean =
        guidanceLeases.hasPendingOrClaimedSince(revision)

    /**
     * Remove a queued send by id (X button). Returns the removed item (or null) so the caller can
     * settle its live ownership and schedule reference-aware cleanup through [discardQueuedSend].
     */
    internal fun removeQueuedSend(id: String): QueuedSend? = guidanceLeases.remove(id)
    internal fun discardQueuedSend(send: QueuedSend) = guidanceLeases.discard(send)

    /** Called under queueMutationMutex; persist dismissal before releasing visible ownership. */
    internal suspend fun dismissQueuedSend(id: String) {
        val send = queuedSends.value.firstOrNull { it.id == id } ?: return
        onQueuedSendDismissed?.invoke(send)
        removeQueuedSend(id)?.let(::discardQueuedSend)
    }

    internal fun containsSubagentResult(childId: String, resultId: String): Boolean =
        guidanceLeases.containsSubagentResult(childId, resultId)

    internal fun removeSubagentResults(receipts: Map<String, String>) {
        guidanceLeases.removeSubagentResults(receipts).forEach(::discardQueuedSend)
    }

    internal fun retireSubagentResults(lease: GuidanceBatchLease, receipts: Set<Pair<String, String>>): GuidanceBatchLease? =
        guidanceLeases.retireSubagentResults(lease, receipts)

    /** Transfer the pending batch to one explicit in-flight owner before leaving memory-only state. */
    internal fun claimQueuedSends(): GuidanceBatchLease? = guidanceLeases.claim()

    /**
     * End one in-flight ownership lease. A durable commit transfers file ownership to Room;
     * otherwise the exact batch returns to the front, unless disposal now owns cleanup.
     */
    fun settleGuidanceClaim(leaseId: String, durable: Boolean): Boolean =
        guidanceLeases.settle(leaseId, durable)

    data class StopResult(
        val stoppedMessage: ChatMessage?,
        val conversationId: String,
        val runId: String?,
        val finalizationEffect: RunEffect.FinalizeStop?,
    )

    sealed interface RunBindingOutcome {
        data object Active : RunBindingOutcome

        data class Stopping(
            val finalizationEffect: RunEffect.FinalizeStop,
        ) : RunBindingOutcome

        data object Rejected : RunBindingOutcome
    }

    enum class StopFinalizationOutcome {
        /** Old, duplicate, wrong-Run, wrong-pass, or otherwise illegal callback. */
        REJECTED,
        /** Current finalization effect failed; STOPPING remains occupied. */
        FAILED,
        /** Durable barrier recorded; coroutine barrier is still pending. */
        RECORDED,
        /** Both barriers settled and the slot was released. */
        SETTLED;

        val accepted: Boolean get() = this != REJECTED
    }

    enum class RunFinalizationOutcome {
        /** Old, duplicate, wrong-Run, wrong-pass, or otherwise illegal callback. */
        REJECTED,
        /** Current finalization effect failed; the live Run remains occupied. */
        FAILED,
        /** Durable barrier recorded; coroutine barrier is still pending. */
        RECORDED,
        /** Both barriers settled and the slot was released. */
        SETTLED;

        val accepted: Boolean get() = this != REJECTED
    }

    private fun RunState.identityOrNull(): RuntimeRunIdentity? = when (this) {
        is RunState.Idle -> null
        is RunState.Recovering -> null
        is RunState.Preparing -> ownerIdentity
        is RunState.Active -> identity
        is RunState.Finalizing -> identity
        is RunState.Stopping -> identity
    }

    private fun RunState.isLaunchableOwner(ownerToken: Long): Boolean = when (this) {
        is RunState.Preparing -> ownerIdentity.ownerToken == ownerToken
        is RunState.Active -> !coroutineSettled && identity.ownerToken == ownerToken
        is RunState.Idle,
        is RunState.Recovering,
        is RunState.Finalizing,
        is RunState.Stopping,
        -> false
    }

    private fun RunState.isStoppingOwner(ownerToken: Long): Boolean =
        this is RunState.Stopping && identity.ownerToken == ownerToken

    /** Privacy-safe bounded trace for diagnostics/tests; contains no prompt or message content. */
    internal fun runtimeTraceSnapshot(): List<ConversationRuntimeTraceEntry> = runtimeTrace.snapshot()

    private fun reduceMailboxCommand(factory: ConversationCommandFactory): Transition =
        synchronized(genLock) {
            val transition = reduceLocked(factory.create())
            if (transition.accepted) applyMailboxEffectsLocked(transition)
            transition
        }

    /** Apply only effects whose authority has moved to the mailbox in the current phase. */
    private fun applyMailboxEffectsLocked(transition: Transition) {
        val events = resources.applyMailboxEffects(transition, runState)
        resources.publishGenerationSnapshot(runState)
        if (events.activated) notifyActivatedLocked()
        if (events.released) notifyReleasedLocked()
        generationOwner.value = runState.identityOrNull()?.ownerToken
    }

    private fun applyActivatedSlotLocked(identity: RuntimeRunIdentity, loading: Boolean) {
        check(runState !is RunState.Idle)
        resources.activate(identity, loading)
        resources.publishGenerationSnapshot(runState)
        notifyActivatedLocked()
        generationOwner.value = identity.ownerToken
    }

    private fun notifyActivatedLocked() {
        onRegistryActive(conversationId)
        onActive?.invoke(conversationId)
    }

    private fun notifyReleasedLocked() {
        check(runState is RunState.Idle)
        releaseSettledGenerationAdmissions()
        onRegistryIdle(conversationId)
        onIdle?.invoke(conversationId)
    }

    /** Capture the exact released owner while the reducer still serializes slot replacement. */
    internal fun handoffOwnerToken(): Long? = synchronized(genLock) {
        lastReleasedOwnerToken.takeIf { runState is RunState.Idle }
    }

    internal fun retainRunHandoff(ownerToken: Long) = synchronized(genLock) {
        resources.retainRunHandoff(ownerToken)
    }

    internal fun releaseRunHandoffRetention(ownerToken: Long?) = synchronized(genLock) {
        if (ownerToken != null) resources.releaseRunHandoffRetention(ownerToken)
    }

    internal fun finishRunHandoff(ownerToken: Long?, queuedDrainOnly: Boolean = false) = synchronized(genLock) {
        if (ownerToken != null && (!queuedDrainOnly || !resources.isRunHandoffRetained(ownerToken))) {
            resources.releaseRunHandoffRetention(ownerToken)
            onRunHandoffFinished(this, ownerToken)
        }
    }

    private var lastReleasedOwnerToken: Long? = null

    /** Must be called while [genLock] is held. This is the only process-slot state write path. */
    private fun reduceLocked(command: ConversationCommand): com.newoether.agora.model.Transition {
        val oldState = runState
        val transition = ConversationRuntimeReducer.reduce(oldState, command)
        val traceEntry = runtimeTrace.record(oldState, command, transition)
        DeveloperDiagnostics.recordRuntimeTransition(traceEntry)
        if (transition.accepted) {
            if (transition.newState is RunState.Idle) {
                lastReleasedOwnerToken = oldState.identityOrNull()?.ownerToken ?: lastReleasedOwnerToken
            }
            runState = transition.newState
            onRunStateChanged(this, oldState, runState)
        }
        return transition
    }

    private fun Transition.bindingOutcome(): RunBindingOutcome {
        if (!accepted) return RunBindingOutcome.Rejected
        return when (newState) {
            is RunState.Active -> RunBindingOutcome.Active
            is RunState.Stopping -> effects
                .filterIsInstance<RunEffect.FinalizeStop>()
                .singleOrNull()
                ?.let { RunBindingOutcome.Stopping(it) }
                ?: RunBindingOutcome.Rejected
            else -> RunBindingOutcome.Rejected
        }
    }

}
