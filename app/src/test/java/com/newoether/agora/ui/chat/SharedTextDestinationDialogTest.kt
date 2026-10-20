package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.theme.ThemeMode
import com.newoether.agora.ui.motion.ProvideAgoraMotionPolicy
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp")
class SharedTextDestinationDialogTest {
    @get:Rule val compose = createComposeRule()
    private val attachment = SelectedAttachment(
        uri = "file:///excerpt.txt", type = "file", sourceTitle = "Chapter Two",
        sourceBookTitle = "Example Book", sourceAuthors = "Example Author",
        sourceThumbnailPath = "/cover.jpg",
    )

    @Test fun sourceIdentityAndCoverAppearBeforeAnyDraftIsChosen() {
        val chosen = mutableListOf<String>()
        show(onChoose = { chosen += it })
        compose.onNodeWithText("Chapter Two").assertIsDisplayed()
        compose.onNodeWithText("Example Book").assertIsDisplayed()
        compose.onNodeWithText("Example Author").assertIsDisplayed()
        compose.onNodeWithTag("shared-excerpt-cover").assertIsDisplayed()
        compose.runOnIdle { assertTrue(chosen.isEmpty()) }
        compose.onNodeWithText("Existing chat").performClick()
        compose.runOnIdle { assertEquals(listOf("existing"), chosen) }
    }

    @Test fun longFilenameUsesMiddleEllipsisSoItsEndingAndExtensionStayVisible() {
        val name = "An extremely long book filename ".repeat(12) + "last-part.epub"
        compose.setContent {
            AgoraTheme(themeMode = ThemeMode.LIGHT, eInkMode = true) {
                ProvideAgoraMotionPolicy(false, eInkMode = true) {
                    SharedTextDestinationDialog(attachment.copy(sourceTitle = null,fileName = name), emptyList(), false, {}, {})
                }
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(name).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val result = layouts.single()
        assertEquals(1,result.lineCount)
        assertTrue(result.isLineEllipsized(0))
        assertEquals(name,result.layoutInput.text.text)
        assertEquals(androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,result.layoutInput.overflow)
    }

    @Test fun processingLimitIsVisibleBeforeChoosingADraft() {
        compose.setContent {
            AgoraTheme(themeMode = ThemeMode.LIGHT, eInkMode = true) {
                ProvideAgoraMotionPolicy(false, eInkMode = true) {
                    SharedTextDestinationDialog(attachment.copy(warning = "Full file attached. Chat context includes only the first 500000 characters."),
                        emptyList(), false, {}, {})
                }
            }
        }
        compose.onNodeWithText("Full file attached. Chat context includes only the first 500000 characters.").assertIsDisplayed()
    }

    @Test fun newChatAndCancelHaveSeparateExplicitActions() {
        val chosen = mutableListOf<String>()
        var cancelled = 0
        show(onChoose = { chosen += it }, onDismiss = { cancelled++ })
        compose.onNodeWithText("New Chat").performClick()
        compose.runOnIdle { assertEquals(listOf(NEW_CHAT_WORKSPACE_ID), chosen); assertEquals(0, cancelled) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, chosen.size); assertEquals(1, cancelled) }
    }

    @Test fun choosingDisablesDestinationAndCancelActions() {
        var actions = 0
        show(choosing = true, onChoose = { actions++ }, onDismiss = { actions++ })
        compose.onNodeWithText("New Chat").assertIsNotEnabled()
        compose.onNodeWithText("Existing chat").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun einkListOffersPageNavigationWithoutScrollingTheSummary() {
        val chats = List(30) { ChatConversation(id = "$it", title = "Conversation $it") }
        compose.setContent {
            AgoraTheme(themeMode = ThemeMode.LIGHT, eInkMode = true) {
                ProvideAgoraMotionPolicy(false, eInkMode = true) {
                    SharedTextDestinationDialog(attachment, chats, false, {}, {})
                }
            }
        }
        compose.onNodeWithText("Conversation 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next page", substring = true, ignoreCase = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Chapter Two").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    private fun show(
        choosing: Boolean = false,
        onChoose: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            AgoraTheme(themeMode = ThemeMode.LIGHT, eInkMode = true) {
                ProvideAgoraMotionPolicy(false, eInkMode = true) {
                    SharedTextDestinationDialog(attachment,
                        listOf(ChatConversation(id = "existing", title = "Existing chat")),
                        choosing, onChoose, onDismiss)
                }
            }
        }
    }
}
