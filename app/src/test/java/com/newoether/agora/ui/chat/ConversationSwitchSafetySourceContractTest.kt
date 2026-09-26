package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSwitchSafetySourceContractTest {
    @Test
    fun `conversation switch observes current projection without a fixed deadline`() {
        val source = File(
            locateMainSourceRoot(),
            "com/newoether/agora/ui/chat/ChatScrollCoordinator.kt",
        ).readText().replace("\r\n", "\n")

        assertFalse(
            "conversation projection latency must not terminalize the switch on a timer",
            source.contains("CONVERSATION_RESOLVE_TIMEOUT_MS"),
        )
        assertTrue(
            "the switch effect must observe the latest selected conversation id",
            source.contains(
                "rememberUpdatedState(currentConversationId)",
            ),
        )
        assertTrue(
            "the switch effect must observe the latest durable conversation projection",
            source.contains(
                "rememberUpdatedState(currentConversation)",
            ),
        )
        assertTrue(
            "the switch effect must observe the latest Room message projection id",
            Regex(
                """rememberUpdatedState\(\s*loadedMessagesConversationId,?\s*\)""",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the switch effect must observe the latest context projection",
            source.contains("rememberUpdatedState(contextProjection)"),
        )
        assertTrue(
            "the cover must wait for the visible conversation identity",
            source.contains("projection.conversationId == targetConversationId"),
        )
        assertTrue(
            "the cover must wait for the visible selected branch identity",
            source.contains(
                "projection.selectedBranchesJson == conversation.selectedBranchesJson",
            ),
        )
        assertTrue(
            "matching success or failure must be explicit before uncovering",
            source.contains("projection.completed") && source.contains("!projection.loading"),
        )
        val projectionWaitStart = source.indexOf("val projection = latestContextProjection")
        val layoutSettleStart = source.indexOf("settleCoveredTransition", projectionWaitStart)
        assertTrue("matching context wait must precede layout settlement", projectionWaitStart >= 0 && layoutSettleStart > projectionWaitStart)
        assertFalse(
            "context projection latency must not terminalize the switch on a timer",
            source.substring(projectionWaitStart, layoutSettleStart).contains("withTimeoutOrNull"),
        )
        assertFalse(
            "a failed matching projection must still release the cover neutrally",
            source.substring(projectionWaitStart, layoutSettleStart).contains("projection.failed"),
        )
        assertFalse(
            "projection latency must never be interpreted as a request to enter New Chat",
            source.contains("viewModel.createNewChat()"),
        )
    }

    @Test
    fun `pending attachment send retains its exact composer owner across switching`() {
        val root = locateMainSourceRoot()
        val sendButton = File(
            root,
            "com/newoether/agora/ui/chat/bottombar/ComposerSendButton.kt",
        ).readText().replace("\r\n", "\n")
        val submission = File(
            root,
            "com/newoether/agora/viewmodel/ConversationComposerSubmissionController.kt",
        ).readText().replace("\r\n", "\n")
        val generation = File(
            root,
            "com/newoether/agora/viewmodel/MessageGenerationController.kt",
        ).readText().replace("\r\n", "\n")
        val drawer = File(
            root,
            "com/newoether/agora/ui/chat/ChatDrawerContent.kt",
        ).readText().replace("\r\n", "\n")

        assertTrue(
            "attachment membership must freeze at the tap-time owner snapshot",
            sendButton.contains(
                "attachmentIds = snapshot.attachments.map(SelectedAttachment::localId)",
            ),
        )
        assertTrue(
            "the waiting barrier must retain and use the exact tap-time owner",
            submission.contains("composers.freezeSubmission(") &&
                submission.contains(
                    "composers.awaitProcessing(request.ownerId, request.attachmentIds.toSet())",
                ),
        )
        assertTrue(
            "the exact owner retain and freeze must always release non-cancellably",
            submission.contains("withContext(NonCancellable)") &&
                submission.contains("composers.releaseSubmission(request.ownerId, request.id)") &&
                submission.contains("composers.release(request.ownerId)"),
        )
        assertTrue(
            "target and admission configuration must be captured before attachment waiting",
            submission.indexOf("val admission = prepare(request.target, frozen)") in
                0 until submission.indexOf(
                    "composers.awaitProcessing(request.ownerId, request.attachmentIds.toSet())",
                ),
        )
        assertTrue(
            "foreground send must route only through the captured target",
            generation.contains("internal fun captureForegroundSendTarget(") &&
                generation.contains("genId = target.conversationId") &&
                generation.contains("proposedRunId = target.runId"),
        )
        assertFalse(
            "foreground send must not retain an entry point that re-reads selection after tap",
            generation.contains("private suspend fun sendMessageOffMain("),
        )
        assertTrue(
            "drawer Delete must observe the exact conversation submission freeze",
            drawer.contains(
                "viewModel.conversationComposerSubmission\n" +
                    "            .activeOwnerIds\n" +
                    "            .collectAsState()",
            ) &&
                drawer.contains(
                    "conversation.id !in submittingConversationIds",
                ) &&
                drawer.contains("enabled = deleteEnabled"),
        )
        assertFalse(
            "the removed UI attachment owner must not drive submission through pendingSend",
            sendButton.contains("pendingSend") || submission.contains("pendingSend"),
        )
    }

    @Test
    fun `context rollout dims only classified rows through legacy message subtree alpha`() {
        val root = locateMainSourceRoot()
        val chatApp = File(root, "com/newoether/agora/ui/chat/ChatApp.kt").readText().replace("\r\n", "\n")
        val messageList = File(root, "com/newoether/agora/ui/chat/MessageList.kt").readText().replace("\r\n", "\n")
        val messageItem = File(
            root,
            "com/newoether/agora/ui/chat/message/MessageItem.kt",
        ).readText().replace("\r\n", "\n")
        val userBubble = File(
            root,
            "com/newoether/agora/ui/chat/message/UserMessageBubble.kt",
        ).readText().replace("\r\n", "\n")
        val assistantContent = File(
            root,
            "com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        ).readText().replace("\r\n", "\n")

        assertTrue(
            "rollout must be disabled until a matching successful projection is ready",
            chatApp.contains("visualizeContextRollout && contextProjectionReady"),
        )
        listOf(
            "MessageStatus.SENDING",
            "MessageStatus.THINKING",
            "MessageStatus.TOOL_CALLING",
            "MessageStatus.TRANSCRIBING",
        ).forEach { status ->
            assertTrue("active MODEL status $status must stay in normal presentation", status in messageList)
        }
        assertTrue(
            "active MODEL rows must bypass rollout coloring",
            Regex("""messageIsStreaming\s*\|\|\s*\(\s*!isRetainedBranchReplacementExit""")
                .containsMatchIn(messageList),
        )
        assertTrue(
            "only the existing rolled-out classification may target dimmed alpha",
            messageItem.contains(
                "targetValue = if (visualizeContextRollout && !isInContext) 0.38f else 1f",
            ),
        )
        assertTrue(
            "rollout opacity must animate in both directions with the local 240 ms tween",
            messageItem.contains("val contextAlphaValue by animateFloatAsState(") &&
                messageItem.contains("animationSpec = tween(durationMillis = 240)"),
        )
        assertTrue(
            "the animated opacity must still cover the complete legacy message subtree",
            messageItem.contains("val contextAlpha = Modifier.alpha(contextAlphaValue)"),
        )
        assertFalse(
            "rollout must not jump directly to the dimmed opacity",
            messageItem.contains("Modifier.alpha(0.38f)"),
        )
        assertFalse(
            "rollout must not replace semantic text colors",
            "contentTextColor" in messageItem,
        )
        assertTrue(
            "assistant markdown must keep the original text color inside the dimmed subtree",
            Regex("""rememberChatMarkdownAssets\(\s*textColor""")
                .containsMatchIn(messageItem),
        )
        assertTrue(
            "user text must keep the original text color inside the dimmed subtree",
            messageItem.contains("textColor = textColor"),
        )
        assertTrue(
            "compact rollout must dim the complete compact container",
            Regex("""fillMaxWidth\(\)\s*\.then\(contextAlpha\)""")
                .containsMatchIn(messageItem),
        )
        assertTrue(
            "user bubble and branch navigation must both receive the legacy alpha modifier",
            "contextAlpha: Modifier" in userBubble &&
                Regex("""\.then\(contextAlpha\)""").findAll(userBubble).count() >= 2,
        )
        assertTrue(
            "the complete assistant message subtree must receive the legacy alpha modifier",
            "contextAlpha: Modifier" in assistantContent &&
                ".then(contextAlpha)" in assistantContent,
        )
    }

    @Test
    fun `scroll to bottom visibility remembers every captured plain value`() {
        val source = File(
            locateMainSourceRoot(),
            "com/newoether/agora/ui/chat/ChatBottomScrollVisibility.kt",
        ).readText().replace("\r\n", "\n")
        val rememberStart = source.indexOf("return remember(")
        val derivedStart = source.indexOf("derivedStateOf", startIndex = rememberStart)
        assertTrue("scroll button derived state must exist", rememberStart >= 0 && derivedStart > 0)
        val rememberKeys = source.substring(rememberStart, derivedStart)

        listOf(
            "shareSelectionActive",
            "isNearAbsoluteBottom",
            "absoluteBottomScrollPhase",
        ).forEach { key ->
            assertTrue("scroll button must recreate its closure when $key changes", key in rememberKeys)
        }
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
