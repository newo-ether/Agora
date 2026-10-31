package com.newoether.agora.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ChatMediaSourceContractTest : UiSourceContractFixture() {
    @Test
    fun clipboardImagesUseReceiveContentWithoutConsumingUnsupportedItems() {
        val composer = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        )

        assertTrue(composer.contains(".contentReceiver(clipboardImageReceiver)"))
        assertTrue(composer.contains("transferableContent.consume"))
        assertTrue(composer.contains("hasMediaType(MediaType.Image)"))
        assertTrue(composer.contains("importUris(composerOwnerId, imageUris, \"image\", emitSuccessHaptic = false)"))
        assertTrue(composer.contains("inspectAttachmentIngress("))
        assertTrue(composer.contains(
            "composerController.importAttachment(ownerId, attachment) || imported",
        ))
        assertTrue(composer.contains("return remaining"))
    }

    @Test
    fun sendKeepsTheTextFieldEditableAndDoesNotDismissTheKeyboard() {
        val root = sourceRoot()
        val sendButton = source(root, "com/newoether/agora/ui/chat/bottombar/ComposerSendButton.kt")
        val textField = source(root, "com/newoether/agora/ui/chat/bottombar/ChatComposerLayout.kt")
            .substringAfter("TextField(")
            .substringBefore("placeholder =")

        assertFalse(textField.contains("enabled ="))
        assertFalse(sendButton.contains("LocalSoftwareKeyboardController"))
        assertTrue(sendButton.contains("enabled = isActionable"))
        assertTrue(sendButton.contains("strokeWidth = 3.dp"))
        assertEquals(2, sendButton.split("animationSpec = tween(durationMillis = 400)").size - 1)
        assertTrue(sendButton.contains("durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS"))
        assertTrue(sendButton.contains("easing = LinearEasing"))
    }

    @Test
    fun toolSummaryCrossfadesByStateAndToolContentKeepsItsOpacity() {
        val root = sourceRoot()
        val timeline = source(root, "com/newoether/agora/ui/chat/message/MessageItemTimeline.kt")
        val mutedText = source(root, "com/newoether/agora/ui/chat/message/StreamingMutedText.kt")
        val toolSummary = mutedText.substringAfter("internal fun ToolSummaryText(")
            .substringBefore("private fun thoughtPreviewTail(")
        val compactBlock = timeline.substringAfter("internal fun CompactSegmentBlock(")
            .substringBefore("internal fun retainExpandedLayoutDuringFade(")
        val lifecycle = source(root, "com/newoether/agora/ui/chat/message/GenerationLifecycleMotion.kt")
        val messageItem = source(root, "com/newoether/agora/ui/chat/message/MessageItem.kt")
        val assistant = source(root, "com/newoether/agora/ui/chat/message/AssistantMessageContent.kt")

        assertTrue(toolSummary.contains("Crossfade("))
        assertTrue(toolSummary.contains("targetState = presentation.state"))
        assertFalse(toolSummary.contains("targetState = summary"))
        assertFalse(toolSummary.contains("StableStreamingText("))
        assertTrue(compactBlock.contains("shouldPresentInitiallyExpanded("))
        assertTrue(compactBlock.contains("groupedSegmentExpandedState("))
        assertTrue(compactBlock.contains("targetExpanded && initiallyAutoExpanded"))
        assertFalse(compactBlock.contains("forceOpaque = containsToolSummary"))
        assertTrue(timeline.contains("allowSpatialTransitions -> fadeIn(tween(400)) + expandVertically(tween(400))"))
        assertTrue(timeline.contains("allowSpatialTransitions -> fadeOut(tween(400)) + shrinkVertically(tween(400))"))
        assertTrue(timeline.contains("EnterTransition.None"))
        assertTrue(timeline.contains("ExitTransition.None"))
        assertTrue(lifecycle.contains("alpha = if (forceOpaque) 1f else value"))
        assertTrue(messageItem.contains(
            "forceOpaque = displayMessage.segments.orEmpty().any { it.type == \"tool\" }",
        ))
        assertTrue(assistant.contains("forceOpaque = detailSegments.any { it.type == \"tool\" }"))
    }
}
