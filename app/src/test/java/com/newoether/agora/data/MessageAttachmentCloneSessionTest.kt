package com.newoether.agora.data

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.AttachmentFiles
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MessageAttachmentCloneSessionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun eachMessageOwnsItsCopyWhileDuplicateReferencesInsideOneMessageAreReused() {
        val source = temporaryFolder.newFile("source.jpg").apply { writeText("image") }
        val destination = temporaryFolder.newFolder("copies")
        val meta = Json.encodeToString(
            AttachmentMeta(
                listOf(AttachmentItem(originalUri = "file://${source.absolutePath}", type = "image"))
            )
        )
        val message = message(source, meta)
        val session = MessageAttachmentCloneSession(destination)

        val first = session.cloneMessage(message, ownerKey = "first")
        val second = session.cloneMessage(message.copy(id = "second"), ownerKey = "second")
        session.commit()

        val firstMetaPath = Json.decodeFromString<AttachmentMeta>(checkNotNull(first.attachmentMeta))
            .items.single().originalUri!!.removePrefix("file://")
        assertEquals(first.images.single(), firstMetaPath)
        assertNotEquals(first.images.single(), second.images.single())
        assertTrue(File(first.images.single()).isFile)
        assertTrue(File(second.images.single()).isFile)
    }

    @Test
    fun rollbackDeletesOnlyFilesCreatedByTheSession() {
        val source = temporaryFolder.newFile("source.txt").apply { writeText("payload") }
        val destination = temporaryFolder.newFolder("rollback")
        val session = MessageAttachmentCloneSession(destination)
        val cloned = session.cloneMessage(message(source, null))

        session.rollback()

        assertTrue(source.isFile)
        assertFalse(File(cloned.images.single()).exists())
    }

    @Test
    fun imagesMetadataAndToolOccurrencesShareOneByteExactPrivateCopy() {
        val bytes = byteArrayOf(0, 1, 2, 127, -1)
        val source = temporaryFolder.newFile("recorded.png").apply { writeBytes(bytes) }
        val destination = temporaryFolder.newFolder("coherent")
        val artifact = artifact(source.path)
        val meta = Json.encodeToString(AttachmentMeta(listOf(
            AttachmentItem(type = "image", imageIndex = 0, originalUri = "file://${source.path}", imageArtifacts = listOf(artifact)),
        )))
        val segment = MessageSegment(
            type = "tool", toolCallId = "call", toolRoundId = "remapped-round", signature = "opaque",
            toolImages = listOf(artifact, artifact.copy(path = source.toURI().toString())),
        )
        val session = MessageAttachmentCloneSession(destination)
        try {
            val cloned = session.cloneMessage(message(source, meta).copy(
                images = listOf(source.path, source.toURI().toString()),
                toolCallJson = Json.encodeToString(listOf(segment)),
            ))
            val path = cloned.images.first()
            val clonedMeta = Json.decodeFromString<AttachmentMeta>(cloned.attachmentMeta!!).items.single()
            val clonedSegment = Json.decodeFromString<List<MessageSegment>>(cloned.toolCallJson!!).single()
            assertEquals(listOf(path, path), cloned.images)
            assertEquals(listOf(artifact.copy(path = path)), clonedMeta.imageArtifacts)
            assertEquals("file://$path", clonedMeta.originalUri)
            assertEquals(segment.copy(toolImages = List(2) { artifact.copy(path = path) }), clonedSegment)
            org.junit.Assert.assertArrayEquals(bytes, File(path).readBytes())
            assertEquals(1, destination.listFiles().orEmpty().size)
            assertFalse("Uncommitted copy has a live owner", AttachmentFiles.deleteIfUnowned(File(path)))
            session.commit()
            session.rollback()
            assertTrue(File(path).exists())
            assertTrue("Commit releases the clone owner", AttachmentFiles.deleteIfUnowned(File(path)))
            assertTrue(source.isFile)
        } finally {
            session.rollback()
        }
    }

    @Test
    fun missingToolImagesRemainOrderedSanitizedPlaceholders() {
        val source = temporaryFolder.newFile("present.png").apply { writeText("bytes") }
        val missing = artifact(File(temporaryFolder.root, "missing.png").path)
        val unavailable = missing.copy(path = source.path, unavailable = true, width = 99)
        val session = MessageAttachmentCloneSession(temporaryFolder.newFolder("missing"))
        try {
            val cloned = session.cloneMessage(message(source, null).copy(
                images = emptyList(), toolCallJson = Json.encodeToString(listOf(
                    MessageSegment(type = "tool", toolImages = listOf(missing, unavailable, missing)),
                )),
            ))
            val images = Json.decodeFromString<List<MessageSegment>>(cloned.toolCallJson!!).single().toolImages
            assertEquals(listOf(missing, unavailable, missing).map { it.asUnavailablePlaceholder() }, images)
            assertFalse(cloned.toolCallJson!!.contains(temporaryFolder.root.path.replace("\\", "\\\\")))
        } finally {
            session.rollback()
        }
    }

    @Test
    fun legacyOwnedFileProviderImageReusesItsAbsolutePathCopy() {
        val root = temporaryFolder.newFolder("private")
        val images = File(root, "images").apply { mkdirs() }
        val source = File(images, "old image.png").apply { writeText("original bytes") }
        val contentUri = "content://com.newoether.agora.fileprovider/images/old%20image.png"
        val session = MessageAttachmentCloneSession(File(root, "fork-attachments"))
        try {
            val cloned = session.cloneMessage(message(source, null).copy(images = listOf(source.path, contentUri)))
            assertEquals(cloned.images.first(), cloned.images.last())
            assertNotEquals(source.path, cloned.images.first())
            assertEquals("original bytes", File(cloned.images.first()).readText())
        } finally {
            session.rollback()
        }
    }

    @Test
    fun malformedToolPayloadRollsBackEarlierCopiesAndReleasesLiveOwnership() {
        val source = temporaryFolder.newFile("tool-rollback.png").apply { writeText("bytes") }
        val destination = temporaryFolder.newFolder("failed-tools")
        val session = MessageAttachmentCloneSession(destination)
        val result = runCatching { session.cloneMessage(message(source, null).copy(toolCallJson = "not-json")) }
        val copied = destination.listFiles().orEmpty().single()
        assertTrue(result.isFailure)
        session.rollback()
        assertFalse(copied.exists())
        assertTrue(source.exists())
        assertTrue(AttachmentFiles.releaseLivePaths(session).isEmpty())
    }

    private fun artifact(path: String) = ImageArtifact(
        path = path, mimeType = "image/png", sizeBytes = 5, width = 320, height = 240,
        policyVersion = 1,
    )

    private fun message(source: File, meta: String?) = MessageEntity(
        id = "message",
        conversationId = "conversation",
        text = "attachment",
        images = listOf(source.absolutePath),
        status = MessageStatus.SUCCESS,
        participant = Participant.USER,
        timestamp = 0,
        attachmentMeta = meta,
        runId = "run",
        runSequence = 0,
    )
}
