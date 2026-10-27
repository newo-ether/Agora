package com.newoether.agora.api.util

import android.app.Application
import com.newoether.agora.api.OpenAiContentPart
import com.newoether.agora.api.OpenAiImageUrl
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

@RunWith(ImageWireTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageConverterTest {

    @Test
    fun buildToolCallId_deterministic() {
        val id1 = buildToolCallId("read_file", """{"path":"/test"}""")
        val id2 = buildToolCallId("read_file", """{"path":"/test"}""")
        assertEquals(id1, id2)
    }

    @Test
    fun buildToolCallId_prefixApplied() {
        val id = buildToolCallId("my_tool", "{}", "pre_")
        assertTrue(id.startsWith("pre_my_tool_"))
    }

    @Test
    fun limitContext_emptyList() {
        val result = limitContext(emptyList(), 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun limitContext_respectsEstimatedTokenBudget() {
        val msgs = (1..10).map { i ->
            ChatMessage(
                id = "msg$i",
                parentId = null,
                text = "message-$i " + "payload ".repeat(40),
                participant = if (i % 2 == 0) Participant.MODEL else Participant.USER
            )
        }
        val result = limitContext(msgs, 140)
        val userCount = result.count { it.participant == Participant.USER }
        assertEquals(1, userCount)
        assertEquals(listOf("msg9", "msg10"), result.map { it.id })
    }

    @Test
    fun limitContext_includesToolMessages() {
        val msgs = listOf(
            ChatMessage(id = "u1", parentId = null, text = "user1", participant = Participant.USER),
            ChatMessage(id = "m1", parentId = "u1", text = "resp1", participant = Participant.MODEL),
            ChatMessage(id = Constants.TOOL_MSG_PREFIX + "t1", parentId = "m1", text = "",
                participant = Participant.MODEL),
            ChatMessage(id = Constants.RESULT_MSG_PREFIX + "r1", parentId = "t1", text = "result",
                participant = Participant.MODEL),
            ChatMessage(id = "m2", parentId = "r1", text = "resp2", participant = Participant.MODEL),
            ChatMessage(id = "u2", parentId = "m2", text = "user2", participant = Participant.USER),
            ChatMessage(id = "m3", parentId = "u2", text = "resp3", participant = Participant.MODEL)
        )
        val result = limitContext(msgs, 20)
        assertEquals(2, result.size)
        assertEquals("u2", result[0].id)
        assertEquals("m3", result[1].id)
    }
    @Test
    fun limitContextCountsOrdinaryReasoningOnlyWhenRequested() {
        val anchor = ChatMessage(
            id = "u1",
            text = "question",
            participant = Participant.USER,
        )
        val older = ChatMessage(
            id = "m1",
            text = "answer",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(type = "thought", content = "reasoning ".repeat(80)),
            ),
        )
        val latest = ChatMessage(
            id = "u2",
            text = "latest",
            participant = Participant.USER,
        )
        val messages = listOf(anchor, older, latest)
        val budget = ContextTokenEstimator.estimate(messages)
        assertEquals(
            listOf("u1", "m1", "u2"),
            limitContext(messages, budget).map(ChatMessage::id),
        )
        assertEquals(
            listOf("u2"),
            limitContext(
                messages,
                budget,
                includeAssistantReasoning = true,
            ).map(ChatMessage::id),
        )
    }

    @Test
    fun convertToOpenAiMessages_systemPrompt() {
        val msgs = listOf(
            ChatMessage(id = "u1", parentId = null, text = "hello", participant = Participant.USER)
        )
        val result = convertToOpenAiMessages(
            msgs,
            "You are helpful",
            base64Files = Base64FileRegistry(),
        )
        assertEquals("system", result.first().role)
        assertEquals("You are helpful", result.first().content!!.first().text)
    }

    @Test
    fun convertToOpenAiMessages_userAndModelRoles() {
        val msg = ChatMessage(id = "u1", text = "hello", participant = Participant.USER)
        val result = convertToOpenAiMessages(listOf(msg), base64Files = Base64FileRegistry())
        assertEquals("user", result.first().role)

        val modelMsg = ChatMessage(id = "m1", text = "response", participant = Participant.MODEL)
        val result2 = convertToOpenAiMessages(listOf(modelMsg), base64Files = Base64FileRegistry())
        assertEquals("assistant", result2.first().role)
    }

    @Test
    fun convertToOpenAiMessages_includeImagesFalse() {
        val msg = ChatMessage(
            id = "u1", text = "look at this",
            images = listOf("/nonexistent/image.jpg"),
            participant = Participant.USER
        )
        val result = convertToOpenAiMessages(
            listOf(msg),
            includeImages = false,
            base64Files = Base64FileRegistry(),
        )
        assertEquals(1, result.first().content!!.size) // only text, no image
        assertEquals("text", result.first().content!!.first().type)
    }

    @Test
    fun convertToOpenAiMessages_usesArtifactMimeBytesAndAutoDetailForUriReferences() {
        withMeasuredPng { image, artifact ->
            val expected = image.readBytes()
            for (reference in listOf(image.absolutePath, image.toURI().toString(), "content://agora-test/image")) {
                val registry = Base64FileRegistry()
                val message = ChatMessage(
                    text = "inspect", participant = Participant.USER,
                    images = listOf(reference), imageArtifacts = mapOf(reference to artifact),
                )
                val result = convertToOpenAiMessages(listOf(message), base64Files = registry)
                assertEquals("auto", result.single().content!!.single { it.type == "image_url" }.imageUrl!!.detail)
                val template = Json.encodeToString(result)
                val request = checkNotNull(registry.prepare(template))
                val encoded = Buffer().also(request.body::writeTo).readUtf8()
                val imageUrl = Json.parseToJsonElement(encoded).jsonArray.single().jsonObject
                    .getValue("content").jsonArray.single { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" }
                    .jsonObject.getValue("image_url").jsonObject
                val uri = imageUrl.getValue("url").jsonPrimitive.content
                assertTrue(uri.startsWith("data:image/png;base64,"))
                assertEquals("auto", imageUrl.getValue("detail").jsonPrimitive.content)
                assertArrayEquals(expected, Base64.getDecoder().decode(uri.substringAfter(";base64,")))
                assertArrayEquals(expected, image.readBytes())
            }
        }
    }

    @Test
    fun convertToOpenAiMessages_silentlyOmitsUnavailableAndBlankImagesAndKeepsText() {
        withMeasuredPng { _, artifact ->
            val message = ChatMessage(
                text = "inspect", participant = Participant.USER,
                images = listOf(artifact.path), imageArtifacts = mapOf(artifact.path to artifact),
            )
            val blank = artifact.copy(path = "", unavailable = true)
            for (invalid in listOf(
                message.copy(imageArtifacts = emptyMap()),
                message.copy(imageArtifacts = mapOf(artifact.path to artifact.copy(unavailable = true))),
                message.copy(images = listOf(""), imageArtifacts = mapOf("" to blank)),
                message.copy(images = listOf(""), imageArtifacts = mapOf("" to artifact)),
                message.copy(images = listOf(" "), imageArtifacts = mapOf(" " to artifact)),
            )) {
                val registry = Base64FileRegistry()
                val result = convertToOpenAiMessages(listOf(invalid), base64Files = registry)
                assertEquals(listOf("inspect"), result.single().content!!.map { it.text })
                assertNull(registry.prepare(Json.encodeToString(result)))
            }
        }
    }

    @Test
    fun convertToOpenAiMessages_mixedBrokenAndReadableImagesKeepOnlyReadableOccurrences() {
        withMeasuredPng { image, artifact ->
            val broken = artifact.copy(path = File(image.parentFile, "missing-${image.name}").absolutePath)
            val registry = Base64FileRegistry()
            val input = ChatMessage(
                text = "keep this text", participant = Participant.USER,
                images = listOf(broken.path, artifact.path, broken.path, artifact.path),
                imageArtifacts = mapOf(artifact.path to artifact, broken.path to broken),
            )
            val result = convertToOpenAiMessages(listOf(input), base64Files = registry)
            val request = checkNotNull(registry.prepare(Json.encodeToString(result)))
            val body = Buffer().also(request.body::writeTo).readUtf8()
            val parts = Json.parseToJsonElement(body).jsonArray.single().jsonObject.getValue("content").jsonArray
            assertEquals(3, parts.size)
            assertEquals("keep this text", parts.first().jsonObject.getValue("text").jsonPrimitive.content)
            assertFalse(body.contains("unavailable", ignoreCase = true))
            assertFalse(body.contains("__AGORA_BASE64_"))
        }
    }

    private fun withMeasuredPng(test: (File, ImageArtifact) -> Unit) {
        val image = File.createTempFile("agora-converter-image-", ".jpg")
        try {
            val pixels = BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB)
            pixels.setRGB(3, 2, 0x345678)
            assertTrue(ImageIO.write(pixels, "png", image))
            val artifact = ImageArtifact(
                path = image.absolutePath, mimeType = "image/png", sizeBytes = image.length(),
                width = pixels.width, height = pixels.height,
                policyVersion = 1,
            )
            test(image, artifact)
        } finally {
            image.delete()
        }
    }

    @Test
    fun convertToOpenAiMessages_emptyTextUsesNeutralPartWithoutAnImageWarning() {
        val msg = ChatMessage(id = "u1", text = "", participant = Participant.USER)
        val result = convertToOpenAiMessages(listOf(msg), base64Files = Base64FileRegistry())
        assertEquals(1, result.first().content!!.size)
        assertEquals("Continue", result.first().content!!.first().text)
    }

    @Test
    fun projectAssistantImagesToLatestUserMessage_movesLatestGeneratedImageContext() {
        val messages = listOf(
            ChatMessage(id = "u1", text = "draw this", participant = Participant.USER),
            ChatMessage(id = "m1", text = "done", images = listOf("old.jpg"), participant = Participant.MODEL),
            ChatMessage(id = "m2", text = "follow-up answer", participant = Participant.MODEL),
            ChatMessage(id = "u2", text = "what is in it?", images = listOf("user.jpg"), participant = Participant.USER)
        )

        val result = projectAssistantImagesToLatestUserMessage(messages, includeImages = true)

        assertTrue(result[1].images.isEmpty())
        assertEquals(listOf("old.jpg", "user.jpg"), result[3].images)
        assertTrue(result[3].text.contains("generated by the assistant"))
        assertTrue(result[3].text.endsWith("what is in it?"))
    }

    @Test
    fun projectAssistantImagesToLatestUserMessage_usesMostRecentGeneratedImageSet() {
        val messages = listOf(
            ChatMessage(id = "u1", text = "draw first", participant = Participant.USER),
            ChatMessage(id = "m1", text = "first", images = listOf("first.jpg"), participant = Participant.MODEL),
            ChatMessage(id = "u2", text = "draw second", participant = Participant.USER),
            ChatMessage(id = "m2", text = "second", images = listOf("second-a.jpg", "second-b.jpg"), participant = Participant.MODEL),
            ChatMessage(id = "u3", text = "compare details", participant = Participant.USER)
        )

        val result = projectAssistantImagesToLatestUserMessage(messages, includeImages = true)

        assertTrue(result[1].images.isEmpty())
        assertTrue(result[3].images.isEmpty())
        assertEquals(listOf("second-a.jpg", "second-b.jpg"), result[4].images)
        assertTrue(result[4].text.contains("first 2 attached images"))
    }

    @Test
    fun projectAssistantImagesToLatestUserMessage_includeImagesFalse_doesNotInjectContext() {
        val messages = listOf(
            ChatMessage(id = "u1", text = "draw this", participant = Participant.USER),
            ChatMessage(id = "m1", text = "done", images = listOf("generated.jpg"), participant = Participant.MODEL),
            ChatMessage(id = "u2", text = "continue", participant = Participant.USER)
        )

        val result = projectAssistantImagesToLatestUserMessage(messages, includeImages = false)

        assertTrue(result[1].images.isEmpty())
        assertTrue(result[2].images.isEmpty())
        assertEquals("continue", result[2].text)
    }

    @Test
    fun convertToOpenAiMessages_forwardAssistantReasoning_replaysStoredChainOfThought() {
        val messages = listOf(
            ChatMessage(id = "u1", text = "use a tool", participant = Participant.USER),
            ChatMessage(
                id = "m1",
                text = "final answer",
                participant = Participant.MODEL,
                segments = listOf(
                    MessageSegment(type = "thought", content = "step one"),
                    MessageSegment(type = "thought", content = "step two"),
                    MessageSegment(type = "answer", content = "final answer"),
                ),
            ),
        )

        val forwarded = convertToOpenAiMessages(
            messages,
            base64Files = Base64FileRegistry(),
            forwardAssistantReasoning = true,
        )
        val plain = convertToOpenAiMessages(messages, base64Files = Base64FileRegistry())

        assertEquals("step one\nstep two", forwarded.last().reasoningContent)
        assertNull(plain.last().reasoningContent)
        assertNull(forwarded.first().reasoningContent)
    }

    @Test
    fun convertToOpenAiMessages_forwardAssistantReasoning_omitsTurnsWithoutThought() {
        val messages = listOf(
            ChatMessage(id = "u1", text = "hello", participant = Participant.USER),
            ChatMessage(
                id = "m1",
                text = "answer",
                participant = Participant.MODEL,
                segments = listOf(MessageSegment(type = "answer", content = "answer")),
            ),
        )

        val result = convertToOpenAiMessages(
            messages,
            base64Files = Base64FileRegistry(),
            forwardAssistantReasoning = true,
        )

        assertNull(result.last().reasoningContent)
    }
}
