package com.newoether.agora.model

sealed interface RunState {
    val conversationId: String

    data class Idle(override val conversationId: String) : RunState {
        init {
            require(conversationId.isNotBlank())
        }
    }

    /** Ephemeral startup-only ownership while an orphaned durable Run is terminalized. */
    data class Recovering(
        val snapshot: RunRecoverySnapshot,
        val effectIdentity: RunEffectIdentity,
        val failureReported: Boolean = false,
    ) : RunState {
        override val conversationId: String = snapshot.conversationId

        init {
            require(effectIdentity.conversationId == snapshot.conversationId)
            require(effectIdentity.runId == snapshot.runId)
            require(effectIdentity.pass == snapshot.pass)
        }
    }

    /** A foreground Send owns the slot while its conversation/Run/message transaction executes. */
    data class Preparing(
        val ownerIdentity: RuntimeRunIdentity,
        val inputEffectIdentity: RunEffectIdentity,
        val inputFailureReported: Boolean = false,
    ) : RunState {
        override val conversationId: String = ownerIdentity.conversationId

        init {
            require(ownerIdentity.runId == null)
            require(ownerIdentity.pass == 0)
            require(inputEffectIdentity.conversationId == ownerIdentity.conversationId)
            require(inputEffectIdentity.ownerToken == ownerIdentity.ownerToken)
            require(inputEffectIdentity.pass == 0)
        }
    }

    data class Active(
        val identity: RuntimeRunIdentity,
        val coroutineSettled: Boolean = false,
        val providerPhase: RunProviderPhase = RunProviderPhase.None,
        val toolPhase: RunToolPhase = RunToolPhase.None,
    ) : RunState {
        override val conversationId: String = identity.conversationId

        init {
            require(
                providerPhase == RunProviderPhase.None || toolPhase == RunToolPhase.None,
            ) { "Provider and tool phases cannot overlap" }
            when (providerPhase) {
                RunProviderPhase.None -> Unit
                is RunProviderPhase.Running -> require(
                    providerPhase.identity.runIdentity() == identity,
                )
            }
            when (toolPhase) {
                RunToolPhase.None -> Unit
                is RunToolPhase.Executing -> require(
                    toolPhase.batchIdentity.runIdentity() == identity,
                )
                is RunToolPhase.Committing -> {
                    require(toolPhase.batchIdentity.runIdentity() == identity)
                    require(toolPhase.commitIdentity.runIdentity() == identity)
                }
            }
        }
    }


    data class Stopping(
        val identity: RuntimeRunIdentity,
        val finalizationEffectId: String?,
        val coroutineSettled: Boolean,
        val persistenceSettled: Boolean,
        val persistenceFailureReported: Boolean = false,
    ) : RunState {
        override val conversationId: String = identity.conversationId

        init {
            require(finalizationEffectId == null || finalizationEffectId.isNotBlank())
            require(finalizationEffectId != null || persistenceSettled) {
                "A Stop without a persistence effect must already have a settled persistence barrier"
            }
            require(!persistenceFailureReported || finalizationEffectId != null)
            require(!(coroutineSettled && persistenceSettled)) {
                "A fully settled Stop must release to Idle in the same transition"
            }
        }
    }

    /** Natural SUCCESS/FAILED/external-cancellation terminalization with two settlement barriers. */
    data class Finalizing(
        val identity: RuntimeRunIdentity,
        val effectIdentity: RunEffectIdentity,
        val status: RunStatus,
        val reason: RunEndReason,
        val markConversationUnread: Boolean,
        val coroutineSettled: Boolean,
        val persistenceSettled: Boolean,
        val persistenceFailureReported: Boolean = false,
    ) : RunState {
        override val conversationId: String = identity.conversationId

        init {
            require(identity.runId != null)
            require(effectIdentity.runIdentity() == identity)
            require(status.isTerminal)
            require(!persistenceFailureReported || !persistenceSettled)
            require(!(coroutineSettled && persistenceSettled)) {
                "A fully settled finalization must release to Idle in the same transition"
            }
        }
    }
}
sealed interface RunProviderPhase {
    data object None : RunProviderPhase
    data class Running(val identity: RunEffectIdentity) : RunProviderPhase
}

/** Authoritative in-process boundary for one validated provider tool batch. */
sealed interface RunToolPhase {
    data object None : RunToolPhase

    data class Executing(
        val batchIdentity: RunEffectIdentity,
    ) : RunToolPhase

    data class Committing(
        val batchIdentity: RunEffectIdentity,
        val commitIdentity: RunEffectIdentity,
        val failureReported: Boolean = false,
    ) : RunToolPhase
}


enum class ProviderPassResult {
    COMPLETED_TEXT,
    COMPLETED_TOOL_CALLS,
    TRUNCATED,
    FAILED,
    CANCELLED,
}
