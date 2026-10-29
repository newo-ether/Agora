package com.newoether.agora.data.repository

import com.newoether.agora.data.local.SemanticMetadataStateEntity
import kotlinx.coroutines.flow.first

/** Reads only the durable bootstrap row; it never backfills on the caller's coroutine. */
internal suspend fun ConversationRepository.ensureSemanticMetadataState(): SemanticMetadataStateEntity =
    database?.semanticMetadataDao()?.ensureMetadataState()
        ?: SemanticMetadataStateEntity(complete = true)

/** Page-owned, one-shot readiness wait. Cancelling it leaves the durable worker untouched. */
internal suspend fun ConversationRepository.awaitSemanticMetadataReady() {
    val metadata = database?.semanticMetadataDao() ?: return
    metadata.observeMetadataComplete().first { it == true }
}
