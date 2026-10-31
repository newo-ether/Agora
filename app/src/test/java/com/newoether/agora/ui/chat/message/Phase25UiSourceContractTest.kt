package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase25UiSourceContractTest {
    @Test
    fun `Timeline info entrances have one card owned unbounded appearance layer`() {
        val timeline = source("MessageItemTimeline.kt") +
            source("TimelineSegmentsContent.kt")

        assertEquals(
            2,
            timeline.windowed("AnimatedTimelineBlockAppearance(".length)
                .count { it == "AnimatedTimelineBlockAppearance(" },
        )
        assertFalse(timeline.contains("val blockContent: @Composable () -> Unit"))
        assertFalse(timeline.contains("val cardContent: @Composable () -> Unit"))
        val infoCard = timeline.substringAfter("internal fun TimelineInfoSegmentCard(")
        assertTrue(infoCard.contains(".then(cardAppearanceModifier)"))
        assertTrue(infoCard.indexOf(".then(cardAppearanceModifier)") <
            infoCard.indexOf(".clip(groupShape)"))
        assertTrue(timeline.contains("StartAnchoredHorizontalOverflowHost"))
    }

    @Test
    fun `Timeline clicks request stable detail host independent of item lifetime`() {
        val item = source("MessageItem.kt")
        val assistant = source("AssistantMessageContent.kt")
        val list = chatSource("MessageList.kt")
        val detail = source("SegmentDetailSheet.kt")

        assertFalse(item.contains("usesExplicitDetailBackHandler("))
        assertFalse(item.contains("var showSegmentDetail"))
        assertFalse(item.contains("if (showSegmentDetail"))
        assertTrue(item.contains("onSegmentDetailRequest(message.id, indices, showListFirst)"))
        assertTrue(list.contains("MessageSegmentDetailHost("))
        assertTrue(list.contains("authoritativeMessages = authoritativeMessages.list"))
        assertTrue(detail.contains("observeMessage(messageId)"))
        assertTrue(detail.contains("streamingMessage = streamingMessage"))
        assertTrue(assistant.contains("onSegmentSelected: (List<Int>, Boolean) -> Unit"))
        assertTrue(assistant.contains("onSegmentSelected(indices, false)"))
        assertTrue(assistant.contains(
            "onSegmentSelected(detailSegments.indices.toList(), true)"
        ))
        assertTrue(assistant.contains("onSegmentSelected(listOf(index), false)"))
    }

    @Test
    fun `Thinking sheet uses twenty five percent neutral cards gray arrows and local back chrome`() {
        val timeline = source("MessageItemTimeline.kt") +
            source("TimelineSegmentsContent.kt")
        val detail = source("SegmentDetailSheet.kt")
        val back = componentSource("CircularBackButton.kt")

        assertTrue(timeline.contains(
            "MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)"
        ))
        assertTrue(timeline.contains(
            "val iconTint = if (neutralPalette) MaterialTheme.colorScheme.primary"
        ))
        assertTrue(timeline.contains(
            "tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)"
        ))
        val backCall = detail
            .substringAfter("CircularBackButton(")
            .substringBefore("Text(")
        assertTrue(backCall.contains("containerColor ="))
        assertTrue(backCall.contains(
            "MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)"
        ))
        assertTrue(back.contains("containerColor: Color = MaterialTheme.colorScheme.surface"))
    }

    private fun source(name: String): String =
        File(mainSourceRoot(), "com/newoether/agora/ui/chat/message/$name").readText()

    private fun chatSource(name: String): String =
        File(mainSourceRoot(), "com/newoether/agora/ui/chat/$name").readText()

    private fun componentSource(name: String): String =
        File(mainSourceRoot(), "com/newoether/agora/ui/components/$name").readText()

    private fun mainSourceRoot(): File = locate("app/src/main/java")

    private fun locate(relative: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relative).takeIf(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relative")
    }
}
