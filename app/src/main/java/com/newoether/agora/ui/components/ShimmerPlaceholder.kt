package com.newoether.agora.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

@Composable
fun rememberShimmerBrush(
    showShimmer: Boolean = true,
    targetValue: Float = 1300f
): Brush {
    return if (showShimmer) {
        val transition = rememberInfiniteTransition(label = "shimmerTransition")
        val translateAnimation by transition.animateFloat(
            initialValue = 0f,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmerTranslate"
        )
        val shimmerColors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        )
        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset.Zero,
            end = Offset(x = translateAnimation, y = translateAnimation)
        )
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
fun ShimmerChatPlaceholder(modifier: Modifier = Modifier) {
    val brush = rememberShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Pseudo top bar space padding
        Spacer(modifier = Modifier.height(100.dp))

        // User message skeleton (right aligned)
        Box(
            modifier = Modifier
                .align(Alignment.End)
                .fillMaxWidth(0.65f)
                .height(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(brush)
        )

        // Assistant message skeleton (left aligned)
        Column(
            modifier = Modifier.fillMaxWidth(0.85f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status bar placeholder
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(brush)
            )
            // Message body placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(brush)
            )
        }

        // User message skeleton (right aligned)
        Box(
            modifier = Modifier
                .align(Alignment.End)
                .fillMaxWidth(0.45f)
                .height(44.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(brush)
        )

        // Assistant message skeleton (left aligned)
        Column(
            modifier = Modifier.fillMaxWidth(0.75f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(brush)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(brush)
            )
        }
    }
}
