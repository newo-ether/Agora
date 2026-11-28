package com.newoether.agora.model

sealed interface ConversationCommand {
    val conversationId: String

    /** Convert one Room live-Run snapshot into an identified terminal recovery effect. */
    data class Recover(val snapshot: RunRecoverySnapshot) : ConversationCommand {
        override val conversationId: String = snapshot.conversationId
    }

    /** Result of the exact [RunEffect.RecoverDurableRun] transaction. */
    data class RecoveryCompleted(
        val identity: RunEffectIdentity,
        val success: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    data class AcquireSlot(val identity: RuntimeRunIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(identity.runId == null)
            require(identity.pass == 0)
        }
    }

    data class SendRequested(
        val identity: RunEffectIdentity,
        val directOnly: Boolean,
        val hasPendingGuidance: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(identity.pass == 0)
        }
    }

    /** Result of the exact [RunEffect.PersistAcceptedInput] emitted for this Send. */
    data class InputPersisted(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    data class InputPersistenceFailed(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** Cancellation won after a direct claim but before its state-owned Job could be installed. */
    data class SendLaunchAbandoned(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    data class BindRun(val identity: RuntimeRunIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(identity.runId != null)
        }
    }

    /** Request execution of exactly one Provider pass for the current Run/pass. */
    data class ProviderPassRequested(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** Closed semantic result of the exact emitted Provider pass. */
    data class ProviderPassCompleted(
        val identity: RunEffectIdentity,
        val result: ProviderPassResult,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** A termination-validated Provider outcome requests execution of exactly one tool batch. */
    data class ToolBatchRequested(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** All tools in the exact emitted batch completed with authoritative results. */
    data class ToolBatchCompleted(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** Result of the exact atomic protocol-round Room commit. */
    data class ToolRoundCommitted(
        val identity: RunEffectIdentity,
        val success: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }


    /** Request the one normal terminal Room transaction for the active Run. */
    data class FinalizationRequested(
        val identity: RunEffectIdentity,
        val status: RunStatus,
        val reason: RunEndReason,
        val markConversationUnread: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(status.isTerminal)
        }
    }

    /** Result of the exact [RunEffect.FinalizeRun] Room transaction. */
    data class FinalizationCompleted(
        val identity: RunEffectIdentity,
        val success: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    data class StopRequested(
        val identity: RuntimeRunIdentity,
        val coroutineAlreadySettled: Boolean,
        val requiresPersistence: Boolean,
        val effectId: String?,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(requiresPersistence == (effectId != null))
            require(requiresPersistence == (identity.runId != null)) {
                "A durable Run must have exactly one identified Stop finalization effect"
            }
            require(effectId == null || effectId.isNotBlank())
        }
    }

    data class CoroutineSettled(val identity: RuntimeRunIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    data class PersistenceSettled(
        val identity: RunEffectIdentity,
        val success: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }
}
