package com.newoether.agora.ui.chat

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.ui.chat.message.hasActiveAnswerSegment
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val INLINE_SHARE_LIMIT_BYTES = 256 * 1024
private const val SHARE_ERROR_DETAIL_TOKEN = "__AGORA_SHARE_ERROR_DETAIL__"
private const val STREAM_SCROLL_RESUME_DELAY_MS = 160L
internal const val DRAWER_COMPOSER_DISMISS_THRESHOLD = 0.5f

internal fun drawerPastComposerDismissThreshold(progress: Float): Boolean =
    progress > DRAWER_COMPOSER_DISMISS_THRESHOLD

internal data class NewChatMotionPolicy(
    val animateBackground: Boolean,
    val animateWelcomeText: Boolean,
)

internal fun newChatMotionPolicy(
    reduceMotion: Boolean,
    isNewChatMode: Boolean,
    isLoading: Boolean,
    isSwitching: Boolean,
    newChatEntryId: Long,
): NewChatMotionPolicy {
    if (reduceMotion) {
        return NewChatMotionPolicy(
            animateBackground = false,
            animateWelcomeText = false,
        )
    }
    return NewChatMotionPolicy(
        animateBackground = isNewChatMode && !isLoading && !isSwitching,
        animateWelcomeText = newChatEntryId == 1L,
    )
}

/**
 * Text/argument growth within an existing message tree can be coalesced while LazyColumn owns a
 * scroll animation. Structural changes remain immediate so a new thinking/tool block or lifecycle
 * state is never hidden behind the gate.
 */
internal fun sameStreamingRenderStructure(
    previous: List<ChatMessage>,
    next: List<ChatMessage>,
): Boolean {
    if (previous.size != next.size) return false
    return previous.indices.all { index ->
        val before = previous[index]
        val after = next[index]
        if (before === after) return@all true
        if (
            before.id != after.id ||
            before.parentId != after.parentId ||
            before.participant != after.participant ||
            before.status != after.status ||
            before.images.size != after.images.size ||
            before.retryText != after.retryText ||
            before.thoughts.isNullOrBlank() != after.thoughts.isNullOrBlank()
        ) {
            return@all false
        }
        val beforeSegments = before.segments
        val afterSegments = after.segments
        if (beforeSegments == null || afterSegments == null) {
            return@all beforeSegments == null && afterSegments == null
        }
        if (beforeSegments.size != afterSegments.size) return@all false
        beforeSegments.indices.all { segmentIndex ->
            val beforeSegment = beforeSegments[segmentIndex]
            val afterSegment = afterSegments[segmentIndex]
            beforeSegment.type == afterSegment.type &&
                beforeSegment.toolCallId == afterSegment.toolCallId &&
                beforeSegment.toolName == afterSegment.toolName &&
                beforeSegment.toolState == afterSegment.toolState &&
                (beforeSegment.toolResult == null) == (afterSegment.toolResult == null)
        }
    }
}

@Composable
internal fun rememberScrollIsolatedMessages(
    conversationId: String?,
    upstream: State<List<ChatMessage>>,
    listState: LazyListState,
    bypassScrollIsolation: Boolean,
    eInkMode: Boolean = false,
    presentationIdentity: Any? = null,
): State<List<ChatMessage>> {
    val rendered = remember(conversationId, upstream, eInkMode, presentationIdentity) {
        mutableStateOf(upstream.value)
    }
    val latestBypassScrollIsolation by rememberUpdatedState(bypassScrollIsolation)
    LaunchedEffect(conversationId, upstream, listState, eInkMode, presentationIdentity) {
        if (eInkMode) {
            snapshotFlow { upstream.value }.coalesceEinkMessages().collect { rendered.value = it }
            return@LaunchedEffect
        }
        coroutineScope {
            var latest = upstream.value
            var deferred = listState.isScrollInProgress
            var hasOwnedScroll = listState.isScrollInProgress
            var resumeJob: Job? = null

            launch {
                snapshotFlow {
                    listState.isScrollInProgress to latestBypassScrollIsolation
                }
                    .distinctUntilChanged()
                    .collect { (scrolling, bypass) ->
                        resumeJob?.cancel()
                        if (bypass) {
                            deferred = false
                            hasOwnedScroll = false
                            if (rendered.value !== latest) rendered.value = latest
                        } else if (scrolling) {
                            hasOwnedScroll = true
                            deferred = true
                        } else if (hasOwnedScroll) {
                            deferred = true
                            resumeJob = launch {
                                delay(STREAM_SCROLL_RESUME_DELAY_MS)
                                deferred = false
                                hasOwnedScroll = false
                                if (rendered.value !== latest) {
                                    rendered.value = latest
                                }
                            }
                        } else {
                            // Initial idle observation: do not impose a synthetic 160 ms delay on
                            // the first provider token.
                            deferred = false
                        }
                    }
            }

            launch {
                snapshotFlow { upstream.value }
                    .distinctUntilChanged()
                    .collect { next ->
                        latest = next
                        if (
                            latestBypassScrollIsolation ||
                            !deferred ||
                            !sameStreamingRenderStructure(rendered.value, next)
                        ) {
                            rendered.value = next
                        }
                    }
            }
        }
    }
    return rendered
}

