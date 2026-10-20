package com.newoether.agora.ui.chat.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text

/** The drawer heading and its trailing, horizontally expanding search capsule. */
@Composable
internal fun DrawerSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean = false,
    onSubmit: () -> Unit = {},
    drawerVisible: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val allowMotion = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val searchHint = stringResource(R.string.search_hint)
    val collapseSearch = {
        onQueryChange("")
        focusManager.clearFocus()
        expanded = false
    }
    BackHandler(enabled = drawerVisible && expanded, onBack = collapseSearch)
    LaunchedEffect(drawerVisible) {
        if (!drawerVisible) {
            if (expanded || query.isNotEmpty()) onQueryChange("")
            if (expanded) focusManager.clearFocus()
            expanded = false
        }
    }
    LaunchedEffect(expanded) {
        if (expanded && drawerVisible) focusRequester.requestFocus()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
        val searchWidth by animateDpAsState(
            targetValue = if (expanded) maxWidth else 56.dp,
            animationSpec = tween(if (allowMotion) 400 else 0, easing = LinearOutSlowInEasing),
            label = "DrawerHeaderSearchWidth",
        )
        AnimatedVisibility(visible = !expanded,
            modifier = Modifier.align(Alignment.CenterStart).width((maxWidth - 64.dp).coerceAtLeast(0.dp)),
            enter = fadeIn(tween(if (allowMotion) 180 else 0)),
            exit = fadeOut(tween(if (allowMotion) 180 else 0))) {
            Text(stringResource(R.string.app_name), style = ChatType.conversationsTitle,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(modifier = Modifier.align(Alignment.CenterEnd).width(searchWidth).height(56.dp)
            .testTag("DrawerHeaderSearch"), shape = CircleShape,
            color = if (expanded) MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp) else Color.Transparent,
            tonalElevation = if (expanded) 8.dp else 0.dp) {
            Row(Modifier.fillMaxSize().padding(end = if (expanded) 14.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (expanded) collapseSearch() else expanded = true
                }, modifier = Modifier.size(56.dp)) {
                    Icon(if (expanded) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Search,
                        if (expanded) stringResource(R.string.back) else searchHint,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
                if (expanded) {
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(searchHint, style = ChatType.drawerSearch,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicTextField(value = query, onValueChange = onQueryChange,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                                .semantics { contentDescription = searchHint },
                            singleLine = true, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            textStyle = ChatType.drawerSearch.copy(color = MaterialTheme.colorScheme.onSurface))
                    }
                    if (LocalAgoraMotionPolicy.current.eInkMode) {
                        IconButton(onSubmit) { Icon(Icons.Default.Search, searchHint) }
                    }
                    Box(modifier = Modifier.size(28.dp).testTag("DrawerSearchTrailing"),
                        contentAlignment = Alignment.Center) {
                        com.newoether.agora.ui.motion.AnimatedVisibility(visible = searching,
                            enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        com.newoether.agora.ui.motion.AnimatedVisibility(visible = query.isNotEmpty() && !searching,
                            enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
                            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, stringResource(R.string.clear_search),
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
