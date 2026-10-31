package com.newoether.agora.ui.chat.message

import com.newoether.agora.readLocaleStringResourceSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase24UiSourceContractTest {
    @Test
    fun `Timeline and sheet group corners use one monotonic clamped motion policy`() {
        val segments = source("message/MessageItemSegments.kt")
        val timeline = source("message/MessageItemTimeline.kt")

        assertTrue(segments.contains("internal fun rememberAnimatedSegmentGroupShape("))
        assertTrue(segments.contains("FastOutSlowInEasing"))
        assertTrue(segments.contains("tween("))
        assertTrue(segments.contains("durationMillis = 240"))
        assertFalse(segments.contains("DampingRatioHighBouncy"))
        assertFalse(segments.contains("spring("))
        assertEquals(4, segments.windowed(".coerceIn(innerCorner, outerCorner)".length)
            .count { it == ".coerceIn(innerCorner, outerCorner)" })
        assertTrue(segments.contains("motionPolicy.allowSpatialTransitions"))
        assertTrue(timeline.contains("rememberAnimatedSegmentGroupShape(groupPosition)"))
        assertFalse(timeline.contains("internal fun segmentGroupShape("))
        assertTrue(timeline.contains("vertical = 10.dp"))
        assertTrue(timeline.contains("durationMillis = SEGMENT_ENTER_DURATION_MS"))
    }

    @Test
    fun `Thinking durations use one localized seconds minutes and hours breakdown`() {
        val presentation = source("message/ThinkingSegmentPresentation.kt")
        val timeline = source("message/MessageItemTimeline.kt")

        listOf(
            "thinking_for_seconds_ellipsis",
            "thinking_for_minutes_ellipsis",
            "thinking_for_hours_ellipsis",
            "thought_for_seconds",
            "thought_for_minutes",
            "thought_for_hours",
            "thought_for_seconds_called_tools",
            "thought_for_minutes_called_tools",
            "thought_for_hours_called_tools",
            "thought_for_a_while_called_tools",
        ).forEach { key -> assertTrue("Missing presentation key $key", presentation.contains(key)) }
        assertTrue(presentation.contains("seconds / 3_600"))
        assertTrue(presentation.contains("(seconds % 3_600) / 60"))
        assertTrue(presentation.contains("seconds % 60"))
        val terminalTitle = presentation
            .substringAfter("internal fun Resources.compactSegmentTitle(")
            .substringBefore("internal fun compactSegmentDisplayTitle(")
        assertTrue(terminalTitle.contains("val hasThought = segs.any { it.type == \"thought\" }"))
        assertTrue(terminalTitle.contains(
            "hasThought -> thoughtDurationTitle(thoughtMs, toolCount)"
        ))
        assertTrue(terminalTitle.contains(
            "toolCount > 0 -> getString(R.string.called_n_tools, toolCount)"
        ))
        val compactBlock = timeline
            .substringAfter("internal fun compactSegmentIcon(")
            .substringBefore("internal fun retainExpandedLayoutDuringFade(")
        val allToolSegmentCount = "val toolCount = segs.count { it.type == \"tool\" }"
        assertTrue(terminalTitle.contains(allToolSegmentCount))
        assertTrue(compactBlock.contains(allToolSegmentCount))
        assertFalse(terminalTitle.contains("toolResult != null"))
        assertFalse(compactBlock.contains("toolResult != null"))
        assertFalse(terminalTitle.contains(
            "message.thoughtTitle != null -> message.thoughtTitle"
        ))
        assertFalse(terminalTitle.contains(
            "else -> getString(R.string.thinking_complete)"
        ))

        val expectedPlaceholders = mapOf(
            "thinking_for_minutes_ellipsis" to setOf(1, 2),
            "thinking_for_hours_ellipsis" to setOf(1, 2, 3),
            "thought_for_hours" to setOf(1, 2, 3),
            "thought_for_hours_called_tools" to setOf(1, 2, 3, 4),
            "thought_for_a_while_called_tools" to setOf(1),
        )
        resourceDirectories.forEach { directory ->
            val xml = resourceFile(directory)
            expectedPlaceholders.forEach { (key, expected) ->
                assertEquals(
                    "$directory $key placeholders",
                    expected,
                    placeholders(stringValue(xml, key)),
                )
            }
        }
    }

    @Test
    fun `Thinking sheet uses approved inset height and translucent neutral palette`() {
        val timeline = source("message/MessageItemTimeline.kt")
        val detail = source("message/SegmentDetailSheet.kt")

        assertTrue(detail.contains(".padding(horizontal = 20.dp, vertical = 8.dp)"))
        assertTrue(timeline.contains("Modifier.padding(horizontal = 10.dp, vertical = 10.dp)"))
        assertTrue(timeline.contains(
            "MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)"
        ))
        assertTrue(timeline.contains(
            "val iconTint = if (neutralPalette) MaterialTheme.colorScheme.primary"
        ))
        assertTrue(timeline.contains(
            "tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)"
        ))
    }

    private fun placeholders(value: String): Set<Int> =
        (1..4).filterTo(linkedSetOf()) { value.contains("%${it}\$d") }

    private fun stringValue(xml: String, key: String): String =
        requireNotNull(Regex("""<string name="$key">([^<]*)</string>""").find(xml)) {
            "Missing $key"
        }.groupValues[1]

    private fun source(relative: String): String =
        File(mainSourceRoot(), "com/newoether/agora/ui/chat/$relative").readText()

    private fun resourceFile(directory: String): String =
        File(resourceRoot(), "$directory/strings.xml").readLocaleStringResourceSources()

    private fun mainSourceRoot(): File = locate("app/src/main/java")
    private fun resourceRoot(): File = locate("app/src/main/res")

    private fun locate(relative: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relative).takeIf(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relative")
    }

    private companion object {
        val resourceDirectories = listOf(
            "values",
            "values-ar",
            "values-de",
            "values-es",
            "values-fr",
            "values-ja",
            "values-ko",
            "values-pt-rBR",
            "values-ru",
            "values-vi",
            "values-zh",
            "values-zh-rTW",
        )
    }
}
