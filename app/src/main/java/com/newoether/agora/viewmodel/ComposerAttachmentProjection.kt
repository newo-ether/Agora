package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment

internal fun durableAttachmentProjection(
    durable: ConversationComposerSnapshot,
    visible: ConversationComposerSnapshot,
    attachmentId: String,
    replacement: SelectedAttachment,
): List<SelectedAttachment> {
    val remaining = durable.attachments.associateByTo(linkedMapOf()) { it.localId }
    return buildList {
        visible.attachments.forEach { attachment ->
            when {
                attachment.localId == attachmentId -> {
                    remaining.remove(attachmentId)
                    add(replacement)
                }
                else -> remaining.remove(attachment.localId)?.let(::add)
            }
        }
        addAll(remaining.values)
    }
}

internal fun LoadedComposerDraft.toSnapshot() = ConversationComposerSnapshot(
    text = text,
    attachments = attachments,
    revision = revision,
    loaded = true,
)

internal fun ConversationComposerSnapshot.replaceAttachment(
    replacement: SelectedAttachment,
) = copy(
    attachments = attachments.map { current ->
        if (current.localId == replacement.localId) replacement else current
    },
)

internal fun SelectedAttachment.asProcessing() = copy(
    processedFrames = null,
    preRenderedPaths = null,
    notesThumbnailPath = null,
    preparedText = null,
    notesMarkdownText = null,
    importState = AttachmentImportState.PROCESSING,
    unavailable = false,
)

internal fun SelectedAttachment.isConfiguredForProcessing(): Boolean = when (type) {
    "pdf" -> selectedPages != null
    "video" -> frameCount != null && sliceIntervalMs != null
    else -> true
}
internal fun SelectedAttachment.shouldPreparePdfPreview(): Boolean =
    type == "pdf" &&
        importState == AttachmentImportState.PROCESSING &&
        selectedPages == null &&
        preRenderedPaths.isNullOrEmpty()

internal fun SelectedAttachment.shouldStartProcessingJob(): Boolean =
    importState == AttachmentImportState.PROCESSING &&
        (isConfiguredForProcessing() || shouldPreparePdfPreview())

internal fun SelectedAttachment.withProcessingConfiguration(
    current: SelectedAttachment,
): SelectedAttachment {
    if (importState != AttachmentImportState.PROCESSING) return this
    return when (type) {
        "pdf" -> copy(selectedPages = current.selectedPages ?: selectedPages)
        "video" -> copy(
            frameCount = current.frameCount ?: frameCount,
            sliceIntervalMs = current.sliceIntervalMs ?: sliceIntervalMs,
        )
        else -> this
    }
}
