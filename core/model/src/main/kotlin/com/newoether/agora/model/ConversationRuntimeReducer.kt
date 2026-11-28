package com.newoether.agora.model

private const val RECOVERY_OWNER_TOKEN = Long.MAX_VALUE

/**
 * Authoritative migrated slice of the conversation runtime reducer.
 *
 * It owns startup recovery planning, ordinary Send placement/input acceptance, the process slot,
 * Provider-pass acceptance, normal/Stop coroutine/persistence barriers, the
 * tool-batch/result/commit/continuation gate, and shared generation-slot lifecycle.
 * Guidance and external effect bodies remain bounded adapters. The reducer has no Android,
 * coroutine, Room, network, or Compose dependency.
 */
object ConversationRuntimeReducer {
    fun reduce(
        state: RunState,
        command: ConversationCommand,
    ): Transition {
        if (state.conversationId != command.conversationId) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }

        return when (command) {
            is ConversationCommand.Recover -> requestRecovery(state, command)
            is ConversationCommand.RecoveryCompleted -> completeRecovery(state, command)
            is ConversationCommand.AcquireSlot -> acquire(state, command)
            is ConversationCommand.SendRequested -> requestSend(state, command)
            is ConversationCommand.InputPersisted -> inputPersisted(state, command)
            is ConversationCommand.InputPersistenceFailed -> inputPersistenceFailed(state, command)
            is ConversationCommand.SendLaunchAbandoned -> abandonSendLaunch(state, command)
            is ConversationCommand.BindRun -> bindRun(state, command)
            is ConversationCommand.ProviderPassRequested -> requestProviderPass(state, command)
            is ConversationCommand.ProviderPassCompleted -> completeProviderPass(state, command)
            is ConversationCommand.ToolBatchRequested -> requestToolBatch(state, command)
            is ConversationCommand.ToolBatchCompleted -> completeToolBatch(state, command)
            is ConversationCommand.ToolRoundCommitted -> commitToolRound(state, command)
            is ConversationCommand.FinalizationRequested -> requestFinalization(state, command)
            is ConversationCommand.FinalizationCompleted -> completeFinalization(state, command)
            is ConversationCommand.StopRequested -> requestStop(state, command)
            is ConversationCommand.CoroutineSettled -> settleCoroutine(state, command)
            is ConversationCommand.PersistenceSettled -> settlePersistence(state, command)
        }
    }

    private fun requestRecovery(
        state: RunState,
        command: ConversationCommand.Recover,
    ): Transition = when (state) {
        is RunState.Idle -> {
            val snapshot = command.snapshot
            val effectIdentity = RunEffectIdentity(
                conversationId = snapshot.conversationId,
                ownerToken = RECOVERY_OWNER_TOKEN,
                runId = snapshot.runId,
                pass = snapshot.pass,
                effectId = "recover-${snapshot.runId}-${snapshot.pass}",
            )
            Transition(
                newState = RunState.Recovering(snapshot, effectIdentity),
                effects = listOf(
                    RunEffect.RecoverDurableRun(effectIdentity, snapshot.status),
                ),
            )
        }
        is RunState.Recovering -> reject(
            state,
            if (state.snapshot == command.snapshot) {
                CommandRejection.DUPLICATE_RESULT
            } else {
                CommandRejection.STALE_IDENTITY
            },
        )
        is RunState.Preparing,
        is RunState.Active,
        is RunState.Finalizing,
        is RunState.Stopping,
        -> reject(state, CommandRejection.ILLEGAL_STATE)
    }

    private fun completeRecovery(
        state: RunState,
        command: ConversationCommand.RecoveryCompleted,
    ): Transition {
        val recovering = state as? RunState.Recovering
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (recovering.effectIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (!command.success) {
            if (recovering.failureReported) {
                return reject(state, CommandRejection.DUPLICATE_RESULT)
            }
            return Transition(
                newState = recovering.copy(failureReported = true),
                effects = listOf(RunEffect.RunRecoveryFailed(command.identity)),
            )
        }
        return Transition(RunState.Idle(recovering.conversationId))
    }

    private fun acquire(
        state: RunState,
        command: ConversationCommand.AcquireSlot,
    ): Transition = when (state) {
        is RunState.Idle -> Transition(
            newState = RunState.Active(command.identity),
            effects = listOf(RunEffect.SlotActivated(command.identity)),
        )
        is RunState.Recovering,
        is RunState.Preparing,
        is RunState.Active,
        is RunState.Finalizing,
        is RunState.Stopping,
        -> reject(state, CommandRejection.ILLEGAL_STATE)
    }

    private fun requestSend(
        state: RunState,
        command: ConversationCommand.SendRequested,
    ): Transition = when (state) {
        is RunState.Idle -> when {
            command.directOnly && command.hasPendingGuidance -> Transition(
                newState = state,
                effects = listOf(
                    RunEffect.RejectSendBusy(command.identity),
                ),
            )
            command.hasPendingGuidance -> Transition(
                newState = state,
                effects = listOf(
                    RunEffect.DrainGuidanceFirst(command.identity),
                ),
            )
            else -> {
                val ownerIdentity = RuntimeRunIdentity(
                    conversationId = command.identity.conversationId,
                    ownerToken = command.identity.ownerToken,
                )
                Transition(
                    newState = RunState.Preparing(ownerIdentity, command.identity),
                    effects = listOf(RunEffect.PersistAcceptedInput(command.identity)),
                )
            }
        }
        is RunState.Preparing -> if (command.directOnly) {
            busy(state, command)
        } else {
            Transition(
                newState = state,
                effects = listOf(
                    RunEffect.AcceptGuidance(
                        command.identity.copy(
                            ownerToken = state.ownerIdentity.ownerToken,
                            runId = state.inputEffectIdentity.runId,
                            pass = 0,
                        ),
                    ),
                ),
            )
        }
        is RunState.Active -> when {
            command.directOnly -> busy(state, command)
            state.identity.runId == null -> deferred(state, command)
            else -> Transition(
                newState = state,
                effects = listOf(
                    RunEffect.AcceptGuidance(
                        RunEffectIdentity(
                            conversationId = state.identity.conversationId,
                            ownerToken = state.identity.ownerToken,
                            runId = state.identity.runId,
                            pass = state.identity.pass,
                            effectId = command.identity.effectId,
                        ),
                    ),
                ),
            )
        }
        is RunState.Stopping -> deferredOrBusy(state, command)
        is RunState.Finalizing -> deferredOrBusy(state, command)
        is RunState.Recovering -> reject(state, CommandRejection.ILLEGAL_STATE)
    }

    private fun inputPersisted(
        state: RunState,
        command: ConversationCommand.InputPersisted,
    ): Transition {
        return when (state) {
            is RunState.Preparing -> {
                if (state.inputEffectIdentity != command.identity) {
                    reject(state, CommandRejection.STALE_IDENTITY)
                } else {
                    Transition(RunState.Active(command.identity.runIdentity()))
                }
            }
            is RunState.Stopping -> bindDurableRunAfterStop(
                state,
                command.identity.runIdentity(),
            )
            else -> reject(state, CommandRejection.ILLEGAL_STATE)
        }
    }

    private fun inputPersistenceFailed(
        state: RunState,
        command: ConversationCommand.InputPersistenceFailed,
    ): Transition {
        val preparing = state as? RunState.Preparing
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (preparing.inputEffectIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (preparing.inputFailureReported) {
            return reject(state, CommandRejection.DUPLICATE_RESULT)
        }
        return Transition(
            newState = preparing.copy(inputFailureReported = true),
        )
    }

    private fun abandonSendLaunch(
        state: RunState,
        command: ConversationCommand.SendLaunchAbandoned,
    ): Transition {
        val preparing = state as? RunState.Preparing
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (preparing.inputEffectIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        return Transition(
            newState = RunState.Idle(state.conversationId),
            effects = listOf(
                RunEffect.ReleaseSlot(
                    preparing.ownerIdentity,
                    SlotReleaseReason.SEND_LAUNCH_ABANDONED,
                ),
            ),
        )
    }

    private fun bindRun(
        state: RunState,
        command: ConversationCommand.BindRun,
    ): Transition {
        if (state is RunState.Stopping) {
            return bindDurableRunAfterStop(state, command.identity)
        }
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (
            active.coroutineSettled ||
            active.providerPhase != RunProviderPhase.None ||
            active.toolPhase != RunToolPhase.None
        ) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        if (!sameOwner(active.identity, command.identity)) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        val currentRunId = active.identity.runId
        if (currentRunId != null && currentRunId != command.identity.runId) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (command.identity.pass < active.identity.pass) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (active.identity == command.identity) {
            return reject(state, CommandRejection.DUPLICATE_RESULT)
        }
        if (currentRunId != null && command.identity.pass != active.identity.pass + 1) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        return Transition(RunState.Active(command.identity))
    }

    private fun bindDurableRunAfterStop(
        stopping: RunState.Stopping,
        boundIdentity: RuntimeRunIdentity,
    ): Transition {
        if (stopping.identity.runId != null) {
            return reject(
                stopping,
                if (stopping.identity == boundIdentity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
        }
        if (stopping.finalizationEffectId != null || !stopping.persistenceSettled) {
            return reject(stopping, CommandRejection.ILLEGAL_STATE)
        }
        if (!sameOwner(stopping.identity, boundIdentity)) {
            return reject(stopping, CommandRejection.STALE_IDENTITY)
        }
        val effectId = "stop-${boundIdentity.ownerToken}"
        val effectIdentity = boundIdentity.effectIdentity(effectId)
        return Transition(
            newState = stopping.copy(
                identity = boundIdentity,
                finalizationEffectId = effectId,
                persistenceSettled = false,
            ),
            effects = listOf(RunEffect.FinalizeStop(effectIdentity)),
        )
    }

    private fun requestProviderPass(
        state: RunState,
        command: ConversationCommand.ProviderPassRequested,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.coroutineSettled) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        if (active.identity != command.identity.runIdentity()) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (active.toolPhase != RunToolPhase.None) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        return when (val phase = active.providerPhase) {
            RunProviderPhase.None -> Transition(
                newState = active.copy(
                    providerPhase = RunProviderPhase.Running(command.identity),
                ),
                effects = listOf(RunEffect.StartProviderPass(command.identity)),
            )
            is RunProviderPhase.Running -> reject(
                state,
                if (phase.identity == command.identity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
        }
    }

    private fun completeProviderPass(
        state: RunState,
        command: ConversationCommand.ProviderPassCompleted,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.coroutineSettled) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        val running = active.providerPhase as? RunProviderPhase.Running
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (running.identity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        return Transition(
            newState = active.copy(providerPhase = RunProviderPhase.None),
            effects = listOf(RunEffect.ProviderPassAccepted(command.identity, command.result)),
        )
    }

    private fun requestToolBatch(
        state: RunState,
        command: ConversationCommand.ToolBatchRequested,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.coroutineSettled || active.providerPhase != RunProviderPhase.None) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        if (active.identity != command.identity.runIdentity()) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        val batchIdentity = command.identity.derived("tool-batch")
        return when (val phase = active.toolPhase) {
            RunToolPhase.None -> Transition(
                newState = active.copy(toolPhase = RunToolPhase.Executing(batchIdentity)),
                effects = listOf(RunEffect.ExecuteToolBatch(batchIdentity)),
            )
            is RunToolPhase.Executing -> reject(
                state,
                if (phase.batchIdentity == batchIdentity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
            is RunToolPhase.Committing -> reject(
                state,
                if (phase.batchIdentity == batchIdentity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
        }
    }

    private fun completeToolBatch(
        state: RunState,
        command: ConversationCommand.ToolBatchCompleted,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.coroutineSettled) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        return when (val phase = active.toolPhase) {
            RunToolPhase.None -> reject(state, CommandRejection.ILLEGAL_STATE)
            is RunToolPhase.Executing -> {
                if (phase.batchIdentity != command.identity) {
                    reject(state, CommandRejection.STALE_IDENTITY)
                } else {
                    val commitIdentity = command.identity.derived("tool-round")
                    Transition(
                        newState = active.copy(
                            toolPhase = RunToolPhase.Committing(
                                batchIdentity = command.identity,
                                commitIdentity = commitIdentity,
                            ),
                        ),
                        effects = listOf(RunEffect.CommitToolRound(commitIdentity)),
                    )
                }
            }
            is RunToolPhase.Committing -> reject(
                state,
                if (phase.batchIdentity == command.identity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
        }
    }

    private fun commitToolRound(
        state: RunState,
        command: ConversationCommand.ToolRoundCommitted,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.coroutineSettled) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        val committing = active.toolPhase as? RunToolPhase.Committing
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (committing.commitIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (!command.success) {
            if (committing.failureReported) {
                return reject(state, CommandRejection.DUPLICATE_RESULT)
            }
            return Transition(
                newState = active.copy(
                    toolPhase = committing.copy(failureReported = true),
                ),
                effects = listOf(RunEffect.ToolRoundCommitFailed(command.identity)),
            )
        }
        return Transition(
            newState = active.copy(toolPhase = RunToolPhase.None),
            effects = listOf(RunEffect.ContinueProviderPass(command.identity)),
        )
    }


    private fun requestFinalization(
        state: RunState,
        command: ConversationCommand.FinalizationRequested,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.identity != command.identity.runIdentity()) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        val effect = RunEffect.FinalizeRun(
            identity = command.identity,
            status = command.status,
            reason = command.reason,
            markConversationUnread = command.markConversationUnread,
        )
        return Transition(
            newState = RunState.Finalizing(
                identity = active.identity,
                effectIdentity = command.identity,
                status = command.status,
                reason = command.reason,
                markConversationUnread = command.markConversationUnread,
                coroutineSettled = active.coroutineSettled,
                persistenceSettled = false,
            ),
            effects = listOf(effect),
        )
    }

    private fun completeFinalization(
        state: RunState,
        command: ConversationCommand.FinalizationCompleted,
    ): Transition {
        val finalizing = state as? RunState.Finalizing
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (finalizing.effectIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (finalizing.persistenceSettled) {
            return reject(state, CommandRejection.DUPLICATE_RESULT)
        }
        if (!command.success) {
            if (finalizing.persistenceFailureReported) {
                return reject(state, CommandRejection.DUPLICATE_RESULT)
            }
            return Transition(
                newState = finalizing.copy(persistenceFailureReported = true),
                effects = listOf(RunEffect.RunFinalizationFailed(command.identity)),
            )
        }
        return if (finalizing.coroutineSettled) {
            releaseSettledFinalization(finalizing)
        } else {
            Transition(finalizing.copy(persistenceSettled = true))
        }
    }

    private fun requestStop(
        state: RunState,
        command: ConversationCommand.StopRequested,
    ): Transition {
        val activeIdentity = when (state) {
            is RunState.Recovering -> return reject(state, CommandRejection.ILLEGAL_STATE)
            is RunState.Preparing -> state.ownerIdentity
            is RunState.Active -> state.identity
            is RunState.Finalizing -> {
                if (!state.persistenceFailureReported) {
                    return reject(state, CommandRejection.ILLEGAL_STATE)
                }
                state.identity
            }
            is RunState.Stopping -> return when {
                state.identity != command.identity ||
                    state.finalizationEffectId != command.effectId ->
                    reject(state, CommandRejection.STALE_IDENTITY)
                !state.persistenceFailureReported ->
                    reject(state, CommandRejection.DUPLICATE_RESULT)
                state.finalizationEffectId == null ->
                    reject(state, CommandRejection.ILLEGAL_STATE)
                else -> Transition(
                    newState = state.copy(persistenceFailureReported = false),
                    effects = listOf(
                        RunEffect.FinalizeStop(
                            state.identity.effectIdentity(state.finalizationEffectId),
                        ),
                    ),
                )
            }
            is RunState.Idle -> return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        if (activeIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }

        val effects = mutableListOf<RunEffect>(RunEffect.CancelProviderPass(activeIdentity))
        val effectId = command.effectId
        if (effectId != null) {
            effects += RunEffect.FinalizeStop(activeIdentity.effectIdentity(effectId))
        }
        val persistenceSettled = !command.requiresPersistence
        if (command.coroutineAlreadySettled && persistenceSettled) {
            effects += RunEffect.ReleaseSlot(activeIdentity, SlotReleaseReason.EMPTY_STOP)
            return Transition(RunState.Idle(activeIdentity.conversationId), effects)
        }
        return Transition(
            newState = RunState.Stopping(
                identity = activeIdentity,
                finalizationEffectId = effectId,
                coroutineSettled = command.coroutineAlreadySettled,
                persistenceSettled = persistenceSettled,
            ),
            effects = effects,
        )
    }

    private fun settleCoroutine(
        state: RunState,
        command: ConversationCommand.CoroutineSettled,
    ): Transition = when (state) {
        is RunState.Idle -> reject(state, CommandRejection.ILLEGAL_STATE)
        is RunState.Recovering -> reject(state, CommandRejection.ILLEGAL_STATE)
        is RunState.Preparing -> {
            if (state.ownerIdentity != command.identity) {
                reject(state, CommandRejection.STALE_IDENTITY)
            } else {
                Transition(
                    newState = RunState.Idle(state.conversationId),
                    effects = listOf(
                        RunEffect.ReleaseSlot(
                            state.ownerIdentity,
                            SlotReleaseReason.NORMAL_COMPLETION,
                        ),
                    ),
                )
            }
        }
        is RunState.Active -> {
            if (state.identity != command.identity) {
                reject(state, CommandRejection.STALE_IDENTITY)
            } else if (state.coroutineSettled) {
                reject(state, CommandRejection.DUPLICATE_RESULT)
            } else if (state.identity.runId != null) {
                // A bound durable Run cannot become Idle merely because its coroutine ended. The
                // exact terminal Room result (or a subsequent Stop recovery) must settle it.
                Transition(state.copy(coroutineSettled = true))
            } else {
                Transition(
                    newState = RunState.Idle(state.conversationId),
                    effects = listOf(
                        RunEffect.ReleaseSlot(state.identity, SlotReleaseReason.NORMAL_COMPLETION),
                    ),
                )
            }
        }
        is RunState.Finalizing -> when {
            state.identity != command.identity ->
                reject(state, CommandRejection.STALE_IDENTITY)
            state.coroutineSettled ->
                reject(state, CommandRejection.DUPLICATE_RESULT)
            state.persistenceSettled -> releaseSettledFinalization(state)
            else -> Transition(state.copy(coroutineSettled = true))
        }
        is RunState.Stopping -> {
            when {
                state.identity != command.identity ->
                    reject(state, CommandRejection.STALE_IDENTITY)
                state.coroutineSettled ->
                    reject(state, CommandRejection.DUPLICATE_RESULT)
                state.persistenceSettled -> releaseSettledStop(state)
                else -> Transition(state.copy(coroutineSettled = true))
            }
        }
    }

    private fun settlePersistence(
        state: RunState,
        command: ConversationCommand.PersistenceSettled,
    ): Transition {
        val stopping = state as? RunState.Stopping
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        val expectedEffectId = stopping.finalizationEffectId
            ?: return reject(state, CommandRejection.STALE_IDENTITY)
        val expectedIdentity = stopping.identity.effectIdentity(expectedEffectId)
        if (expectedIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (stopping.persistenceSettled) {
            return reject(state, CommandRejection.DUPLICATE_RESULT)
        }
        if (!command.success) {
            if (stopping.persistenceFailureReported) {
                return reject(state, CommandRejection.DUPLICATE_RESULT)
            }
            return Transition(
                newState = stopping.copy(persistenceFailureReported = true),
                effects = listOf(RunEffect.StopPersistenceFailed(command.identity)),
            )
        }
        return if (stopping.coroutineSettled) {
            releaseSettledStop(stopping)
        } else {
            Transition(stopping.copy(persistenceSettled = true))
        }
    }

    private fun releaseSettledStop(stopping: RunState.Stopping) = Transition(
        newState = RunState.Idle(stopping.conversationId),
        effects = listOf(
            RunEffect.ReleaseSlot(
                identity = stopping.identity,
                reason = SlotReleaseReason.STOP_BARRIERS_SETTLED,
            ),
        ),
    )

    private fun releaseSettledFinalization(finalizing: RunState.Finalizing) = Transition(
        newState = RunState.Idle(finalizing.conversationId),
        effects = listOf(
            RunEffect.ReleaseSlot(
                identity = finalizing.identity,
                reason = SlotReleaseReason.NORMAL_FINALIZATION_SETTLED,
            ),
        ),
    )

    private fun RuntimeRunIdentity.effectIdentity(effectId: String) = RunEffectIdentity(
        conversationId = conversationId,
        ownerToken = ownerToken,
        runId = requireNotNull(runId) {
            "An asynchronous Run effect requires a bound Run id"
        },
        pass = pass,
        effectId = effectId,
    )

    private fun RunEffectIdentity.derived(prefix: String) = copy(
        effectId = "$prefix-$effectId",
    )

    private fun sameOwner(first: RuntimeRunIdentity, second: RuntimeRunIdentity): Boolean =
        first.conversationId == second.conversationId && first.ownerToken == second.ownerToken

    private fun deferredOrBusy(
        state: RunState,
        command: ConversationCommand.SendRequested,
    ): Transition = if (command.directOnly) busy(state, command) else deferred(state, command)

    private fun busy(
        state: RunState,
        command: ConversationCommand.SendRequested,
    ) = Transition(
        newState = state,
        effects = listOf(
            RunEffect.RejectSendBusy(command.identity),
        ),
    )

    private fun deferred(
        state: RunState,
        command: ConversationCommand.SendRequested,
    ) = Transition(
        newState = state,
        effects = listOf(
            RunEffect.AwaitRunRelease(command.identity),
        ),
    )

    private fun reject(state: RunState, rejection: CommandRejection) = Transition(
        newState = state,
        rejection = rejection,
    )
}
