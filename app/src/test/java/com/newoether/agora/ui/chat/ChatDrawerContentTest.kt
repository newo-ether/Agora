package com.newoether.agora.ui.chat

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.ui.chat.search.DrawerSearchBar
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.model.ChatConversation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatDrawerContentTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun interactionIndicatorUsesTheExistingSlotAndReturnsToUnreadWhenCleared() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        val question = resources.getString(com.newoether.agora.ui.chat.interaction.InteractionKind.Question.titleRes)
        val approval = resources.getString(com.newoether.agora.ui.chat.interaction.InteractionKind.Approval.titleRes)
        var status by mutableStateOf(DrawerConversationIndicator.QUESTION)
        compose.setContent { MaterialTheme { DrawerStatus(status, MaterialTheme.colorScheme.primary) } }
        compose.onNodeWithContentDescription(question).assertIsDisplayed().assertWidthIsEqualTo(18.dp)
        compose.runOnIdle { status = DrawerConversationIndicator.APPROVAL }
        compose.onNodeWithContentDescription(approval).assertIsDisplayed().assertWidthIsEqualTo(18.dp)
        compose.onNodeWithContentDescription(question).assertDoesNotExist()
        compose.runOnIdle { status = DrawerConversationIndicator.UNREAD }
        compose.onNodeWithContentDescription(approval).assertDoesNotExist()
        compose.onNodeWithContentDescription(resources.getString(R.string.conversation_unread_generation))
            .assertIsDisplayed().assertWidthIsEqualTo(8.dp)
    }

    @Test
    fun headerSearchFillsTheRowAndClearDoesNotCollapseButReturnResetsQuery() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        val hint = resources.getString(R.string.search_hint)
        var query by mutableStateOf("")
        var reduced by mutableStateOf(false)
        var width by mutableStateOf(328.dp)
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme {
                    Box(Modifier.width(width)) { DrawerSearchBar(query, { query = it }) }
                }
            }
        }
        compose.onNodeWithText("Agora").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription(hint).assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
            .performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("alpha")
        compose.onNodeWithText("Agora").assertDoesNotExist()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("DrawerHeaderSearch").assertHeightIsEqualTo(56.dp)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(root.left, capsule.left, 1f)
        assertEquals(root.right, capsule.right, 1f)
        compose.onNodeWithContentDescription(resources.getString(R.string.back))
            .assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
        compose.onNodeWithContentDescription(resources.getString(R.string.clear_search)).performClick()
        compose.runOnIdle { assertEquals("", query) }
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("beta")
        compose.onNodeWithContentDescription(resources.getString(R.string.back)).performClick()
        compose.runOnIdle { assertEquals("", query) }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Agora").assertIsDisplayed()
        compose.runOnIdle { reduced = true; width = 248.dp }
        compose.onNodeWithContentDescription(hint).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("narrow")
        compose.onNodeWithText("Agora").assertDoesNotExist()
        val narrow = compose.onNodeWithTag("DrawerHeaderSearch").fetchSemanticsNode().boundsInRoot
        assertEquals(compose.onRoot().fetchSemanticsNode().boundsInRoot.width, narrow.width, 1f)
        compose.onNodeWithContentDescription(resources.getString(R.string.back)).performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { assertEquals("", query) }
    }

    @Test
    fun headerSearchWidthReversesFromCurrentValueAndReducedMotionSnaps() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        var reduced by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme { Box(Modifier.width(328.dp)) { DrawerSearchBar("", {}) } }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val icon = compose.onNodeWithContentDescription(resources.getString(R.string.search_hint))
        val before = icon.fetchSemanticsNode().boundsInRoot
        val rowWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        icon.performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(160)
        val back = compose.onNodeWithContentDescription(resources.getString(R.string.back))
        val middle = back.fetchSemanticsNode().boundsInRoot
        assertTrue("The control expands toward the title", middle.left < before.left)
        back.performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val reversed = icon.fetchSemanticsNode().boundsInRoot
        assertTrue("Reversal must not snap to the collapsed endpoint", reversed.left < before.left)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(before.left, icon.fetchSemanticsNode().boundsInRoot.left, 1f)
        compose.runOnIdle { reduced = true }
        compose.waitForIdle()
        icon.performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val snapped = back.fetchSemanticsNode().boundsInRoot
        assertEquals(before.right, snapped.right + rowWidth - before.width, 2f)
        val full = compose.onNodeWithTag("DrawerHeaderSearch").fetchSemanticsNode().boundsInRoot
        assertEquals(rowWidth, full.width, 1f)
        assertEquals(before.center.y, snapped.center.y, 1f)
        compose.mainClock.autoAdvance = true
    }
    @Test
    fun headerSearchFadesTitleAndResetsOnlyWhenDrawerIsFullyClosed() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        var query by mutableStateOf("")
        var visible by mutableStateOf(true)
        var searching by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(328.dp)) {
                    DrawerSearchBar(query, { query = it }, searching, drawerVisible = visible)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(resources.getString(R.string.search_hint)).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Agora").assertExists()
        compose.mainClock.advanceTimeBy(450)
        compose.onNodeWithText("Agora").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.onNode(hasSetTextAction()).performTextInput("retained")
        val capsule = compose.onNodeWithTag("DrawerHeaderSearch").fetchSemanticsNode().boundsInRoot
        val trailing = compose.onNodeWithTag("DrawerSearchTrailing").fetchSemanticsNode().boundsInRoot
        assertEquals(trailing.top - capsule.top, capsule.bottom - trailing.bottom, 1f)
        assertEquals(trailing.top - capsule.top, capsule.right - trailing.right, 1f)
        compose.runOnIdle { searching = true }
        val loading = compose.onNodeWithTag("DrawerSearchTrailing").fetchSemanticsNode().boundsInRoot
        assertEquals(trailing, loading)
        compose.runOnIdle { assertEquals("retained", query) }
        compose.runOnIdle { visible = false }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { assertEquals("", query) }
        compose.onNodeWithText("Agora").assertIsDisplayed()
        compose.runOnIdle { visible = true }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription(resources.getString(R.string.search_hint)).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused().assertTextEquals("")
    }

    @Test
    fun backCollapsesExpandedSearchBeforeClosingDrawerEvenWithEmptyOrUnfocusedQuery() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        var query by mutableStateOf("")
        var visible by mutableStateOf(true)
        var closes = 0
        lateinit var focusManager: androidx.compose.ui.focus.FocusManager
        compose.setContent {
            focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            BackHandler(enabled = visible) { closes++; visible = false }
            MaterialTheme {
                Box(Modifier.width(328.dp)) {
                    DrawerSearchBar(query, { query = it }, drawerVisible = visible)
                }
            }
        }
        fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        val toggle = compose.onNodeWithContentDescription(resources.getString(R.string.search_hint))
        toggle.performClick()
        back()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { assertTrue(visible); assertEquals(0, closes); assertEquals("", query) }
        toggle.performClick()
        compose.onNode(hasSetTextAction()).performTextInput("retained")
        compose.runOnIdle { focusManager.clearFocus() }
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        back()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { assertTrue(visible); assertEquals(0, closes); assertEquals("", query) }
        back()
        compose.runOnIdle { assertFalse(visible); assertEquals(1, closes) }
    }

    @Test
    fun searchExpansionStartsFastAndDeceleratesWithinTheExisting400Ms() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        compose.setContent {
            MaterialTheme { Box(Modifier.width(328.dp)) { DrawerSearchBar("", {}) } }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(resources.getString(R.string.search_hint)).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val widths = mutableListOf<Float>()
        compose.mainClock.advanceTimeByFrame()
        repeat(4) {
            compose.mainClock.advanceTimeBy(96)
            widths += compose.onNodeWithTag("DrawerHeaderSearch").fetchSemanticsNode().boundsInRoot.width
        }
        val full = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("First quarter covers over half the expansion: $widths", widths[0] > full * .6f)
        assertTrue("Early motion exceeds later motion: $widths", widths[1] - widths[0] > widths[3] - widths[2])
        compose.mainClock.advanceTimeBy(64)
        assertEquals(full, compose.onNodeWithTag("DrawerHeaderSearch").fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun tasksAndRemoteUse46DpAndKeepJoinedGeometry() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
            it.parentFile
        }.first { File(it, "app/src/main/java").isDirectory }
        val source = File(root,
            "app/src/main/java/com/newoether/agora/ui/chat/ChatDrawerContent.kt").readText()
        val group = source.substringAfter("Column(modifier = Modifier.fillMaxSize()) {")
            .substringBefore("val newChatDisabled")
        assertEquals(2, Regex("""\.height\(46\.dp\)""").findAll(group).count())
        assertFalse(group.contains("height(52.dp)"))
        assertTrue(group.contains("Spacer(modifier = Modifier.height(2.dp))"))
        assertTrue(group.contains("topStart = 24.dp, topEnd = 24.dp"))
        assertTrue(group.contains("bottomStart = 5.dp, bottomEnd = 5.dp"))
        assertTrue(group.contains("topStart = 5.dp, topEnd = 5.dp"))
        assertTrue(group.contains("bottomStart = 24.dp, bottomEnd = 24.dp"))
        assertEquals(2, Regex("""focusManager\.clearFocus\(\)""").findAll(group).count())
        assertEquals(2, Regex("""scope\.launch \{ onRequestClose\(\) \}""").findAll(group).count())
        assertTrue(group.indexOf("onOpenTasks()") < group.indexOf("onOpenRemote()"))
    }
    @Test
    fun conversationSelectionColorsCrossfadeWithoutChangingRowGeometry() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
            it.parentFile
        }.first { File(it, "app/src/main/java").isDirectory }
        val source = File(root,
            "app/src/main/java/com/newoether/agora/ui/chat/ChatDrawerContent.kt").readText()

        listOf(
            "drawerConversationSelectionContainer",
            "drawerConversationSelectionContent",
            "drawerConversationSelectionIndicator",
        ).forEach { label ->
            assertTrue(source.contains("label = \"$label\""))
        }
        assertTrue(source.contains("animationSpec = tween(durationMillis = 250)"))
        assertTrue(source.contains("color = selectionContainerColor"))
        assertTrue(source.contains("color = selectionContentColor"))
        assertTrue(source.contains("DrawerStatus(indicator, selectionIndicatorColor)"))
        assertTrue(source.contains(".height(44.dp)"))
    }

    @Test
    fun generationIndicatorHasPriorityOverUnread() {
        assertEquals(
            DrawerConversationIndicator.GENERATING,
            resolveDrawerConversationIndicator(
                isGenerating = true,
                isSelected = false,
                hasUnreadGeneration = true,
            ),
        )
    }

    @Test
    fun unreadIndicatorIsHiddenForOpenConversation() {
        assertEquals(
            DrawerConversationIndicator.NONE,
            resolveDrawerConversationIndicator(
                isGenerating = false,
                isSelected = true,
                hasUnreadGeneration = true,
            ),
        )
    }

    @Test
    fun backgroundConversationShowsUnreadIndicator() {
        assertEquals(
            DrawerConversationIndicator.UNREAD,
            resolveDrawerConversationIndicator(
                isGenerating = false,
                isSelected = false,
                hasUnreadGeneration = true,
            ),
        )
    }

    @Test
    fun pinnedSectionPrecedesOrdinaryConversationsWithoutDuplicates() {
        val keys = drawerConversationKeys(
            listOf(
                ChatConversation(id = "a", title = "A"),
                ChatConversation(id = "b", title = "B", isPinned = true),
                ChatConversation(id = "c", title = "C"),
                ChatConversation(id = "d", title = "D", isPinned = true),
            ),
        )
        assertEquals(
            listOf(
                "section:pinned",
                "conversation:b",
                "conversation:d",
                "section:conversations",
                "conversation:a",
                "conversation:c",
            ),
            keys,
        )
    }

    @Test
    fun emptyPinnedSectionAndLoneHeadingsAreHidden() {
        assertEquals(
            listOf("conversation:a", "conversation:b"),
            drawerConversationKeys(
                listOf(ChatConversation(id = "a", title = "A"), ChatConversation(id = "b", title = "B")),
            ),
        )
        assertEquals(
            listOf("section:pinned", "conversation:a"),
            drawerConversationKeys(listOf(ChatConversation(id = "a", title = "A", isPinned = true))),
        )
        assertEquals(emptyList<String>(), drawerConversationKeys(emptyList()))
    }

    @Test
    fun drawerMenuTogglesPinWithLocalizedLabels() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
            it.parentFile
        }.first { File(it, "app/src/main/java").isDirectory }
        val source = File(root,
            "app/src/main/java/com/newoether/agora/ui/chat/ChatDrawerContent.kt").readText()
        assertTrue(source.contains("R.string.unpin_conversation"))
        assertTrue(source.contains("R.string.pin_conversation"))
        assertTrue(source.contains("R.string.pinned_conversations"))
        assertTrue(source.contains("conversation.id, !conversation.isPinned,"))
        assertTrue(source.contains("Icons.Default.PushPin"))
    }

    @Test
    fun edgeFadeToleranceIsTwoDp() {
        assertEquals(2.dp, DrawerEdgeFadeTolerance)
    }

    @Test
    fun topEdgeAllowsOffsetsThroughTolerance() {
        assertTrue(isDrawerListAtTop(0, 0, 2))
        assertTrue(isDrawerListAtTop(0, 2, 2))
        assertFalse(isDrawerListAtTop(0, 3, 2))
        assertFalse(isDrawerListAtTop(1, 0, 2))
    }

    @Test
    fun bottomEdgeTreatsEmptyListAsReached() {
        assertTrue(
            isDrawerListAtBottom(
                totalItemsCount = 0,
                lastVisibleItemIndex = null,
                lastVisibleItemEndOffsetPx = null,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
    }

    @Test
    fun bottomEdgeAllowsFinalItemThroughTolerance() {
        assertTrue(
            isDrawerListAtBottom(
                totalItemsCount = 5,
                lastVisibleItemIndex = 4,
                lastVisibleItemEndOffsetPx = 100,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
        assertTrue(
            isDrawerListAtBottom(
                totalItemsCount = 5,
                lastVisibleItemIndex = 4,
                lastVisibleItemEndOffsetPx = 102,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
    }

    @Test
    fun bottomEdgeRejectsContentBeyondToleranceOrBeforeFinalItem() {
        assertFalse(
            isDrawerListAtBottom(
                totalItemsCount = 5,
                lastVisibleItemIndex = 4,
                lastVisibleItemEndOffsetPx = 103,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
        assertFalse(
            isDrawerListAtBottom(
                totalItemsCount = 5,
                lastVisibleItemIndex = 3,
                lastVisibleItemEndOffsetPx = 100,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
        assertFalse(
            isDrawerListAtBottom(
                totalItemsCount = 5,
                lastVisibleItemIndex = 4,
                lastVisibleItemEndOffsetPx = null,
                viewportEndOffsetPx = 100,
                tolerancePx = 2,
            ),
        )
    }
}
