package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
class AutomaticUserMessageBubbleTest {
    @get:Rule val compose = createComposeRule()

    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources
    private fun label(id: Int): String = resources.getString(id)
    private fun message(source: MessageSource?, text: String = "Original message") =
        ChatMessage(id = "automatic-input", text = text, participant = Participant.USER, source = source)

    private fun completion(status: String = "completed", errorText: String? = null, errorCode: String? = null) =
        MessageSource(
            MessageSource.Kind.SUBAGENT,
            subagentResults = mapOf("8f13ce72-8888-4444-9999-82cd77c4e890" to "completion-id"),
            subagentResult = MessageSource.SubagentResult("Worker", status, errorText, errorCode),
        )

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
                MaterialTheme { Box(Modifier.fillMaxSize()) { block() } }
            }
        }
    }

    @Composable
    private fun bubble(
        message: ChatMessage,
        isEditing: Boolean = false,
        isEditingAllowed: Boolean = true,
        isLoading: Boolean = false,
        onStartEdit: () -> Unit = {},
        onSelectText: () -> Unit = {},
        onSwitchBranch: (Int) -> Unit = {},
        onShowInfo: () -> Unit = {},
        onShowDelete: () -> Unit = {},
    ) {
        UserMessageBubble(
            message = message,
            shape = UserBubbleShape(),
            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
            textColor = MaterialTheme.colorScheme.onPrimaryContainer,
            contextAlpha = Modifier,
            isEditing = isEditing,
            sizeAnimationReady = false,
            isLoading = isLoading,
            isEditingAllowed = isEditingAllowed,
            showActions = true,
            actionCopyText = message.text,
            showBranchSelector = true,
            branchIndex = 1,
            totalBranches = 3,
            onEdit = { _, _ -> },
            onCancelEdit = {},
            onStartEdit = onStartEdit,
            onSelectText = onSelectText,
            onSwitchBranch = onSwitchBranch,
            onMediaClick = { _, _ -> },
            onFileContentClick = null,
            onPdfPagesClick = null,
            onShowInfo = onShowInfo,
            onShowDelete = onShowDelete,
            searchHighlight = null,
        )
    }

    private fun openMenu() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
    }

    private fun textColor(text: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

    @Test
    fun everyAutomaticSourceKeepsGrayDisabledEditAndOtherActionsInOrder() {
        var source by mutableStateOf<MessageSource?>(MessageSource.TASK)
        var disabledEditColor = Color.Unspecified
        var edits = 0
        var info = 0
        content {
            disabledEditColor = MenuDefaults.itemColors().disabledTextColor
            bubble(message(source), onStartEdit = { edits++ }, onShowInfo = { info++ })
        }
        val sources = listOf(
            MessageSource.TASK,
            MessageSource.LOOP,
            MessageSource.askUser(listOf(MessageSource.AskUserItem("Question?", "Answer"))),
            MessageSource(MessageSource.Kind.SUBAGENT),
            completion(),
        )
        sources.forEach { automatic ->
            compose.runOnIdle { source = automatic }
            openMenu()
            compose.onNodeWithText(label(R.string.edit)).assertIsDisplayed().assertIsNotEnabled().performClick()
            assertEquals(disabledEditColor, textColor(label(R.string.edit)))
            val labels = listOf(R.string.copy, R.string.edit, R.string.select_text, R.string.info, R.string.delete)
            val tops = labels.map { compose.onNodeWithText(label(it)).fetchSemanticsNode().boundsInRoot.top }
            assertEquals(tops.sorted(), tops)
            for (id in listOf(R.string.copy, R.string.select_text, R.string.info, R.string.delete)) {
                compose.onNodeWithText(label(id)).assertIsEnabled()
            }
            compose.onNodeWithText(label(R.string.info)).performClick()
        }
        assertEquals(0, edits)
        assertEquals(sources.size, info)
    }

    @Test
    fun staleEditingStateNeverShowsAnEditorForAnyAutomaticSource() {
        var source by mutableStateOf<MessageSource?>(MessageSource.TASK)
        content { bubble(message(source), isEditing = true) }
        val sources = listOf(
            MessageSource.TASK, MessageSource.LOOP,
            MessageSource.askUser(listOf(MessageSource.AskUserItem("Question?", "Answer"))),
            MessageSource(MessageSource.Kind.SUBAGENT), completion(),
        )
        sources.forEach { automatic ->
            compose.runOnIdle { source = automatic }
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithText("2 / 3").assertIsDisplayed()
            openMenu()
            compose.onNodeWithText(label(R.string.edit)).assertIsNotEnabled()
            compose.onNodeWithText(label(R.string.info)).performClick()
        }
        compose.runOnIdle { source = null }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.cancel)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.send)).assertIsDisplayed()
    }

    @Test
    fun typedMessagesKeepEditAndGenerationAvailabilityRules() {
        var allowed by mutableStateOf(true)
        var edits = 0
        content { bubble(message(null), isEditingAllowed = allowed, onStartEdit = { edits++ }) }
        openMenu()
        compose.onNodeWithText(label(R.string.edit)).assertIsEnabled().performClick()
        assertEquals(1, edits)
        compose.runOnIdle { allowed = false }
        openMenu()
        compose.onNodeWithText(label(R.string.edit)).assertIsNotEnabled().performClick()
        assertEquals(1, edits)
    }

    @Test
    fun collapsedLongInputKeepsFullCopyTextAndExistingSelectionAction() {
        val raw = (1..20).joinToString("\n") { "Original input line $it" }
        var selected = 0
        lateinit var clipboard: ClipboardManager
        content {
            clipboard = LocalClipboardManager.current
            bubble(message(null, raw), onSelectText = { selected++ })
        }
        compose.onNodeWithText(label(R.string.expand)).assertIsDisplayed()
        openMenu()
        compose.onNodeWithText(label(R.string.copy)).performClick()
        compose.runOnIdle { assertEquals(raw, clipboard.getText()?.text) }
        openMenu()
        compose.onNodeWithText(label(R.string.select_text)).performClick()
        assertEquals(1, selected)
        compose.onNodeWithText(label(R.string.expand)).performClick()
        compose.onNodeWithText(label(R.string.collapse)).assertIsDisplayed()
        compose.onNodeWithText(raw, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun completionSummaryHidesOriginalBodyAndKeepsCopySelectionAndDeleteCallbacks() {
        val raw = "Worker (8f13ce72-8888-4444-9999-82cd77c4e890) [failed]\nPRIVATE RESULT BODY\n\nLast line."
        var selected = 0
        var deleted = 0
        var expectedText = Color.Unspecified
        lateinit var clipboard: ClipboardManager
        content {
            expectedText = MaterialTheme.colorScheme.onPrimaryContainer
            clipboard = LocalClipboardManager.current
            bubble(
                message(completion("failed", "Connection closed", "E_CONNECTION"), raw),
                onSelectText = { selected++ },
                onShowDelete = { deleted++ },
            )
        }
        compose.onNodeWithText("Worker", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.subagent_failed), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("PRIVATE RESULT BODY", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("8f13ce72", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Connection closed", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("E_CONNECTION", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(expectedText, textColor(label(R.string.subagent_failed)))
        openMenu()
        compose.onNodeWithText(label(R.string.copy)).performClick()
        compose.runOnIdle { assertEquals(raw, clipboard.getText()?.text) }
        openMenu()
        compose.onNodeWithText(label(R.string.select_text)).performClick()
        assertEquals(1, selected)
        openMenu()
        compose.onNodeWithText(label(R.string.delete)).performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun statusChangesNeverExposeResultBodyOrFailureDiagnostics() {
        var result by mutableStateOf(completion("running", "Private failure detail", "E_PRIVATE"))
        val raw = "PRIVATE RESULT BODY\nLast line"
        content { bubble(message(result, raw)) }
        for (status in listOf("failed", "stopped", "completed", "idle", "running")) {
            compose.runOnIdle { result = completion(status, "Private failure detail", "E_PRIVATE") }
            compose.onNodeWithText("Worker", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(label(checkNotNull(subagentResultStatusLabel(status))), useUnmergedTree = true)
                .assertIsDisplayed()
            compose.onNodeWithText("PRIVATE RESULT BODY", substring = true, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("Private failure detail", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("E_PRIVATE", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun legacyReceiptsStayCompactAndInstructionsKeepTheirVisibleBody() {
        val childId = "8f13ce72-8888-4444-9999-82cd77c4e890"
        val legacySource = MessageSource(MessageSource.Kind.SUBAGENT, subagentResults = mapOf(childId to "receipt"))
        var current by mutableStateOf(message(legacySource, "Legacy Worker ($childId) [stopped]\nHidden result"))
        content { bubble(current) }
        compose.onNodeWithText("Legacy Worker", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.subagent_stopped), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Hidden result", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { current = message(legacySource, "Unrecognized old receipt body") }
        compose.onAllNodesWithText(label(R.string.message_source_subagent), useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("Unrecognized old receipt body", useUnmergedTree = true).assertDoesNotExist()
        for (status in listOf(R.string.subagent_completed, R.string.subagent_failed, R.string.subagent_stopped)) {
            compose.onNodeWithText(label(status), useUnmergedTree = true).assertDoesNotExist()
        }
        compose.runOnIdle { current = message(MessageSource(MessageSource.Kind.SUBAGENT), "Inspect the native bubble") }
        compose.onNodeWithText("Inspect the native bubble", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun completionTextUsesUserBubblePaletteAndKeepsExistingTypography() {
        var expectedName = Color.Unspecified
        var expectedStatus = Color.Unspecified
        content {
            expectedName = MaterialTheme.colorScheme.onPrimaryContainer
            expectedStatus = MaterialTheme.colorScheme.onPrimaryContainer
            bubble(message(completion()))
        }
        fun style(text: String): androidx.compose.ui.text.TextStyle {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style
        }
        val name = style("Worker")
        val status = style(label(R.string.subagent_completed))
        assertEquals(expectedName, name.color)
        assertEquals(FontWeight.SemiBold, name.fontWeight)
        assertEquals(14f, name.fontSize.value, 0f)
        assertEquals(20f, name.lineHeight.value, 0f)
        assertEquals(expectedStatus, status.color)
        assertEquals(FontWeight.Medium, status.fontWeight)
        assertEquals(12f, status.fontSize.value, 0f)
        assertEquals(16f, status.lineHeight.value, 0f)
    }

    @Test
    fun automaticSourceDoesNotDisableExistingBranchNavigation() {
        val directions = mutableListOf<Int>()
        content { bubble(message(completion()), onSwitchBranch = { directions += it }) }
        val buttons = compose.onAllNodes(
            hasClickAction() and !SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
        )
        buttons.assertCountEquals(2)
        buttons[0].assertIsEnabled().performClick()
        buttons[1].assertIsEnabled().performClick()
        assertEquals(listOf(-1, 1), directions)
    }

    @Test
    fun existingMessageItemSelectTextSheetReceivesEveryOriginalResultLine() {
        val raw = "Worker (8f13ce72-8888-4444-9999-82cd77c4e890) [completed]\nFull original result\n\nLast line."
        content {
            MessageItem(
                message = message(completion(), raw),
                onEdit = { _, _ -> },
                segmentAppearanceRegistry = remember { SegmentAppearanceRegistry() },
                userBubbleSizeAnimationReady = false,
            )
        }
        compose.onNodeWithText(raw, useUnmergedTree = true).assertDoesNotExist()
        openMenu()
        compose.onNodeWithText(label(R.string.select_text)).performClick()
        compose.onNodeWithText(raw, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(raw.contains("\n\nLast line."))
    }
}
