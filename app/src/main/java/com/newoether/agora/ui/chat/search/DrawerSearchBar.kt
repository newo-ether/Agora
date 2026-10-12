package com.newoether.agora.ui.chat.search

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text

/** The pill-shaped search input at the top of the conversation drawer. */
@Composable
internal fun DrawerSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean = false,
    onSubmit: () -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxWidth().height(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp), tonalElevation = 8.dp) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(stringResource(R.string.search_hint), style = ChatType.drawerSearch, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(value = query, onValueChange = onQueryChange,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }), modifier = Modifier.fillMaxWidth(), singleLine = true, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), textStyle = ChatType.drawerSearch.copy(color = MaterialTheme.colorScheme.onSurface))
            }
            if (com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode) {
                IconButton(onSubmit) { Icon(Icons.Default.Search, stringResource(R.string.search_hint)) }
            }
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                com.newoether.agora.ui.motion.AnimatedVisibility(
                    visible = searching,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                com.newoether.agora.ui.motion.AnimatedVisibility(
                    visible = query.isNotEmpty() && !searching,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                ) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.clear_search),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
