package com.newoether.agora.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Where an attachment's owned bytes live, and whether Agora is still allowed to reclaim them.
 *
 * Local Sandbox files stop belonging to the composer immediately before their first Send
 * submission. Keeping that transfer explicit prevents message, queue, draft, and fork cleanup from
 * deleting a runtime workspace that an agent may have already changed.
 */
@Serializable
enum class AttachmentStorage {
    @SerialName("app_private")
    APP_PRIVATE,

    @SerialName("local_sandbox_pending")
    LOCAL_SANDBOX_PENDING,

    @SerialName("local_sandbox_runtime")
    LOCAL_SANDBOX_RUNTIME;

    val isLocalSandbox: Boolean
        get() = this != APP_PRIVATE

    val canPreview: Boolean
        get() = !isLocalSandbox

    val reclaimWhenAbandoned: Boolean
        get() = this != LOCAL_SANDBOX_RUNTIME

    fun transferForSend(): AttachmentStorage = when (this) {
        LOCAL_SANDBOX_PENDING -> LOCAL_SANDBOX_RUNTIME
        else -> this
    }
}

@Serializable
enum class AttachmentImportState {
    @SerialName("processing")
    PROCESSING,

    @SerialName("ready")
    READY,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class AttachmentMeta(val items: List<AttachmentItem> = emptyList())

@Serializable
data class AttachmentItem(
    val originalUri: String? = null,
    val type: String,               // "image", "video", "file", "pdf"
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("image_index") val imageIndex: Int? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    /** Pixel size of the artifact actually sent, measured at import. Providers price by pixels. */
    @SerialName("pixel_width") val pixelWidth: Int? = null,
    @SerialName("pixel_height") val pixelHeight: Int? = null,
    val warning: String? = null,
    @SerialName("text_content") val textContent: String? = null,
    @SerialName("transcription") val transcription: String? = null,
    val storage: AttachmentStorage = AttachmentStorage.APP_PRIVATE,
    @SerialName("sandbox_path") val sandboxPath: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    val unavailable: Boolean = false,
    /** One measured artifact per image, page or frame, in the same order as its image range. */
    val imageArtifacts: List<ImageArtifact> = emptyList(),
)

/** Used for passing attachment metadata from ChatBottomBar to ViewModel. */
@Serializable
data class SelectedAttachment(
    /** Stable identity for list keys. The same file can be picked twice (identical [uri]), and a
     *  pick is mutated in place as it processes — an index or uri key would recycle the wrong row
     *  and cross-wire its thumbnail/progress. Generated per pick; persisted with drafts so a
     *  restored draft keeps its keys. */
    val localId: String = java.util.UUID.randomUUID().toString(),
    val uri: String,
    val type: String,               // "image", "video", "file", "pdf"
    val frameCount: Int? = null,
    val sliceIntervalMs: Long? = null,
    val pageCount: Int? = null,
    val videoDurationMs: Long? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    /** Pixel size of the artifact that will be sent, measured once the import produced it. */
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val processedFrames: List<String>? = null,
    val selectedPages: Set<Int>? = null,
    val preRenderedPaths: List<String>? = null,
    val localPath: String? = null,  // copied into storage owned by [storage] at pick time
    val storage: AttachmentStorage = AttachmentStorage.APP_PRIVATE,
    val sandboxPath: String? = null,
    val importState: AttachmentImportState = AttachmentImportState.READY,
    val preparedText: String? = null,
    val unavailable: Boolean = false,
    /** One measured artifact per image, page or frame, in the same order as its image range. */
    val imageArtifacts: List<ImageArtifact> = emptyList(),
)
