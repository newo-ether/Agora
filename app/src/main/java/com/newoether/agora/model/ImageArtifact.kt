package com.newoether.agora.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/** Measured facts about the one encoded file shared by preview, upload and token accounting. */
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
@Serializable
data class ImageArtifact(
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    /** Zero identifies legacy or display-only bytes that have not passed image preparation. */
    val policyVersion: Int = 0,
    /** A missing image keeps its occurrence and any known dimensions without an invented file. */
    val unavailable: Boolean = false,
)

/** Completeness is independent of policy version so legacy measured bytes remain reusable. */
internal fun ImageArtifact.hasMeasuredFactsFor(imagePath: String): Boolean =
    path == imagePath && path.isNotBlank() && !unavailable &&
        mimeType.startsWith("image/") && sizeBytes > 0 &&
        (width ?: 0) > 0 && (height ?: 0) > 0
