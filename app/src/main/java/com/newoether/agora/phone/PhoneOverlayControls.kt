package com.newoether.agora.phone

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.interaction.InteractionDeck
import com.newoether.agora.ui.chat.interaction.QuestionDrafts
import com.newoether.agora.ui.chat.interaction.UserInteraction
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.AskUserController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun PhoneControls(
    runtime: PhoneUseRuntime,
    epoch: Long,
    focusable: (Boolean) -> Unit,
    bounds: (String, Rect?) -> Unit,
    visible: Boolean = true,
) {
    val held by runtime.lease.held.collectAsState()
    val requests by runtime.askUser.requests.collectAsState()
    val action by runtime.actionName.collectAsState()
    val liveOwner = held?.takeIf { it.epoch == epoch }
    var retainedOwner by remember(epoch) { mutableStateOf(liveOwner) }
    if (liveOwner != null) SideEffect { retainedOwner = liveOwner }
    val owner = liveOwner ?: retainedOwner ?: return
    val active = visible && liveOwner != null
    val liveQuestions = requests.filter { it.conversationId == owner.owner.conversationId && it.runId == owner.owner.runId }
    var retainedQuestions by remember(epoch) { mutableStateOf(liveQuestions) }
    var retainedAction by remember(epoch) { mutableStateOf(action) }
    if (active) SideEffect { retainedQuestions = liveQuestions; retainedAction = action }
    val questions = if (active) liveQuestions else retainedQuestions
    val displayedAction = if (active) action else retainedAction
    var elapsed by remember(owner.epoch) { mutableLongStateOf(0) }
    LaunchedEffect(owner.epoch, active) {
        if (active) while (true) {
            elapsed = (SystemClock.elapsedRealtime() - owner.claimedAt) / 1000
            delay(1000)
        }
    }
    val motion = LocalAgoraMotionPolicy.current
    val reveal = remember(epoch) { Animatable(0f) }
    LaunchedEffect(active, motion.systemAnimationsDisabled) {
        if (motion.systemAnimationsDisabled) reveal.snapTo(if (active) 1f else 0f)
        else reveal.animateTo(if (active) 1f else 0f, tween(240, easing = FastOutSlowInEasing))
    }
    val progress = if (motion.systemAnimationsDisabled) if (active) 1f else 0f else reveal.value
    if (!active && progress == 0f) return
    var exitingQuestions by remember(owner.epoch) { mutableStateOf(emptyList<AskUserController.Request>()) }
    if (questions.isNotEmpty()) SideEffect { exitingQuestions = questions }
    val contentQuestions = questions.ifEmpty { exitingQuestions }
    val label = if (questions.isNotEmpty()) stringResource(R.string.phone_waiting)
        else displayedAction ?: stringResource(R.string.phone_thinking)
    val reduce = motion.reduceMotion
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        var position by remember(owner.epoch) { mutableStateOf(Offset.Zero) }
        var stripSize by remember { mutableStateOf(IntSize.Zero) }
        fun constrained(value: Offset) = Offset(
            value.x.coerceIn(0f, (constraints.maxWidth - stripSize.width).coerceAtLeast(0).toFloat()),
            value.y.coerceIn(0f, (constraints.maxHeight - stripSize.height).coerceAtLeast(0).toFloat()),
        )
        val placed = constrained(position)
        PhoneControlStrip(elapsed, label, {
            if (active) runtime.lease.held.value?.takeIf { it.epoch == epoch }?.let {
                runtime.revoke(it.owner, it.epoch)
            }
        },
            Modifier.align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(placed.x.roundToInt(), placed.y.roundToInt()) }
                .onSizeChanged { stripSize = it }
                .graphicsLayer {
                    alpha = progress
                    val scale = if (reduce) 1f else 0.96f + 0.04f * progress
                    scaleX = scale; scaleY = scale
                }.then(phoneTouchBounds("strip", bounds)),
            enabled = active,
            thinking = questions.isEmpty() && displayedAction == null,
            onDrag = { if (active && runtime.lease.held.value?.epoch == epoch)
                position = constrained(constrained(position) + it) })
        val askTop = with(LocalDensity.current) { stripSize.height.toDp() } + 8.dp
        val questionDuration = if (motion.systemAnimationsDisabled) 0 else 240
        AnimatedVisibility(questions.isNotEmpty(), modifier = Modifier.align(Alignment.TopCenter).padding(top = askTop)
            .graphicsLayer { alpha = progress },
            enter = fadeIn(tween(questionDuration)) + if (reduce) androidx.compose.animation.EnterTransition.None
                else scaleIn(tween(questionDuration), initialScale = 0.96f),
            exit = fadeOut(tween(questionDuration)) + if (reduce) androidx.compose.animation.ExitTransition.None
                else scaleOut(tween(questionDuration), targetScale = 0.96f)) {
            val first = contentQuestions.firstOrNull()
            if (first != null) {
                val group = contentQuestions.filter { if (first.setId == null) it.id == first.id else it.setId == first.setId }
                Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().then(phoneTouchBounds("ask", bounds))) {
                    PhoneAskInteraction(group, runtime.askUser, focusable, bounds,
                        enabled = active && questions.isNotEmpty())
                }
            }
        }
    }
}

