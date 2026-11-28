package com.newoether.agora.data

import kotlinx.serialization.Serializable
import java.util.UUID

enum class EmbeddingModelType { REMOTE, LOCAL }

@Serializable
data class EmbeddingModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: EmbeddingModelType,
    val remoteModelName: String = "",
    val remoteBaseUrl: String = "",
    val remoteApiKey: String = "",
    val localFilePath: String = "",
    val batchSize: Int = 8
)
