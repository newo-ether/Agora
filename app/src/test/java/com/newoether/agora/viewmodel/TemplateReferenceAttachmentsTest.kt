package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.AttachmentFiles
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemplateReferenceAttachmentsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun selectedMarkdownAppearsAsAnOrdinaryAttachmentAndProjectsOnceToTheProvider() {
        val batch = TemplateReferenceAttachments { folder.root }
        batch.add("Medicine/Neurology/Migraines.md", "# Migraines\nSelected article")
        val attachment = batch.attachments.single()
        val payload = MessagePayloadBuilder().buildComposerPayload(batch.attachments)
        val metadata = payload.attachmentMeta!!
        assertEquals("Migraines.md", metadata.items.single().fileName)
        assertEquals("text/markdown", metadata.items.single().mimeType)
        assertTrue(File(attachment.localPath!!).isFile)
        val message = MessageEntity(id = "user", conversationId = "c", text = "Act as a meticulous examiner.",
            attachmentMeta = Json.encodeToString(metadata), status = MessageStatus.SUCCESS,
            participant = Participant.USER, timestamp = 1L, runId = "r")
        val projected = projectProviderMessages(listOf(message), false).single().text
        assertTrue(projected.startsWith(message.text))
        assertEquals(1, Regex("Selected article").findAll(projected).count())
        assertTrue(projected.contains("--- File: Migraines.md ---"))
        batch.handOffForSend()
        batch.close()
        assertTrue(File(attachment.localPath!!).exists())
    }
    @Test fun cancellingBeforeSendDeletesOnlyTheNewSnapshotAndReleasesItsLiveOwner() {
        val original = folder.newFile("Original.md").apply { writeText("Original article") }
        val batch = TemplateReferenceAttachments { folder.root }
        batch.add(original.name, original.readText())
        val copy = File(batch.attachments.single().localPath!!)
        assertFalse(AttachmentFiles.deleteIfUnowned(copy))
        batch.close()
        assertFalse(copy.exists())
        assertEquals("Original article", original.readText())
    }
}
