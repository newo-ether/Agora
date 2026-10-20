package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.AttachmentItem

/** Source covers are display-only; legacy notebook records can use their first saved page. */
internal fun attachmentSourcePreviewPath(item: AttachmentItem?, images: List<String>): String? {
    item?.sourceThumbnailPath?.takeIf { it.isNotBlank() }?.let { return it }
    if (item?.mimeType != "application/vnd.kalamos.notes-document+zip") return null
    return item.notesThumbnailPath?.takeIf { it.isNotBlank() }
        ?: item.imageIndex?.let(images::getOrNull)?.takeIf { it.isNotBlank() }
}
