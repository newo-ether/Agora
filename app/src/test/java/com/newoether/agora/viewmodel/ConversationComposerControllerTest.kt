package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.AttachmentFiles
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ConversationComposerControllerTest : ComposerControllerTestFixture() {
    @Test
    fun appendingAnAuthoringRequestLoadsAndPreservesTheExactExistingOwnerDraft() = runTest {
        val fixture = fixture(mockk<AttachmentImportProcessor>(), initial = mapOf(
            OWNER_A to draft("Existing user text"), OWNER_B to draft("Other conversation")))
        fixture.controller.load(OWNER_A)
        val appended = fixture.controller.appendText(OWNER_A, "Create a Skill")
        assertEquals("Existing user text\n\nCreate a Skill", appended)
        assertTrue(fixture.controller.persistText(OWNER_A, appended))
        assertEquals(appended, fixture.controller.state(OWNER_A).value.text)
        assertEquals(0, fixture.persistence.loadCount(OWNER_B))
        fixture.controller.release(OWNER_A)
        assertEquals("Other conversation", fixture.controller.load(OWNER_B).text)
    }

    @Test
    fun `two owners keep independent processing state across selection changes`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val gates = mapOf(
            "a" to CompletableDeferred<Unit>(),
            "b" to CompletableDeferred<Unit>(),
        )
        coEvery { processor.stage(any(), any()) } coAnswers {
            val source = firstArg<SelectedAttachment>()
            AttachmentImportProcessor.StageResult.Success(
                attachment = source.processing("/stage/${source.localId}"),
                createdPaths = emptyList(),
            )
        }
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            val staged = firstArg<SelectedAttachment>()
            gates.getValue(staged.localId).await()
            AttachmentImportProcessor.ProcessResult.Ready(staged.ready())
        }
        val fixture = fixture(processor)
        fixture.controller.load(OWNER_A)
        fixture.controller.load(OWNER_B)

        fixture.controller.importAttachment(OWNER_A, attachment("a"))
        fixture.controller.importAttachment(OWNER_B, attachment("b"))
        runCurrent()

        assertEquals(AttachmentImportState.PROCESSING, fixture.state(OWNER_A, "a").importState)
        assertEquals(AttachmentImportState.PROCESSING, fixture.state(OWNER_B, "b").importState)
        gates.getValue("b").complete(Unit)
        fixture.controller.awaitProcessing(OWNER_B)
        assertEquals(AttachmentImportState.READY, fixture.state(OWNER_B, "b").importState)
        assertEquals(AttachmentImportState.PROCESSING, fixture.state(OWNER_A, "a").importState)

        gates.getValue("a").complete(Unit)
        fixture.controller.awaitProcessing(OWNER_A)
        assertEquals(AttachmentImportState.READY, fixture.state(OWNER_A, "a").importState)
    }

    @Test
    fun `controller startup stays dormant and exact load restores only that owner`() = runTest {
        val ordinary = attachment("ordinary").processing("/stage/ordinary")
        val unopened = attachment("unopened").processing("/stage/unopened")
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            AttachmentImportProcessor.ProcessResult.Ready(
                firstArg<SelectedAttachment>().ready(),
            )
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(
                OWNER_A to draft("ordinary text", ordinary),
                OWNER_B to draft("unopened text", unopened),
            ),
        )

        runCurrent()
        assertEquals(0, fixture.persistence.loadCount(OWNER_A))
        assertEquals(0, fixture.persistence.loadCount(OWNER_B))
        coVerify(exactly = 0) { processor.process(any(), any(), any()) }

        fixture.controller.load(OWNER_A)
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(AttachmentImportState.READY, fixture.state(OWNER_A, ordinary.localId).importState)
        assertEquals(AttachmentImportState.READY, fixture.persistence.attachment(OWNER_A).importState)
        assertEquals(1, fixture.persistence.loadCount(OWNER_A))
        assertEquals(0, fixture.persistence.loadCount(OWNER_B))
        assertEquals(AttachmentImportState.PROCESSING, fixture.persistence.attachment(OWNER_B).importState)
        coVerify(exactly = 1) { processor.process(match { it.localId == ordinary.localId }, any(), any()) }
        coVerify(exactly = 0) { processor.process(match { it.localId == unopened.localId }, any(), any()) }
    }

    @Test
    fun `active job and command retain one session until both finish`() = runTest {
        val processing = attachment("active").processing("/stage/active")
        val processingStarted = CompletableDeferred<Unit>()
        val finishProcessing = CompletableDeferred<Unit>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(processing, any(), any()) } coAnswers {
            processingStarted.complete(Unit)
            finishProcessing.await()
            AttachmentImportProcessor.ProcessResult.Ready(processing.ready())
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(processing))),
        )
        fixture.controller.load(OWNER_A)
        processingStarted.await()
        val awaiting = launch { fixture.controller.awaitProcessing(OWNER_A) }
        runCurrent()

        fixture.controller.release(OWNER_A)
        fixture.controller.load(OWNER_A)
        assertEquals(1, fixture.persistence.loadCount(OWNER_A))
        assertEquals(AttachmentImportState.PROCESSING, fixture.state(OWNER_A, "active").importState)
        fixture.controller.release(OWNER_A)

        finishProcessing.complete(Unit)
        awaiting.join()
        advanceUntilIdle()
        assertEquals(AttachmentImportState.READY, fixture.persistence.attachment(OWNER_A).importState)

        val reloaded = fixture.controller.load(OWNER_A)
        assertEquals(2, fixture.persistence.loadCount(OWNER_A))
        assertEquals(AttachmentImportState.READY, reloaded.attachments.single().importState)
    }

    @Test
    fun `idle release evicts session and unopened commands fail closed`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(text = "first")),
        )
        fixture.controller.load(OWNER_A)
        fixture.controller.release(OWNER_A)
        fixture.persistence.setDraft(OWNER_A, draft(text = "second"))

        val failure = runCatching {
            fixture.controller.updateText(OWNER_B, "must not admit")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, fixture.persistence.loadCount(OWNER_B))

        assertEquals("second", fixture.controller.load(OWNER_A).text)
        assertEquals(2, fixture.persistence.loadCount(OWNER_A))
    }

    @Test
    fun `failed load rolls back retain before a later admission`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val fixture = fixture(processor)
        fixture.persistence.failLoads = true

        val failure = runCatching { fixture.controller.load(OWNER_A) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)

        fixture.persistence.failLoads = false
        fixture.persistence.setDraft(OWNER_A, draft(text = "recovered"))
        assertEquals("recovered", fixture.controller.load(OWNER_A).text)
        assertEquals(2, fixture.persistence.loadCount(OWNER_A))
        fixture.controller.release(OWNER_A)
        assertTrue(runCatching { fixture.controller.state(OWNER_A) }.isFailure)
    }

    @Test
    fun `missing restored staged source becomes durable failed`() = runTest {
        val processing = attachment("missing").processing("/stage/missing")
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(processing, any(), any()) } returns
            AttachmentImportProcessor.ProcessResult.Failure(
                IllegalStateException("missing staged source"),
            )
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(processing))),
        )

        assertEquals(0, fixture.persistence.loadCount(OWNER_A))
        assertEquals(AttachmentImportState.PROCESSING, fixture.persistence.attachment(OWNER_A).importState)
        fixture.controller.load(OWNER_A)
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(AttachmentImportState.FAILED, fixture.state(OWNER_A, "missing").importState)
        assertEquals(AttachmentImportState.FAILED, fixture.persistence.attachment(OWNER_A).importState)
    }

    @Test
    fun `staging failure preserves private ingress source for retry`() = runTest {
        val privateSource = temporaryFolder.newFile("camera-source.jpg").apply {
            writeText("camera")
        }
        val source = attachment("private-retry").copy(localPath = privateSource.absolutePath)
        val processor = mockk<AttachmentImportProcessor>()
        var stageAttempts = 0
        coEvery { processor.stage(match { it.localId == source.localId }, any()) } coAnswers {
            stageAttempts += 1
            if (stageAttempts == 1) {
                AttachmentImportProcessor.StageResult.Failure(
                    IllegalStateException("staging interrupted"),
                )
            } else {
                AttachmentImportProcessor.StageResult.Success(
                    attachment = source.processing("/stage/private-retry"),
                    createdPaths = emptyList(),
                )
            }
        }
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            AttachmentImportProcessor.ProcessResult.Ready(
                firstArg<SelectedAttachment>().ready("/final/private-retry.jpg"),
            )
        }
        val fixture = fixture(processor)
        fixture.controller.load(OWNER_A)

        fixture.controller.importAttachment(OWNER_A, source)
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(AttachmentImportState.FAILED, fixture.state(OWNER_A, source.localId).importState)
        assertEquals(privateSource.absolutePath, fixture.persistence.attachment(OWNER_A).localPath)
        assertTrue(privateSource.isFile)
        coVerify(exactly = 0) {
            fixture.repository.deleteUnreferencedDraftAttachmentFiles(listOf(source))
        }

        assertTrue(fixture.controller.retry(OWNER_A, source.localId))
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(AttachmentImportState.READY, fixture.state(OWNER_A, source.localId).importState)
        coVerify(exactly = 2) { processor.stage(any(), any()) }
        coVerify(exactly = 1) {
            processor.process(match { it.localPath == "/stage/private-retry" }, any(), any())
        }
    }

    @Test
    fun `pdf metadata failure durably replaces private ingress source`() = runTest {
        val ingress = temporaryFolder.newFile("pdf-ingress.pdf").apply { writeText("ingress") }
        val failedStage = temporaryFolder.newFile("pdf-failed-stage.pdf").apply {
            writeText("staged")
        }
        val source = attachment("pdf-failure").copy(
            type = "pdf",
            fileName = "document.pdf",
            localPath = ingress.absolutePath,
        )
        val failed = source.copy(
            localPath = failedStage.absolutePath,
            importState = AttachmentImportState.FAILED,
        )
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.stage(match { it.localId == source.localId }, any()) } returns
            AttachmentImportProcessor.StageResult.Failure(
                cause = IllegalStateException("missing page count"),
                attachment = failed,
                createdPaths = listOf(failedStage.absolutePath),
            )
        val fixture = fixture(processor)
        fixture.controller.load(OWNER_A)

        fixture.controller.importAttachment(OWNER_A, source)
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(failed, fixture.persistence.attachment(OWNER_A))
        assertEquals(failed, fixture.state(OWNER_A, source.localId))
        assertTrue(failedStage.isFile)
        coVerify(exactly = 1) {
            fixture.repository.deleteUnreferencedDraftAttachmentFiles(
                match { removed ->
                    removed.singleOrNull()?.let {
                        it.localId == source.localId && it.localPath == source.localPath
                    } == true
                },
            )
        }
        coVerify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `failed attachment retry restages private source before processing`() = runTest {
        val retrySource = temporaryFolder.newFile("retry-source.jpg").apply {
            writeText("retry")
        }
        val failed = attachment("retry").copy(
            localPath = retrySource.absolutePath,
            importState = AttachmentImportState.FAILED,
        )
        val processor = mockk<AttachmentImportProcessor>()
        val stagedAttempts = mutableListOf<File>()
        coEvery { processor.stage(any(), any()) } coAnswers {
            val source = firstArg<SelectedAttachment>()
            val staged = File(
                temporaryFolder.root,
                "retry-stage-${stagedAttempts.size}.jpg",
            ).apply {
                AttachmentFiles.retainLivePath(secondArg<Any>(), absolutePath)
                writeText("restaged")
            }
            stagedAttempts += staged
            AttachmentImportProcessor.StageResult.Success(
                attachment = source.processing(staged.absolutePath),
                createdPaths = listOf(staged.absolutePath),
                obsoletePaths = listOf(retrySource.absolutePath),
            )
        }
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            AttachmentImportProcessor.ProcessResult.Ready(
                firstArg<SelectedAttachment>().ready(),
            )
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(failed))),
        )
        fixture.controller.load(OWNER_A)
        fixture.persistence.failWrites = true
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } just Runs

        try {
            assertTrue(fixture.controller.retry(OWNER_A, "retry"))
            fixture.controller.awaitProcessing(OWNER_A)
            assertEquals(AttachmentImportState.FAILED, fixture.state(OWNER_A, "retry").importState)
            assertEquals(AttachmentImportState.FAILED, fixture.persistence.attachment(OWNER_A).importState)
            assertEquals(1, stagedAttempts.size)
            assertFalse(stagedAttempts.single().exists())
            assertTrue(retrySource.isFile)
            fixture.persistence.failWrites = false
            assertTrue(fixture.controller.retry(OWNER_A, "retry"))
            fixture.controller.awaitProcessing(OWNER_A)
        } finally {
            unmockkObject(DebugLog)
        }

        assertEquals(
            listOf(AttachmentImportState.PROCESSING, AttachmentImportState.READY),
            fixture.persistence.updatedStates(OWNER_A),
        )
        assertEquals(AttachmentImportState.READY, fixture.state(OWNER_A, "retry").importState)
        coVerify(exactly = 2) { processor.stage(any(), any()) }
        coVerify(exactly = 1) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `removal during work cancels only the selected attachment`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val siblingStageGate = CompletableDeferred<Unit>()
        val siblingProcessGate = CompletableDeferred<Unit>()
        val targetProcessingStarted = CompletableDeferred<Unit>()
        val targetCancelled = CompletableDeferred<Unit>()
        coEvery { processor.stage(any(), any()) } coAnswers {
            val source = firstArg<SelectedAttachment>()
            if (source.localId == "sibling") siblingStageGate.await()
            AttachmentImportProcessor.StageResult.Success(
                attachment = source.processing("/stage/${source.localId}"),
                createdPaths = emptyList(),
            )
        }
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            val staged = firstArg<SelectedAttachment>()
            if (staged.localId == "target") {
                targetProcessingStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    targetCancelled.complete(Unit)
                }
            } else {
                siblingProcessGate.await()
                AttachmentImportProcessor.ProcessResult.Ready(staged.ready())
            }
        }
        val fixture = fixture(processor)
        fixture.controller.load(OWNER_A)
        fixture.controller.importAttachment(OWNER_A, attachment("target"))
        fixture.controller.importAttachment(OWNER_A, attachment("sibling"))
        targetProcessingStarted.await()

        assertEquals(listOf("target"), fixture.persistence.attachments(OWNER_A).map { it.localId })
        assertTrue(fixture.controller.remove(OWNER_A, "target"))
        targetCancelled.await()
        assertEquals(listOf("sibling"), fixture.controller.state(OWNER_A).value.ids())
        assertTrue(fixture.persistence.attachments(OWNER_A).isEmpty())
        assertEquals(AttachmentImportState.PROCESSING, fixture.state(OWNER_A, "sibling").importState)

        siblingStageGate.complete(Unit)
        runCurrent()
        siblingProcessGate.complete(Unit)
        fixture.controller.awaitProcessing(OWNER_A)
        assertEquals(AttachmentImportState.READY, fixture.state(OWNER_A, "sibling").importState)
        assertEquals(listOf("sibling"), fixture.persistence.attachments(OWNER_A).map { it.localId })
    }

    @Test
    fun `failed removal keeps attachment processing until a durable retry succeeds`() = runTest {
        val processing = attachment("keep").processing("/stage/keep")
        val gate = CompletableDeferred<Unit>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(processing, any(), any()) } coAnswers {
            gate.await()
            AttachmentImportProcessor.ProcessResult.Ready(processing.ready())
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(processing))),
        )
        fixture.controller.load(OWNER_A)
        runCurrent()
        fixture.persistence.failWrites = true
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } just Runs

        try {
            assertFalse(fixture.controller.remove(OWNER_A, "keep"))
            assertEquals(AttachmentImportState.PROCESSING, fixture.state(OWNER_A, "keep").importState)
            assertEquals(AttachmentImportState.PROCESSING, fixture.persistence.attachment(OWNER_A).importState)

            fixture.persistence.failWrites = false
            gate.complete(Unit)
            fixture.controller.awaitProcessing(OWNER_A)
            assertEquals(AttachmentImportState.READY, fixture.state(OWNER_A, "keep").importState)
            assertEquals(AttachmentImportState.READY, fixture.persistence.attachment(OWNER_A).importState)
        } finally {
            unmockkObject(DebugLog)
        }
    }

    @Test
    fun `stale completion deletes generated output after removal`() = runTest {
        val staged = attachment("stale").processing("/stage/stale")
        val generated = File(temporaryFolder.root, "stale-output.jpg")
        val gate = CompletableDeferred<Unit>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(staged, any(), any()) } coAnswers {
            val outputOwner = thirdArg<Any>()
            withContext(NonCancellable) {
                gate.await()
                AttachmentFiles.retainLivePath(outputOwner, generated.absolutePath)
                generated.writeText("generated")
                AttachmentImportProcessor.ProcessResult.Ready(
                    attachment = staged.ready(generated.absolutePath),
                    createdPaths = listOf(generated.absolutePath),
                )
            }
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(staged))),
        )
        fixture.controller.load(OWNER_A)
        runCurrent()

        assertTrue(fixture.controller.remove(OWNER_A, "stale"))
        gate.complete(Unit)
        fixture.controller.awaitProcessing(OWNER_A)

        assertFalse(generated.exists())
        assertTrue(fixture.controller.state(OWNER_A).value.attachments.isEmpty())
        assertTrue(fixture.persistence.attachments(OWNER_A).isEmpty())
    }

    @Test
    fun `cancellation after ready result keeps durably committed output`() = runTest {
        val staged = attachment("cancelled-ready").processing("/stage/cancelled-ready")
        val generated = File(temporaryFolder.root, "cancelled-ready.jpg")
        val ready = staged.ready(generated.absolutePath)
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(staged, any(), any()) } coAnswers {
            AttachmentFiles.retainLivePath(thirdArg<Any>(), generated.absolutePath)
            generated.writeText("generated")
            currentCoroutineContext().cancel()
            AttachmentImportProcessor.ProcessResult.Ready(
                attachment = ready,
                createdPaths = listOf(generated.absolutePath),
            )
        }
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(staged))),
        )

        fixture.controller.load(OWNER_A)
        fixture.controller.awaitProcessing(OWNER_A)

        assertTrue(generated.exists())
        assertEquals(ready, fixture.persistence.attachment(OWNER_A))
        assertEquals(ready, fixture.controller.state(OWNER_A).value.single())
    }

    @Test
    fun `text persistence updates only the exact owner revision`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val fixture = fixture(
            processor = processor,
            initial = mapOf(
                OWNER_A to draft(text = "owner a"),
                OWNER_B to draft(text = "owner b"),
            ),
        )
        fixture.controller.load(OWNER_A)
        fixture.controller.load(OWNER_B)

        fixture.controller.updateText(OWNER_A, "updated a")
        assertTrue(fixture.controller.persistText(OWNER_A, "updated a"))

        assertEquals("updated a", fixture.controller.state(OWNER_A).value.text)
        assertEquals(1L, fixture.controller.state(OWNER_A).value.revision)
        assertEquals("owner b", fixture.controller.state(OWNER_B).value.text)
        assertEquals(0L, fixture.controller.state(OWNER_B).value.revision)
        assertEquals("updated a", fixture.persistence.text(OWNER_A))
        assertEquals("owner b", fixture.persistence.text(OWNER_B))
    }

    @Test
    fun `text typed before async freeze is preserved after older acceptance`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(text = "sent text")),
        )
        fixture.controller.load(OWNER_A)
        fixture.controller.updateText(OWNER_A, "newer text")

        val frozen = checkNotNull(
            fixture.controller.freezeSubmission(
                ownerId = OWNER_A,
                requestId = 7L,
                text = "sent text",
                attachmentIds = emptyList(),
            ),
        )
        val result = fixture.controller.clearAccepted(
            ownerId = OWNER_A,
            submissionId = 7L,
            acceptedRevision = frozen.revision,
            acceptedText = frozen.text,
            acceptedAttachmentIds = emptySet(),
        )

        assertTrue(result.succeeded)
        assertEquals("sent text", frozen.text)
        assertEquals(0L, frozen.revision)
        assertEquals("newer text", fixture.controller.state(OWNER_A).value.text)
        assertEquals("newer text", fixture.persistence.text(OWNER_A))
    }

    @Test
    fun `attachment completion preserves the active text projection version`() = runTest {
        val source = attachment("projection")
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.stage(match { it.localId == source.localId }, any()) } returns
            AttachmentImportProcessor.StageResult.Success(
                attachment = source.processing("/stage/projection"),
                createdPaths = emptyList(),
            )
        coEvery { processor.process(any(), any(), any()) } coAnswers {
            AttachmentImportProcessor.ProcessResult.Ready(
                firstArg<SelectedAttachment>().ready(),
            )
        }
        val fixture = fixture(processor)
        fixture.controller.load(OWNER_A)
        fixture.controller.updateText(OWNER_A, "active input")

        fixture.controller.importAttachment(OWNER_A, source)
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals("active input", fixture.controller.state(OWNER_A).value.text)
        assertEquals(0L, fixture.controller.state(OWNER_A).value.textProjectionVersion)
        assertEquals(AttachmentImportState.READY, fixture.state(OWNER_A, source.localId).importState)
    }

    @Test
    fun `accepted clear rejects a stale debounced text write`() = runTest {
        val processor = mockk<AttachmentImportProcessor>()
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(text = "pending")),
        )
        fixture.controller.load(OWNER_A)
        fixture.controller.updateText(OWNER_A, "pending edit")

        val clearResult = fixture.controller.clearAccepted(OWNER_A)
        assertTrue(clearResult.succeeded)
        assertFalse(fixture.controller.persistText(OWNER_A, "pending edit"))

        assertEquals("", fixture.controller.state(OWNER_A).value.text)
        assertEquals(clearResult.revision, fixture.controller.state(OWNER_A).value.revision)
        assertEquals("", fixture.persistence.text(OWNER_A))
    }

    @Test
    fun `accepted clear synchronizes durable and visible owner state`() = runTest {
        val ready = attachment("accepted").ready("/final/accepted.jpg")
        val processor = mockk<AttachmentImportProcessor>()
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft("pending", ready)),
        )
        fixture.controller.load(OWNER_A)

        val result = fixture.controller.clearAccepted(OWNER_A)

        assertEquals(
            DraftClearResult(
                attachments = listOf(ready),
                revision = 1L,
                succeeded = true,
            ),
            result,
        )
        assertEquals(
            ConversationComposerSnapshot(
                revision = 1L,
                textProjectionVersion = 1L,
                loaded = true,
            ),
            fixture.controller.state(OWNER_A).value,
        )
        assertEquals("", fixture.persistence.text(OWNER_A))
        assertTrue(fixture.persistence.attachments(OWNER_A).isEmpty())
    }

    @Test
    fun `ready replacement leaves staged cleanup to durable draft persistence`() = runTest {
        val staged = attachment("ready").processing("/stage/ready")
        val ready = staged.ready("/final/ready.jpg")
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(staged, any(), any()) } returns
            AttachmentImportProcessor.ProcessResult.Ready(ready)
        val fixture = fixture(
            processor = processor,
            initial = mapOf(OWNER_A to draft(attachments = arrayOf(staged))),
        )

        fixture.controller.load(OWNER_A)
        fixture.controller.awaitProcessing(OWNER_A)

        assertEquals(ready, fixture.persistence.attachment(OWNER_A))
        coVerify(exactly = 0) {
            fixture.repository.deleteUnreferencedDraftAttachmentFiles(listOf(staged))
        }
    }
    @Test
    fun `producer output survives processing then transfers to Composer until scope closes`() = runTest {
        val source = temporaryFolder.newFile("image-staged.jpg")
        val output = File(temporaryFolder.root, "img_producer.jpg")
        val staged = attachment("image").processing(source.path)
        val finish = CompletableDeferred<Unit>()
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(staged, any(), any()) } coAnswers {
            AttachmentFiles.retainLivePath(thirdArg<Any>(), output.path)
            output.writeText("image")
            assertFalse(AttachmentFiles.deleteIfUnowned(output))
            finish.await()
            AttachmentImportProcessor.ProcessResult.Ready(staged.ready(output.path), listOf(output.path))
        }
        val fixture = fixture(processor, mapOf(OWNER_A to draft(attachments = arrayOf(staged))))
        fixture.controller.load(OWNER_A)
        runCurrent()
        assertTrue(output.exists())
        assertFalse(AttachmentFiles.deleteIfUnowned(output))
        finish.complete(Unit)
        fixture.controller.awaitProcessing(OWNER_A)
        assertEquals(output.path, fixture.state(OWNER_A, "image").localPath)
        assertFalse(AttachmentFiles.deleteIfUnowned(output))
        backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        runCurrent()
        assertTrue(AttachmentFiles.deleteIfUnowned(output))
        assertFalse(output.exists())
    }
    @Test
    fun `cancelled producer cleans unpublished output but not another live owner`() = runTest {
        val source = temporaryFolder.newFile("cancel-staged.jpg")
        val output = File(temporaryFolder.root, "img_cancelled.jpg")
        val other = Any()
        val staged = attachment("cancel").processing(source.path)
        val processor = mockk<AttachmentImportProcessor>()
        coEvery { processor.process(staged, any(), any()) } coAnswers {
            AttachmentFiles.retainLivePath(thirdArg<Any>(), output.path)
            output.writeText("image")
            AttachmentFiles.retainLivePath(other, output.path)
            awaitCancellation()
        }
        val fixture = fixture(processor, mapOf(OWNER_A to draft(attachments = arrayOf(staged))))
        try {
            fixture.controller.load(OWNER_A)
            runCurrent()
            assertTrue(fixture.controller.remove(OWNER_A, staged.localId))
            runCurrent()
            assertTrue(output.exists())
            AttachmentFiles.releaseLivePaths(other)
            assertTrue(AttachmentFiles.deleteIfUnowned(output))
            assertFalse(output.exists())
        } finally {
            AttachmentFiles.releaseLivePaths(other)
        }
    }
}
