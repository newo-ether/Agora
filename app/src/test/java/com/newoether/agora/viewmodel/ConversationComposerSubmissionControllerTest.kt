package com.newoether.agora.viewmodel

import android.util.Log
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.DebugLog
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationComposerSubmissionControllerTest {
    private val diagnosticLines = CopyOnWriteArrayList<String>()

    @Before
    fun mockDebugLog() {
        mockkStatic(Log::class)
        every { Log.i("SendDiagnostics", any()) } answers {
            diagnosticLines += secondArg<String>()
            0
        }
        mockkObject(DebugLog)
        every { DebugLog.w(any(), any(), any()) } just Runs
    }

    @After
    fun restoreDebugLog() {
        unmockkObject(DebugLog)
        unmockkStatic(Log::class)
    }

    @Test
    fun diagnosticFailureDoesNotPreventAcceptanceOrDraftRelease() = runTest {
        every { Log.i("SendDiagnostics", any()) } throws IllegalStateException("logger unavailable")
        val fixture = Fixture(
            this,
            acceptance = SendAcceptance.Direct("message", "conversation"),
        )
        assertTrue(fixture.controller.submit("conversation", "text", emptyList()))
        runCurrent()
        assertEquals(1, fixture.sendCount)
        assertEquals(1L, fixture.controller.state("conversation").value.directAcceptedVersion)
        assertEquals(ComposerSubmissionPhase.IDLE, fixture.controller.state("conversation").value.phase)
        assertFalse(fixture.controller.isFrozen("conversation"))
    }

    @Test
    fun targetCaptureRejectionDoesNotFreezeOrLaunch() = runTest {
        val fixture = Fixture(this, captureAllowed = false)

        assertFalse(fixture.controller.submit("owner", "text", emptyList()))
        runCurrent()

        assertFalse(fixture.controller.isFrozen("owner"))
        assertTrue(fixture.events.isEmpty())
        assertTrue(diagnosticLines.isEmpty())
        assertEquals(0, fixture.sendCount)
    }

    @Test
    fun admissionFailureUnlocksBeforeAttachmentWaitOrSend() = runTest {
        val fixture = Fixture(this, prepareAdmission = false)

        assertTrue(fixture.controller.submit("owner", "text", emptyList()))
        runCurrent()

        assertFalse(fixture.controller.isFrozen("owner"))
        assertEquals(ComposerSubmissionPhase.IDLE, fixture.controller.state("owner").value.phase)
        assertEquals(
            listOf(
                "load:owner",
                "freeze:owner:1:text:",
                "prepare:owner",
                "unfreeze:owner:1",
                "release:owner",
            ),
            fixture.events,
        )
        assertEquals(0, fixture.sendCount)
    }

    @Test
    fun waitingRequestFreezesExactOwnerAndCancellationKeepsDraft() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fixture = Fixture(this, awaitGate = gate)

        assertTrue(fixture.controller.submit("owner-a", "frozen", listOf("b", "a")))
        runCurrent()

        assertEquals(
            ConversationComposerSubmissionSnapshot(
                phase = ComposerSubmissionPhase.WAITING,
                requestId = 1L,
                frozenText = "frozen",
                frozenAttachmentIds = listOf("b", "a"),
            ),
            fixture.controller.state("owner-a").value,
        )
        assertTrue(fixture.controller.cancelWaiting("owner-a"))
        runCurrent()

        assertEquals(ComposerSubmissionPhase.IDLE, fixture.controller.state("owner-a").value.phase)
        assertEquals(
            listOf(
                "load:owner-a",
                "freeze:owner-a:1:frozen:b, a",
                "prepare:owner-a",
                "await:owner-a:b, a",
                "unfreeze:owner-a:1",
                "release:owner-a",
            ),
            fixture.events,
        )
        coVerify(exactly = 0) { fixture.composers.clearAccepted(any(), any(), any(), any(), any(), any(), any()) }
        assertFalse(fixture.controller.cancelWaiting("owner-a"))
        assertEquals(
            listOf(
                "load-composer", "freeze-draft", "prepare-admission", "await-attachments",
                "cancelled", "release-submission", "finished",
            ),
            diagnosticLines.map { it.substringAfter("stage=").substringBefore(' ') },
        )
        assertTrue(diagnosticLines.all { it.startsWith("run=run:owner-a component=composer ") })
        assertTrue(diagnosticLines.none { "frozen" in it || "b, a" in it })
    }

    @Test
    fun readyAttachmentsAreFilteredInFrozenOrderAndSandboxOwnershipTransfers() = runTest {
        val readyA = attachment("a")
        val readyB = attachment("b", storage = AttachmentStorage.LOCAL_SANDBOX_PENDING)
        val failed = attachment("failed", state = AttachmentImportState.FAILED)
        val unavailable = attachment("unavailable", unavailable = true)
        val fixture = Fixture(
            this,
            attachments = listOf(readyA, failed, unavailable, readyB),
        )

        fixture.controller.submit("owner", "text", listOf("b", "failed", "unavailable", "a"))
        runCurrent()

        assertEquals(listOf("b", "a"), fixture.sentAttachments.map(SelectedAttachment::localId))
        assertEquals(AttachmentStorage.LOCAL_SANDBOX_RUNTIME, fixture.sentAttachments.first().storage)
        assertEquals(ComposerSubmissionPhase.IDLE, fixture.controller.state("owner").value.phase)
        coVerify(exactly = 0) { fixture.composers.clearAccepted(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun blankTextWithNoReadyAttachmentDoesNotSubmitOrClear() = runTest {
        val fixture = Fixture(
            this,
            attachments = listOf(
                attachment("failed", state = AttachmentImportState.FAILED),
                attachment("unavailable", unavailable = true),
                SelectedAttachment(
                    localId = "incomplete",
                    uri = "content://legacy/incomplete",
                    type = "image",
                ),
            ),
        )

        fixture.controller.submit(
            "owner",
            "   ",
            listOf("failed", "unavailable", "incomplete"),
        )
        runCurrent()

        assertEquals(0, fixture.sendCount)
        assertEquals(ComposerSubmissionPhase.IDLE, fixture.controller.state("owner").value.phase)
        coVerify(exactly = 0) { fixture.composers.clearAccepted(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun rejectedAndFailedSubmissionsReturnExactOwnerToIdle() = runTest {
        val rejected = Fixture(this)
        rejected.controller.submit("owner-a", "text", emptyList())
        runCurrent()
        assertEquals(ComposerSubmissionPhase.IDLE, rejected.controller.state("owner-a").value.phase)

        val failed = Fixture(this, sendFailure = IllegalStateException("failed"))
        failed.controller.submit("owner-b", "text", emptyList())
        runCurrent()
        assertEquals(ComposerSubmissionPhase.IDLE, failed.controller.state("owner-b").value.phase)
        coVerify(exactly = 0) { failed.composers.clearAccepted(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun directAcceptanceClearsFrozenOwnerWithoutReclaimingDurableAttachment() = runTest {
        val attachment = attachment("app-private")
        val fixture = Fixture(
            this,
            attachments = listOf(attachment),
            acceptance = SendAcceptance.Direct("message", "accepted-conversation"),
            clearedAttachments = listOf(attachment),
        )

        fixture.controller.submit("draft-owner", "text", listOf(attachment.localId))
        runCurrent()

        assertEquals(
            listOf(
                "load:draft-owner",
                "freeze:draft-owner:1:text:app-private",
                "prepare:draft-owner",
                "await:draft-owner:app-private",
                "send:text:app-private",
                "clear:draft-owner",
                "unfreeze:draft-owner:1",
                "release:draft-owner",
            ),
            fixture.events,
        )
        assertEquals(1L, fixture.controller.state("draft-owner").value.acceptedVersion)
        assertEquals(1L, fixture.controller.state("draft-owner").value.directAcceptedVersion)
        val stages = diagnosticLines.map { it.substringAfter("stage=").substringBefore(' ') }
        val ordered = listOf("send", "accepted-direct", "draft-cleared", "finished")
        assertEquals(ordered, stages.filter { it in ordered })
        assertEquals("finished", stages.last())
        assertTrue(diagnosticLines.all { "previousMs=" in it && "elapsedMs=" in it })
        assertTrue(diagnosticLines.none { "app-private" in it || "text" in it })
        coVerify(exactly = 0) { fixture.drafts.reclaimAttachments(any()) }
        coVerify(exactly = 0) {
            fixture.composers.clearAccepted(
                "accepted-conversation",
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun directAcceptanceReclaimsOnlyClearedAttachmentsOutsideDurablePayload() = runTest {
        val durable = attachment("durable")
        val abandoned = attachment("abandoned", state = AttachmentImportState.FAILED)
        val fixture = Fixture(
            this,
            attachments = listOf(durable, abandoned),
            acceptance = SendAcceptance.Direct("message", "conversation"),
            clearedAttachments = listOf(durable, abandoned),
        )

        fixture.controller.submit("conversation", "text", listOf(durable.localId, abandoned.localId))
        runCurrent()

        assertEquals(listOf("durable"), fixture.sentAttachments.map(SelectedAttachment::localId))
        coVerify(exactly = 1) {
            fixture.drafts.reclaimAttachments(
                match { attachments -> attachments.map(SelectedAttachment::localId) == listOf("abandoned") },
            )
        }
    }

    @Test
    fun directAcceptanceClearFailureRemainsNonResendable() = runTest {
        val fixture = Fixture(
            this,
            acceptance = SendAcceptance.Direct("message", "conversation"),
            clearSucceeded = false,
        )

        assertTrue(fixture.controller.submit("conversation", "text", emptyList()))
        runCurrent()

        assertEquals(
            ComposerSubmissionPhase.ACCEPTED_PENDING_CLEAR,
            fixture.controller.state("conversation").value.phase,
        )
        assertEquals(0L, fixture.controller.state("conversation").value.acceptedVersion)
        assertTrue(fixture.controller.isFrozen("conversation"))
        assertFalse(fixture.controller.submit("conversation", "text", emptyList()))
    }

    @Test
    fun queuedAcceptanceClearsButRetainsMemoryOwnedAttachments() = runTest {
        val attachment = attachment("queued")
        val fixture = Fixture(
            this,
            attachments = listOf(attachment),
            acceptance = SendAcceptance.Queued("queued-message", "conversation"),
            clearedAttachments = listOf(attachment),
        )

        fixture.controller.submit("conversation", "text", listOf(attachment.localId))
        runCurrent()

        assertEquals(1L, fixture.controller.state("conversation").value.acceptedVersion)
        assertEquals(0L, fixture.controller.state("conversation").value.directAcceptedVersion)
        coVerify(exactly = 0) { fixture.drafts.reclaimAttachments(any()) }
    }

    @Test
    fun runtimeAttachmentIdentityCannotBeReclaimedFromStaleDraft() = runTest {
        val pending = attachment("stable", storage = AttachmentStorage.LOCAL_SANDBOX_PENDING)
        val fixture = Fixture(
            this,
            attachments = listOf(pending),
            acceptance = SendAcceptance.Direct("message", "conversation"),
            clearedAttachments = listOf(pending),
        )

        fixture.controller.submit("conversation", "inspect", listOf(pending.localId))
        runCurrent()

        coVerify(exactly = 0) { fixture.drafts.reclaimAttachments(any()) }
    }

    @Test
    fun newChatAcceptanceMatchesTheSettledAttachmentDraftState() {
        val pending = attachment("image", state = AttachmentImportState.PROCESSING)
        val settled = attachment("image", state = AttachmentImportState.READY)
        val target = ForegroundSendTarget(
            ownerId = NEW_CHAT_WORKSPACE_ID,
            conversationId = "conversation",
            runId = "run",
            wasNewChat = true,
            newChatEntryId = 7L,
            modelId = "provider:model",
        )
        val admission = ForegroundSendAdmission(
            target = target,
            generationSnapshot = testGenerationAdmissionSnapshot(
                conversationId = target.conversationId,
                runId = target.runId,
            ),
            newConversation = ChatEntity(target.conversationId, "New Chat"),
            newConversationSettings = null,
            newChatPersistSnapshot = NewChatPersistEntity(
                modelId = target.modelId,
                draftText = "sent",
                draftAttachments = Json.encodeToString(listOf(pending)),
            ),
        )

        val finalized = admission.withSettledComposerDraft("sent", listOf(settled))

        assertEquals(
            Json.encodeToString(listOf(settled)),
            finalized.newChatPersistSnapshot?.draftAttachments,
        )
        assertEquals(target.modelId, finalized.newChatPersistSnapshot?.modelId)
    }

    @Test
    fun duplicateTapIsRejectedButDifferentOwnersRemainIndependent() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fixture = Fixture(this, awaitGate = gate)

        assertTrue(fixture.controller.submit("owner-a", "first", emptyList()))
        assertFalse(fixture.controller.submit("owner-a", "second", emptyList()))
        assertTrue(fixture.controller.submit("owner-b", "other", emptyList()))
        runCurrent()

        assertEquals(ComposerSubmissionPhase.WAITING, fixture.controller.state("owner-a").value.phase)
        assertEquals(ComposerSubmissionPhase.WAITING, fixture.controller.state("owner-b").value.phase)
        fixture.controller.cancelWaiting("owner-a")
        fixture.controller.cancelWaiting("owner-b")
        runCurrent()
    }

    @Test
    fun clearFailureKeepsAcceptedRequestNonResendableUntilClearOnlyRetrySucceeds() = runTest {
        val fixture = Fixture(
            this,
            acceptance = SendAcceptance.Direct("message", "conversation"),
            clearSucceeded = false,
        )

        fixture.controller.submit("owner", "text", emptyList())
        runCurrent()

        assertEquals(
            ComposerSubmissionPhase.ACCEPTED_PENDING_CLEAR,
            fixture.controller.state("owner").value.phase,
        )
        assertFalse(fixture.controller.submit("owner", "text", emptyList()))
        assertEquals(1, fixture.sendCount)
        assertEquals(0L, fixture.controller.state("owner").value.acceptedVersion)

        fixture.clearSucceeded = true
        assertTrue(fixture.controller.retryAcceptedClear("owner"))
        runCurrent()

        assertEquals(ComposerSubmissionPhase.IDLE, fixture.controller.state("owner").value.phase)
        assertEquals(1L, fixture.controller.state("owner").value.acceptedVersion)
        assertEquals(1, fixture.sendCount)
        coVerify(exactly = 0) { fixture.drafts.reclaimAttachments(any()) }
    }

    @Test
    fun immediateCompletionDoesNotRetainAStaleRequestOrJob() = runTest {
        val fixture = Fixture(this, eagerScope = true)

        assertTrue(fixture.controller.submit("owner", " ", emptyList()))
        assertEquals(ComposerSubmissionPhase.IDLE, fixture.controller.state("owner").value.phase)
        assertTrue(fixture.controller.submit("owner", "text", emptyList()))
        assertEquals(ComposerSubmissionPhase.IDLE, fixture.controller.state("owner").value.phase)
        assertEquals(1, fixture.sendCount)
    }

    @Test
    fun exceptionAfterAuthoritativeAcceptanceStillCompletesAcceptedOwner() = runTest {
        val fixture = Fixture(
            this,
            acceptance = SendAcceptance.Direct("message", "conversation"),
            postAcceptanceFailure = IllegalStateException("after acceptance"),
        )

        fixture.controller.submit("owner", "text", emptyList())
        runCurrent()

        assertEquals(1L, fixture.controller.state("owner").value.acceptedVersion)
        coVerify(exactly = 1) { fixture.composers.clearAccepted("owner", false, 1L, any(), any(), any(), any()) }
    }

    @Test
    fun releasedUiOwnerIsEvictedAfterItsSubmissionSettles() = runTest {
        val fixture = Fixture(
            this,
            acceptance = SendAcceptance.Direct("message", "conversation"),
        )
        val observed = fixture.controller.observeState("owner")

        fixture.controller.submit("owner", "text", emptyList())
        runCurrent()
        assertEquals(1L, observed.value.acceptedVersion)

        fixture.controller.releaseState("owner")

        assertEquals(0L, fixture.controller.snapshot("owner").acceptedVersion)
        assertFalse(fixture.controller.isFrozen("owner"))
    }

    @Test
    fun duplicateAcceptanceCallbackClearsTheFrozenOwnerOnlyOnce() = runTest {
        val fixture = Fixture(
            this,
            acceptance = SendAcceptance.Direct("message", "conversation"),
            acceptanceCallbacks = 2,
        )

        fixture.controller.submit("owner", "text", emptyList())
        runCurrent()

        assertEquals(1L, fixture.controller.state("owner").value.acceptedVersion)
        coVerify(exactly = 1) { fixture.composers.clearAccepted("owner", false, 1L, any(), any(), any(), any()) }
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        attachments: List<SelectedAttachment> = emptyList(),
        private val acceptance: SendAcceptance? = null,
        private val awaitGate: CompletableDeferred<Unit>? = null,
        private val sendFailure: Throwable? = null,
        private val postAcceptanceFailure: Throwable? = null,
        private val acceptanceCallbacks: Int = 1,
        private val clearedAttachments: List<SelectedAttachment> = emptyList(),
        var clearSucceeded: Boolean = true,
        private val captureAllowed: Boolean = true,
        private val prepareAdmission: Boolean = true,
        eagerScope: Boolean = false,
    ) {
        val composers = mockk<ConversationComposerController>()
        val drafts = mockk<ComposerDraftController>()
        val events = mutableListOf<String>()
        val sentAttachments = mutableListOf<SelectedAttachment>()
        var sendCount = 0
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        private val submissionScope = if (eagerScope) {
            CoroutineScope(
                testScope.backgroundScope.coroutineContext +
                    UnconfinedTestDispatcher(testScope.testScheduler),
            )
        } else {
            testScope.backgroundScope
        }
        private val composerState = kotlinx.coroutines.flow.MutableStateFlow(
            ConversationComposerSnapshot(attachments = attachments, loaded = true),
        )
        val controller = ConversationComposerSubmissionController(
            scope = submissionScope,
            composers = composers,
            drafts = drafts,
            captureTarget = { ownerId ->
                if (!captureAllowed) {
                    null
                } else {
                    ForegroundSendTarget(
                        ownerId = ownerId,
                        conversationId = ownerId,
                        runId = "run:$ownerId",
                        wasNewChat = false,
                        newChatEntryId = null,
                        modelId = "provider:model",
                    )
                }
            },
            prepare = { target, _ ->
                events += "prepare:${target.ownerId}"
                if (!prepareAdmission) {
                    null
                } else {
                    ForegroundSendAdmission(
                        target = target,
                        generationSnapshot = testGenerationAdmissionSnapshot(
                            conversationId = target.conversationId,
                            runId = target.runId,
                        ),
                        newConversation = null,
                        newConversationSettings = null,
                    )
                }
            },
            send = { _, text, submitted, onAccepted ->
                sendCount += 1
                sentAttachments += submitted
                events += "send:$text:${submitted.joinToString { it.localId }}"
                sendFailure?.let { throw it }
                repeat(acceptanceCallbacks) { acceptance?.let { onAccepted(it) } }
                postAcceptanceFailure?.let { throw it }
                acceptance
            },
            ioDispatcher = dispatcher,
        )

        init {
            coEvery { composers.load(any()) } answers {
                events += "load:${firstArg<String>()}"
                composerState.value
            }
            coEvery { composers.freezeSubmission(any(), any(), any(), any()) } answers {
                val ids = arg<List<String>>(3)
                events += "freeze:${firstArg<String>()}:${secondArg<Long>()}:" +
                    "${thirdArg<String>()}:${ids.joinToString()}"
                composerState.value.copy(text = thirdArg())
            }
            coEvery { composers.state(any()) } returns composerState
            coEvery { composers.awaitProcessing(any(), any()) } coAnswers {
                val ids = secondArg<Set<String>>()
                events += "await:${firstArg<String>()}:${ids.joinToString()}"
                awaitGate?.await()
            }
            coEvery { composers.releaseSubmission(any(), any()) } answers {
                events += "unfreeze:${firstArg<String>()}:${secondArg<Long>()}"
                true
            }
            coEvery { composers.release(any()) } answers {
                events += "release:${firstArg<String>()}"
            }
            coEvery {
                composers.clearAccepted(any(), false, any(), any(), any(), any(), any())
            } answers {
                events += "clear:${firstArg<String>()}"
                DraftClearResult(
                    attachments = clearedAttachments,
                    revision = 1L,
                    succeeded = clearSucceeded,
                )
            }
            coEvery { drafts.reclaimAttachments(any()) } answers {
                events += "reclaim:${firstArg<List<SelectedAttachment>>().joinToString { it.localId }}"
            }
        }
    }

    private companion object {
        fun attachment(
            id: String,
            state: AttachmentImportState = AttachmentImportState.READY,
            storage: AttachmentStorage = AttachmentStorage.APP_PRIVATE,
            unavailable: Boolean = false,
        ) = SelectedAttachment(
            localId = id,
            uri = "uri:$id",
            type = "file",
            localPath = "/ready/$id",
            storage = storage,
            sandboxPath = if (storage.isLocalSandbox) "/home/agora/$id" else null,
            importState = state,
            preparedText = if (storage.isLocalSandbox) null else "prepared:$id",
            unavailable = unavailable,
        )
    }
}
