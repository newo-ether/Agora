package com.newoether.agora.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.components.EinkPageControls
import com.newoether.agora.ui.components.einkPageStep
import kotlinx.coroutines.launch

@Composable
internal fun ChatEinkNavigation(
    listState: LazyListState,
    viewportHeight: Int,
    topObstruction: Int,
    bottomObstruction: Dp,
    modifier: Modifier = Modifier,
    onLatest: () -> Unit,
    readingMode: EinkReadingMode? = null,
) {
    val scope = rememberCoroutineScope()
    val bottom = bottomObstruction + if (readingMode?.active == true) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
    val step = with(LocalDensity.current) {
        einkPageStep(viewportHeight, topObstruction,
            (bottom + 48.dp).roundToPx(),
            MaterialTheme.typography.bodyLarge.lineHeight.toPx().toInt())
    }
    EinkPageControls(listState.canScrollBackward, listState.canScrollForward,
        onPrevious = { scope.launch { listState.scrollBy(-step.toFloat()) } },
        onNext = { scope.launch { listState.scrollBy(step.toFloat()) } },
        onLatest = onLatest, modifier = modifier.padding(bottom = bottom),
        reading = readingMode?.active == true, onReadingToggle = readingMode?.let { it::toggle })
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.ChatNavigationControls(
    eInk: Boolean,
    showBottomButton: Boolean,
    listState: LazyListState,
    viewportHeight: Int,
    topObstruction: Int,
    bottomObstruction: Dp,
    modifier: Modifier = Modifier,
    onLatest: () -> Unit,
    readingMode: EinkReadingMode? = null,
) {
    if (eInk) ChatEinkNavigation(listState, viewportHeight, topObstruction, bottomObstruction, modifier, onLatest, readingMode)
    else ChatBottomScrollButton(showBottomButton, bottomObstruction, onLatest)
}
