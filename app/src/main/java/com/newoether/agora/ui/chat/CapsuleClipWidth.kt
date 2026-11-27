package com.newoether.agora.ui.chat

/**
 * Left edge, in local pixels, of the visible slice of an identity capsule that reveals only
 * [visibleWidthPx] of its [sizeWidth] width.
 *
 * Capsules keep their final width and collapse by clipping. The clip is drawn in absolute local
 * coordinates, so in an RTL layout the visible slice has to stay pinned to the right edge: pinning
 * it to the left cuts away the leading affordance (the menu button sits at the capsule's start,
 * which is the right edge in RTL) and keeps the empty margin instead.
 */
internal fun capsuleClipLeft(sizeWidth: Float, visibleWidthPx: Float, isRtl: Boolean): Float {
    val width = visibleWidthPx.coerceIn(0f, sizeWidth)
    return if (isRtl) sizeWidth - width else 0f
}
