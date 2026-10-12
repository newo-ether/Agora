package com.newoether.agora.ui.components

import com.newoether.agora.ui.components.IconButton
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import com.newoether.agora.ui.components.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlinx.coroutines.launch

@Composable
internal fun EinkListNavigation(state: LazyListState) {
    if (!LocalAgoraMotionPolicy.current.eInkMode) return
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) {
        einkPageStep(state.layoutInfo.viewportSize.height, 0, 0,
            MaterialTheme.typography.bodyLarge.lineHeight.toPx().toInt())
    }
    EinkPageControls(state.canScrollBackward, state.canScrollForward,
        onPrevious = { scope.launch { state.scrollBy(-step.toFloat()) } },
        onNext = { scope.launch { state.scrollBy(step.toFloat()) } })
}

@Composable
internal fun EinkMoreAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!LocalAgoraMotionPolicy.current.eInkMode) return
    IconButton(onClick, modifier) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
}
