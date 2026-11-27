package com.newoether.agora.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleClipWidthTest {
    @Test
    fun ltrRevealsTheCapsuleFromItsLeftEdge() {
        assertEquals(0f, capsuleClipLeft(sizeWidth = 300f, visibleWidthPx = 120f, isRtl = false))
    }

    @Test
    fun rtlRevealsTheCapsuleFromItsRightEdge() {
        assertEquals(180f, capsuleClipLeft(sizeWidth = 300f, visibleWidthPx = 120f, isRtl = true))
    }

    @Test
    fun visibleWidthIsClampedToTheCapsuleWidth() {
        assertEquals(0f, capsuleClipLeft(sizeWidth = 300f, visibleWidthPx = 900f, isRtl = false))
        assertEquals(0f, capsuleClipLeft(sizeWidth = 300f, visibleWidthPx = 900f, isRtl = true))
        assertEquals(300f, capsuleClipLeft(sizeWidth = 300f, visibleWidthPx = -20f, isRtl = true))
    }
}
