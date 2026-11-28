package com.newoether.agora.model

enum class SlotReleaseReason {
    NORMAL_COMPLETION,
    NORMAL_FINALIZATION_SETTLED,
    STOP_BARRIERS_SETTLED,
    EMPTY_STOP,
    SEND_LAUNCH_ABANDONED,
}
sealed interface RunEffect {
    data class RecoverDurableRun(
        val identity: RunEffectIdentity,
        val priorStatus: RunStatus,
    ) : RunEffect {
        init {
            require(priorStatus == RunStatus.ACTIVE || priorStatus == RunStatus.STOPPING)
        }
    }
    data class RunRecoveryFailed(val identity: RunEffectIdentity) : RunEffect
    data class SlotActivated(val identity: RuntimeRunIdentity) : RunEffect
    data class PersistAcceptedInput(val identity: RunEffectIdentity) : RunEffect
    data class AcceptGuidance(val identity: RunEffectIdentity) : RunEffect
    data class DrainGuidanceFirst(val identity: RunEffectIdentity) : RunEffect
    data class AwaitRunRelease(val identity: RunEffectIdentity) : RunEffect
    data class RejectSendBusy(val identity: RunEffectIdentity) : RunEffect
    data class StartProviderPass(val identity: RunEffectIdentity) : RunEffect
    data class ProviderPassAccepted(
        val identity: RunEffectIdentity,
        val result: ProviderPassResult,
    ) : RunEffect
    data class CancelProviderPass(val identity: RuntimeRunIdentity) : RunEffect
    data class FinalizeStop(val identity: RunEffectIdentity) : RunEffect
    data class StopPersistenceFailed(val identity: RunEffectIdentity) : RunEffect
    data class ExecuteToolBatch(val identity: RunEffectIdentity) : RunEffect
    data class CommitToolRound(val identity: RunEffectIdentity) : RunEffect
    data class ContinueProviderPass(val identity: RunEffectIdentity) : RunEffect
    data class ToolRoundCommitFailed(val identity: RunEffectIdentity) : RunEffect
    data class FinalizeRun(
        val identity: RunEffectIdentity,
        val status: RunStatus,
        val reason: RunEndReason,
        val markConversationUnread: Boolean,
    ) : RunEffect {
        init {
            require(status.isTerminal)
        }
    }
    data class RunFinalizationFailed(val identity: RunEffectIdentity) : RunEffect
    data class ReleaseSlot(
        val identity: RuntimeRunIdentity,
        val reason: SlotReleaseReason,
    ) : RunEffect
}
