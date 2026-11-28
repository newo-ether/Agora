package com.newoether.agora.webui

import com.newoether.agora.tool.SessionToolGroup
import com.newoether.agora.viewmodel.SessionToolCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

/** Browser wire projection of the same immutable catalog the Compose sheet displays. */
internal fun SessionToolCatalog?.toWebSessionTools(
    connectionId: String,
    target: WebUiChatSession.OpenTarget,
    revision: Long,
    enabled: Set<SessionToolGroup>? = null,
    failed: Boolean = false,
): WebSyncEvent.SessionTools {
    val available = if (failed) emptyList() else this?.groups.orEmpty()
    return WebSyncEvent.SessionTools(
        connectionId = connectionId,
        conversationId = target.conversationId,
        seq = target.browserSeq,
        revision = revision,
        failed = failed,
        groups = available.map { group ->
            WebSessionToolGroup(
                id = group.group.id,
                tokens = group.tokens,
                unavailable = group.unavailable?.name?.lowercase(),
                tools = group.tools.map { tool ->
                    WebSessionToolEntry(tool.name, tool.description, tool.tokens)
                },
            )
        },
        enabledGroups = if (failed) emptyList() else (enabled ?: this?.enabledGroups.orEmpty()).map(SessionToolGroup::id),
        toolSetTokens = if (failed) 0 else this?.toolSetTokens ?: 0,
    )
}

/** Catalog pricing runs off the command loop. Toggle writes and replies stay on the ordered
 * connection loop, so two same-revision replies cannot pass each other on the way to the browser. */
internal suspend fun CoroutineScope.dispatchWebUiSessionToolsCommand(
    command: WebSyncCommand,
    connectionId: String,
    session: WebUiChatSession,
    connectionJob: Job,
    outbound: SendChannel<WebSyncEvent>,
) {
    if (command.connectionId != connectionId) return
    val tools = session.sessionTools
    if (command.type == "session_tools") {
        launch {
            val event = tools.capture(command, connectionId)
            if (event != null && connectionJob.isActive && tools.isCurrent(event)) outbound.send(event)
        }
        return
    }
    val event = tools.toggle(command, connectionId)
    if (event != null && connectionJob.isActive && tools.isCurrent(event)) outbound.send(event)
}
