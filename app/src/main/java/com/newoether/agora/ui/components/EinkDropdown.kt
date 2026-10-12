package com.newoether.agora.ui.components

import com.newoether.agora.ui.components.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Static popup at the ordinary anchor. Position is clamped and flips above a low anchor. */
@Composable
internal fun EinkDropdown(
    onDismiss: () -> Unit,
    modifier: Modifier,
    offset: DpOffset,
    scrollState: ScrollState,
    properties: PopupProperties,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val position = remember(offset, density) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val dx = with(density) { offset.x.roundToPx() }
                val dy = with(density) { offset.y.roundToPx() }
                val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left + dx
                    else anchorBounds.right - popupContentSize.width - dx
                val below = anchorBounds.bottom + dy
                val y = if (below + popupContentSize.height <= windowSize.height) below
                    else anchorBounds.top - popupContentSize.height - dy
                return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        }
    }
    Popup(position, onDismissRequest = onDismiss, properties = properties) {
        Surface(modifier.widthIn(min = 112.dp, max = 640.dp),
            shape = DROPDOWN_MENU_SHAPE, color = Color.White,
            border = BorderStroke(1.dp, Color.Black)) {
            Column(Modifier.verticalScroll(scrollState).padding(vertical = 8.dp), content = content)
        }
    }
}
