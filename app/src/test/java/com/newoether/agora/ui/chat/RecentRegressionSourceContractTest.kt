package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import com.newoether.agora.ui.chat.bottombar.composerSendActionEnabled
import com.newoether.agora.viewmodel.ComposerSubmissionPhase
import com.newoether.agora.viewmodel.ConversationComposerSubmissionSnapshot
import android.os.Trace
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame

class RecentRegressionSourceContractTest {
    @Test
    fun busySendCannotDispatchAnOtherwiseAvailableStopOrSend() {
        val expectedWhenActionAvailable = mapOf(
            ComposerSubmissionPhase.IDLE to true,
            ComposerSubmissionPhase.WAITING to true,
            ComposerSubmissionPhase.SUBMITTING to false,
            ComposerSubmissionPhase.ACCEPTED_PENDING_CLEAR to false,
        )
        expectedWhenActionAvailable.forEach { (phase, expected) ->
            val submission = ConversationComposerSubmissionSnapshot(phase = phase)
            listOf(true to false, false to true, true to true).forEach { (stop, send) ->
                assertEquals(phase.name, expected, composerSendActionEnabled(
                    submission, isSwitching = false, isStopping = false, stop, send,
                ))
                assertFalse(composerSendActionEnabled(
                    submission, isSwitching = true, isStopping = false, stop, send,
                ))
                assertFalse(composerSendActionEnabled(
                    submission, isSwitching = false, isStopping = true, stop, send,
                ))
            }
            assertEquals(phase == ComposerSubmissionPhase.WAITING, composerSendActionEnabled(
                submission, isSwitching = false, isStopping = false, showStop = false, canSend = false,
            ))
        }
        val button = source("ui/chat/bottombar/ComposerSendButton.kt")
        assertTrue(button.contains("enabled = isActionable"))
        val click = button.substringAfter("onClick = {").substringBefore("enabled = isActionable")
        assertTrue(click.indexOf("if (!isActionable) return@Surface") < click.indexOf("when {"))
    }

    @Test
    fun idleQueueKeepsTheSendButtonActionableWithAnEmptyComposer() {
        val submission = ConversationComposerSubmissionSnapshot(phase = ComposerSubmissionPhase.IDLE)
        assertFalse(composerSendActionEnabled(
            submission, isSwitching = false, isStopping = false, showStop = false, canSend = false,
        ))
        assertTrue(composerSendActionEnabled(
            submission, isSwitching = false, isStopping = false, showStop = false,
            canSend = false, canSendQueued = true,
        ))
        assertFalse(composerSendActionEnabled(
            submission, isSwitching = true, isStopping = false, showStop = false,
            canSend = false, canSendQueued = true,
        ))
        val button = source("ui/chat/bottombar/ComposerSendButton.kt")
        assertTrue(button.contains("val canSendQueued = hasQueuedSends && !isLoading"))
        assertTrue(button.contains("canSendQueued -> {"))
    }
    @Test
    fun attachmentPainterRemainsDrawnWhileLoading() {
        val source = source("ui/chat/bottombar/AttachmentPreviewRow.kt")
        val loading = source.substringAfter(
            "AttachmentPreviewPresentation.MEDIA_LOADING -> {",
        ).substringBefore("AttachmentPreviewPresentation.MEDIA_SUCCESS")

        assertTrue(loading.contains("Image("))
        assertTrue(loading.contains("painter = mediaPainter"))
        assertTrue(loading.contains("CircularProgressIndicator("))
    }

