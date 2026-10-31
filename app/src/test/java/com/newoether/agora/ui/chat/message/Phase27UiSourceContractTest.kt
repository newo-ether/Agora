package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase27UiSourceContractTest {
    @Test
    fun `terminal background tool cannot keep Thinking header loading`() {
        val timeline = source("MessageItemTimeline.kt") +
            source("TimelineSegmentsContent.kt")
        val presentation = source("ToolPresentation.kt")

        assertTrue(timeline.contains("): Boolean = generationActive && isCurrentCard"))
        assertTrue(timeline.contains("): Boolean = generationActive && isCurrentCard && useLiveStatus"))
        assertTrue(timeline.contains("val cardUsesLiveStatus = compactSegmentUsesLiveStatus("))
        assertTrue(timeline.contains("generationActive = generationActive"))
        // isActive drives the loading indicator and must exclude detached background jobs.
        assertFalse(presentation.contains(
            "state == ToolPresentationState.BACKGROUND_RUNNING"
        ))
        assertTrue(presentation.contains(
            "state == ToolPresentationState.CALLING ||"
        ))
    }

    private fun source(name: String): String =
        File(mainSourceRoot(), "com/newoether/agora/ui/chat/message/$name").readText()

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
