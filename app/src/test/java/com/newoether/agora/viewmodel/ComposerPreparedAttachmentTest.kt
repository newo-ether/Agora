package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import com.newoether.agora.model.SelectedAttachment
import io.mockk.*
import org.junit.Before
import org.junit.After
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class ComposerPreparedAttachmentTest : ComposerControllerTestFixture() {
    @Before fun setupLog() { mockkObject(DebugLog); every { DebugLog.e(any(), any(), any()) } just Runs }
    @After fun cleanupLog() { unmockkObject(DebugLog) }
    private fun ready(id: String = "receipt") = SelectedAttachment(localId = id, uri = "file:///notes/$id.zip",
        type = "file", localPath = "/notes/$id.zip", preparedText = "Task", notesDocumentId = "document")

    @Test fun `receipt replay is idempotent and preserves exact draft owner and newer text`() = runTest {
        val fixture = fixture(mockk(), mapOf(OWNER_A to draft("old text"), OWNER_B to draft("other draft")))
        fixture.controller.load(OWNER_A)
        fixture.controller.updateText(OWNER_A, "new text")
        assertTrue(fixture.controller.attachPrepared(OWNER_A, ready()))
        assertTrue(fixture.controller.attachPrepared(OWNER_A, ready()))
        assertEquals("new text", fixture.persistence.text(OWNER_A))
        assertEquals(1, fixture.persistence.attachments(OWNER_A).size)
        assertEquals("other draft", fixture.persistence.text(OWNER_B))
        assertEquals(0, fixture.persistence.loadCount(OWNER_B))
        assertFalse(fixture.controller.attachPrepared(OWNER_A, ready().copy(preparedText = "different revision")))
    }

    @Test fun `durable write failure leaves draft and retry intact`() = runTest {
        val fixture = fixture(mockk(), mapOf(OWNER_A to draft("keep")))
        fixture.controller.load(OWNER_A)
        fixture.persistence.failWrites = true
        assertFalse(fixture.controller.attachPrepared(OWNER_A, ready()))
        assertTrue(fixture.controller.state(OWNER_A).value.attachments.isEmpty())
        fixture.persistence.failWrites = false
        assertTrue(fixture.controller.attachPrepared(OWNER_A, ready()))
        assertEquals("keep", fixture.persistence.text(OWNER_A))
    }

    @Test fun `frozen submission refuses external attachment until released`() = runTest {
        val fixture = fixture(mockk(), mapOf(OWNER_A to draft("sending")))
        fixture.controller.load(OWNER_A)
        assertNotNull(fixture.controller.freezeSubmission(OWNER_A, 7, "sending", emptyList()))
        assertFalse(fixture.controller.attachPrepared(OWNER_A, ready()))
        fixture.controller.releaseSubmission(OWNER_A, 7)
        assertTrue(fixture.controller.attachPrepared(OWNER_A, ready()))
    }
    @Test fun `selected group attaches atomically and replays without duplication`() = runTest {
        val fixture=fixture(mockk(),mapOf(OWNER_A to draft("keep"),OWNER_B to draft("other")))
        fixture.controller.load(OWNER_A)
        val group=listOf(ready("first"),ready("second"))
        fixture.persistence.failWrites=true
        assertFalse(fixture.controller.attachPreparedMany(OWNER_A,group))
        assertTrue(fixture.controller.state(OWNER_A).value.attachments.isEmpty())
        fixture.persistence.failWrites=false
        assertTrue(fixture.controller.attachPreparedMany(OWNER_A,group))
        assertTrue(fixture.controller.attachPreparedMany(OWNER_A,group))
        assertEquals(2,fixture.persistence.attachments(OWNER_A).size)
        assertEquals("keep",fixture.persistence.text(OWNER_A))
        assertEquals(0,fixture.persistence.loadCount(OWNER_B))
    }
    @Test fun `one conflicting receipt rejects the whole selected group`() = runTest {
        val fixture=fixture(mockk(),mapOf(OWNER_A to draft("keep")))
        fixture.controller.load(OWNER_A)
        assertTrue(fixture.controller.attachPrepared(OWNER_A,ready("first")))
        assertFalse(fixture.controller.attachPreparedMany(OWNER_A,listOf(ready("first").copy(preparedText="conflict"),ready("second"))))
        assertEquals(1,fixture.persistence.attachments(OWNER_A).size)
    }

    @Test fun `copied files resume normal processing once and replay after preparation without resetting state`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val gate = CompletableDeferred<Unit>()
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            gate.await()
            AttachmentImportProcessor.ProcessResult.Ready(firstArg<SelectedAttachment>().copy(
                preparedText = "Prepared", importState = AttachmentImportState.READY))
        }
        val fixture = fixture(processor, mapOf(OWNER_A to draft("keep")))
        fixture.controller.load(OWNER_A)
        val copied = ready("copied").copy(importState = AttachmentImportState.PROCESSING, preparedText = null)
        assertTrue(fixture.controller.attachPreparedMany(OWNER_A, listOf(copied)))
        runCurrent()
        assertTrue(fixture.controller.attachPreparedMany(OWNER_A, listOf(copied)))
        gate.complete(Unit)
        fixture.controller.awaitProcessing(OWNER_A)
        assertTrue(fixture.controller.attachPreparedMany(OWNER_A, listOf(copied)))
        assertEquals("Prepared", fixture.persistence.attachment(OWNER_A).preparedText)
        assertEquals("keep", fixture.persistence.text(OWNER_A))
        coVerify(exactly = 1) { processor.process(any(), any(), any()) }
        coVerify(exactly = 0) { processor.stage(any(), any()) }
    }

    @Test fun `copied group write failure starts no processing and preserves retry`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val fixture = fixture(processor, mapOf(OWNER_A to draft("keep")))
        fixture.controller.load(OWNER_A)
        fixture.persistence.failWrites = true
        val group = listOf(ready("one"),ready("two")).map { it.copy(importState = AttachmentImportState.PROCESSING,preparedText = null) }
        assertFalse(fixture.controller.attachPreparedMany(OWNER_A,group))
        runCurrent()
        assertTrue(fixture.controller.state(OWNER_A).value.attachments.isEmpty())
        coVerify(exactly = 0) { processor.process(any(), any(), any()) }
    }

}
