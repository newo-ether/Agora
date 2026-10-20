package com.newoether.agora.ui.chat

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.DialogWindowNoSystemDim
import java.util.UUID
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.motion.animateFloat

internal data class MediaPreviewTarget(
    val urls: List<String>,
    val index: Int,
    val requestId: UUID = UUID.randomUUID(),
)

/** Retention supplies exit content only; a new open must initialize from its current target. */
@Composable
internal fun rememberMediaPreviewTargetForExit(current: MediaPreviewTarget?): MediaPreviewTarget? {
    var retained by remember { mutableStateOf<MediaPreviewTarget?>(null) }
    SideEffect { if (current != null) retained = current }
    return current ?: retained
}

private const val MEDIA_PREVIEW_BACKDROP_ENTER_DURATION_MS = 220
private const val MEDIA_PREVIEW_BACKDROP_EXIT_DURATION_MS = 180

/**
 * Hosts media in a dedicated window so it sits above source sheets. Sheets opened from the
 * viewer are composed afterward and therefore remain above the viewer window.
 */
@Composable
internal fun FullScreenMediaPreviewDialog(
    currentTarget: MediaPreviewTarget?,
    currentPdfPages: List<String>,
    currentPdfSelectedPages: Set<Int>,
    currentPdfSelectionEnabled: Boolean,
    currentPdfTogglePage: ((Int) -> Unit)?,
    enter: EnterTransition,
    exit: ExitTransition,
    onHidden: () -> Unit,
    onClose: (MediaPreviewTarget) -> Unit,
    onNavigate: (MediaPreviewTarget, Int) -> Unit,
    onMessage: (String) -> Unit,
    hapticsEnabled: Boolean,
) {
    val visibilityTransition = updateTransition(
        targetState = currentTarget != null,
        label = "mediaPreviewDialog",
    )
    val backdropAlpha by visibilityTransition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (false isTransitioningTo true) {
                    MEDIA_PREVIEW_BACKDROP_ENTER_DURATION_MS
                } else {
                    MEDIA_PREVIEW_BACKDROP_EXIT_DURATION_MS
                },
            )
        },
        label = "mediaPreviewBackdropAlpha",
    ) { visible ->
        if (visible) 1f else 0f
    }
    val latestTarget by rememberUpdatedState(currentTarget)
    LaunchedEffect(visibilityTransition) {
        snapshotFlow {
            visibilityTransition.currentState to visibilityTransition.isRunning
        }.collect { (currentState, isRunning) ->
            if (!currentState && !isRunning && latestTarget == null) onHidden()
        }
    }

    val target = rememberMediaPreviewTargetForExit(currentTarget)
    var retainedPdfPages by remember { mutableStateOf<List<String>>(emptyList()) }
    var retainedPdfSelectionEnabled by remember { mutableStateOf(false) }
    var retainedPdfTogglePage by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    SideEffect {
        if (currentTarget != null) {
            retainedPdfPages = currentPdfPages
            retainedPdfSelectionEnabled = currentPdfSelectionEnabled
            retainedPdfTogglePage =
                if (currentPdfSelectionEnabled) currentPdfTogglePage else null
        }
    }

    if (target == null) return
    if (
        !visibilityTransition.currentState &&
        !visibilityTransition.targetState &&
        !visibilityTransition.isRunning
    ) {
        return
    }
    val pdfPages = if (currentTarget != null) currentPdfPages else retainedPdfPages
    val pdfSelectionEnabled = if (currentTarget != null) currentPdfSelectionEnabled else retainedPdfSelectionEnabled
    val togglePdfPage = if (currentTarget != null) {
        currentPdfTogglePage.takeIf { currentPdfSelectionEnabled }
    } else retainedPdfTogglePage
    Dialog(
        onDismissRequest = { onClose(target) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        DialogWindowEdgeToEdge()
        DialogWindowNoSystemDim()
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = backdropAlpha }
                    .background(Color.Black),
            )
            visibilityTransition.AnimatedVisibility(
                visible = { it },
                enter = enter,
                exit = exit,
                modifier = Modifier.fillMaxSize(),
            ) {
                key(target.requestId) {
                    FullScreenMediaViewer(
                        urls = target.urls,
                        initialIndex = target.index,
                        pdfPages = pdfPages,
                        pdfSelectedPages = currentPdfSelectedPages.takeIf {
                            pdfPages.isNotEmpty() && pdfSelectionEnabled
                        },
                        onTogglePdfPage = togglePdfPage,
                        onClose = { onClose(target) },
                        onNavigate = { onNavigate(target, it) },
                        onMessage = onMessage,
                        hapticsEnabled = hapticsEnabled,
                    )
                }
            }
        }
    }
}
