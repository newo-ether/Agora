package com.newoether.agora.data.local

import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.ConversationRuntimeReducer
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunRecoveryPolicy
import com.newoether.agora.model.RunRecoverySnapshot
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.DebugLog
import kotlinx.serialization.json.Json

/** Body of [ChatDao.recoverConversationRuntime]; Room's @Transaction on the DAO method wraps this extension, so every DAO call joins the same transaction. */
internal suspend fun ChatDao.executeRuntimeRecovery(
    conversationId: String,
    at: Long,
): Int {
    val startedAt = System.currentTimeMillis()
    fun stage(stage: String, rows: Int? = null) {
        DebugLog.recoveryStage(conversationId, stage, System.currentTimeMillis() - startedAt, rows)
    }
    stage("entered")
    val conversation = getConversation(conversationId) ?: run {
        stage("conversation-missing")
        return 0
    }
    stage("conversation-read")
    var changedRows = 0
    val liveRun = getLiveRun(conversationId)
    stage("live-run-read")
    if (liveRun != null) {
        val snapshot = RunRecoverySnapshot(
            conversationId = conversationId,
            runId = liveRun.id,
            pass = liveRun.currentPass,
            status = liveRun.status,
        )
        val requested = ConversationRuntimeReducer.reduce(
            RunState.Idle(conversationId),
            ConversationCommand.Recover(snapshot),
        )
        val recoveryEffect = requested.effects
            .filterIsInstance<RunEffect.RecoverDurableRun>()
            .single()
        check(recoveryEffect.priorStatus == liveRun.status)
        getMessagesForRuns(listOf(liveRun.id)).forEach { message ->
            val recoveredStatus = RunRecoveryPolicy.recoverMessageStatus(
                message.participant,
                message.status,
            )
            val recoveredToolJson = message.toolCallJson?.let { raw ->
                RunRecoveryPolicy.stopIncompleteToolsJson(raw) ?: raw
            }
            if (recoveredStatus != message.status || recoveredToolJson != message.toolCallJson) {
                changedRows += updateMessageCheckpoint(
                    MessageStreamCheckpoint(
                        id = message.id,
                        text = message.text,
                        images = message.images,
                        thoughts = message.thoughts,
                        thoughtTitle = message.thoughtTitle,
                        tokenCount = message.tokenCount,
                        inputTokenCount = message.inputTokenCount,
                        cachedInputTokenCount = message.cachedInputTokenCount,
                        cacheWriteInputTokenCount = message.cacheWriteInputTokenCount,
                        uncachedInputTokenCount = message.uncachedInputTokenCount,
                        outputTokenCount = message.outputTokenCount,
                        reasoningTokenCount = message.reasoningTokenCount,
                        generationDurationMs = message.generationDurationMs,
                        status = recoveredStatus,
                        thoughtTimeMs = message.thoughtTimeMs,
                        toolCallJson = recoveredToolJson,
                    )
                )
            }
        }
        val durableSuccess = terminalizeLiveRun(
            runId = recoveryEffect.identity.runId,
            status = RunStatus.STOPPED,
            reason = RunEndReason.PROCESS_RECOVERED,
            at = at,
        ) == 1
        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.RecoveryCompleted(
                recoveryEffect.identity,
                durableSuccess,
            ),
        )
        check(durableSuccess && completed.accepted && completed.newState is RunState.Idle) {
            "Run recovery transaction lost ownership for ${liveRun.id}"
        }
        changedRows += 1
        stage("live-run-repaired")
    }

    conversation.selectedRunBranchesJson?.let { raw ->
        val decoded = runCatching {
            Json.decodeFromString<Map<String, String>>(raw)
                .mapKeys { if (it.key == "null") null else it.key }
        }.getOrNull()
        if (decoded != null) {
            val repaired = RunBranchSelectionIntegrity.retainValidEdges(
                selections = decoded,
                runs = getRunsForConversationSnapshot(conversationId),
            )
            if (repaired != decoded) {
                changedRows += compareAndSetRunBranchSelections(
                    conversationId = conversationId,
                    expected = raw,
                    replacement = encodeSelectionMap(repaired),
                )
            }
        }
    }
    stage("branches-validated")
    changedRows += stopStuckMessagesForConversation(conversationId)
    stage("stuck-stopped")
    if (changedRows > 0) check(touchConversationData(conversationId, at) == 1)
    stage("returned", changedRows)
    return changedRows
}
