package com.newoether.agora.phone

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.AskUserController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PhoneControls(
    runtime: PhoneUseRuntime,
    epoch: Long,
    focusable: (Boolean) -> Unit,
    bounds: (String, Rect?) -> Unit,
) {
    val held by runtime.lease.held.collectAsState()
    val requests by runtime.askUser.requests.collectAsState()
    val action by runtime.actionName.collectAsState()
    val owner = held?.takeIf { it.epoch == epoch } ?: return
    val questions = requests.filter { it.conversationId == owner.owner.conversationId && it.runId == owner.owner.runId }
    var elapsed by remember(owner.epoch) { mutableLongStateOf(0) }
    LaunchedEffect(owner.epoch) {
        while (true) { elapsed = (SystemClock.elapsedRealtime() - owner.claimedAt) / 1000; delay(1000) }
    }
    var exitingQuestions by remember(owner.epoch) { mutableStateOf(emptyList<AskUserController.Request>()) }
    if (questions.isNotEmpty()) SideEffect { exitingQuestions = questions }
    val contentQuestions = questions.ifEmpty { exitingQuestions }
    val label = if (questions.isNotEmpty()) R.string.phone_waiting
        else if (action == "phone_type") R.string.phone_typing else phoneActionLabel(action)
    val reduce = LocalAgoraMotionPolicy.current.reduceMotion
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        var position by remember(owner.epoch) { mutableStateOf(Offset.Zero) }
        var stripSize by remember { mutableStateOf(IntSize.Zero) }
        fun constrained(value: Offset) = Offset(
            value.x.coerceIn(0f, (constraints.maxWidth - stripSize.width).coerceAtLeast(0).toFloat()),
            value.y.coerceIn(0f, (constraints.maxHeight - stripSize.height).coerceAtLeast(0).toFloat()),
        )
        val placed = constrained(position)
        PhoneControlStrip(elapsed, stringResource(label), { runtime.revoke(owner.owner, owner.epoch) },
            Modifier.align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(placed.x.roundToInt(), placed.y.roundToInt()) }
                .onSizeChanged { stripSize = it }.then(phoneTouchBounds("strip", bounds)),
            onDrag = { position = constrained(constrained(position) + it) })
        val askTop = with(LocalDensity.current) { stripSize.height.toDp() } + 8.dp
        AnimatedVisibility(questions.isNotEmpty(), modifier = Modifier.align(Alignment.TopCenter).padding(top = askTop),
            enter = fadeIn() + if (reduce) androidx.compose.animation.EnterTransition.None else scaleIn(initialScale = 0.96f),
            exit = fadeOut() + if (reduce) androidx.compose.animation.ExitTransition.None else scaleOut(targetScale = 0.96f)) {
            val first = contentQuestions.firstOrNull()
            if (first != null) {
                val group = contentQuestions.filter { if (first.setId == null) it.id == first.id else it.setId == first.setId }
                Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth().then(phoneTouchBounds("ask", bounds)), shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 8.dp, shadowElevation = 6.dp) {
                    PhoneAskCard(group, runtime.askUser, focusable, bounds)
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
            .pointerInput(Unit) {
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
        Row(Modifier.pointerInput(Unit) {
            detectDragGestures { change, delta -> change.consume(); latestDrag(delta) }
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            PhoneDurationCapsule(elapsed, containerColor = containerColor)
            PhoneStatusCapsule(status, containerColor = containerColor)
        }
        val shape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 24.dp, bottomEnd = 24.dp)
        Surface(shape = shape, color = containerColor, contentColor = contentColor) {
            Box(Modifier.width(48.dp).fillMaxHeight().heightIn(min = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button, onClick = onStop,
                ), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Stop, stringResource(R.string.phone_stop), Modifier.size(24.dp))
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneAskCard(
    questions: List<AskUserController.Request>, controller: AskUserController,
    focusable: (Boolean) -> Unit, bounds: (String, Rect?) -> Unit,
) {
    val selections = remember(questions.map { it.id }) { mutableStateMapOf<Long, Set<String>>() }
    val texts = remember(questions.map { it.id }) { mutableStateMapOf<Long, String>() }
    val focusedFields = remember(questions.map { it.id }) { mutableStateMapOf<Long, Boolean>() }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { focusable(false) } }
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        questions.forEach { question ->
            Text(question.question, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                question.options.forEach { option ->
                    val selected = option in selections[question.id].orEmpty()
                    FilterChip(selected = selected, onClick = {
                        selections[question.id] = if (!question.allowMultiple) if (selected) emptySet() else setOf(option)
                        else selections[question.id].orEmpty().let { if (selected) it - option else it + option }
                    }, label = { Text(option) })
                }
            }
            OutlinedTextField(value = texts[question.id].orEmpty(), onValueChange = { texts[question.id] = it },
                modifier = Modifier.fillMaxWidth().then(phoneTouchBounds("text-${question.id}", bounds))
                    .pointerInput(question.id) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            focusable(true)
                        }
                    }.onFocusChanged {
                        if (it.isFocused) { focusedFields[question.id] = true; focusable(true) }
                        else {
                            focusedFields.remove(question.id)
                            scope.launch { withFrameNanos { }; focusable(focusedFields.isNotEmpty()) }
                        }
                    },
                label = { Text(stringResource(R.string.phone_answer)) }, maxLines = 4)
        }
        Button(onClick = {
            focusable(false)
            controller.submitAll(questions.map { question ->
                val choices = selections[question.id].orEmpty().toList()
                val text = texts[question.id]?.takeIf(String::isNotBlank)
                question.id to AskUserController.Answer(choices, text, choices.isNotEmpty() || text != null)
            })
        }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.phone_send)) }
    }
}

@Composable
private fun phoneTouchBounds(key: String, bounds: (String, Rect?) -> Unit): Modifier {
    DisposableEffect(key) { onDispose { bounds(key, null) } }
    return Modifier.onGloballyPositioned { bounds(key, it.boundsInRoot()) }
}

internal fun phoneActionLabel(name: String?): Int = when (name) {
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
    else -> R.string.phone_thinking
}
