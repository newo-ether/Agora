package com.newoether.agora.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

private val ROW_GAP = 2.dp
private val OUTER_RADIUS = 24.dp
private val INNER_RADIUS = 5.dp
private const val LIFTED_SCALE = 1.02f
private const val SHAPE_ANIMATION_MS = 200

/**
 * [SettingsGroup] whose leading rows can be reordered by dragging a handle.
 *
 * [row] receives the handle modifier to place on its drag affordance. The new order is
 * reported once, at drag end, through [onMove]; an external [items] change while no drag is
 * active replaces the working order. [footer] rows are fixed after the reorderable rows and
 * share the group's positional shapes.
 *
 * Motion: the lifted row follows the finger with fully rounded corners and a slight scale; rows it
 * passes slide into their new slot instead of jumping, and on release the row springs into place
 * while its corners animate back to the positional group shape.
 */
@Composable
internal fun <T> ReorderableSettingsGroup(
    title: String,
    items: List<T>,
    keyOf: (T) -> String,
    onMove: (List<T>) -> Unit,
    footer: List<@Composable () -> Unit> = emptyList(),
    row: @Composable (item: T, dragHandle: Modifier) -> Unit,
) {
    var working by remember { mutableStateOf(items) }
    var lastItems by remember { mutableStateOf(items) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var settlingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    // Per-row visual displacement that animates back to zero after a slot change.
    val displacements = remember { HashMap<String, Animatable<Float, AnimationVector1D>>() }
    val scope = rememberCoroutineScope()
    // Gesture coroutines outlive recompositions; they must see the latest callbacks.
    val currentOnMove by rememberUpdatedState(onMove)
    val currentKeyOf by rememberUpdatedState(keyOf)
    val gapPx = with(LocalDensity.current) { ROW_GAP.toPx() }
    if (items != lastItems && draggingKey == null) {
        lastItems = items
        working = items
    }

    fun displacementOf(key: String) = displacements.getOrPut(key) { Animatable(0f) }

    fun maybeSwap(dragged: String) {
        val list = working
        val index = list.indexOfFirst { currentKeyOf(it) == dragged }
        if (index < 0) return
        val direction = if (dragOffset > 0) 1 else -1
        val neighbor = index + direction
        if (neighbor !in list.indices) return
        val neighborKey = currentKeyOf(list[neighbor])
        val step = (heights[neighborKey] ?: return) + gapPx
        if (kotlin.math.abs(dragOffset) < step / 2) return
        val draggedStep = (heights[dragged] ?: return) + gapPx
        working = list.toMutableList().apply { add(neighbor, removeAt(index)) }
        dragOffset -= direction * step
        // The neighbor's layout slot moved by the dragged row's height; start it at its old
        // on-screen position and let it slide into the new slot.
        val slide = displacementOf(neighborKey)
        scope.launch {
            slide.snapTo(slide.value + direction * draggedStep)
            slide.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }

    fun finish() {
        val dragged = draggingKey ?: return
        val final = working
        val releaseOffset = dragOffset
        val settle = displacementOf(dragged)
        scope.launch {
            settle.snapTo(releaseOffset)
            settlingKey = dragged
            draggingKey = null
            dragOffset = 0f
            if (final != lastItems) currentOnMove(final)
            settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            if (settlingKey == dragged) settlingKey = null
        }
    }

    val total = working.size + footer.size

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            working.forEachIndexed { index, item ->
                val itemKey = keyOf(item)
                key(itemKey) {
                    if (index > 0) Spacer(Modifier.height(ROW_GAP))
                    val dragging = itemKey == draggingKey
                    val lifted = dragging || itemKey == settlingKey
                    val displacement = displacementOf(itemKey)
                    val scale by animateFloatAsState(
                        targetValue = if (dragging) LIFTED_SCALE else 1f,
                        animationSpec = tween(SHAPE_ANIMATION_MS),
                        label = "reorderScale",
                    )
                    val elevation by animateDpAsState(
                        targetValue = if (dragging) 6.dp else 0.dp,
                        animationSpec = tween(SHAPE_ANIMATION_MS),
                        label = "reorderElevation",
                    )
                    Surface(
                        shape = animatedGroupShape(index, total, fullyRounded = dragging),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp + elevation,
                        shadowElevation = elevation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { heights[itemKey] = it.height }
                            .zIndex(if (dragging) 2f else if (lifted) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) dragOffset else displacement.value
                                scaleX = scale
                                scaleY = scale
                            },
                    ) {
                        row(
                            item,
                            Modifier.pointerInput(itemKey) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingKey = itemKey
                                        // Pick up from wherever a previous settle left the row.
                                        dragOffset = displacement.value
                                        scope.launch { displacement.snapTo(0f) }
                                    },
                                    onDragEnd = { finish() },
                                    onDragCancel = { finish() },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        maybeSwap(itemKey)
                                    },
                                )
                            },
                        )
                    }
                }
            }
            footer.forEachIndexed { offset, item ->
                val index = working.size + offset
                if (index > 0) Spacer(Modifier.height(ROW_GAP))
                Surface(
                    shape = animatedGroupShape(index, total, fullyRounded = false),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) { item() }
            }
        }
    }
}

/** Positional [SettingsGroup] shape whose corner radii animate when a row changes slot. */
@Composable
private fun animatedGroupShape(index: Int, total: Int, fullyRounded: Boolean): Shape {
    fun radius(outer: Boolean): Dp = if (fullyRounded || outer) OUTER_RADIUS else INNER_RADIUS
    val top by animateDpAsState(
        targetValue = radius(index == 0),
        animationSpec = tween(SHAPE_ANIMATION_MS),
        label = "reorderTopRadius",
    )
    val bottom by animateDpAsState(
        targetValue = radius(index == total - 1),
        animationSpec = tween(SHAPE_ANIMATION_MS),
        label = "reorderBottomRadius",
    )
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}
