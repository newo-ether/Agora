package com.newoether.agora.ui.chat.message

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.QuestionAnswer
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MessageSourcePresentationTest {
    private val source = MessageSource.askUser(
        listOf(
            MessageSource.AskUserItem("Which port?", "8080"),
            MessageSource.AskUserItem("Restart now?", null),
            MessageSource.AskUserItem("Note", "line one\nline two"),
        ),
    )

    @Test
    fun `subagent source shares the idle capsule icon without changing ask_user`() {
        assertEquals(Icons.Rounded.AccountTree, messageSourceIcon(MessageSource.Kind.SUBAGENT))
        assertEquals(Icons.Rounded.QuestionAnswer, messageSourceIcon(MessageSource.Kind.ASK_USER))
    }

    @Test
    fun `ask_user display text uses the localized unanswered label`() {
        assertEquals(
            "Which port?\n8080\n\nRestart now?\n未回答\n\nNote\nline one\nline two",
            askUserDisplayText(source, "未回答"),
        )
    }

    @Test
    fun `blocks map onto the stored text so search slices line up`() {
        val stored = source.askUserReadableText()
        val groups = askUserBlocks(source, "未回答")

        assertEquals(3, groups.size)
        groups.flatten().filter { it.storedStart != null }.forEach { block ->
            val start = checkNotNull(block.storedStart)
            assertEquals(block.text, stored.substring(start, start + block.text.length))
        }
        val (question, skipped) = groups[1]
        assertEquals(true, question.isQuestion)
        assertEquals("未回答", skipped.text)
        // The localized label has no stored counterpart and is never highlighted.
        assertNull(skipped.storedStart)
    }

    @Test
    fun `subagent result statuses use existing localized resources without inventing legacy success`() {
        assertEquals(R.string.subagent_completed, subagentResultStatusLabel("completed"))
        assertEquals(R.string.subagent_failed, subagentResultStatusLabel("failed"))
        assertEquals(R.string.subagent_stopped, subagentResultStatusLabel("stopped"))
        assertNull(subagentResultStatusLabel(""))
        assertNull(subagentResultStatusLabel("unknown"))
    }

    @Test
    fun `subagent completions keep the original full body for existing message actions`() {
        val raw = "Worker (child-id) [completed]\nFull result\n\nWith every original line."
        val result = MessageSource.SubagentResult("Worker", "completed")
        val message = ChatMessage(
            text = raw,
            participant = Participant.USER,
            source = MessageSource(
                MessageSource.Kind.SUBAGENT,
                subagentResults = mapOf("child-id" to "completion-id"),
                subagentResult = result,
            ),
        )
        assertSame(message, message.withAskUserDisplayText("-"))
        assertEquals(raw, message.text)
    }

    @Test
    fun `only ask_user messages change their visible text`() {
        val askUser = ChatMessage(text = source.askUserReadableText(), participant = Participant.USER, source = source)
        assertEquals(askUserDisplayText(source, "-"), askUser.withAskUserDisplayText("-").text)
        val task = ChatMessage(text = "Run", participant = Participant.USER, source = MessageSource.TASK)
        assertSame(task, task.withAskUserDisplayText("-"))
        val typed = ChatMessage(text = "hi", participant = Participant.USER)
        assertSame(typed, typed.withAskUserDisplayText("-"))
    }
}
