package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.viewmodel.QueuedSend
import kotlinx.coroutines.sync.Mutex
import com.newoether.agora.viewmodel.ChatClients
import com.newoether.agora.viewmodel.ConversationComposerSnapshot
import com.newoether.agora.viewmodel.ConversationGenerationSnapshot
import com.newoether.agora.viewmodel.ConversationGenerationState
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ForegroundSendTarget
import com.newoether.agora.viewmodel.ForegroundSendAdmission
import com.newoether.agora.viewmodel.SendAcceptance
import com.newoether.agora.viewmodel.testGenerationAdmissionSnapshot
import com.newoether.agora.viewmodel.GenerationStopAdapter
import com.newoether.agora.viewmodel.MessageGenerationController
import com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
import com.newoether.agora.viewmodel.NewChatWorkspaceSnapshot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import android.app.Application
import com.newoether.agora.viewmodel.AttachmentImportProcessor
import com.newoether.agora.model.AttachmentImportState
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.FileValidator
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ChatEntity

class WebUiChatSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uploadDirectory by lazy { temporary.newFolder("private") }
    private var imageOutput: String? = null
    private var imageAttempts = 0
    private val videoConfigs = CopyOnWriteArrayList<com.newoether.agora.viewmodel.VideoSliceConfig>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val conversations = mockk<ConversationRepository>(relaxed = true) {
        every { observeConversation("a") } returns
            flowOf(ChatConversation(id = "a", title = "Alpha", modelId = "conversation-model"))
        every { observeMessageTopology(any()) } returns flowOf(emptyList())
        coEvery { getConversation(any()) } returns null
        coEvery { recoverConversationRuntime(any(), any()) } returns 0
    }
    private val state = mockk<ConversationGenerationState> {
        every { generationSnapshot } returns MutableStateFlow(ConversationGenerationSnapshot())
        every { generating } returns MutableStateFlow(false)
        every { stopping } returns MutableStateFlow(false)
        every { queuedSends } returns MutableStateFlow(emptyList())
        every { streamingMessage } returns MutableStateFlow(null)
        every { discardQueuedSend(any()) } just Runs
    }
    private val registry = mockk<ConversationStateRegistry> {
        every { getOrCreate(any()) } returns state
    }
    private val settings = mockk<SettingsRepository> {
        every { selectedModel } returns MutableStateFlow("default-model")
        every { enabledModels } returns MutableStateFlow(setOf("default-model", "conversation-model"))
        every { developerOptionsEnabled } returns MutableStateFlow(false)
        every { debugModelEnabled } returns MutableStateFlow(false)
        every { modelAliases } returns MutableStateFlow(emptyMap())
        every { modelProviderNames } returns MutableStateFlow(emptyMap())
        every { systemPrompts } returns MutableStateFlow(emptyList())
        every { activeSystemPromptId } returns MutableStateFlow(null)
        every { customProviders } returns MutableStateFlow(emptyList())
        every { conversationSettings } returns MutableStateFlow(emptyMap())
        every { codeExecutionEnabled } returns MutableStateFlow(false)
        every { googleSearchEnabled } returns MutableStateFlow(false)
        every { thinkingEnabled } returns MutableStateFlow(true)
        every { thinkingLevel } returns MutableStateFlow("medium")
        every { thinkingBudgetEnabled } returns MutableStateFlow(false)
        every { thinkingBudgetTokens } returns MutableStateFlow(4096)
        every { openAiServiceTierEnabled } returns MutableStateFlow(false)
        every { openAiServiceTier } returns MutableStateFlow("auto")
        every { openAiResponsesApiEnabled } returns MutableStateFlow(false)
        every { webSearchEnabled } returns MutableStateFlow(true)
        every { shellEnabled } returns MutableStateFlow(true)
        every { localLowContextModeEnabled } returns MutableStateFlow(false)
        every { maxContextWindow } returns MutableStateFlow(32768)
        every { defaultTemperature } returns MutableStateFlow(null)
        every { defaultMaxTokens } returns MutableStateFlow(null)
        every { defaultTopP } returns MutableStateFlow(null)
        every { defaultFrequencyPenalty } returns MutableStateFlow(null)
        every { defaultPresencePenalty } returns MutableStateFlow(null)
        every { contextCompactModel } returns MutableStateFlow(null)
        every { contextCompactPrompt } returns MutableStateFlow("summary")
        every { contextCompactRetainCount } returns MutableStateFlow(4)
        coEvery { awaitInitialLoad() } just Runs
    }

    /** Arguments of every send-target capture: owner, current id, New Chat mode, entry, model, workspace. */
    private val captures = CopyOnWriteArrayList<Capture>()
    private val prepared = CopyOnWriteArrayList<ConversationComposerSnapshot>()
    private val generation = mockk<MessageGenerationController> {
        every {
            captureForegroundSendTarget(any(), any(), any(), any(), any(), any())
        } answers {
            val ownerId = arg<String>(0)
            val newChat = ownerId == NEW_CHAT_WORKSPACE_ID
            val workspace = if (newChat) arg<() -> NewChatWorkspaceSnapshot>(5).invoke() else null
            captures += Capture(ownerId, arg(1), arg(2), arg(3), arg(4), workspace)
            ForegroundSendTarget(
                ownerId = ownerId,
                conversationId = if (newChat) "created" else ownerId,
                runId = "run",
                wasNewChat = newChat,
                newChatEntryId = arg<Long>(3).takeIf { newChat },
                modelId = arg(4),
                newChatWorkspace = workspace,
            )
        }
        // Admission is refused, so each submission ends right after preparation.
        coEvery { prepareForegroundSend(any(), any(), any()) } answers {
            prepared += secondArg<ConversationComposerSnapshot>()
            null
        }
    }
    private val generationStop = mockk<GenerationStopAdapter>(relaxed = true)
    private val clients = ChatClients()

    private val session by lazy { WebUiChatSession(
        generation = generation,
        generationStop = generationStop,
        clients = clients,
        conversations = conversations,
        registry = registry,
        executionCoordinator = ConversationExecutionCoordinator(),
        settings = settings,
        transfers = mockk<ConversationSettingsTransferCoordinator>(relaxed = true),
        attachmentProcessor = AttachmentImportProcessor(
            mockk<Application> { every { filesDir } returns uploadDirectory },
            normalizeImage = { _, owner ->
                imageAttempts++
                imageOutput?.also { AttachmentFiles.retainLivePath(requireNotNull(owner), it) }
            },
            measurePixels = { null },
            readPdfPageCount = { 3 },
            renderAllPdfPages = { _, _, progress, owner ->
                (0..2).map { index ->
                    File(uploadDirectory, "page-$index.jpg").also { file ->
                        AttachmentFiles.retainLivePath(requireNotNull(owner), file.absolutePath)
                        file.writeText("page $index")
                    }.absolutePath.also { progress?.invoke(index + 1, 3) }
                }
            },
            renderPdf = { _, pages, owner -> pages.orEmpty().sorted().map { index ->
                File(uploadDirectory, "selected-$index.jpg").also {
                    AttachmentFiles.retainLivePath(requireNotNull(owner), it.absolutePath)
                    it.writeText("selected $index")
                }.absolutePath
            } },
            readVideoDurationMs = { 15_000L },
            extractVideoFrames = { _, config, _ -> videoConfigs += config; emptyList() },
        ),
        scope = scope,
        uploadDirectory = uploadDirectory,
        compactFailureMessage = { it.reason.name },
    ) }

    @After
    fun tearDown() {
        scope.cancel()
    }
    @Test
    fun forkNavigationRequiresTheExactCurrentOpenTarget() = runBlocking {
        session.start()
        session.open("a", 1)
        val origin = session.openTarget.value
        session.open(null, 2)
        assertFalse(session.openForkIfCurrent("forked", origin))
        assertNull(session.openTarget.value.conversationId)
        session.open("a", 3)
        assertFalse(session.openForkIfCurrent("forked", origin))
        assertTrue(session.openForkIfCurrent("forked", session.openTarget.value))
        assertEquals(WebUiChatSession.OpenTarget("forked", 3L, true), session.openTarget.value)
        assertFalse(session.openForkIfCurrent("duplicate", origin))
    }
    @Test
    fun uploadUsesTheCanonicalFileImporterAndSendFreezesItsMembership() = runBlocking {
        session.start()
        awaitModel()
        assertEquals(HttpStatusCode.Accepted, session.upload(0, "notes.txt", "text/plain", null, 5, ByteReadChannel("hello".toByteArray())))
        val composer = withTimeout(TIMEOUT_MS) {
            session.composerState.first { it.attachments.singleOrNull()?.importState == AttachmentImportState.READY }
        }
        val attachment = composer.attachments.single()
        assertEquals("hello", attachment.preparedText)
        assertTrue(File(attachment.localPath!!).isFile)
        assertFalse(AttachmentFiles.deleteIfUnowned(File(attachment.localPath!!)))
        assertEquals("notes.txt", attachment.fileName)
        session.send("with file")
        withTimeout(TIMEOUT_MS) { while (prepared.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf(attachment.localId), prepared.single().attachments.map { it.localId })
        coVerify(exactly = 0) { conversations.updateDraft(any(), any(), any(), any()) }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        session.close()
        coVerify { conversations.deleteUnreferencedDraftAttachmentFiles(match { files -> files.any { it.localPath == attachment.localPath } }) }
        assertTrue(AttachmentFiles.deleteIfUnowned(File(attachment.localPath!!)))
    }
    @Test
    fun aSelectedOwnerSwitchCannotRedirectAnAdmittedUpload() = runBlocking {
        session.start()
        session.open("a", 1)
        val input = ByteChannel(autoFlush = true)
        val result = async { session.upload(1, "notes.txt", "text/plain", null, 5, input) }
        withTimeout(TIMEOUT_MS) { while (uploadDirectory.listFiles().orEmpty().isEmpty()) kotlinx.coroutines.delay(10) }
        session.open(null, 2)
        input.writeFully("hello".toByteArray())
        input.close()
        assertEquals(HttpStatusCode.Accepted, result.await())
        assertTrue(session.composerState.first { it.seq == 2L }.attachments.isEmpty())
        session.open("a", 3)
        val original = withTimeout(TIMEOUT_MS) {
            session.composerState.first { it.seq == 3L && it.attachments.singleOrNull()?.importState == AttachmentImportState.READY }
        }
        assertEquals("hello", original.attachments.single().preparedText)
        assertEquals(HttpStatusCode.Conflict, session.upload(1, "late.txt", "text/plain", null, 1, ByteReadChannel(byteArrayOf(1))))
    }
    @Test
    fun cancelledAndTruncatedTransportDeletesItsTemporarySource() = runBlocking {
        session.start()
        val pending = async { session.upload(0, "notes.txt", "text/plain", null, null, ByteChannel(autoFlush = true)) }
        withTimeout(TIMEOUT_MS) { while (uploadDirectory.listFiles().orEmpty().isEmpty()) kotlinx.coroutines.delay(10) }
        val transport = uploadDirectory.listFiles()!!.single()
        assertFalse(AttachmentFiles.deleteIfUnowned(transport))
        pending.cancelAndJoin()
        assertTrue(AttachmentFiles.deleteIfUnowned(transport))
        assertTrue(uploadDirectory.listFiles().orEmpty().isEmpty())
        assertEquals(HttpStatusCode.BadRequest, session.upload(0, "short.txt", "text/plain", null, 3, ByteReadChannel(byteArrayOf(1))))
        assertTrue(uploadDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(session.composerState.first().attachments.isEmpty())
        session.endUploads()
        assertEquals(HttpStatusCode.Conflict, session.upload(0, "late.txt", "text/plain", null, null, ByteReadChannel(byteArrayOf(1))))
    }
    @Test
    fun streamedSizeLimitDoesNotTrustTheMissingSizeHint() = runBlocking {
        session.start()
        val channel = ByteChannel(autoFlush = true)
        val writer = launch {
            val chunk = ByteArray(64 * 1024)
            repeat((AttachmentFiles.MAX_ATTACHMENT_BYTES / chunk.size).toInt() + 1) { channel.writeFully(chunk) }
            channel.close()
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, session.upload(0, "large.txt", "text/plain", null, null, channel))
        writer.cancelAndJoin()
        assertTrue(uploadDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(session.composerState.first().attachments.isEmpty())
    }
    @Test
    fun sharedIngressPreservesForcedMediaAndSandboxAdmission() {
        fun inspect(mime: String?, forced: String? = null, sandbox: Boolean = false) =
            FileValidator.inspectAttachment("source", "file", mime, 3, forced, sandbox)
        assertEquals("image", inspect("image/png")!!.type)
        assertEquals("video", inspect("video/mp4")!!.type)
        assertEquals("pdf", inspect("application/pdf")!!.type)
        assertEquals("file", inspect("application/json")!!.type)
        assertNull(inspect("application/octet-stream"))
        assertEquals(AttachmentStorage.LOCAL_SANDBOX_PENDING, inspect(null, sandbox = true)!!.storage)
        assertEquals("image", inspect(null, forced = "image")!!.type)
    }

    @Test
    fun exactAttachmentCommandsRejectStaleIdsAndRetryThroughTheImporter() = runBlocking {
        session.start()
        session.upload(0, "photo.png", "image/png", null, 1, ByteReadChannel(byteArrayOf(1)))
        val failed = withTimeout(TIMEOUT_MS) { session.composerState.first { it.attachments.singleOrNull()?.importState == AttachmentImportState.FAILED } }.attachments.single()
        imageOutput = File(uploadDirectory, "img_ready.jpg").apply { writeText("raster") }.absolutePath
        session.attachmentCommand(WebSyncCommand("attachment_retry", seq = 1, attachmentId = failed.localId))
        session.attachmentCommand(WebSyncCommand("attachment_retry", attachmentId = "missing"))
        assertEquals(1, imageAttempts)
        session.attachmentCommand(WebSyncCommand("attachment_retry", attachmentId = failed.localId, actionId = 5))
        val ready = withTimeout(TIMEOUT_MS) { session.composerState.first { it.attachments.singleOrNull()?.importState == AttachmentImportState.READY } }
        assertEquals(failed.localId, ready.attachments.single().localId)
        assertEquals(2, imageAttempts)
        assertEquals(5L, ready.actionId)
        assertTrue(session.previewAttachment(0, failed.localId, "source", 0) { file, mime ->
            assertEquals("raster", file.readText())
            assertEquals("image/jpeg", mime)
        })
        File(imageOutput!!).delete()
        assertFalse(session.previewAttachment(0, failed.localId, "source", 0) { _, _ -> error("Missing artifact") })
        session.attachmentCommand(WebSyncCommand("attachment_remove", seq = 1, attachmentId = failed.localId))
        assertEquals(1, session.composerState.first().attachments.size)
        session.attachmentCommand(WebSyncCommand("attachment_remove", attachmentId = failed.localId, actionId = 6))
        assertTrue(session.composerState.first { it.actionId == 6L }.attachments.isEmpty())
        assertFalse(session.previewAttachment(0, failed.localId, "source", 0) { _, _ -> error("Removed attachment") })
    }

    @Test
    fun pdfAndVideoConfigurationUseExactProcessingMembershipAndValidateInputs() = runBlocking {
        session.start()
        session.upload(0, "doc.pdf", "application/pdf", null, 1, ByteReadChannel(byteArrayOf(1)))
        val pdf = withTimeout(TIMEOUT_MS) { session.composerState.first { it.attachments.singleOrNull()?.preRenderedPaths?.size == 3 } }.attachments.single()
        assertTrue(session.previewAttachment(0, pdf.localId, "page", 2) { file, _ -> assertEquals("page 2", file.readText()) })
        assertFalse(session.previewAttachment(0, pdf.localId, "page", 3) { _, _ -> error("Out of range") })
        for (pages in listOf(emptyList(), listOf(-1), listOf(3))) {
            session.attachmentCommand(WebSyncCommand("attachment_pdf", attachmentId = pdf.localId, pages = pages))
            assertNull(session.composerState.first().attachments.single().selectedPages)
        }
        session.attachmentCommand(WebSyncCommand("attachment_pdf", attachmentId = pdf.localId, pages = listOf(2, 0, 2)))
        val configured = withTimeout(TIMEOUT_MS) { session.composerState.first { it.attachments.singleOrNull()?.importState == AttachmentImportState.READY } }.attachments.single()
        assertEquals(setOf(0, 1), configured.selectedPages)
        assertEquals(listOf("selected 0", "selected 2"), configured.preRenderedPaths!!.map { File(it).readText() })
        session.attachmentCommand(WebSyncCommand("attachment_pdf", attachmentId = pdf.localId, pages = listOf(1)))
        assertEquals(configured, session.composerState.first().attachments.single())
        session.upload(0, "clip.mp4", "video/mp4", "video", 1, ByteReadChannel(byteArrayOf(1)))
        val video = withTimeout(TIMEOUT_MS) { session.composerState.first { it.attachments.lastOrNull()?.videoDurationMs == 15_000L } }.attachments.last()
        assertTrue(session.previewAttachment(0, video.localId, "source", 0) { _, mime -> assertEquals("video/mp4", mime) })
        for ((frames, interval) in listOf(1 to 1000L, 3 to -1L, 3 to Long.MAX_VALUE)) {
            session.attachmentCommand(WebSyncCommand("attachment_video", attachmentId = video.localId, frameCount = frames, intervalMs = interval))
            assertNull(session.composerState.first().attachments.last().frameCount)
        }
        session.attachmentCommand(WebSyncCommand("attachment_video", attachmentId = video.localId, frameCount = 3, intervalMs = 5000))
        withTimeout(TIMEOUT_MS) { while (videoConfigs.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(3, videoConfigs.single().frameCount)
        assertEquals(5_000_000L, videoConfigs.single().intervalMicros)
    }

    @Test
    fun previewPinsTheExactArtifactAcrossRemovalAndPropagatesStreamFailures() = runBlocking {
        session.start()
        imageOutput = File(uploadDirectory, "img_pin.jpg").apply { writeText("raster") }.absolutePath
        session.upload(0, "photo.png", "image/png", null, 1, ByteReadChannel(byteArrayOf(1)))
        val attachment = withTimeout(TIMEOUT_MS) { session.composerState.first { it.attachments.singleOrNull()?.importState == AttachmentImportState.READY } }.attachments.single()
        for ((seq, kind, index) in listOf(Triple(1L, "source", 0), Triple(0L, "frame", 0), Triple(0L, "source", 1))) {
            assertFalse(session.previewAttachment(seq, attachment.localId, kind, index) { _, _ -> error("Invalid preview") })
        }
        assertTrue(runCatching {
            session.previewAttachment(0, attachment.localId, "source", 0) { _, _ -> throw java.io.IOException("Broken stream") }
        }.exceptionOrNull() is java.io.IOException)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val preview = async { session.previewAttachment(0, attachment.localId, "source", 0) { file, _ ->
            entered.complete(Unit)
            finish.await()
            assertEquals("raster", file.readText())
        } }
        withTimeout(TIMEOUT_MS) { entered.await() }
        session.attachmentCommand(WebSyncCommand("attachment_remove", attachmentId = attachment.localId))
        assertFalse(AttachmentFiles.deleteIfUnowned(File(imageOutput!!)))
        finish.complete(Unit)
        assertTrue(preview.await())
        assertTrue(AttachmentFiles.deleteIfUnowned(File(imageOutput!!)))
    }

    @Test
    fun previewRejectsPrivateRootEscapeAndDisconnectCancelsAnAdmittedStream() = runBlocking {
        session.start()
        imageOutput = temporary.newFile("outside.jpg").apply { writeText("outside") }.absolutePath
        session.upload(0, "photo.png", "image/png", null, 1, ByteReadChannel(byteArrayOf(1)))
        var attachment = withTimeout(TIMEOUT_MS) { session.composerState.first { it.attachments.singleOrNull()?.importState == AttachmentImportState.READY } }.attachments.single()
        assertFalse(session.previewAttachment(0, attachment.localId, "source", 0) { _, _ -> error("Escaped root") })
        session.attachmentCommand(WebSyncCommand("attachment_remove", attachmentId = attachment.localId))
        imageOutput = File(uploadDirectory, "img_cancel.jpg").apply { writeText("raster") }.absolutePath
        session.upload(0, "photo.png", "image/png", null, 1, ByteReadChannel(byteArrayOf(1)))
        attachment = withTimeout(TIMEOUT_MS) { session.composerState.first { it.attachments.singleOrNull()?.importState == AttachmentImportState.READY } }.attachments.single()
        val entered = CompletableDeferred<Unit>()
        val preview = async { session.previewAttachment(0, attachment.localId, "source", 0) { _, _ -> entered.complete(Unit); kotlinx.coroutines.awaitCancellation() } }
        withTimeout(TIMEOUT_MS) { entered.await() }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(runCatching { preview.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        session.close()
        assertTrue(AttachmentFiles.deleteIfUnowned(File(imageOutput!!)))
    }

    @Test
    fun aNewChatSendUsesTheSessionWorkspaceAndNeverThePhonesDrafts() = runBlocking {
        session.start()
        awaitModel()
        session.send("hello")
        withTimeout(TIMEOUT_MS) { while (prepared.isEmpty()) kotlinx.coroutines.delay(10) }

        val capture = captures.single()
        assertEquals(NEW_CHAT_WORKSPACE_ID, capture.ownerId)
        assertNull(capture.currentId)
        assertTrue(capture.isNewChat)
        assertEquals("default-model", capture.modelId)
        assertTrue(capture.workspace!!.sessionLocal)
        assertNull(capture.workspace.persisted)
        assertEquals("hello", prepared.single().text)
        coVerify(exactly = 0) { conversations.updateDraft(any(), any(), any(), any()) }
    }

    @Test
    fun anExistingConversationSendsWithItsOwnModelAndStopTargetsIt() = runBlocking {
        session.start()
        session.open("a", seq = 1L)
        awaitModel()
        withTimeout(TIMEOUT_MS) {
            while (captures.none { it.modelId == "conversation-model" }) {
                captures.clear()
                prepared.clear()
                session.send("question")
                withTimeout(TIMEOUT_MS) { while (prepared.isEmpty()) kotlinx.coroutines.delay(10) }
            }
        }
        val capture = captures.last()
        assertEquals("a", capture.ownerId)
        assertEquals("a", capture.currentId)
        assertFalse(capture.isNewChat)

        session.stop()
        verify(exactly = 1) { generationStop.stop("a", session) }
    }

    @Test
    fun onlyTheMatchingNewChatEntryFollowsItsAcceptedConversation() = runBlocking {
        session.start()
        session.open(null, seq = 7L)
        assertFalse(session.publishAcceptedNewConversation("created", "m", entryId = 0L))
        assertEquals(null, session.openTarget.value.conversationId)

        assertTrue(session.publishAcceptedNewConversation("created", "m", entryId = 1L))
        assertEquals(WebUiChatSession.OpenTarget("created", browserSeq = 7L, movedByServer = true), session.openTarget.value)
        assertEquals(listOf(session), clients.showing("created"))

        session.open("a", seq = 8L)
        assertFalse(session.publishAcceptedNewConversation("other", "m", entryId = 1L))
        assertEquals(WebUiChatSession.OpenTarget("a", browserSeq = 8L, movedByServer = false), session.openTarget.value)
    }

    @Test
    fun closingDetachesTheSessionFromTheRuntime() = runBlocking {
        session.start()
        session.open("a", seq = 1L)
        assertTrue(clients.isConversationOpen("a"))
        session.close()
        assertFalse(clients.isConversationOpen("a"))
    }
    @Test
    fun draftsAreSessionLocalAndOldTargetEditsAreRejected() = runBlocking {
        session.start()
        session.edit("new draft", revision = 1L, seq = 0L)
        assertEquals("new draft", session.composerState.first { it.editRevision == 1L }.text)
        session.open("a", seq = 2L)
        session.edit("conversation draft", revision = 1L, seq = 2L)
        session.edit("stale", revision = 2L, seq = 0L)
        assertEquals("conversation draft", session.composerState.first { it.seq == 2L }.text)
        session.open(null, seq = 3L)
        assertEquals("new draft", session.composerState.first { it.seq == 3L }.text)
        session.open(null, seq = 4L)
        assertEquals("new draft", session.composerState.first { it.seq == 4L }.text)
        coVerify(exactly = 0) { conversations.updateDraft(any(), any(), any(), any()) }
    }
    @Test
    fun newChatPublicationTransfersUnacceptedInputToTheCreatedOwner() = runBlocking {
        session.start()
        session.edit("remaining input", revision = 1L, seq = 0L)
        assertTrue(session.publishAcceptedNewConversation("created", "m", entryId = 0L))
        assertEquals("remaining input", session.composerState.first().text)
        session.open(null, seq = 1L)
        assertEquals("", session.composerState.first().text)
    }
    @Test
    fun staleSendAndStopCannotTargetTheNewSelection() = runBlocking {
        session.start()
        session.open("a", seq = 2L)
        session.send("stale send", seq = 1L, commandId = 7L)
        session.stop(seq = 1L)
        assertTrue(captures.isEmpty())
        verify(exactly = 0) { generationStop.stop(any(), any()) }
    }
    @Test
    fun modelSelectionSettlesBeforeTheNextTapAndNewChatDoesNotWritePhoneState() = runBlocking {
        session.start()
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid } }
        session.selectModel("conversation-model", seq = 0L, commandId = 1L)
        session.send("new choice", seq = 0L)
        withTimeout(TIMEOUT_MS) { while (captures.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals("conversation-model", captures.single().modelId)
        coVerify(exactly = 0) { conversations.upsertNewChatPersist(any()) }
        val canonical = MutableStateFlow<ChatConversation?>(ChatConversation("a", "Alpha", modelId = "conversation-model"))
        val dao = mockk<ChatDao>()
        every { conversations.chatDao } returns dao
        every { conversations.observeConversation("a") } returns canonical
        coEvery { dao.updateConversationModel("a", "default-model", any()) } answers {
            canonical.value = canonical.value!!.copy(modelId = "default-model")
            1
        }
        session.open("a", seq = 2L)
        session.selectModel("default-model", seq = 2L, commandId = 2L)
        captures.clear()
        session.send("next tap", seq = 2L)
        withTimeout(TIMEOUT_MS) { while (captures.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals("default-model", captures.single().modelId)
        session.selectModel("invalid", seq = 2L, commandId = 3L)
        session.selectModel("conversation-model", seq = 1L, commandId = 4L)
        coVerify(exactly = 1) { dao.updateConversationModel(any(), any(), any()) }
    }
    @Test
    fun sessionLocalSettingsCaptureBeforeTapAndRemainIndependentFromPhoneAndOtherOwners() = runBlocking {
        session.start()
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid && it.controls != null } }
        session.settingCommand(WebSyncCommand("setting", setting = "webSearchEnabled", enabled = false, actionId = 1))
        val disabled = withTimeout(TIMEOUT_MS) { session.composerState.first { it.actionId == 1L } }
        assertFalse(disabled.controls!!.webSearchEnabled)
        session.send("freeze settings")
        withTimeout(TIMEOUT_MS) { while (captures.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(false, captures.single().workspace!!.conversationSettings!!.webSearchEnabled)
        session.settingCommand(WebSyncCommand("setting", setting = "webSearchEnabled", enabled = true, actionId = 2))
        assertEquals(false, captures.single().workspace!!.conversationSettings!!.webSearchEnabled)
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
        coVerify(exactly = 0) { conversations.upsertNewChatPersist(any()) }
        session.open("a", 1)
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.conversationId == "a" && it.controls != null } }
        assertTrue(session.composerState.first().controls!!.webSearchEnabled)
        session.open(null, 2)
        val local = withTimeout(TIMEOUT_MS) { session.composerState.first { it.seq == 2L && it.controls != null } }
        assertTrue(local.controls!!.webSearchEnabled)
    }
    @Test
    fun sharedSettingsTransformPreservesConcurrentFieldsAndRejectsUnavailableOrStaleEdits() = runBlocking {
        val shared = MutableStateFlow(mapOf("a" to ConversationSettings(temperature = 0.7f, shellEnabled = true)))
        every { settings.conversationSettings } returns shared
        every { settings.updateConversationSettings("a", any()) } answers {
            shared.value = shared.value + ("a" to secondArg<(ConversationSettings) -> ConversationSettings>().invoke(shared.value.getValue("a")))
        }
        session.start()
        session.open("a", 1)
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid && it.seq == 1L && it.controls != null } }
        session.settingCommand(WebSyncCommand("setting", seq = 1, setting = "webSearchEnabled", enabled = false, actionId = 1))
        assertEquals(0.7f, shared.value.getValue("a").temperature)
        assertEquals(true, shared.value.getValue("a").shellEnabled)
        assertEquals(false, shared.value.getValue("a").webSearchEnabled)
        session.settingCommand(WebSyncCommand("setting", seq = 0, setting = "shellEnabled", enabled = false))
        session.settingCommand(WebSyncCommand("setting", seq = 1, setting = "openAiWebSearchEnabled", enabled = true, actionId = 2))
        session.settingCommand(WebSyncCommand("setting", seq = 1, setting = "unknown", enabled = true, actionId = 3))
        verify(exactly = 1) { settings.updateConversationSettings(any(), any()) }
        val acknowledged = withTimeout(TIMEOUT_MS) { session.composerState.first { it.actionId == 3L } }
        assertFalse(acknowledged.controls!!.openAiWebSearchAvailable)
        session.close()
        session.settingCommand(WebSyncCommand("setting", seq = 1, setting = "shellEnabled", enabled = false))
        verify(exactly = 1) { settings.updateConversationSettings(any(), any()) }
    }
    @Test
    fun thinkingAndTierCommandsUseTheSelectedModelsAcceptedOptions() = runBlocking {
        val model = MutableStateFlow("OpenAI:gpt-6-astra")
        every { settings.selectedModel } returns model
        every { settings.enabledModels } returns MutableStateFlow(setOf(model.value, "OpenAI:gpt-5.6-sol", "relay:test"))
        every { settings.openAiResponsesApiEnabled } returns MutableStateFlow(true)
        session.start()
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelId == model.value && it.modelValid } }
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingEnabled", enabled = false))
        assertTrue(session.composerState.first().controls!!.thinkingEnabled)
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingLevel", value = "minimal"))
        assertEquals("medium", session.composerState.first().controls!!.thinkingLevel)
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingLevel", value = "max"))
        assertEquals("max", session.composerState.first().controls!!.thinkingLevel)
        session.settingCommand(WebSyncCommand("setting", modelId = "old-model", setting = "thinkingLevel", value = "low"))
        assertEquals("max", session.composerState.first().controls!!.thinkingLevel)
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingBudgetEnabled", enabled = true))
        assertFalse(session.composerState.first().controls!!.thinkingBudgetEnabled)
        session.settingCommand(WebSyncCommand("setting", setting = "openAiServiceTier", value = "fast"))
        assertEquals("auto", session.composerState.first().controls!!.openAiServiceTierState.tier)
        session.settingCommand(WebSyncCommand("setting", setting = "openAiServiceTierEnabled", enabled = true))
        session.settingCommand(WebSyncCommand("setting", setting = "openAiServiceTier", value = "ultrafast"))
        assertEquals("auto", session.composerState.first().controls!!.openAiServiceTierState.tier)
        session.settingCommand(WebSyncCommand("setting", setting = "openAiServiceTier", value = "fast"))
        assertEquals("fast", session.composerState.first().controls!!.openAiServiceTierState.tier)
        model.value = "relay:test"
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelId == model.value } }
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingBudgetEnabled", enabled = true))
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingBudgetTokens", tokens = 8192))
        assertEquals(8192, session.composerState.first().controls!!.thinkingBudgetTokens)
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingLevel", value = "low"))
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingBudgetTokens", tokens = -1))
        assertEquals("max", session.composerState.first().controls!!.thinkingLevel)
        assertEquals(8192, session.composerState.first().controls!!.thinkingBudgetTokens)
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingBudgetEnabled", enabled = false))
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingLevel", value = "low"))
        assertEquals("low", session.composerState.first().controls!!.thinkingLevel)
        model.value = "OpenAI:gpt-5.6-sol"
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelId == model.value } }
        assertEquals("fast", session.composerState.first().controls!!.openAiServiceTierState.tier)
        session.settingCommand(WebSyncCommand("setting", setting = "openAiServiceTier", value = "ultrafast", actionId = 7))
        val changed = withTimeout(TIMEOUT_MS) { session.composerState.first { it.actionId == 7L } }
        assertEquals("ultrafast", changed.controls!!.openAiServiceTierState.tier)
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
    }
    @Test
    fun customResponsesAvailabilityUsesItsIdentityAndTracksProtocolChanges() = runBlocking {
        val providerId = "custom-provider-12345678-1234-4234-8234-123456789abc"
        val model = "$providerId:unlisted"
        val custom = MutableStateFlow(listOf(com.newoether.agora.data.CustomProviderConfig(
            name = "Relay", id = providerId, responsesApiEnabled = true)))
        every { settings.selectedModel } returns MutableStateFlow(model)
        every { settings.enabledModels } returns MutableStateFlow(setOf(model))
        every { settings.customProviders } returns custom
        session.start()
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid && it.controls?.openAiWebSearchAvailable == true } }
        session.settingCommand(WebSyncCommand("setting", setting = "openAiWebSearchEnabled", enabled = false, actionId = 1))
        assertFalse(session.composerState.first().controls!!.openAiWebSearchEnabled)
        custom.value = listOf(custom.value.single().copy(protocol = com.newoether.agora.data.CustomEndpointProtocol.ANTHROPIC))
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.controls?.openAiWebSearchAvailable == false } }
        session.settingCommand(WebSyncCommand("setting", setting = "openAiWebSearchEnabled", enabled = true, actionId = 2))
        assertFalse(session.composerState.first().controls!!.openAiWebSearchEnabled)
    }
    @Test
    fun localLowContextModeUsesTheGlobalAvailabilityGateAndKeepsOtherSettings() = runBlocking {
        val model = "${com.newoether.agora.util.Constants.PROVIDER_LOCAL}:test"
        every { settings.selectedModel } returns MutableStateFlow(model)
        every { settings.enabledModels } returns MutableStateFlow(setOf(model))
        val lowContext = MutableStateFlow(false)
        every { settings.localLowContextModeEnabled } returns lowContext
        every { settings.systemPrompts } returns MutableStateFlow(listOf(com.newoether.agora.data.SystemPromptEntry(id = "prompt", title = "Prompt")))
        session.start()
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid && it.controls?.showLowContextMode == true } }
        session.editorCommand(WebSyncCommand("system_prompt", value = "prompt"))
        lowContext.value = true
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.controls?.lowContextModeEnabled == true } }
        session.editorCommand(WebSyncCommand("system_prompt", value = null))
        assertEquals("prompt", session.composerState.first().systemPromptId)
        session.settingCommand(WebSyncCommand("setting", setting = "webSearchEnabled", enabled = false, actionId = 1))
        assertTrue(session.composerState.first().controls!!.webSearchEnabled)
        session.settingCommand(WebSyncCommand("setting", setting = "lowContextModeEnabled", enabled = false, actionId = 2))
        val changed = withTimeout(TIMEOUT_MS) { session.composerState.first { it.actionId == 2L } }
        assertFalse(changed.controls!!.lowContextModeEnabled)
        session.settingCommand(WebSyncCommand("setting", setting = "webSearchEnabled", enabled = false, actionId = 3))
        assertFalse(session.composerState.first().controls!!.webSearchEnabled)
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
    }
    @Test
    fun newChatAcceptanceConsumesOnlyMatchingSettingsAndPromptAndPreservesPostTapEdits() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val accept = CompletableDeferred<Unit>()
        every { settings.systemPrompts } returns MutableStateFlow(listOf("before", "after").map { com.newoether.agora.data.SystemPromptEntry(id = it, title = it) })
        coEvery { generation.prepareForegroundSend(any(), any(), any()) } answers {
            val target = firstArg<ForegroundSendTarget>()
            ForegroundSendAdmission(target, testGenerationAdmissionSnapshot(target.conversationId, target.runId),
                ChatEntity(id = target.conversationId, title = "New"), target.newChatWorkspace!!.conversationSettings)
        }
        coEvery { generation.sendMessage(any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            accept.await()
            SendAcceptance.Direct("accepted", "created").also { arg<suspend (SendAcceptance) -> Unit>(3).invoke(it) }
        }
        session.start()
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid && it.controls != null } }
        session.settingCommand(WebSyncCommand("setting", setting = "webSearchEnabled", enabled = false))
        session.editorCommand(WebSyncCommand("system_prompt", value = "before"))
        session.send("first")
        withTimeout(TIMEOUT_MS) { entered.await() }
        session.settingCommand(WebSyncCommand("setting", setting = "shellEnabled", enabled = false, actionId = 2))
        session.editorCommand(WebSyncCommand("system_prompt", value = "after", actionId = 3))
        accept.complete(Unit)
        val settled = withTimeout(TIMEOUT_MS) { session.composerState.first { it.snapshot.acceptedVersion == 1L } }
        assertEquals("before", captures.single().workspace!!.systemPromptId)
        assertEquals("after", settled.systemPromptId)
        assertFalse(settled.controls!!.shellEnabled)
        assertFalse(settled.controls.webSearchEnabled)
        assertNull(captures.single().workspace!!.conversationSettings!!.shellEnabled)
        session.send("second")
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.snapshot.acceptedVersion == 2L } }
        assertEquals("after", captures.last().workspace!!.systemPromptId)
        assertNull(session.composerState.first().systemPromptId)
        assertTrue(session.composerState.first().controls!!.webSearchEnabled)
        assertTrue(session.composerState.first().controls!!.shellEnabled)
    }
    @Test
    fun queueCommandsUseTheCanonicalOwnerAndRejectStaleSelections() = runBlocking {
        val queued = QueuedSend("q", "guidance", "default-model", emptyList(), "run")
        val queue = MutableStateFlow(listOf(queued))
        val generating = MutableStateFlow(true)
        var drains = 0
        every { state.queuedSends } returns queue
        every { state.generating } returns generating
        every { state.onQueueDrainRequested } returns { _: ConversationGenerationState -> drains++ }
        every { state.queueMutationMutex } returns Mutex()
        every { state.removeQueuedSend("q") } answers { queue.value = emptyList(); queued }
        session.start()
        session.open("a", seq = 1L)
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid && it.queue.size == 1 } }
        session.sendQueued(seq = 0L, commandId = 1L)
        session.removeQueued("q", seq = 0L)
        session.sendQueued(seq = 1L, commandId = 2L)
        assertEquals(0, drains)
        generating.value = false
        session.sendQueued(seq = 1L, commandId = 3L)
        assertEquals(1, drains)
        session.removeQueued("q", seq = 1L)
        withTimeout(TIMEOUT_MS) { queue.first { it.isEmpty() } }
        verify(exactly = 1) { state.removeQueuedSend("q") }
    }
    @Test
    fun canonicalAcceptanceClearsOnlyTheTapTextAndKeepsPostTapEdits() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val accept = CompletableDeferred<Unit>()
        coEvery { generation.prepareForegroundSend(any(), any(), any()) } answers {
            val target = firstArg<ForegroundSendTarget>()
            ForegroundSendAdmission(
                target, testGenerationAdmissionSnapshot(target.conversationId, target.runId), null, null,
            )
        }
        coEvery { generation.sendMessage(any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            accept.await()
            SendAcceptance.Direct("accepted", "a").also {
                arg<suspend (SendAcceptance) -> Unit>(3).invoke(it)
            }
        }
        session.start()
        session.open("a", seq = 1L)
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid } }
        session.upload(1, "notes.txt", "text/plain", null, 5, ByteReadChannel("hello".toByteArray()))
        val file = withTimeout(TIMEOUT_MS) { session.composerState.first { it.attachments.singleOrNull()?.importState == AttachmentImportState.READY } }.attachments.single()
        session.edit("tap text", revision = 1L, seq = 1L)
        session.send("tap text", seq = 1L, commandId = 2L)
        withTimeout(TIMEOUT_MS) { entered.await() }
        session.attachmentCommand(WebSyncCommand("attachment_remove", seq = 1, attachmentId = file.localId, actionId = 3))
        assertEquals(listOf(file.localId), session.composerState.first().attachments.map { it.localId })
        session.edit("later edit", revision = 2L, seq = 1L)
        accept.complete(Unit)
        val settled = withTimeout(TIMEOUT_MS) { session.composerState.first { it.snapshot.acceptedVersion == 1L } }
        assertEquals("later edit", settled.text)
        assertEquals(2L, settled.editRevision)
        assertEquals(3L, settled.actionId)
        coVerify(exactly = 1) { generation.sendMessage(any(), "tap text", any(), any(), session) }
    }

    @Test
    fun aSessionLocalWorkspaceCarriesNoPhoneNewChatPersistSnapshot() {
        val builder = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt")
        assertTrue(builder.contains("newChatPersistSnapshot = if (target.wasNewChat && workspace?.sessionLocal != true) {"))
    }

    private suspend fun awaitModel() {
        // The active model resolves once settings report their valid models.
        withTimeout(TIMEOUT_MS) { settings.enabledModels.first { it.isNotEmpty() } }
        kotlinx.coroutines.delay(50)
    }

    private data class Capture(
        val ownerId: String,
        val currentId: String?,
        val isNewChat: Boolean,
        val entryId: Long,
        val modelId: String,
        val workspace: NewChatWorkspaceSnapshot?,
    )

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
