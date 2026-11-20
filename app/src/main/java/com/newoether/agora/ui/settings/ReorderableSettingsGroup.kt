package com.newoether.agora.ui.settings

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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private val ROW_GAP = 2.dp

/**
 * [SettingsGroup] whose leading rows can be reordered by dragging a handle.
 *
 * [row] receives the handle modifier to place on its drag affordance. The new order is
 * reported once, at drag end, through [onMove]; an external [items] change while no drag is
 * active replaces the working order. [footer] rows are fixed after the reorderable rows and
 * share the group's positional shapes.
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
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    // Gesture coroutines outlive recompositions; they must see the latest callbacks.
    val currentOnMove by rememberUpdatedState(onMove)
    val currentKeyOf by rememberUpdatedState(keyOf)
    val gapPx = with(LocalDensity.current) { ROW_GAP.toPx() }
    if (items != lastItems && draggingKey == null) {
        lastItems = items
        working = items
    }

    fun maybeSwap(dragged: String) {
        val list = working
        val index = list.indexOfFirst { currentKeyOf(it) == dragged }
        if (index < 0) return
        val neighbor = if (dragOffset > 0) index + 1 else index - 1
        if (neighbor !in list.indices) return
        val step = (heights[currentKeyOf(list[neighbor])] ?: return) + gapPx
        if (kotlin.math.abs(dragOffset) < step / 2) return
        working = list.toMutableList().apply { add(neighbor, removeAt(index)) }
        dragOffset -= if (dragOffset > 0) step else -step
    }

    fun finish() {
        val final = working
        draggingKey = null
        dragOffset = 0f
        if (final != lastItems) currentOnMove(final)
    }

    val total = working.size + footer.size
    fun shapeAt(index: Int) = when {
        total == 1 -> RoundedCornerShape(24.dp)
        index == 0 -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
        index == total - 1 -> RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
        else -> RoundedCornerShape(5.dp)
    }

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
                    Surface(
                        shape = shapeAt(index),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = if (dragging) 6.dp else 1.dp,
                        shadowElevation = if (dragging) 6.dp else 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { heights[itemKey] = it.height }
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f },
                    ) {
                        row(
                            item,
                            Modifier.pointerInput(itemKey) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingKey = itemKey
                                        dragOffset = 0f
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
                    shape = shapeAt(index),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) { item() }
            }
        }
    }
}
