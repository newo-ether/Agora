package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.templates.InteractiveTemplate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal data class LocalComposerResult(
    val acceptance: SendAcceptance? = null,
    val expectedWorkspace: NewChatPersistEntity? = null,
)

/** A local content transaction, not a generation. The DAO rechecks that no live Run exists. */
internal class InteractiveTemplateController(
    private val context: android.content.Context,
    private val conversations: ConversationRepository,
    private val origin: ChatClient,
    private val coordinator: com.newoether.agora.automation.ConversationExecutionCoordinator,
    private val lastMessage: () -> com.newoether.agora.model.ChatMessage?,
    private val isBusy: () -> Boolean,
) {
    suspend fun open(template: InteractiveTemplate, target: ForegroundSendTarget, consumeDraft: Boolean = false): LocalComposerResult {
        check(!isBusy()) { "Wait for the current reply before opening a template" }
        var result: LocalComposerResult? = null
        check(coordinator.tryWithConversationLock(target.conversationId) {
            result = openLocked(template, target, consumeDraft)
        }) { "Wait for the current reply before opening a template" }
        return checkNotNull(result)
    }

    private suspend fun openLocked(template: InteractiveTemplate, target: ForegroundSendTarget, consumeDraft: Boolean): LocalComposerResult {
        template.validate()
        check(target.wasNewChat || origin.openConversationId == target.conversationId) { "Conversation changed" }
        val parent = if (target.wasNewChat) null else lastMessage()
        val now = System.currentTimeMillis()
        val workspace = target.newChatWorkspace?.awaitCaptured()
        val message = MessageEntity(
            id = UUID.randomUUID().toString(), conversationId = target.conversationId,
            parentId = parent?.id, text = "", participant = Participant.MODEL, timestamp = now,
            toolCallJson = Json.encodeToString(listOf(template.segment())),
            runId = target.runId, runSequence = 0,
        )
        val run = RunEntity(
            id = target.runId, conversationId = target.conversationId, parentRunId = parent?.runId,
            status = RunStatus.COMPLETED, activeSlot = null, startedAt = now, lastCheckpointAt = now,
            endedAt = now, endReason = RunEndReason.LOCAL_TEMPLATE_OPENED,
        )
        return withContext(NonCancellable) {
            val graph = if (target.wasNewChat) {
                conversations.createConversationRunWithMessages(
                    conversation = ChatEntity(id = target.conversationId, title = template.name, lastUpdated = now,
                        systemPromptId = workspace?.systemPromptId, modelId = target.modelId),
                    run = run, messages = listOf(message), messageSelectionUpdates = mapOf(null to message.id),
                    conversationModelId = target.modelId,
                    conversationSettingsJson = workspace?.conversationSettings?.let { Json.encodeToString(it) },
                    expectedNewChatPersist = workspace?.persisted.takeIf { consumeDraft },
                )
            } else {
                conversations.createRunWithMessages(run, listOf(message), mapOf(parent?.id to message.id),
                    conversationModelId = null, touchConversationOnAdmission = true)
            }
            if (target.wasNewChat) {
                origin.applyCommittedNewConversationState(target.conversationId)
                origin.publishAcceptedNewConversation(target.conversationId, target.modelId, checkNotNull(target.newChatEntryId))
            }
            if (origin.openConversationId == target.conversationId) origin.renderStore.commitGraph(
                committedMessages = graph.messages.map { it.toUiChatMessage(context) },
                selectedChildren = graph.messageSelections,
                streamingMessage = null,
            )
            origin.onSendAccepted(target.conversationId, message.id)
            origin.requestScrollToBottomAfter(target.conversationId, message.id, attachedOnly = false)
            LocalComposerResult(SendAcceptance.Direct(message.id, target.conversationId), workspace?.persisted)
        }
    }
}
