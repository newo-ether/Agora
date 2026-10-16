package com.newoether.agora.viewmodel

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LOCAL_CONTEXT_CAPACITY_ERROR_CODE
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.StreamingTextDelta
import com.newoether.agora.model.citationRecords
import com.newoether.agora.model.toMessageSegment
import com.newoether.agora.ui.chat.message.assistantErrorContent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStreamingSegmentsTest {
    @Test
    fun `live segments merge answers and unsigned thoughts but preserve signed boundaries`() {
        val unsigned = buildLiveSegments(
            flushed = listOf(MessageSegment(type = "answer", content = "a")),
            answer = "b",
            thought = "c",
            thoughtDurationMs = 4L,
        )
        assertEquals(listOf("ab", "c"), unsigned?.map { it.content })
        assertEquals(4L, unsigned?.last()?.durationMs)

        val signed = mutableListOf(
            MessageSegment(type = "thought", content = "old", signature = "sig-old"),
        )
        appendMergedSegment(
            signed,
            MessageSegment(type = "thought", content = "new", signature = "sig-new"),
        )
        assertEquals(2, signed.size)
        assertNull(buildLiveSegments(emptyList(), "", ""))
    }

    @Test
    fun `live answer delta metadata is an immutable publication snapshot`() {
        val deltas = mutableListOf(StreamingTextDelta(sequence = 0L, codePointCount = 3))
        val published = checkNotNull(buildLiveSegments(emptyList(), "abc", "", answerDeltas = deltas))
            .single().streamingTextDeltas
        deltas += StreamingTextDelta(sequence = 1L, codePointCount = 2)
        assertEquals(listOf(StreamingTextDelta(0L, 3)), published)
    }

    @Test
    fun `thought timing remains call scoped and deterministic`() {
        var now = 100L
        val timing = GenerationThoughtTiming { now }

        timing.ensureStarted()
        now = 125L
        assertEquals(25L, timing.liveDurationMs())
        timing.finishCurrent()
        assertEquals(25L, timing.currentDurationMs)
        assertEquals(25L, timing.totalDurationMs)
        timing.resetCurrentDuration()
        now = 200L
        timing.ensureStarted()
        now = 215L
        timing.finishCurrent()

        assertEquals(15L, timing.currentDurationMs)
        assertEquals(40L, timing.totalDurationMs)
    }

    @Test
    fun `provider pass finish stops live thinking before publishing final duration`() {
        assertEquals(MessageStatus.SENDING, statusAfterThoughtPhaseFinished(MessageStatus.THINKING))
        assertEquals(MessageStatus.SENDING, statusAfterThoughtPhaseFinished(MessageStatus.SENDING))
        assertEquals(MessageStatus.ERROR, statusAfterThoughtPhaseFinished(MessageStatus.ERROR))
        assertEquals(MessageStatus.STOPPED, statusAfterThoughtPhaseFinished(MessageStatus.STOPPED))
    }

    @Test
    fun `final snapshot preserves the terminal message projection`() {
        val oversized = "x".repeat(2_000_000)
        val snapshot = GenerationFinalSnapshot(
            messageId = "model",
            parentId = "user",
            text = oversized,
            images = listOf("image"),
            thoughts = "thought",
            thoughtTitle = "title",
            tokenCount = 9,
            tokenUsage = null,
            status = MessageStatus.SUCCESS,
            timestamp = 10L,
            thoughtTimeMs = 20L,
            modelName = "model-name",
            flushedSegments = listOf(MessageSegment(type = "answer", content = "first")),
            answerBuffer = "second",
            thoughtBuffer = "",
            thoughtSignature = null,
            thoughtSignatureProvider = null,
            thoughtDurationMs = null,
            errorMessage = null,
            runId = "run",
            runSequence = 3L,
        )

        val message: ChatMessage = snapshot.toMessage()

        assertEquals(MessagePersistenceGuard.clipText(oversized), message.text)
        assertEquals("firstsecond", message.segments?.single()?.content)
        assertEquals("run", message.runId)
        assertEquals(3L, message.runSequence)
    }

    @Test
    fun `provider pass citation anchors rebase into the merged answer`() {
        val prefix = "First answer before tool. "
        val cited = "([openai.com](https://openai.com/research))"
        val passAnswer = "Second answer $cited"
        val localStart = passAnswer.indexOf(cited)
        val local = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "OpenAI Research",
                url = "https://openai.com/research",
                anchors = listOf(
                    CitationAnchor(localStart, localStart + cited.length, cited),
                ),
                answerText = passAnswer,
            ),
        )

        val rebased = rebaseCitationForFinalAnswer(
            citation = local,
            providerAnswerStart = prefix.length,
            finalAnswer = prefix + passAnswer,
        )

        assertEquals(prefix.length + localStart, rebased.anchors.single().startIndex)
        assertEquals(prefix.length + localStart + cited.length, rebased.anchors.single().endIndex)
    }

    @Test
    fun `provider citation rebase fails closed when exact recovery is ambiguous`() {
        val cited = "same"
        val local = requireNotNull(
            CitationPolicy.create(
                provider = "test",
                kind = "document",
                title = "Source",
                providerSourceId = "source",
                anchors = listOf(CitationAnchor(0, cited.length, cited)),
                answerText = cited,
            ),
        )

        val rebased = rebaseCitationForFinalAnswer(
            citation = local,
            providerAnswerStart = 1,
            finalAnswer = "same same",
        )

        assertTrue(rebased.anchors.isEmpty())
    }

    @Test
    fun `normal stop and partial failure retain one ordered citation`() {
        val answer = "Claim tail"
        val citation = requireNotNull(
            CitationPolicy.create(
                provider = "test",
                kind = "web",
                title = "Source",
                url = "https://example.com/source",
                anchors = listOf(CitationAnchor(0, 5, "Claim")),
                answerText = answer,
            ),
        )

        listOf(MessageStatus.SUCCESS, MessageStatus.STOPPED, MessageStatus.ERROR).forEach { status ->
            val message = GenerationFinalSnapshot(
                messageId = "model-$status",
                parentId = "user",
                text = answer,
                images = emptyList(),
                thoughts = "",
                thoughtTitle = null,
                tokenCount = 0,
                tokenUsage = null,
                status = status,
                timestamp = 10L,
                thoughtTimeMs = null,
                modelName = "model-name",
                flushedSegments = listOf(
                    MessageSegment(type = "answer", content = "Claim"),
                    citation.toMessageSegment(),
                ),
                answerBuffer = " tail",
                thoughtBuffer = "",
                thoughtSignature = null,
                thoughtSignatureProvider = null,
                thoughtDurationMs = null,
                errorMessage = "Partial failure".takeIf { status == MessageStatus.ERROR },
                runId = "run",
                runSequence = 1L,
            ).toMessage()

            assertEquals(status, message.status)
            assertEquals(listOf(citation), message.citationRecords())
            assertEquals(1, message.segments?.count { it.type == "citation" })
            assertEquals(
                if (status == MessageStatus.ERROR) {
                    listOf("answer", "error", "citation")
                } else {
                    listOf("answer", "citation")
                },
                message.segments?.map { it.type },
            )
        }
    }

    @Test
    fun `terminal generation errors always retain a visible error value`() {
        assertEquals(
            "Generation failed",
            terminalGenerationErrorMessage(
                status = MessageStatus.ERROR,
                currentError = null,
                fallbackError = "Generation failed",
            ),
        )
        assertEquals(
            "Provider failed",
            terminalGenerationErrorMessage(
                status = MessageStatus.ERROR,
                currentError = "Provider failed",
                fallbackError = "Generation failed",
            ),
        )
        assertNull(
            terminalGenerationErrorMessage(
                status = MessageStatus.SUCCESS,
                currentError = null,
                fallbackError = "Generation failed",
            ),
        )
    }

    @Test
    fun `output transform updates streaming text and answer segments together`() {
        val wrapped = "<context_summary>\nsummary\n</context_"
        val original = ChatMessage(
            id = "compact",
            text = wrapped,
            participant = com.newoether.agora.model.Participant.MODEL,
            status = MessageStatus.SENDING,
            segments = listOf(
                MessageSegment(
                    type = "answer",
                    content = wrapped,
                    streamingTextDeltas = listOf(StreamingTextDelta(0L, wrapped.length)),
                ),
            ),
        )

        val transformed = original.withBoundedOutputTextTransform { text, _ ->
            normalizeContextCompactOutput(text)
        }

        assertEquals("summary", transformed.text)
        assertEquals("summary", transformed.segments?.single()?.content)
        assertTrue(transformed.segments?.single()?.streamingTextDeltas?.isEmpty() == true)
        assertFalse(transformed.text.contains("context_summary"))
        assertFalse(transformed.segments.orEmpty().any { it.content.contains("context_summary") })
    }

    @Test
    fun `final text transform is field restricted and persistence bounded`() {
        val original = ChatMessage(
            id = "compact",
            parentId = "parent",
            text = "summary",
            participant = com.newoether.agora.model.Participant.MODEL,
            status = MessageStatus.SUCCESS,
            runId = "fresh-run",
            runSequence = 0,
        )
        val oversizedSuffix = "x".repeat(2_000_000)

        val transformed = original.withBoundedOutputTextTransform { text, status ->
            assertEquals(MessageStatus.SUCCESS, status)
            text + oversizedSuffix
        }

        assertEquals(
            MessagePersistenceGuard.clipText(original.text + oversizedSuffix),
            transformed.text,
        )
        assertEquals(original, transformed.copy(text = original.text))
    }

    @Test
    fun `native context callback persists help eligibility through final UI projection`() {
        val failure = GenerationError.LocalModel(
            message = "This conversation needs 24636 prompt tokens",
            code = LOCAL_CONTEXT_CAPACITY_ERROR_CODE,
        )
        val snapshot = GenerationFinalSnapshot(
            messageId = "model",
            parentId = "user",
            text = "",
            images = emptyList(),
            thoughts = "",
            thoughtTitle = null,
            tokenCount = 24_636,
            tokenUsage = null,
            status = MessageStatus.ERROR,
            timestamp = 10L,
            thoughtTimeMs = null,
            modelName = "Local: model.gguf",
            flushedSegments = emptyList(),
            answerBuffer = "",
            thoughtBuffer = "",
            thoughtSignature = null,
            thoughtSignatureProvider = null,
            thoughtDurationMs = null,
            errorMessage = failure.message,
            errorCode = failure.code,
            runId = "run",
            runSequence = 1L,
        )
        val finalMessage = snapshot.toMessage()
        val encoded = MessagePersistenceGuard.encodeSegmentsBounded(finalMessage.segments)
        val projected = MessageEntity(
            id = finalMessage.id,
            conversationId = "conversation",
            parentId = finalMessage.parentId,
            text = finalMessage.text,
            images = finalMessage.images,
            thoughts = finalMessage.thoughts,
            thoughtTitle = finalMessage.thoughtTitle,
            tokenCount = finalMessage.tokenCount,
            status = finalMessage.status,
            participant = finalMessage.participant,
            timestamp = finalMessage.timestamp,
            thoughtTimeMs = finalMessage.thoughtTimeMs,
            modelName = finalMessage.modelName,
            toolCallJson = encoded,
            runId = checkNotNull(finalMessage.runId),
            runSequence = checkNotNull(finalMessage.runSequence),
        ).toUiChatMessage { it }
        val errorContent = checkNotNull(
            assistantErrorContent(
                message = projected,
                mergedSegments = projected.segments.orEmpty(),
                fallbackErrorText = "Failed to generate",
            )
        )

        assertEquals(LOCAL_CONTEXT_CAPACITY_ERROR_CODE, failure.code)
        assertEquals(
            LOCAL_CONTEXT_CAPACITY_ERROR_CODE,
            projected.segments?.single { it.type == "error" }?.errorCode,
        )
        assertTrue(errorContent.showLocalContextHelp)
    }

    @Test
    fun `failed snapshot keeps generated answer separate from terminal error`() {
        val snapshot = GenerationFinalSnapshot(
            messageId = "model",
            parentId = "user",
            text = "Useful partial answer",
            images = emptyList(),
            thoughts = "",
            thoughtTitle = null,
            tokenCount = 0,
            tokenUsage = null,
            status = MessageStatus.ERROR,
            timestamp = 10L,
            thoughtTimeMs = null,
            modelName = "model-name",
            flushedSegments = listOf(
                MessageSegment(type = "answer", content = "Useful partial answer"),
            ),
            answerBuffer = "",
            thoughtBuffer = "",
            thoughtSignature = null,
            thoughtSignatureProvider = null,
            thoughtDurationMs = null,
            errorMessage = "Connection closed before a valid terminator",
            runId = "run",
            runSequence = 1L,
        )

        val message = snapshot.toMessage()

        assertEquals("Useful partial answer", message.text)
        assertEquals(listOf("answer", "error"), message.segments?.map { it.type })
        assertEquals(
            "Connection closed before a valid terminator",
            message.segments?.last()?.content,
        )
    }

    @Test
    fun `generation answer accumulation uses one mutable buffer and immutable snapshots`() {
        val source = File(
            locateMainSourceRoot(),
            "com/newoether/agora/viewmodel/GenerationManager.kt",
        ).readText()
        val accumulator = File(
            locateMainSourceRoot(),
            "com/newoether/agora/viewmodel/GenerationOutputAccumulator.kt",
        ).readText()

        assertTrue(source.contains("val output = GenerationOutputAccumulator(toolExecutor, config.providerName)"))
        assertTrue(source.contains("output.handleStreamEvent("))
        assertEquals(1, Regex("""val totalText = StringBuilder\(\)""").findAll(accumulator).count())
        assertTrue(
            Regex("""output\.totalText\.clear\(\)\s+output\.totalText\.append\(snapshot\.text\)""")
                .containsMatchIn(source),
        )
        assertTrue(accumulator.contains("totalText.append(answerText)"))
        assertFalse((source + accumulator).contains("totalText += answerText"))
        assertTrue(source.contains("text = output.totalText.toString(), thoughts ="))
        assertTrue(accumulator.contains("finalAnswer = totalText.toString()"))
        assertTrue(
            Regex("""text = output\.totalText\.toString\(\),\s+images =""")
                .containsMatchIn(source),
        )
        assertTrue(source.contains("val providerAnswerStart = output.totalText.length"))
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
