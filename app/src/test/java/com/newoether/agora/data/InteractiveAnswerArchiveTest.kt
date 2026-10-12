package com.newoether.agora.data

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.interactiveAnswer
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InteractiveAnswerArchiveTest {
    private val answer = InteractiveAnswer("Calculator", "A local tool", "Two plus two is four",
        eInkAware = true, html = "<output>4</output>", jsFunctions = "function add(a,b){return a+b;}", jsExpressions = listOf("add(2,2);"))
    private val raw = Json.encodeToString(listOf(MessageSegment(type = "tool",
        toolName = InteractiveAnswer.TOOL_NAME, toolCallId = "call", toolState = ToolExecutionStates.SUCCEEDED,
        toolStructuredResult = answer.encode())))

    @Test fun `native archive export and restore retain structured artifact code exactly`() {
        val exported = NativeBackupMediaPolicy.rewriteToolImagePathsForExport(raw) { fail("No file-backed media expected"); null }
        val restored = NativeBackupMediaPolicy.restoreToolImagePaths(exported, 5) { fail("No private path expected"); null }
        assertEquals(answer, Json.decodeFromString<List<MessageSegment>>(restored!!).single().interactiveAnswer())
    }
    @Test fun `fork attachment ownership leaves inline artifact intact without creating files`() {
        val directory = Files.createTempDirectory("interactive-answer-fork").toFile()
        try {
            val session = MessageAttachmentCloneSession(directory)
            val original = MessageEntity(id = "m", conversationId = "c", text = "", participant = Participant.MODEL,
                status = MessageStatus.SUCCESS, timestamp = 1, runId = "run", toolCallJson = raw)
            val clone = session.cloneMessage(original.copy(id = "fork-m", conversationId = "fork-c", runId = "fork-run"))
            assertEquals(raw, clone.toolCallJson)
            assertEquals("fork-c", clone.conversationId)
            assertEquals(answer, Json.decodeFromString<List<MessageSegment>>(clone.toolCallJson!!).single().interactiveAnswer())
            session.rollback()
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