internal suspend fun launchConversationShare(
    context: Context,
    text: String,
    chooserTitle: String,
) {
    val sendIntent = withContext(Dispatchers.IO) {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        if (utf8.size <= INLINE_SHARE_LIMIT_BYTES) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        } else {
            val shareDirectory = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File.createTempFile("agora_conversation_", ".md", shareDirectory).apply {
                writeBytes(utf8)
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Agora conversation", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
    withContext(Dispatchers.Main.immediate) {
        val chooser = Intent.createChooser(sendIntent, chooserTitle)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

@Composable
internal fun ConversationShareEffect(
    viewModel: ChatViewModel,
    context: Context,
) {
    val shareChooserTitle = stringResource(R.string.conversation_share)
    val shareFailureTemplate = stringResource(
        R.string.conversation_share_failed,
        SHARE_ERROR_DETAIL_TOKEN,
    )
    LaunchedEffect(viewModel, context, shareChooserTitle, shareFailureTemplate) {
        viewModel.conversationShareText.collect { text ->
            try {
                launchConversationShare(
                    context = context,
                    text = text,
                    chooserTitle = shareChooserTitle,
                )
            } catch (e: Exception) {
                DebugLog.e("ChatShare", "Unable to launch conversation share", e)
                viewModel.emitSnackbar(
                    shareFailureTemplate.replace(
                        SHARE_ERROR_DETAIL_TOKEN,
                        e.localizedMessage?.let(viewModel::displayText)
                            ?: e.javaClass.simpleName,
                    )
                )
            }
        }
    }
}

@Composable
internal fun ChatLaunchInteractionEffects(
    initialComposerFocusReady: Boolean,
    inputFocusRequester: FocusRequester,
    onShowLaunchContent: () -> Unit,
    onInitialFocusRequested: () -> Unit = {},
) {
    val latestOnShowLaunchContent by rememberUpdatedState(onShowLaunchContent)
    val latestOnInitialFocusRequested by rememberUpdatedState(onInitialFocusRequested)
    LaunchedEffect(Unit) {
        delay(50)
        latestOnShowLaunchContent()
    }
    LaunchedEffect(initialComposerFocusReady, inputFocusRequester) {
        if (initialComposerFocusReady) {
            delay(50)
            inputFocusRequester.requestFocus()
            latestOnInitialFocusRequested()
        }
    }
}

@Composable
internal fun ChatNavigationEffects(
    drawerState: ChatDrawerState,
    focusManager: FocusManager,
    scope: CoroutineScope,
    motionPolicy: AgoraMotionPolicy,
    onNavigateBack: (() -> Unit)?,
    conversationInteraction: ConversationInteractionProjection,
    onCollapseComposer: () -> Unit,
) {
    BackHandler(enabled = drawerState.shouldHandleBack) {
        focusManager.clearFocus()
        scope.launch { drawerState.closeFromBack(motionPolicy) }
    }
    BackHandler(
        enabled = onNavigateBack != null && !drawerState.shouldHandleBack,
    ) {
        focusManager.clearFocus()
        onNavigateBack?.invoke()
    }
    BackHandler(enabled = conversationInteraction.searchActive) {
        conversationInteraction.dismissSearch()
        focusManager.clearFocus()
    }
    BackHandler(enabled = conversationInteraction.shareSelectionActive) {
        conversationInteraction.dismissShareSelection()
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerPastComposerDismissThreshold(drawerState.progress) }
            .distinctUntilChanged()
            .collect { pastThreshold ->
                if (pastThreshold) {
                    onCollapseComposer()
                    focusManager.clearFocus()
                }
            }
    }
}

@Composable
internal fun SendAcceptedHapticBindingEffect(
    viewModel: ChatViewModel,
    haptics: AgoraHaptics,
    chatHapticActive: Boolean,
) {
    val latestChatHapticActive by rememberUpdatedState(chatHapticActive)
    DisposableEffect(viewModel, haptics) {
        viewModel.onSendAccepted = { _, _ ->
            if (latestChatHapticActive) haptics.confirm()
        }
        onDispose { viewModel.onSendAccepted = null }
    }
}

internal data class ComposerSpacerAnimation(
    val outerHeightPx: Float,
    val isRunning: Boolean,
)

@Composable
internal fun rememberComposerSpacerAnimation(
    isExpanded: Boolean,
    allowSpatialTransitions: Boolean,
    expandedHeightPx: Float,
): ComposerSpacerAnimation {
    val spacerProgress = remember { Animatable(0f) }
    val spacerEasing = remember { CubicBezierEasing(0.15f, 0.5f, 0.25f, 1.0f) }
    LaunchedEffect(isExpanded, allowSpatialTransitions) {
        if (isExpanded) {
            if (allowSpatialTransitions) {
                spacerProgress.snapTo(0f)
                spacerProgress.animateTo(1f, tween(400, easing = spacerEasing))
            } else {
                spacerProgress.snapTo(1f)
            }
        } else {
            spacerProgress.snapTo(0f)
        }
    }
    return ComposerSpacerAnimation(
        outerHeightPx = if (isExpanded) {
            expandedHeightPx * (1f - spacerProgress.value)
        } else {
            0f
        },
        isRunning = spacerProgress.isRunning,
    )
}

@Composable
internal fun SnackbarOffsetEffect(
    drawerProgress: Float,
    isExpanded: Boolean,
    bottomBarHeight: Dp,
    settingsButtonTopDp: Float,
    bottomInset: Dp,
    onOffsetChanged: (Dp) -> Unit,
) {
    val expandedCapsuleOffset = bottomInset + 74.dp
    val targetSnackbarOffset = if (drawerProgress <= 0.5f) {
        if (isExpanded) expandedCapsuleOffset else (bottomBarHeight - 4.dp).coerceAtLeast(0.dp)
    } else {
        val t = ((drawerProgress - 0.5f) * 2f).coerceIn(0f, 1f)
        (bottomBarHeight.value + (settingsButtonTopDp - bottomBarHeight.value) * t).dp
    }
    LaunchedEffect(targetSnackbarOffset) { onOffsetChanged(targetSnackbarOffset) }
}

internal fun answeringHapticEligible(
    snapshot: com.newoether.agora.viewmodel.ConversationGenerationSnapshot,
    presentation: com.newoether.agora.TopLevelPresentation,
): Boolean = presentation == com.newoether.agora.TopLevelPresentation.CHAT &&
    snapshot.isLoading && snapshot.isGenerating &&
    snapshot.streamingMessage?.let { message ->
        !message.isContextCompact() &&
            message.participant == Participant.MODEL &&
            message.status == MessageStatus.SENDING && message.hasActiveAnswerSegment()
    } == true

@Composable
internal fun AnsweringHapticEffect(
    generationSnapshot: com.newoether.agora.viewmodel.ConversationGenerationSnapshot,
    topLevelPresentation: com.newoether.agora.TopLevelPresentation,
    hapticsEnabled: Boolean,
    haptics: com.newoether.agora.ui.common.AgoraHaptics,
) {
    val answeringHapticActive = answeringHapticEligible(
        generationSnapshot,
        topLevelPresentation,
    )
    val appInForeground by com.newoether.agora.service.AppForegroundTracker.foreground.collectAsState()
    DisposableEffect(answeringHapticActive, hapticsEnabled, appInForeground, haptics) {
        if (answeringHapticActive && hapticsEnabled && appInForeground) {
            haptics.startAnsweringTexture()
        }
        onDispose {
            haptics.stopAnsweringTexture()
        }
    }
}

// isVisibleAnswerSegment() / hasActiveAnswerSegment() are shared (internal) from
// MessageItemSegments.kt.