@Composable
internal fun PhoneControlStrip(
    elapsed: Long,
    status: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thinking: Boolean = false,
    onDrag: (Offset) -> Unit,
) {
    val latestDrag by rememberUpdatedState(onDrag)
    var pressed by remember { mutableStateOf(false) }
    val allowScale = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val scale by animateFloatAsState(
        if (pressed && allowScale) 1.05f else 1f,
        tween(if (allowScale) 120 else 0, easing = FastOutSlowInEasing),
        label = "phoneStripPressScale",
    )
    val baseColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 1f)
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 1f)
    val containerColor by animateColorAsState(
        if (pressed) lerp(baseColor, contentColor, 0.06f) else baseColor,
        tween(120), label = "phoneStripPressTint",
    )
    Row(
        modifier.layout { measurable, constraints ->
            // Reserve the maximum press size so drawing and hit bounds remain inside the safe frame.
            val child = measurable.measure(constraints.copy(
                minWidth = 0, minHeight = 0,
                maxWidth = (constraints.maxWidth / 1.05f).toInt(),
                maxHeight = (constraints.maxHeight / 1.05f).toInt(),
            ))
            val width = constraints.constrainWidth(ceil(child.width * 1.05f).toInt())
            val height = constraints.constrainHeight(ceil(child.height * 1.05f).toInt())
            layout(width, height) { child.place((width - child.width) / 2, (height - child.height) / 2) }
        }.graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // Observe before children consume events; dragging and Stop keep their own input owners.
                awaitEachGesture {
                    try {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pressed = true
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        pressed = false
                    }
                }
            }.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.pointerInput(enabled) {
            if (enabled) detectDragGestures { change, delta -> change.consume(); latestDrag(delta) }
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            PhoneDurationCapsule(elapsed, containerColor = containerColor)
            PhoneStatusCapsule(status, containerColor = containerColor, thinking = thinking, active = enabled)
        }
        val shape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 24.dp, bottomEnd = 24.dp)
        Surface(shape = shape, color = containerColor, contentColor = contentColor) {
            Box(Modifier.width(48.dp).fillMaxHeight().heightIn(min = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled, role = Role.Button, onClick = onStop,
                ).phoneCapsuleCenter(roundedStart = false), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Stop, stringResource(R.string.phone_stop), Modifier.size(24.dp))
            }
        }
    }
}