    @Test
    fun mediaUrlsAndIndexArePublishedAtomically() {
        val activity = source("MainActivity.kt")
        val dialog = source("ui/chat/FullScreenMediaPreviewDialog.kt")

        assertTrue(activity.contains("MediaPreviewTarget(urls, index)"))
        assertTrue(activity.contains("MediaPreviewTarget(pages, idx)"))
        assertFalse(activity.contains("fullScreenMediaUrls"))
        assertFalse(activity.contains("fullScreenMediaIndex"))
        assertTrue(activity.contains("currentTarget = mediaPreviewTarget"))
        assertTrue(activity.contains("if (mediaPreviewTarget?.requestId != target.requestId)"))
        assertTrue(activity.contains("if (mediaPreviewTarget?.requestId == target.requestId)"))
        assertTrue(dialog.contains("rememberMediaPreviewTargetForExit(currentTarget)"))
        assertTrue(dialog.contains("key(target.requestId)"))
        assertTrue(dialog.contains("initialIndex = target.index"))
        assertFalse(dialog.contains("LaunchedEffect(currentUrls, currentIndex)"))
        val viewer = source("ui/chat/FullScreenMediaViewer.kt")
        val entry = viewer.substringAfter("fun FullScreenMediaViewer(").substringBefore("// --- PDF pager")
        assertTrue(entry.indexOf("PdfPager(") < entry.indexOf("rememberIsVideoMedia(url)"))
        assertTrue(entry.indexOf("MediaPager(") < entry.indexOf("rememberIsVideoMedia(url)"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun previewReopeningUsesTheTappedOccurrenceBeforeAnyEffectAndKeepsOnePagerPerRequest() = runTest {
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } answers { }
        every { Trace.endSection() } answers { }
        val current = mutableStateOf<MediaPreviewTarget?>(null)
        val observations = mutableListOf<MediaPreviewTarget?>()
        var observedPager: PagerState? = null
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        var frame = 0L
        fun settle() {
            repeat(2) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(++frame * 16_000_000)
                runCurrent()
            }
        }
        try {
            composition.setContent {
                val target = rememberMediaPreviewTargetForExit(current.value)
                SideEffect { observations += target }
                if (target != null) key(target.requestId) {
                    val pager = rememberPagerState(initialPage = target.index) { target.urls.size }
                    SideEffect { observedPager = pager }
                }
            }
            settle()
            val urls = listOf("same.jpg", "video.mp4", "same.jpg")
            val first = MediaPreviewTarget(urls, 2)
            current.value = first
            observations.clear()
            settle()
            assertEquals(first, observations.first())
            assertEquals(2, observedPager!!.currentPage)
            val firstPager = observedPager
            current.value = first.copy(index = 1)
            settle()
            assertSame(firstPager, observedPager)
            current.value = null
            settle()
            assertEquals(first.copy(index = 1), observations.last())
            assertSame(firstPager, observedPager)
            val reopened = MediaPreviewTarget(urls, 0)
            assertNotEquals(first.requestId, reopened.requestId)
            current.value = reopened
            observations.clear()
            settle()
            assertEquals(reopened, observations.first())
            assertEquals(0, observedPager!!.currentPage)
            assertTrue(firstPager !== observedPager)
            current.value = null
            current.value = MediaPreviewTarget(listOf("new.jpg", "new-2.jpg"), 1)
            observations.clear()
            settle()
            assertEquals(current.value, observations.first())
            assertEquals(1, observedPager!!.currentPage)
        } finally {
            composition.dispose()
            recomposer.close()
            unmockkStatic(Trace::class)
        }
    }

    @Test
    fun everyDeleteKeepsTheDialogUntilCompletion() {
        val item = source("ui/chat/message/MessageItem.kt")
        val confirm = item.substringAfter("val onConfirmDelete = {")
            .substringBefore("if (pending.deletesConversation)")
        val effect = item.substringAfter("LaunchedEffect(confirmedDelete)")
            .substringBefore("pendingDelete?.let")
        val lifecycle = source("viewmodel/ConversationLifecycleController.kt")
        val deleteBody = lifecycle.substringAfter("scope.launch(ioDispatcher)")
            .substringBefore("return true")
        val dialogHost = source("ui/chat/ChatAppDialogHost.kt")
        val conversationConfirm = dialogHost.substringAfter("ChatDeleteConfirmDialog(")
            .substringBefore("onDismiss = state::dismissDelete")
        val messageDialog = source("ui/chat/message/MessageDialogs.kt")
            .substringAfter("internal fun MessageDeleteDialog(")

        assertFalse(confirm.contains("pendingDelete = null"))
        assertTrue(item.contains("pending = confirmedDelete != null"))
        assertTrue(effect.contains("pendingDelete = if (deleted) null else confirmed"))
        assertTrue(effect.indexOf("withFrameNanos") < effect.indexOf("onDeleteConversation"))
        assertTrue(effect.indexOf("withFrameNanos") < effect.indexOf("onDelete("))
        assertTrue(
            deleteBody.indexOf("beginSelectedDeleteTransition") <
                deleteBody.indexOf("tryWithConversationLock"),
        )
        assertTrue(
            conversationConfirm.indexOf("state.beginDelete(id)") <
                conversationConfirm.indexOf("deleteConversation()"),
        )
        assertTrue(
            conversationConfirm.indexOf("withFrameNanos") <
                conversationConfirm.indexOf("deleteConversation()"),
        )
        assertFalse(conversationConfirm.contains("state.completeDelete(id)"))
        assertTrue(messageDialog.contains("dismissOnBackPress = !pending"))
        assertTrue(messageDialog.contains("dismissOnClickOutside = !pending"))
        assertTrue(messageDialog.contains("enabled = enabled && !pending"))
        val taskEditor = source("ui/tasks/TaskEditorPage.kt")
        val taskConfirmation = taskEditor.substringAfter("executionToDelete?.let {")
            .substringBefore("/** A group row")
        val taskDeletion = taskEditor.substringAfter("LaunchedEffect(executionDeleteId, executionDeletePhase)")
            .substringBefore("val savedListIndex")
        assertTrue(taskConfirmation.contains("phase = executionDeletePhase"))
        assertFalse(taskConfirmation.substringBefore("onDismiss").contains("executionToDelete = null"))
        assertTrue(taskConfirmation.contains("executionDeletePhase != ChatDeleteDialogPhase.PENDING"))
        assertTrue(taskDeletion.indexOf("withFrameNanos") < taskDeletion.indexOf("viewModel.deleteConversation"))
        assertTrue(taskDeletion.contains("executionToDelete?.conversation?.id == executionDeleteId"))
        assertTrue(taskDeletion.contains("if (deleted) executionToDelete = null"))
        assertTrue(taskDeletion.contains("if (!accepted) executionDeletePhase = ChatDeleteDialogPhase.FAILED"))
    }

    private fun source(relativePath: String): String =
        File(mainSourceRoot(), "com/newoether/agora/$relativePath")
            .readText()
            .replace("\r\n", "\n")

    private fun mainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(directory, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile ?: error("Unable to locate app/src/main/java")
        }
    }
}
