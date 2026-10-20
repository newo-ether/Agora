package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Text

@Composable
internal fun AssistantBranchSelector(
    branchIndex: Int,
    totalBranches: Int,
    terminalActionsAlpha: Float,
    terminalEnabled: Boolean,
    isEditingAllowed: Boolean,
    onSwitchBranch: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 8.dp)
            .graphicsLayer { alpha = terminalActionsAlpha }
            .clip(RoundedCornerShape(100))
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .padding(horizontal = 4.dp),
    ) {
        IconButton(
            onClick = { onSwitchBranch(-1) },
            enabled =
                terminalEnabled &&
                    branchIndex > 0 &&
                    isEditingAllowed,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            "${branchIndex + 1} / $totalBranches",
            style = MaterialTheme.typography.labelSmall,
        )
        IconButton(
            onClick = { onSwitchBranch(1) },
            enabled = terminalEnabled &&
                branchIndex < totalBranches - 1 &&
                isEditingAllowed,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
