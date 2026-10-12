package com.newoether.agora.ui.components

import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.VerticalAlignBottom
import com.newoether.agora.ui.components.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R

/** Keep three lines for reading continuity; cap overlap at half a short viewport to allow progress. */
internal fun einkPageStep(viewport: Int, top: Int, bottom: Int, line: Int): Int {
    val available = (viewport - top.coerceAtLeast(0) - bottom.coerceAtLeast(0)).coerceAtLeast(0)
    val overlap = minOf(available.toLong() / 2, line.coerceAtLeast(0).toLong() * 3).toInt()
    return available - overlap
}

@Composable
internal fun EinkPageControls(
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onLatest: (() -> Unit)? = null,
    reading: Boolean = false,
    onReadingToggle: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            if (onReadingToggle != null) {
                IconButton(onReadingToggle) {
                    Icon(if (reading) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        stringResource(if (reading) R.string.e_ink_exit_reading else R.string.e_ink_reading))
                }
            }
            if (onLatest != null) {
                IconButton(onLatest) {
                    Icon(Icons.Default.VerticalAlignBottom, stringResource(R.string.e_ink_latest))
                }
            }
            IconButton(onPrevious, enabled = canPrevious) {
                Icon(Icons.Default.ArrowUpward, stringResource(R.string.e_ink_previous_page))
            }
            IconButton(onNext, enabled = canNext) {
                Icon(Icons.Default.ArrowDownward, stringResource(R.string.e_ink_next_page))
            }
        }
    }
}
