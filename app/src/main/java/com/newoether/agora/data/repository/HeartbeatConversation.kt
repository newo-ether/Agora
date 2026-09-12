package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatEntity

private const val HEARTBEAT_ORIGIN = "heartbeat"
private const val HEARTBEAT_CONVERSATION_TITLE = "Heartbeat"

/**
 * Returns the single dedicated heartbeat conversation, creating it on first use.
 *
 * Looks up the newest conversation with origin "heartbeat". Legacy "Heartbeat"-titled
 * conversations are adopted by [migrateHeartbeatConversationSetting], not here. Every
 * heartbeat run reuses this id so history accumulates in one conversation instead of
 * spawning a fresh one per run.
 */
suspend fun ConversationRepository.getOrCreateHeartbeatConversationId(
    modelId: String? = null,
): String {
    getConversationByOrigin(HEARTBEAT_ORIGIN)?.let { return it.id }
    val id = java.util.UUID.randomUUID().toString()
    upsertConversation(
        ChatEntity(
            id = id,
            title = HEARTBEAT_CONVERSATION_TITLE,
            modelId = modelId,
            origin = HEARTBEAT_ORIGIN,
        )
    )
    return id
}

/**
 * One-shot migration: if `heartbeatConversationId` is not yet set but there is an existing
 * heartbeat conversation (by origin or legacy title), adopt it into the new setting so the
 * user doesn't have to re-select it manually after the upgrade.
 */
suspend fun ConversationRepository.migrateHeartbeatConversationSetting(
    settingsRepository: SettingsRepository,
) {
    if (!settingsRepository.heartbeatConversationId.value.isNullOrBlank()) return
    val existing = getConversationByOrigin(HEARTBEAT_ORIGIN)
        ?: getConversationByTitle(HEARTBEAT_CONVERSATION_TITLE)
    if (existing != null) {
        settingsRepository.saveHeartbeatConversationId(existing.id)
    }
}