package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.components.Surface

/** Original chat bar surface and inset ownership, shared by both transports. */
@Composable
internal fun ChatComposerSurface(
    isExpanded: Boolean,
    onBarHeightChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    outerSpacerHeightPx: Float = 0f,
    backdrop: @Composable () -> Unit = {},
    contentMaxWidth: Dp = Dp.Unspecified,
    content: @Composable () -> Unit,
) {
    val eInk = com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode
    val density = LocalDensity.current
val expandedGradientTopPaddingPx = with(density) { 20.dp.toPx() }
val gradientWidthPx = with(density) { 40.dp.toPx() }
val bgColor = MaterialTheme.colorScheme.background
Surface(
    modifier = Modifier
        .then(modifier)
        .fillMaxWidth()
        .then(if (isExpanded) Modifier.fillMaxHeight().statusBarsPadding() else Modifier)
        .drawBehind {
            val totalH = size.height
            if (isExpanded && totalH > 0f && !eInk) {
                val h = expandedGradientTopPaddingPx.coerceAtMost(totalH * 0.12f)
                val w = gradientWidthPx.coerceAtMost(totalH * 0.24f)
                val transparentEnd = h / totalH
                val fadeEnd = (h + w) / totalH
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to bgColor.copy(alpha = 0f),
                            transparentEnd to bgColor.copy(alpha = 0f),
                            fadeEnd to bgColor,
                        ),
                        startY = 0f,
                        endY = totalH
                    )
                )
            }
        },
    color = if (eInk) Color.White else Color.Transparent
) {
    Column {
        if (!isExpanded) Spacer(modifier = Modifier.height(12.dp))
        if (outerSpacerHeightPx > 0f) {
            Spacer(modifier = Modifier.height(with(density) { outerSpacerHeightPx.toDp() }))
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentMaxWidth)
                .fillMaxWidth()
                .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                .onSizeChanged {
                    if (!isExpanded) onBarHeightChanged(it.height.toFloat())
                }
                .navigationBarsPadding()
                .imePadding()
                .padding(8.dp),
        ) {
            backdrop()

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.weight(1f) else Modifier),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                shape = CHAT_BOTTOM_BAR_OUTER_SHAPE,
            ) {
                Box(
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    content()
                }
            }
        }
    }
}
}
