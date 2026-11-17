package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.projectAssistantImagesToLatestUserMessage
import com.newoether.agora.api.util.projectToolResultImagesToUserMessage
import com.newoether.agora.api.util.projectedImageOccurrences
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolImageProjectionTest {
    @Test
    fun `live builder and Room replay count image occurrences once across all mirrors`() {
        val first = image("first")
        val second = image("second")
        val third = image("third")
        val calls = listOf(call("one", listOf(first, second)), call("two", listOf(third)))
        val builtRound = round("round", calls)
        val live = builtRound.pathMessages
        val replay = projectProviderMessages(builtRound.entities, includeStoredTranscriptions = false)

        val fallback = round("fallback", calls, completedSegments = false)
        listOf(live, replay, fallback.pathMessages,
            projectProviderMessages(fallback.entities, includeStoredTranscriptions = false)).forEach { messages ->
            val original = messages.toList()
            val projected = projectPhoneToolImages(messages, 2)
            assertEquals(messages.map { it.id }, projected.map { it.id })
            assertEquals(listOf(second.path), projected[1].images)
            assertEquals(listOf(third.path), projected[2].images)
            assertEquals(listOf(second), projected[1].toolCall!!.resultImages)
            assertEquals(listOf(third), projected[2].toolCall!!.resultImages)
            assertEquals(emptyList<ImageArtifact>(), projected[1].segments.orEmpty()
                .flatMap { it.toolImages }.filter { it == first })
            assertEquals(listOf(second.path, third.path), contextImages(projected))
            messages.zip(projected).forEach { (before, after) ->
                assertEquals(before.copy(images = emptyList(), imageArtifacts = emptyMap(),
                    attachmentMeta = null, segments = null, toolCall = null),
                    after.copy(images = emptyList(), imageArtifacts = emptyMap(),
                        attachmentMeta = null, segments = null, toolCall = null))
                before.toolCall?.let { originalCall ->
                    assertEquals(originalCall.copy(resultImages = emptyList()),
                        after.toolCall!!.copy(resultImages = emptyList()))
                }
                assertEquals(before.segments?.map { it.copy(toolImages = emptyList()) },
                    after.segments?.map { it.copy(toolImages = emptyList()) })
            }
            assertEquals(original, messages)
            assertEquals(projected, projectPhoneToolImages(projected, 2))
            assertTrue(projected.none { first.path in it.imageArtifacts })
        }
    }

    @Test
    fun `newest message then tool and image order wins independent of timestamps`() {
        val old = round("old", listOf(call("old", listOf(image("old"))))).pathMessages
        val images = (1..4).map { image("new-$it") }
        val recent = ChatMessage(
            id = "result_mixed", text = "same tool order", participant = Participant.USER,
            timestamp = 0L,
            images = images.map { it.path },
            segments = listOf(
                segment(call("first", images.take(2))),
                MessageSegment("thought", "opaque thought", signature = "thought-signature"),
                segment(call("second", images.drop(2))),
            ),
        )
        val projected = projectPhoneToolImages(old + recent, 3)

        assertEquals(emptyList<String>(), projected[1].images)
        assertEquals(images.drop(1).map { it.path }, projected.last().images)
        assertEquals(listOf(images[1]), projected.last().segments!![0].toolImages)
        assertEquals(recent.segments!![1], projected.last().segments!![1])
        assertEquals(images.drop(2), projected.last().segments!![2].toolImages)
        assertEquals(images.drop(1).map { it.path }, contextImages(projected))
    }

    @Test
    fun `zero drops only exact built in Phone tools and preserves same path other images`() {
        val phone = image("shared", width = 100)
        val other = phone.copy(width = 200)
        val names = listOf("mcp_call_tool", "mcp_phone_screenshot_ab1234", "phone_screenshot_extra",
            "openai_search", "google_search", "code_execution", "file_view_image")
        val ordinary = ChatMessage(
            id = "user", text = "attachment", participant = Participant.USER,
            images = listOf(phone.path), imageArtifacts = mapOf(phone.path to phone),
        )
        val generated = ordinary.copy(id = "assistant", participant = Participant.MODEL)
        val calls = listOf(call("phone", listOf(phone))) + names.mapIndexed { index, name ->
            call("other-$index", listOf(other), name)
        }
        val messages = listOf(ordinary, generated) + round("mixed", calls).pathMessages
        val projected = projectPhoneToolImages(messages, 0)

        assertSame(ordinary, projected[0])
        assertSame(generated, projected[1])
        assertEquals(emptyList<String>(), projected[3].images)
        calls.drop(1).indices.forEach { index ->
            assertSame(messages[index + 4], projected[index + 4])
        }
        val context = projectToolResultImagesToUserMessage(projected, true)
            .single { it.id.startsWith("image_context_") }
        assertEquals(listOf(other), context.projectedImageOccurrences().map { it.artifact })
        val withGenerated = projectAssistantImagesToLatestUserMessage(projected, true)
        assertTrue(withGenerated.first().images.contains(phone.path))
        assertEquals(messages, projectPhoneToolImages(messages, Int.MAX_VALUE))
    }

    @Test
    fun `same path slots in one mixed result keep independent descriptors and budget`() {
        val phone = image("same", width = 100)
        val other = phone.copy(width = 900)
        val phoneCall = call("phone", listOf(phone))
        val result = ChatMessage(
            id = "result_shared", text = "mixed", participant = Participant.USER,
            images = listOf(phone.path, other.path),
            segments = listOf(segment(phoneCall), segment(call("other", listOf(other), "file_view_image"))),
            toolCall = phoneCall,
            imageArtifacts = mapOf(phone.path to phone),
        )
        val projected = projectPhoneToolImages(listOf(result), 0).single()

        assertEquals(listOf(other.path), projected.images)
        assertEquals(listOf(other), projected.projectedImageOccurrences().map { it.artifact })
        assertEquals(emptyList<ImageArtifact>(), projected.toolCall!!.resultImages)
        assertEquals(emptyList<ImageArtifact>(), projected.segments!![0].toolImages)
        assertEquals(result.segments!![1], projected.segments!![1])
        assertEquals(listOf(other), projectToolResultImagesToUserMessage(listOf(projected), true)
            .last().projectedImageOccurrences().map { it.artifact })
        assertEquals(listOf(phone, other), result.projectedImageOccurrences().map { it.artifact })
    }

    @Test
    fun `legacy result images without segments and top level only images cannot revive`() {
        val pictures = listOf(image("legacy-1"), image("legacy-2"))
        val call = call("legacy", pictures).copy(toolCallId = null)
        val request = ChatMessage(id = "tool_legacy", text = "", participant = Participant.MODEL,
            toolCall = call)
        val result = ChatMessage(
            id = "result_legacy", parentId = request.id, text = "result", participant = Participant.USER,
            images = pictures.map { it.path }, toolCall = call.copy(resultImages = emptyList()),
            imageArtifacts = pictures.associateBy { it.path },
            attachmentMeta = AttachmentMeta(pictures.mapIndexed { index, image ->
                AttachmentItem(type = "image", imageIndex = index, imageArtifacts = listOf(image))
            }),
        )
        val messages = listOf(request, result)
        val projected = projectPhoneToolImages(messages, 1)

        assertEquals(listOf(pictures.last()), projected.first().toolCall!!.resultImages)
        assertEquals(listOf(pictures.last().path), projected.last().images)
        assertEquals(listOf(pictures.last().path), contextImages(projected))
        val zero = projectPhoneToolImages(messages, 0)
        assertTrue(zero.all { it.images.isEmpty() && it.toolCall!!.resultImages.isEmpty() })
        assertTrue(zero.all { it.imageArtifacts.isEmpty() })
        assertTrue(contextImages(zero).isEmpty())
        assertEquals(zero, projectPhoneToolImages(messages, -1))
        assertEquals(pictures.map { it.path }, result.images)
    }

    @Test
    fun `identical paths returned by distinct calls count as distinct Phone images`() {
        val screenshot = image("reused")
        val round = round("duplicates", listOf(
            call("a", listOf(screenshot)), call("b", listOf(screenshot)),
        ))
        val projected = projectPhoneToolImages(round.pathMessages, 1)

        assertTrue(projected[1].images.isEmpty())
        assertEquals(listOf(screenshot.path), projected[2].images)
        assertTrue(projected[0].toolCall!!.resultImages.isEmpty())
        assertEquals(listOf(screenshot.path), contextImages(projected))
    }

    @Test
    fun `null call ids pair each result occurrence with its ordered request call`() {
        val calls = listOf(call("a", listOf(image("a"))).copy(toolCallId = null),
            call("b", listOf(image("b"))).copy(toolCallId = null))
        val round = round("null-ids", calls)
        val replay = projectProviderMessages(round.entities, includeStoredTranscriptions = false)
        val projected = projectPhoneToolImages(replay, 1)

        assertTrue(projected[0].segments!!.first().toolImages.isEmpty())
        assertEquals(calls[1].resultImages, projected[0].segments!!.last().toolImages)
        assertEquals(calls[1].resultImages, projected[0].toolCall!!.resultImages)
        assertTrue(projected[1].images.isEmpty())
        assertEquals(listOf(calls[1].resultImages.single().path), projected[2].images)
    }

    @Test
    fun `continuation recomputes the budget from immutable history and new tool results`() {
        val history = round("history", listOf(call("old", listOf(image("old"))))).pathMessages
        val first = projectPhoneToolImages(history, 1)
        assertEquals(listOf(image("old").path), contextImages(first))
        val continuation = history + round("next", listOf(call("new", listOf(image("new")))))
            .pathMessages
        val next = projectPhoneToolImages(continuation, 1)

        assertEquals(listOf(image("new").path), contextImages(next))
        assertTrue(next[1].images.isEmpty())
        assertEquals(listOf(image("old").path), history[1].images)
        assertEquals(listOf(image("old").path, image("new").path),
            contextImages(projectPhoneToolImages(continuation, 2)))
        assertEquals(next, projectPhoneToolImages(first + continuation.drop(history.size), 1))
    }

    @Test
    fun `each occurrence in one call counts even when its path and descriptor repeat`() {
        val screenshot = image("repeated")
        val messages = round("repeated", listOf(call("one", List(3) { screenshot }))).pathMessages
        val projected = projectPhoneToolImages(messages, 2)

        assertEquals(List(2) { screenshot.path }, projected[1].images)
        assertEquals(List(2) { screenshot }, projected[0].segments!!.single().toolImages)
        assertEquals(List(2) { screenshot }, projected[1].toolCall!!.resultImages)
        assertEquals(3, messages[1].images.size)
    }

    @Test
    fun `all registered built in Phone tools share the budget without prefix matching`() {
        val calls = com.newoether.agora.phone.PHONE_TOOL_NAMES.mapIndexed { index, name ->
            call("phone-$index", listOf(image("phone-$index")), name)
        }
        val projected = projectPhoneToolImages(round("all-phone", calls).pathMessages, 0)

        assertTrue(projected.all { it.images.isEmpty() })
        assertTrue(projected.all { message -> message.segments.orEmpty().all { it.toolImages.isEmpty() } })
        assertTrue(projected.all { it.toolCall!!.resultImages.isEmpty() })
    }

    @Test
    fun `unrelated messages and an empty history preserve their original objects`() {
        val messages = listOf(ChatMessage(id = "user", text = "hello", participant = Participant.USER))
        assertSame(messages, projectPhoneToolImages(messages, 0))
        val empty = emptyList<ChatMessage>()
        assertSame(empty, projectPhoneToolImages(empty, 0))
    }

    private fun contextImages(messages: List<ChatMessage>): List<String> =
        projectToolResultImagesToUserMessage(messages, true)
            .filter { it.id.startsWith("image_context_") }.flatMap { it.images }

    private fun image(name: String, width: Int = 100) = ImageArtifact(
        path = "/private/$name.png", mimeType = "image/png", sizeBytes = 80L,
        width = width, height = 200, policyVersion = 1,
    )

    private fun call(id: String, images: List<ImageArtifact>, name: String = "phone_screenshot") =
        ToolCallData(name, "{}", "result:$id", signature = "signature:$id", toolCallId = id,
            resultImages = images, resultText = "display:$id", structuredResult = "{}")

    private fun segment(call: ToolCallData) = MessageSegment(
        type = "tool", toolName = call.toolName, toolArgs = call.arguments,
        toolResult = call.result, toolCallId = call.toolCallId, signature = call.signature,
        signatureProvider = "provider", toolImages = call.resultImages,
        toolResultText = call.resultText, toolStructuredResult = call.structuredResult,
    )

    private fun round(
        id: String,
        calls: List<ToolCallData>,
        completedSegments: Boolean = true,
    ): PersistableToolRound {
        var next = 0
        return GenerationToolRoundBuilder(newId = { "$id-${next++}" }, nowMs = { 100L }).build(
            previousMessageId = "parent", conversationId = "conversation", runId = "run-$id",
            modelName = "model", providerName = "provider", calls = calls,
            completedSegments = if (completedSegments) calls.map(::segment) else emptyList(),
        )
    }
}