@Composable
internal fun PhoneAskInteraction(
    questions: List<AskUserController.Request>,
    controller: AskUserController,
    focusable: (Boolean) -> Unit,
    bounds: (String, Rect?) -> Unit,
    enabled: Boolean,
) {
    val drafts = remember { QuestionDrafts() }
    drafts.retainOnly(questions)
    var minimized by remember { mutableStateOf(false) }
    var pageKey by remember { mutableStateOf<String?>(null) }
    val focusedFields = remember { mutableStateMapOf<Long, Boolean>() }
    var preparingInput by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val window = LocalWindowInfo.current
    val latestFocusable by rememberUpdatedState(focusable)
    val latestEnabled by rememberUpdatedState(enabled)
    fun clearInputFocus() {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        focusedFields.clear()
        preparingInput = false
        latestFocusable(false)
    }
    LaunchedEffect(enabled) { if (!enabled) clearInputFocus() }
    DisposableEffect(Unit) { onDispose { latestFocusable(false) } }
    InteractionDeck(
        interactions = listOf(UserInteraction.Question(questions)),
        autoWrapCodeBlocks = false,
        onSubmitQuestions = { answers ->
            if (enabled) {
                clearInputFocus()
                controller.submitAll(answers)
            }
        },
        onSkipQuestion = { id ->
            if (enabled) {
                clearInputFocus()
                controller.dismiss(id)
            }
        },
        onShellDecision = { _, _, _ -> },
        onCardMeasured = {},
        minimized = minimized,
        onMinimizedChange = {
            clearInputFocus()
            minimized = it
        },
        drafts = drafts,
        pageKey = pageKey,
        onPageChange = {
            clearInputFocus()
            pageKey = it
        },
        inSharedRow = true,
        onExpansionChanged = {},
        active = enabled,
        questionTextFieldModifier = { request ->
            val accepting = enabled && !minimized && pageKey == "question:${request.id}"
            DisposableEffect(request.id) {
                onDispose {
                    focusedFields.remove(request.id)
                    if (focusedFields.isEmpty() && !preparingInput) latestFocusable(false)
                }
            }
            Modifier.then(phoneTouchBounds("text-${request.id}", bounds))
                .pointerInput(request.id, accepting) {
                    if (!accepting) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        latestFocusable(true)
                    }
                }.onFocusChanged {
                    if (it.isFocused && accepting) {
                        focusedFields[request.id] = true
                        latestFocusable(true)
                    } else {
                        focusedFields.remove(request.id)
                        scope.launch {
                            withFrameNanos { }
                            latestFocusable(latestEnabled && (preparingInput || focusedFields.isNotEmpty()))
                        }
                    }
                }
        },
        prepareQuestionTextInput = {
            preparingInput = true
            latestFocusable(true)
            var ready = false
            try {
                snapshotFlow { window.isWindowFocused }.first { it }
                ready = true
            } finally {
                preparingInput = false
                if (!ready) latestFocusable(latestEnabled && focusedFields.isNotEmpty())
            }
        },
    )
}

@Composable
private fun phoneTouchBounds(key: String, bounds: (String, Rect?) -> Unit): Modifier {
    DisposableEffect(key) { onDispose { bounds(key, null) } }
    return Modifier.onGloballyPositioned { bounds(key, it.boundsInRoot()) }
}

internal fun phoneActionLabel(name: String?): Int? = when (name) {
    "phone_claim" -> R.string.phone_claim
    "phone_release" -> R.string.phone_release
    "phone_screenshot" -> R.string.phone_screenshot
    "phone_ui_tree" -> R.string.phone_ui_tree
    "phone_tap" -> R.string.phone_tap
    "phone_long_press" -> R.string.phone_long_press
    "phone_swipe" -> R.string.phone_swipe
    "phone_type" -> R.string.phone_type
    "phone_key" -> R.string.phone_key
    "phone_app_launch" -> R.string.phone_app_launch
    "phone_app_list" -> R.string.phone_app_list
    null -> R.string.phone_thinking
    else -> null
}
