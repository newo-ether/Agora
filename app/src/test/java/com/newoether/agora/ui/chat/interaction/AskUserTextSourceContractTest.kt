package com.newoether.agora.ui.chat.interaction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserTextSourceContractTest {
    @Test
    fun `only the question text in the interaction card is selectable`() {
        val card = source("com/newoether/agora/ui/chat/interaction/QuestionCard.kt")
        val host = "NoAutoScrollSelectionContainer(enabled = enabled) {"
        val start = card.indexOf(host)

        assertTrue(start >= 0)
        assertEquals(start, card.lastIndexOf(host))
        // The selectable block holds the question Text and closes before the option rows begin.
        val block = card.substring(start, card.indexOf("if (hasOptions)", start))
        assertTrue(block.contains("text = request.question,"))
        assertTrue(!block.contains("OptionRow("))
        assertTrue(!card.contains("DisableSelection"))
    }

    @Test
    fun `ask_user bubble questions use 14 sp text on a 20 sp line`() {
        val presentation = source("com/newoether/agora/ui/chat/message/MessageSourcePresentation.kt")

        assertTrue(presentation.contains("internal val ASK_USER_QUESTION_FONT_SIZE = 14.sp"))
        assertTrue(presentation.contains("internal val ASK_USER_QUESTION_LINE_HEIGHT = 20.sp"))
    }

    private fun source(relative: String): String =
        File(locate("app/src/main/java"), relative).readText().replace("\r\n", "\n")

    private fun locate(relative: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relative).takeIf(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relative")
    }
}
