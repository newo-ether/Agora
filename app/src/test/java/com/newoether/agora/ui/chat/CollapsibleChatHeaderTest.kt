package com.newoether.agora.ui.chat

import org.junit.Assert.*
import org.junit.Test

class CollapsibleChatHeaderTest {
    @Test fun deliberateDownwardReadingHidesAndASmallReverseDragReveals() {
        val header = CollapsibleChatHeader(48f, 12f)
        header.drag(-30f, true)
        assertFalse(header.collapsed)
        header.drag(-18f, true)
        assertTrue(header.collapsed)
        header.drag(-200f, true)
        header.drag(12f, true)
        assertFalse(header.collapsed)
    }
    @Test fun directionChangesResetTravelAndSmallJitterDoesNotHideTheToolbar() {
        val header = CollapsibleChatHeader(48f, 12f)
        repeat(20) { header.drag(-20f, true); header.drag(1f, true) }
        assertFalse(header.collapsed)
        header.drag(-48f, true)
        assertTrue(header.collapsed)
        header.show()
        assertFalse(header.collapsed)
    }
    @Test fun searchAndOtherPinnedStatesRevealAndDoNotAccumulateHiddenTravel() {
        val header = CollapsibleChatHeader(48f, 12f)
        header.drag(-48f, true)
        header.drag(-500f, false)
        assertFalse(header.collapsed)
        header.drag(-47f, true)
        assertFalse(header.collapsed)
        header.drag(Float.NaN, true)
        header.drag(-1f, true)
        assertTrue(header.collapsed)
    }
}
