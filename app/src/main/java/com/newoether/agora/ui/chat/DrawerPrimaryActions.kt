package com.newoether.agora.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.ChatType

/**
 * Tasks / Remote / New Chat. These are the first rows of the drawer's scrolling list, so they
 * travel with the content instead of pinning to the header.
 */
@Composable
internal fun DrawerPrimaryActions(
    isSwitching: Boolean,
    onTasks: () -> Unit,
    onRemote: () -> Unit,
    onNewChat: () -> Unit,
) {
    FilledTonalButton(
        onClick = onTasks,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomStart = 5.dp,
            bottomEnd = 5.dp,
        ),
    ) {
        Icon(Icons.Rounded.Repeat, null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.tasks), style = ChatType.drawerButton)
    }

    Spacer(modifier = Modifier.height(2.dp))

    FilledTonalButton(
        onClick = onRemote,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(
            topStart = 5.dp,
            topEnd = 5.dp,
            bottomStart = 24.dp,
            bottomEnd = 24.dp,
        ),
    ) {
        Icon(Icons.Rounded.Devices, null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.remote_title), style = ChatType.drawerButton)
    }

    Spacer(modifier = Modifier.height(16.dp))

    val containerColor by animateColorAsState(
        if (isSwitching) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.primary
        },
        tween(300),
        label = "newChatContainer",
    )
    val contentColor by animateColorAsState(
        if (isSwitching) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        tween(300),
        label = "newChatContent",
    )
    Button(
        onClick = onNewChat,
        modifier = Modifier.fillMaxWidth().height(42.dp),
        enabled = true,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.new_chat), style = ChatType.drawerButton)
    }

    Spacer(modifier = Modifier.height(16.dp))
}
