package com.newoether.agora.api.local

import android.app.Application
import android.content.Context
import com.newoether.agora.api.ChatTemplateMessage
import com.newoether.agora.api.util.ImageWireTestRunner
import com.newoether.agora.api.util.projectAssistantImagesToLatestUserMessage
import com.newoether.agora.api.util.projectToolResultImagesToUserMessage
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Exercises the production template/path boundary without loading the native model runtime. */
@RunWith(ImageWireTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalProviderImageRequestTest {
    @Test
    fun uriReferencesResolveToTheCompletedArtifactWithoutChangingBytes() {
        for ((format, suffix) in listOf("png" to ".jpg", "jpeg" to ".png")) {
            withImage(format, suffix) { image, artifact ->
                val original = image.readBytes()
                for (reference in listOf(image.absolutePath, image.toURI().toString(), "content://agora-test/image")) {
                    val paths = mutableListOf<String>()
                    val template = build(listOf(message(reference, artifact)), paths)
                    assertEquals(listOf(image.canonicalPath), paths)
                    assertEquals("user", template.single().role)
                    assertEquals("<__media__>\ninspect", template.single().content)
                    assertArrayEquals(original, File(paths.single()).readBytes())
                    assertArrayEquals(original, image.readBytes())
                }
            }
        }
    }

    @Test
    fun pagesFramesToolResultsAndLegacyAssistantImagesUseTheSameLocalArtifact() =
        withImage("jpeg", ".png") { image, artifact ->
            val sources = listOf("image", "pdf", "video").map { type ->
                listOf(message(artifact.path, artifact).copy(
                    imageArtifacts = emptyMap(),
                    attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                        type = type, imageIndex = 0, imageArtifacts = listOf(artifact),
                    ))),
                ))
            } + listOf(
                toolMessages(artifact),
                projectAssistantImagesToLatestUserMessage(
                    listOf(
                        ChatMessage(id = "draw", text = "draw", participant = Participant.USER),
                        message(artifact.path, artifact).copy(id = "generated", participant = Participant.MODEL),
                        ChatMessage(id = "follow-up", text = "inspect", participant = Participant.USER),
                    ), includeImages = true,
                ),
            )
            for (messages in sources) {
                val paths = mutableListOf<String>()
                val template = build(messages, paths)
                assertEquals(listOf(image.canonicalPath), paths)
                assertEquals(1, template.sumOf { Regex("<__media__>").findAll(it.content).count() })
                assertArrayEquals(image.readBytes(), File(paths.single()).readBytes())
            }
        }

    @Test
    fun invalidOnlyImagesAreSilentlyOmittedAndOrdinaryTextSurvives() = withImage("png", ".jpg") { image, artifact ->
        val corrupt = File.createTempFile("agora-local-corrupt-", ".png")
        val empty = File.createTempFile("agora-local-empty-", ".png")
        try {
            corrupt.writeBytes(image.readBytes().copyOf(33))
            val invalid = listOf(
                message(artifact.path, artifact).copy(imageArtifacts = emptyMap()),
                message(artifact.path, artifact.copy(unavailable = true)),
                message("", artifact), message(" ", artifact),
                message("", artifact.copy(path = "", unavailable = true)),
                message(File(image.parentFile, "wrong-${image.name}").absolutePath, artifact),
                message(corrupt.absolutePath, artifact.copy(path = corrupt.absolutePath)),
                message(empty.absolutePath, artifact.copy(path = empty.absolutePath)),
                message(artifact.path, artifact).copy(attachmentMeta = AttachmentMeta(listOf(
                    AttachmentItem(type = "image", imageIndex = 0, unavailable = true, imageArtifacts = listOf(artifact)),
                ))),
            )
            invalid.forEach { input ->
                val paths = mutableListOf<String>()
                val result = build(listOf(input), paths)
                assertTrue(paths.isEmpty())
                assertEquals("inspect", result.single().content)
            }
            val missing = artifact.copy(path = File(image.parentFile, "missing-${image.name}").absolutePath)
            val paths = mutableListOf<String>()
            build(toolMessages(missing), paths)
            assertTrue(paths.isEmpty())
        } finally {
            corrupt.delete()
            empty.delete()
        }
    }

    @Test
    fun mixedDuplicatePathsKeepOnlyReadableOccurrenceMarkersInOrder() = withImage("png", ".jpg") { file, artifact ->
        val missing = artifact.copy(path = File(file.parentFile, "missing-${file.name}").absolutePath)
        val input = message(artifact.path, artifact).copy(
            images = listOf(artifact.path, missing.path, artifact.path, artifact.path),
            imageArtifacts = emptyMap(),
            attachmentMeta = AttachmentMeta(listOf(artifact, missing, artifact.copy(unavailable = true), artifact)
                .mapIndexed { index, item -> AttachmentItem(type = "image", imageIndex = index, imageArtifacts = listOf(item)) }),
        )
        val paths = mutableListOf<String>()
        val template = build(listOf(input), paths)
        assertEquals(listOf(file.canonicalPath, file.canonicalPath), paths)
        assertEquals("<__media__>\n<__media__>\ninspect", template.single().content)
    }

    @Test
    fun readableReplacementAndStaleMetadataDoNotRejectTheCurrentImage() = withImage("png", ".jpg") { image, artifact ->
        assertTrue(ImageIO.write(BufferedImage(21, 13, BufferedImage.TYPE_INT_RGB), "jpeg", image))
        val paths = mutableListOf<String>()
        val template = build(listOf(message(artifact.path, artifact.copy(
            mimeType = "application/octet-stream", sizeBytes = 0, width = 3000, height = 1, policyVersion = 0,
        ))), paths)
        assertEquals(listOf(image.canonicalPath), paths)
        assertEquals("<__media__>\ninspect", template.single().content)
    }

    @Test
    fun mediaMarkersAndCanonicalPathsKeepImageOrderAndOccurrences() {
        withImage("png", ".jpg") { first, firstArtifact ->
            withImage("jpeg", ".png") { second, secondArtifact ->
                val paths = mutableListOf<String>()
                val input = ChatMessage(
                    text = "inspect", participant = Participant.USER,
                    images = listOf(first.absolutePath, second.absolutePath, first.absolutePath),
                    imageArtifacts = mapOf(first.absolutePath to firstArtifact, second.absolutePath to secondArtifact),
                )
                val template = build(listOf(input), paths)
                assertEquals(listOf(first.canonicalPath, second.canonicalPath, first.canonicalPath), paths)
                assertEquals("<__media__>\n<__media__>\n<__media__>\ninspect", template.single().content)
            }
        }
    }

    private fun toolMessages(artifact: ImageArtifact): List<ChatMessage> {
        val call = MessageSegment(type = "tool", toolName = "inspect_image", toolArgs = "{}", toolCallId = "call-image")
        return projectToolResultImagesToUserMessage(listOf(
            ChatMessage(id = "inspect", text = "inspect", participant = Participant.USER),
            ChatMessage(id = "tool_image", text = "", participant = Participant.MODEL, segments = listOf(call)),
            ChatMessage(id = "result_image", text = "image ready", participant = Participant.USER,
                images = listOf(artifact.path),
                segments = listOf(call.copy(toolResult = "image ready", toolImages = listOf(artifact)))),
        ), includeImages = true)
    }

    private fun message(reference: String, artifact: ImageArtifact) = ChatMessage(
        id = "visual-input", text = "inspect", participant = Participant.USER,
        images = listOf(reference), imageArtifacts = mapOf(reference to artifact),
    )

    private fun build(messages: List<ChatMessage>, paths: MutableList<String>): List<ChatTemplateMessage> {
        val provider = LocalProvider(mockk<Context>(), mockk<SettingsRepository>())
        val method = LocalProvider::class.java.getDeclaredMethod(
            "buildTemplateMessages", List::class.java, String::class.java, MutableList::class.java,
        ).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(provider, messages, null, paths) as List<ChatTemplateMessage>
    }

    private fun withImage(format: String, suffix: String, test: (File, ImageArtifact) -> Unit) {
        val image = File.createTempFile("agora-local-image-", suffix)
        try {
            val pixels = BufferedImage(9, 7, BufferedImage.TYPE_INT_RGB)
            pixels.setRGB(4, 3, 0x456789)
            assertTrue(ImageIO.write(pixels, format, image))
            test(image, ImageArtifact(
                path = image.absolutePath, mimeType = if (format == "jpeg") "image/jpeg" else "image/png",
                sizeBytes = image.length(), width = pixels.width, height = pixels.height, policyVersion = 1,
            ))
        } finally {
            image.delete()
        }
    }
}
