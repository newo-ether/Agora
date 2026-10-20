package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.remote.RemoteConnectionStore
import java.io.File
import com.newoether.agora.R
import com.newoether.agora.SettingsOverlayHost
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.remote.remoteDeviceName
import com.newoether.agora.remote.displayTitle
import com.newoether.agora.remote.displayDirectory
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.ui.settings.*
import com.newoether.agora.ui.chat.ChatRenameDialog
import com.newoether.agora.ui.chat.ChatDeleteConfirmDialog
import com.newoether.agora.ui.chat.ChatDeleteDialogPhase
import com.newoether.agora.ui.chat.DrawerConversationIndicator
import com.newoether.agora.ui.chat.resolveDrawerConversationIndicator
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import com.newoether.agora.ui.motion.MotionAwareLinearProgressIndicator
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.rememberAgoraHaptics
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.motion.AnimatedVisibility

@Composable
internal fun RemoteOverlay(
    visible: Boolean,
    hapticsActive: Boolean,
    settings: SettingsRepository,
    onDismiss: () -> Unit,
    onExitFinished: () -> Unit,
    onMessage: (String, String?, (() -> Unit)?) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit,
) {
    val messageContext by rememberUpdatedState(LocalContext.current)
    val context = LocalContext.current.applicationContext
    val remote: RemoteViewModel = viewModel {
        val imageDirectory = File(context.cacheDir, "remote-images")
        RemoteViewModel(RemoteConnectionStore(File(context.noBackupFilesDir, "remote-connections.json")),
            com.newoether.agora.tool.ToolImageStore(context, imageDirectory),
            com.newoether.agora.remote.RemoteImageCache(imageDirectory),
            attachmentStore = com.newoether.agora.remote.RemoteAttachmentStore(context))
    }
    val messageHandler by rememberUpdatedState(onMessage)
    LaunchedEffect(remote, visible) {
        if (!visible) return@LaunchedEffect
        remote.notices.collect { notice ->
            if (remote.isNoticeCurrent(notice)) messageHandler(
                remoteNoticeMessage(messageContext, notice),
                if (notice.canRetryRead) messageContext.getString(R.string.retry) else null,
                if (notice.canRetryRead) ({ remote.retryNotice(notice) }) else null,
            )
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(remote, visible, lifecycle) {
        fun update() = remote.setVisible(visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); remote.setVisible(false) }
    }
    SettingsOverlayHost(visible, onDismiss, onExitFinished = onExitFinished) {
        val hapticsEnabled by settings.hapticsEnabled.collectAsState(initial = false)
        CompositionLocalProvider(LocalAgoraHaptics provides rememberAgoraHaptics(hapticsEnabled && hapticsActive)) {
            RemoteScreen(remote, settings, visible, onDismiss, onSnackbarOffsetChanged, onMediaClick, onMessage)
        }
    }
}

@Composable
internal fun RemoteScreen(vm: RemoteViewModel, settings: SettingsRepository, active: Boolean, onBack: () -> Unit,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onMessage: (String, String?, (() -> Unit)?) -> Unit) {
    val state by vm.state.collectAsState()
    val inset = maxOf(WindowInsets.ime.asPaddingValues().calculateBottomPadding(),
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    SideEffect { if (active && state.session == null) onSnackbarOffsetChanged(inset) }
    val focus = LocalFocusManager.current
    var forward by remember { mutableStateOf(true) }
    val back = {
        forward = false
        focus.clearFocus()
        when {
            state.session != null -> vm.selectSession(null)
            state.deviceId != null -> vm.selectDevice(null)
            state.addingDevice -> vm.selectDevice(null)
            else -> onBack()
        }
    }
    BackHandler(active, back)
    val target = Triple(state.deviceId, state.session?.let { "conversation" }, if (state.addingDevice) state.editedDeviceId.orEmpty() else null)
    Box(Modifier.fillMaxSize()) {
    GuardedAnimatedContent(targetState = target, forward = forward) { page ->
        var retained by remember(page) { mutableStateOf(state) }
        val current = page == target
        SideEffect { if (current) retained = state }
        val displayed = if (current) state else retained
        when {
            page.third != null -> RemoteAddDevice(displayed, vm, back) { forward = false; focus.clearFocus() }
            page.second != null -> RemoteConversation(displayed, vm, settings, active && current, back, onSnackbarOffsetChanged, onMediaClick, onMessage)
            page.first != null -> {
                var showUsage by remember(displayed.deviceId) { mutableStateOf(false) }
                if (showUsage && current && active) RemoteUsageSheet(vm) { showUsage = false }
                val listState = rememberLazyListState()
                val visibleRows = remember { mutableStateMapOf<String, Boolean>() }
                LaunchedEffect(current, active, displayed.deviceId, displayed.sessions) {
                    if (!current || !active) return@LaunchedEffect
                    snapshotFlow { displayed.sessions.map { it.id }.filter { visibleRows[it] == true } }
                        .collect { vm.observeSessions(page.first!!, it) }
                }
                LaunchedEffect(current, active, displayed.sessionCursor, displayed.loading, displayed.loadingMore, displayed.error) {
                    if (!current || !active || displayed.sessionCursor == null ||
                        displayed.loading || displayed.loadingMore || displayed.error) return@LaunchedEffect
                    snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo.any { it.key == "sessions" } && !listState.canScrollForward
                    }.collect { atBottom -> if (atBottom) vm.loadMore() }
                }
                CollapsingSettingsLazyScaffold(
                    listState = listState,
                    contentBottomPadding = 104.dp,
                    title = displayed.devices.firstOrNull { it.id == displayed.deviceId }?.name
                        ?.ifBlank { null } ?: stringResource(R.string.remote_device), onBack = back,
                    actions = {
                        IconButton(onClick = { showUsage = true }, enabled = current && active) {
                            Icon(Icons.Default.DataUsage, stringResource(R.string.remote_usage))
                        }
                    },
                    floatingActionButton = {
                        Box(Modifier.fillMaxWidth().padding(end = 24.dp, bottom = 8.dp), contentAlignment = Alignment.BottomEnd) {
                            FloatingActionButton(modifier = Modifier.size(64.dp), shape = CircleShape, onClick = { if (current && active && !displayed.controlling) { forward = true; vm.newSession() } }) {
                                Icon(Icons.Default.Add, stringResource(R.string.new_chat), Modifier.size(32.dp))
                            }
                        }
                    },
                ) {
                    item(key = "agents") {
                        RemoteAgentChipRow(displayed.devices.firstOrNull { it.id == displayed.deviceId }?.status
                            ?: RemoteDeviceStatus.IDLE)
                    }
                    if (!displayed.loading && displayed.sessions.isEmpty() && !displayed.error) {
                        item { Text(stringResource(R.string.remote_empty), Modifier.padding(16.dp)) }
                    }
                    if (displayed.sessions.isNotEmpty()) item(key = "sessions") {
                        SettingsGroup(
                            title = stringResource(R.string.remote_sessions),
                            items = displayed.sessions.map { session -> {
                                key(session.id) {
                                    var showMenu by remember { mutableStateOf(false) }
                                    var action by remember { mutableStateOf<String?>(null) }
                                    val actionsEnabled = current && active && !displayed.controlling
                                    DisposableEffect(session.id) { onDispose { visibleRows.remove(session.id) } }
                                    SettingsItem(
                                        modifier = Modifier.onGloballyPositioned { coordinates ->
                                            val bounds = coordinates.boundsInWindow()
                                            val shown = bounds.width > 0 && bounds.height > 0
                                            if (shown) visibleRows[session.id] = true else visibleRows.remove(session.id)
                                        }.clickable(enabled = current) {
                                            forward = true; focus.clearFocus(); vm.selectSession(session)
                                        },
                                        headlineContent = { Text(session.displayTitle(stringResource(R.string.new_chat)), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(session.displayDirectory(),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingContent = { Icon(Icons.Default.ChatBubbleOutline, null) },
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RemoteSessionIndicator(resolveDrawerConversationIndicator(
                                                    isGenerating = displayed.sessionStatuses["${displayed.deviceId}/${session.id}"]?.status == "active",
                                                    isSelected = false,
                                                    hasUnreadGeneration = displayed.hasUnreadGeneration(session.id),
                                                ))
                                                Box {
                                                    IconButton(onClick = { showMenu = true }, enabled = current && active) {
                                                        Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                                                    }
                                                    AgoraDropdownMenu(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                        tonalElevation = 16.dp,
                                                        expanded = showMenu, onDismissRequest = { showMenu = false },
                                                    ) {
                                                        AgoraDropdownMenuItem(
                                                            text = { Text(stringResource(R.string.rename)) },
                                                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                                                            enabled = actionsEnabled,
                                                            onClick = { showMenu = false; action = "rename" },
                                                        )
                                                        AgoraDropdownMenuItem(
                                                            text = { Text(stringResource(R.string.remote_archive), color = MaterialTheme.colorScheme.error) },
                                                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                                            enabled = actionsEnabled,
                                                            onClick = { showMenu = false; action = "archive" },
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                    )
                                    if (action == "rename") ChatRenameDialog(
                                        initialName = session.title,
                                        initialDisplayName = session.displayTitle(stringResource(R.string.new_chat)),
                                        onSave = { vm.renameSession(session.id, it); action = null },
                                        onDismiss = { action = null },
                                    )
                                    if (action == "archive") ChatDeleteConfirmDialog(
                                        phase = ChatDeleteDialogPhase.CONFIRM,
                                        title = stringResource(R.string.remote_archive_title),
                                        message = stringResource(R.string.remote_archive_message),
                                        confirmLabel = stringResource(R.string.remote_archive),
                                        onConfirm = { vm.archiveSession(session.id); action = null },
                                        onDismiss = { action = null },
                                    )
                                }
                            } },
                        )
                    }
                }
            }
            else -> RemoteDevices(displayed, vm, back) { forward = true }
        }
    }
    val progressVisible = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    SideEffect {
        progressVisible.targetState = state.deviceId != null && state.session == null && !state.addingDevice &&
            (state.loading || state.loadingMore || state.controlling)
    }
    AnimatedVisibility(
        visibleState = progressVisible,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(300)),
    ) {
        MotionAwareLinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
    }
    }
}

/** Exact original drawer indicator sizes, priority, color and fade timing. */
@Composable
private fun RemoteSessionIndicator(indicator: DrawerConversationIndicator) {
    val unreadDescription = stringResource(R.string.conversation_unread_generation)
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = indicator == DrawerConversationIndicator.GENERATING,
            enter = fadeIn(tween(200)), exit = fadeOut(tween(200)),
        ) {
            MotionAwareCircularProgressIndicator(
                modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(
            visible = indicator == DrawerConversationIndicator.UNREAD,
            enter = fadeIn(tween(200)), exit = fadeOut(tween(200)),
        ) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                .semantics { contentDescription = unreadDescription })
        }
    }
}

@Composable
private fun RemoteDevices(state: RemoteState, vm: RemoteViewModel, onBack: () -> Unit, onForward: () -> Unit) {
    var deleteId by remember { mutableStateOf<String?>(null) }
    val enabled = !state.restoring && !state.saving
    CollapsingSettingsScaffold(title = stringResource(R.string.remote_title), onBack = onBack) {
        SettingsGroup(
            title = stringResource(R.string.remote_devices),
            items = buildList {
                if (state.devices.isEmpty() && !state.restoring && !state.storageError) add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.remote_no_devices),
                            color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        supportingContent = { Text(stringResource(R.string.remote_no_devices_desc)) },
                        leadingContent = { Icon(Icons.Default.Computer, null) },
                    )
                }
                state.devices.forEach { device -> add { key(device.id) {
                    var menuOpen by remember { mutableStateOf(false) }
                    val status = when (device.status) {
                        RemoteDeviceStatus.IDLE -> McpConnectionStatus.IDLE
                        RemoteDeviceStatus.CONNECTING -> McpConnectionStatus.CONNECTING
                        RemoteDeviceStatus.CONNECTED -> McpConnectionStatus.CONNECTED
                        RemoteDeviceStatus.ERROR -> McpConnectionStatus.ERROR
                    }
                    SettingsItem(
                        modifier = Modifier.clickable(enabled = enabled) {
                            onForward(); vm.selectDevice(device.id)
                        },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(device.name.ifBlank { stringResource(R.string.remote_device) }, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                McpStatusDot(status)
                            }
                        },
                        supportingContent = {
                            Column {
                                Text(device.address, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Computer, null) },
                        trailingContent = { Box {
                            IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.options))
                            }
                            AgoraDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 16.dp) {
                                AgoraDropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, enabled = enabled,
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menuOpen = false; onForward(); vm.editDevice(device.id) })
                                AgoraDropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                    enabled = enabled, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; deleteId = device.id })
                            }
                        } },
                    )
                } } }
                add {
                    SettingsAddItem(label = stringResource(R.string.remote_add_device),
                        enabled = enabled,
                        onClick = { onForward(); vm.addDevice() })
                }
            },
        )
    }
    state.devices.firstOrNull { it.id == deleteId }?.let { device ->
        AlertDialog(onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.remote_delete_title)) },
            text = { Text(stringResource(R.string.remote_delete_message, device.name.ifBlank { stringResource(R.string.remote_device) })) },
            confirmButton = { TextButton(enabled = enabled, onClick = { vm.removeDevice(device.id); deleteId = null }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RemoteAddDevice(state: RemoteState, vm: RemoteViewModel, onBack: () -> Unit, onForward: () -> Unit) {
    val initial = remember { vm.editorConnection() }
    var name by remember { mutableStateOf(remoteDeviceName(initial?.name.orEmpty())) }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    CollapsingSettingsScaffold(
        title = stringResource(if (state.editedDeviceId == null) R.string.remote_add_device else R.string.remote_edit_device),
        onBack = onBack,
        actions = { IconButton(onClick = { onForward(); vm.saveDevice(address, token, name) },
            enabled = !state.restoring && !state.saving && address.isNotBlank() && token.isNotBlank()) {
            Icon(Icons.Default.Save, stringResource(R.string.save))
        } },
    ) {
        SettingsGroup(title = stringResource(R.string.remote_connection), items = listOf({
            SettingsIconContent(Icons.Default.Computer) {
                McpLabeledField(label = stringResource(R.string.shell_device_name), value = name,
                    onValueChange = { name = it }, placeholder = stringResource(R.string.remote_device))
            }
        }, {
            SettingsIconContent(Icons.Default.Link) {
                McpLabeledField(label = stringResource(R.string.remote_address), value = address,
                    onValueChange = { address = it }, keyboardType = KeyboardType.Uri,
                    supportingText = stringResource(R.string.remote_connection_hint),
                    placeholder = stringResource(R.string.remote_address_placeholder))
            }
        }, {
            SettingsIconContent(Icons.Default.Key) {
                McpLabeledField(label = stringResource(R.string.remote_token), value = token,
                    onValueChange = { token = it }, keyboardType = KeyboardType.Password, password = true,
                    placeholder = stringResource(R.string.remote_token_placeholder))
            }
        }))
    }
}
