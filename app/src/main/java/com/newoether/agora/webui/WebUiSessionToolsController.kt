package com.newoether.agora.webui

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.tool.SessionToolGroup
import com.newoether.agora.viewmodel.MessageGenerationController
import com.newoether.agora.viewmodel.SessionToolCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-connection tool catalog snapshot and setting writer. The session mutex admits requests and
 * writes, while definition pricing runs outside it. A newer target or revision fences old results.
 */
internal class WebUiSessionToolsController(
    private val generation: MessageGenerationController,
    private val ownerMutex: Mutex,
    private val target: StateFlow<WebUiChatSession.OpenTarget>,
    private val activeModel: StateFlow<Pair<String?, String>?>,
    private val isClosed: () -> Boolean,
    private val newChatSystemPromptId: () -> String?,
    private val newChatSettings: () -> ConversationSettings?,
    private val updateSettings: (String?, (ConversationSettings) -> ConversationSettings) -> Unit,
) {
    private data class CaptureRequest(
        val opened: WebUiChatSession.OpenTarget,
        val modelId: String,
        val systemPromptId: String?,
        val conversationSettings: ConversationSettings?,
    )

    private data class Snapshot(
        val opened: WebUiChatSession.OpenTarget,
        val revision: Long,
        val catalog: SessionToolCatalog,
        val enabledGroups: Set<SessionToolGroup>,
    )

    @Volatile private var newestRevision = 0L
    private var snapshot: Snapshot? = null

    suspend fun capture(command: WebSyncCommand, connectionId: String): WebSyncEvent.SessionTools? {
        val request = ownerMutex.withLock {
            val opened = target.value
            if (isClosed() || opened.browserSeq != command.seq ||
                opened.conversationId != command.conversationId || command.revision <= newestRevision) {
                return@withLock null
            }
            newestRevision = command.revision
            snapshot = null
            CaptureRequest(
                opened = opened,
                modelId = activeModel.value?.takeIf { it.first == opened.conversationId }?.second.orEmpty(),
                systemPromptId = if (opened.conversationId == null) newChatSystemPromptId() else null,
                conversationSettings = if (opened.conversationId == null) newChatSettings() else null,
            )
        } ?: return null
        val catalog = try {
            generation.captureSessionToolCatalog(
                conversationId = request.opened.conversationId,
                modelId = request.modelId,
                systemPromptIdOverride = request.systemPromptId,
                conversationSettingsOverride = request.conversationSettings,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return ownerMutex.withLock {
            if (isClosed() || target.value != request.opened || newestRevision != command.revision) {
                return@withLock null
            }
            if (catalog != null) {
                snapshot = Snapshot(request.opened, command.revision, catalog, catalog.enabledGroups)
            }
            catalog.toWebSessionTools(
                connectionId = connectionId,
                target = request.opened,
                revision = command.revision,
                failed = catalog == null,
            )
        }
    }

    suspend fun toggle(command: WebSyncCommand, connectionId: String): WebSyncEvent.SessionTools? =
        ownerMutex.withLock {
            val current = snapshot
            val enabled = command.enabled
            val group = command.toolGroup?.let(SessionToolGroup::fromId)
            if (isClosed() || current == null || enabled == null || group == null ||
                current.opened != target.value || current.revision != command.revision ||
                current.revision != newestRevision || current.opened.browserSeq != command.seq ||
                current.opened.conversationId != command.conversationId ||
                current.catalog.groups.none { it.group == group && it.unavailable == null }) {
                return@withLock null
            }
            updateSettings(current.opened.conversationId) { previous ->
                val disabled = previous.disabledToolGroups.orEmpty().toMutableSet().apply {
                    remove(SessionToolGroup.WEB.id)
                    remove(SessionToolGroup.TERMINAL.id)
                    if (!group.linked) {
                        if (enabled) remove(group.id) else add(group.id)
                    }
                }.ifEmpty { null }
                when (group) {
                    SessionToolGroup.WEB -> previous.copy(
                        webSearchEnabled = enabled, disabledToolGroups = disabled,
                    )
                    SessionToolGroup.TERMINAL -> previous.copy(
                        shellEnabled = enabled, disabledToolGroups = disabled,
                    )
                    else -> previous.copy(disabledToolGroups = disabled)
                }
            }
            val enabledGroups = if (enabled) current.enabledGroups + group else current.enabledGroups - group
            snapshot = current.copy(enabledGroups = enabledGroups)
            current.catalog.toWebSessionTools(
                connectionId = connectionId,
                target = current.opened,
                revision = current.revision,
                enabled = enabledGroups,
            )
        }

    /** Cheap final fence before a queued response reaches the single outbound writer. */
    fun isCurrent(event: WebSyncEvent.SessionTools): Boolean =
        event.revision == newestRevision && event.seq == target.value.browserSeq &&
            event.conversationId == target.value.conversationId
}
