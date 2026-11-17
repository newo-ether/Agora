package com.newoether.agora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelPresentationStateTest {
    @Test
    fun presentingChatWhileAlreadyVisibleDoesNotAddAnOverlay() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(onOwnerChanged = owners::add)

        repeat(2) { state.present(TopLevelPresentation.CHAT) }

        assertEquals(TopLevelPresentation.CHAT, state.owner)
        assertEquals(listOf(TopLevelPresentation.CHAT), owners)
        state.present(TopLevelPresentation.SETTINGS)
        assertTrue(state.release(TopLevelPresentation.SETTINGS))
        assertEquals(TopLevelPresentation.CHAT, state.owner)
        assertEquals(
            listOf(TopLevelPresentation.CHAT, TopLevelPresentation.SETTINGS, TopLevelPresentation.CHAT),
            owners,
        )
    }

    @Test
    fun chatRequestRetainsEachCoveringSurfaceUntilItsExitCompletes() {
        TopLevelPresentation.entries.filter { it != TopLevelPresentation.CHAT }.forEach { overlay ->
            val owners = mutableListOf<TopLevelPresentation>()
            val state = TopLevelPresentationState(overlay, owners::add)

            state.present(TopLevelPresentation.CHAT)

            assertEquals(overlay, state.owner)
            assertEquals(listOf(overlay), owners)
            assertTrue(state.release(overlay))
            assertEquals(TopLevelPresentation.CHAT, state.owner)
            assertEquals(listOf(overlay, TopLevelPresentation.CHAT), owners)
            assertFalse(state.release(overlay))
        }
    }

    @Test
    fun chatRequestWaitsForNestedSurfacesToExitInEitherOrder() {
        listOf(TopLevelPresentation.REMOTE, TopLevelPresentation.SETTINGS, TopLevelPresentation.TASKS).forEach { base ->
            listOf(TopLevelPresentation.MEDIA_PREVIEW, TopLevelPresentation.TEXT_PREVIEW).forEach { preview ->
                listOf(listOf(preview, base), listOf(base, preview)).forEach { exitOrder ->
                    val owners = mutableListOf<TopLevelPresentation>()
                    val state = TopLevelPresentationState(base, owners::add)
                    state.present(preview)

                    state.present(TopLevelPresentation.CHAT)
                    assertEquals(preview, state.owner)
                    assertFalse(owners.contains(TopLevelPresentation.CHAT))

                    val firstExit = exitOrder.first()
                    assertEquals(firstExit == preview, state.release(firstExit))
                    assertEquals(exitOrder.last(), state.owner)
                    assertFalse(owners.contains(TopLevelPresentation.CHAT))
                    assertTrue(state.release(exitOrder.last()))
                    assertEquals(TopLevelPresentation.CHAT, state.owner)
                    assertEquals(1, owners.count { it == TopLevelPresentation.CHAT })
                    exitOrder.forEach { assertFalse(state.release(it)) }
                }
            }
        }
    }

    @Test
    fun chatRequestDoesNotLetOldExitsReleaseANewerSurface() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(TopLevelPresentation.REMOTE, owners::add)
        state.present(TopLevelPresentation.MEDIA_PREVIEW)
        state.present(TopLevelPresentation.CHAT)
        state.present(TopLevelPresentation.SETTINGS)

        assertFalse(state.release(TopLevelPresentation.MEDIA_PREVIEW))
        assertFalse(state.release(TopLevelPresentation.REMOTE))
        assertEquals(TopLevelPresentation.SETTINGS, state.owner)
        assertFalse(owners.contains(TopLevelPresentation.CHAT))
        assertTrue(state.release(TopLevelPresentation.SETTINGS))
        assertEquals(TopLevelPresentation.CHAT, state.owner)
        assertEquals(1, owners.count { it == TopLevelPresentation.CHAT })
    }

    @Test
    fun nestedPreviewReturnsToTheStillVisibleOverlay() {
        listOf(TopLevelPresentation.REMOTE, TopLevelPresentation.SETTINGS, TopLevelPresentation.TASKS).forEach { base ->
            val owners = mutableListOf<TopLevelPresentation>()
            val state = TopLevelPresentationState(onOwnerChanged = owners::add)
            state.present(base)
            state.present(TopLevelPresentation.MEDIA_PREVIEW)
            state.present(TopLevelPresentation.TEXT_PREVIEW)
            assertTrue(state.release(TopLevelPresentation.TEXT_PREVIEW))
            assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)
            assertTrue(state.release(TopLevelPresentation.MEDIA_PREVIEW))
            assertEquals(base, state.owner)
            assertFalse(owners.drop(1).contains(TopLevelPresentation.CHAT))
            assertTrue(state.release(base))
            assertEquals(TopLevelPresentation.CHAT, state.owner)
        }
    }

    @Test
    fun exitedUnderlyingOverlayDoesNotReappearAfterPreview() {
        val state = TopLevelPresentationState(TopLevelPresentation.REMOTE)
        state.present(TopLevelPresentation.MEDIA_PREVIEW)
        assertFalse(state.release(TopLevelPresentation.REMOTE))
        assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)
        assertTrue(state.release(TopLevelPresentation.MEDIA_PREVIEW))
        assertEquals(TopLevelPresentation.CHAT, state.owner)
    }

    @Test
    fun repeatedPresentationDoesNotLeaveAnInvisibleBlocker() {
        val state = TopLevelPresentationState()
        repeat(3) { state.present(TopLevelPresentation.REMOTE) }
        assertTrue(state.release(TopLevelPresentation.REMOTE))
        assertEquals(TopLevelPresentation.CHAT, state.owner)
        assertFalse(state.release(TopLevelPresentation.REMOTE))
    }

    @Test
    fun ownerIsRetainedUntilMatchingExitCompletes() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(onOwnerChanged = owners::add)
        state.present(TopLevelPresentation.SETTINGS)

        assertEquals(TopLevelPresentation.SETTINGS, state.owner)
        assertTrue(state.release(TopLevelPresentation.SETTINGS))
        assertEquals(TopLevelPresentation.CHAT, state.owner)
        assertEquals(
            listOf(TopLevelPresentation.CHAT, TopLevelPresentation.SETTINGS, TopLevelPresentation.CHAT),
            owners,
        )
    }

    @Test
    fun restoredBlockingOwnerStartsFailClosed() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(TopLevelPresentation.TASKS, owners::add)

        assertEquals(TopLevelPresentation.TASKS, state.owner)
        assertEquals(listOf(TopLevelPresentation.TASKS), owners)
    }

    @Test
    fun staleExitCannotReleaseNewerPresentation() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(onOwnerChanged = owners::add)
        state.present(TopLevelPresentation.SETTINGS)
        state.present(TopLevelPresentation.MEDIA_PREVIEW)

        assertFalse(state.release(TopLevelPresentation.SETTINGS))
        assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)
        assertEquals(
            listOf(TopLevelPresentation.CHAT, TopLevelPresentation.SETTINGS, TopLevelPresentation.MEDIA_PREVIEW),
            owners,
        )
    }
}
