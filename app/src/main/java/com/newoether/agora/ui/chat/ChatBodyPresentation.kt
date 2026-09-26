package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.ui.components.TypewriterMode
import com.newoether.agora.ui.components.TypewriterText
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator

internal fun AnimatedContentTransitionScope<Pair<Boolean, Boolean>>.chatMainContentTransition(
    motionPolicy: AgoraMotionPolicy,
    pivotY: Float,
): ContentTransform {
    val targetNewChat = targetState.first
    val targetShowLaunch = targetState.second
    val initialNewChat = initialState.first
    val initialShowLaunch = initialState.second

    return if (targetNewChat && (targetShowLaunch != initialShowLaunch || targetNewChat != initialNewChat)) {
        val fadeInSpec = tween<Float>(500)
        val enter = if (motionPolicy.allowSpatialTransitions) {
            val enterSpec = tween<Float>(
                700,
                easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f),
            )
            fadeIn(animationSpec = fadeInSpec) +
                scaleIn(
                    initialScale = 0.6f,
                    transformOrigin = TransformOrigin(0.5f, pivotY),
                    animationSpec = enterSpec,
                )
        } else {
            fadeIn(animationSpec = fadeInSpec)
        }
        enter
            .togetherWith(fadeOut(animationSpec = tween(300)))
    } else if (!targetNewChat && !initialNewChat) {
        // Switching between existing conversations: no animation
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        // Returning from new-chat to an existing conversation
        fadeIn(animationSpec = tween(300))
            .togetherWith(fadeOut(animationSpec = tween(300)))
    }
}

@Composable
internal fun ChatWelcomeContent(
    bottomBarHeight: Dp,
    windowHeightDp: Float,
    topBarH: Dp,
    newChatEntryId: Long,
    newChatMotion: NewChatMotionPolicy,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomBarHeight),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            val welcomeText = stringResource(R.string.welcome_to_agora)
            val availableWelcomeHeight =
                windowHeightDp +
                    topBarH.value / 2f -
                    bottomBarHeight.value
            val welcomeTopPadding =
                (availableWelcomeHeight / 2f).coerceAtLeast(0f).dp
            val welcomeModifier =
                Modifier.padding(top = welcomeTopPadding)
            TypewriterText(
                text = welcomeText,
                animationKey = newChatEntryId,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                typeSpeedMs = 100,
                animate = newChatMotion.animateWelcomeText,
                mode = TypewriterMode.TEXT_GRADIENT,
                modifier = welcomeModifier,
            )
        }
    }
}

@Composable
internal fun BoxScope.ChatSelectionOverlay(
    shareSelectionActive: Boolean,
    motionPolicy: AgoraMotionPolicy,
    bottomBarHeight: Dp,
    selectableShareMessageIds: Set<String>,
    selectedShareMessageIds: Set<String>,
    conversationInteraction: ConversationInteractionProjection,
    haptics: AgoraHaptics,
    onShareMessages: (Set<String>) -> Unit,
) {
    AnimatedVisibility(
        visible = shareSelectionActive,
        enter = if (motionPolicy.allowSpatialTransitions) {
            fadeIn(tween(220)) + scaleIn(
                initialScale = 0.86f,
                animationSpec = tween(220),
            )
        } else {
            fadeIn(tween(220))
        },
        exit = if (motionPolicy.allowSpatialTransitions) {
            fadeOut(tween(180)) + scaleOut(
                targetScale = 0.86f,
                animationSpec = tween(180),
            )
        } else {
            fadeOut(tween(180))
        },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomBarHeight + 10.dp),
    ) {
        ShareSelectionFab(
            allSelected = selectableShareMessageIds.isNotEmpty() &&
                selectedShareMessageIds.containsAll(selectableShareMessageIds),
            hasSelection = selectedShareMessageIds.isNotEmpty(),
            onDismiss = {
                conversationInteraction.dismissShareSelection()
            },
            onToggleAll = {
                haptics.selection()
                conversationInteraction.toggleAllShareMessages()
            },
            onConfirm = {
                val selection = conversationInteraction.takeShareSelection()
                if (selection.isNotEmpty()) {
                    onShareMessages(selection)
                }
            },
        )
    }
}

@Composable
internal fun ChatSwitchingOverlay(isSwitching: Boolean, isTransitioningToNewChat: Boolean) {
    AnimatedVisibility(
        visible = isSwitching && !isTransitioningToNewChat,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 5.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
