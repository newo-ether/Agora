package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.sandboxPastedAttachment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SandboxPastedComposerTest : ComposerControllerTestFixture() {
    @Before fun silenceLogs() {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } returns Unit
        every { DebugLog.w(any(), any(), any()) } returns Unit
        every { DebugLog.w(any(), any()) } returns Unit
    }
    @After fun restoreLogs() { unmockkObject(DebugLog) }

    @Test
    fun admissionStoresSourceBeforeStagingAndFailureKeepsTheExactOwner() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val gate = CompletableDeferred<Unit>()
        coEvery { processor.stage(any(), any()) } coAnswers {
            gate.await()
            AttachmentImportProcessor.StageResult.Failure(IllegalStateException("copy failed"))
        }
        val f = fixture(processor)
        f.controller.load(OWNER_A)
        val pasted = sandboxPastedAttachment("source😀".repeat(2000))
        assertTrue(f.controller.importAttachment(OWNER_A, pasted))
        assertEquals(pasted.sourceText, f.persistence.attachment(OWNER_A).sourceText)
        f.controller.load(OWNER_B)
        gate.complete(Unit)
        f.controller.awaitProcessing(OWNER_A)
        assertEquals(AttachmentImportState.FAILED, f.state(OWNER_A, pasted.localId).importState)
        assertEquals(pasted.sourceText, f.persistence.attachment(OWNER_A).sourceText)
        assertTrue(f.controller.state(OWNER_B).value.attachments.isEmpty())
        val frozen = f.controller.freezeSubmission(OWNER_A, 1, "", listOf(pasted.localId))!!
        f.controller.clearAccepted(OWNER_A, false, 1, frozen.revision, "", emptySet())
        assertEquals(pasted.sourceText, f.persistence.attachment(OWNER_A).sourceText)
    }

    @Test
    fun failedInitialDraftWriteKeepsRecoveryTileAndBlocksSendUntilSaved() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val f = fixture(processor)
        f.controller.load(OWNER_A)
        f.persistence.failWrites = true
        val pasted = sandboxPastedAttachment("complete source".repeat(800))
        assertTrue(f.controller.importAttachment(OWNER_A, pasted))
        assertEquals(AttachmentImportState.FAILED, f.state(OWNER_A, pasted.localId).importState)
        assertNull(f.controller.freezeSubmission(OWNER_A, 1, "", listOf(pasted.localId)))
        f.controller.release(OWNER_A)
        assertEquals(pasted.sourceText, f.controller.load(OWNER_A).single().sourceText)
        f.persistence.failWrites = false
        assertNotNull(f.controller.freezeSubmission(OWNER_A, 2, "", listOf(pasted.localId)))
        assertEquals(pasted.sourceText, f.persistence.attachment(OWNER_A).sourceText)
    }

    @Test
    fun stagingSourceDisappearsOnlyAfterTheReplacementDraftCommits() = runTest {
        val app = mockk<Application>()
        every { app.filesDir } returns temporaryFolder.root
        val processor = AttachmentImportProcessor(app)
        val f = fixture(processor)
        f.controller.load(OWNER_A)
        val pasted = sandboxPastedAttachment("UTF-8 source😀".repeat(1000))
        assertTrue(f.controller.importAttachment(OWNER_A, pasted))
        f.controller.awaitProcessing(OWNER_A)
        // No sandbox is available in this fixture: the staged file remains retryable.
        val failed = f.persistence.attachment(OWNER_A)
        assertEquals(AttachmentImportState.FAILED, failed.importState)
        assertNull(failed.sourceText)
        assertEquals(pasted.sourceText, java.io.File(failed.localPath!!).readText(Charsets.UTF_8))
    }
}
