package com.newoether.agora.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.newoether.agora.R
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.diagnostics.DiagnosticCaptureState
import com.newoether.agora.diagnostics.DiagnosticEvent
import com.newoether.agora.diagnostics.DiagnosticEventPayload
import com.newoether.agora.diagnostics.DiagnosticExportFormat
import com.newoether.agora.diagnostics.DiagnosticRequestContext
import com.newoether.agora.diagnostics.DiagnosticSnapshot
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.motion.AnimatedContent
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.motion.Crossfade
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton

private const val CaptureCrossfadeDurationMillis = 250
private val CaptureEdgeTolerance = 2.dp

private enum class CaptureViewMode {
    SUMMARY,
    RAW,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDeveloperCapturePage(
    onBack: () -> Unit,
    onExportFailed: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val motionPolicy = LocalAgoraMotionPolicy.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snapshot by DeveloperDiagnostics.snapshots.collectAsState()
    val hasNavigableEvents = snapshot.events.isNotEmpty()
    val edgeTolerancePx = with(density) { CaptureEdgeTolerance.roundToPx() }
    val atTop by remember(listState, edgeTolerancePx) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= edgeTolerancePx.coerceAtLeast(0)
        }
    }
    val atBottom by remember(listState, edgeTolerancePx) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.totalItemsCount == 0) {
                true
            } else {
                val lastVisibleItem = layoutInfo.visibleItemsInfo.maxByOrNull { it.index }
                lastVisibleItem?.index == layoutInfo.totalItemsCount - 1 &&
                    lastVisibleItem.offset + lastVisibleItem.size <=
                    layoutInfo.viewportEndOffset + edgeTolerancePx.coerceAtLeast(0)
            }
        }
    }
    val canScrollUp = !atTop
    val canScrollDown = !atBottom
    var viewMode by rememberSaveable { mutableStateOf(CaptureViewMode.SUMMARY) }
    var selectedEvent by remember { mutableStateOf<DiagnosticEvent?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    val hasCaptureData = snapshot.hasCaptureData()
    val chooserTitle = stringResource(R.string.developer_options_export_share_title)
    val playLabel = stringResource(R.string.developer_options_capture_play)
    val pauseLabel = stringResource(R.string.developer_options_capture_pause)
    val scrollUpLabel = stringResource(R.string.developer_options_capture_scroll_to_top)
    val scrollDownLabel = stringResource(R.string.developer_options_capture_scroll_to_bottom)
    val moreActionsLabel = stringResource(R.string.developer_options_capture_more_actions)

    fun requestDirectionalScroll(toTop: Boolean) {
        scope.launch {
            if (toTop) {
                listState.scrollToItem(0)
            } else {
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0) listState.scrollToItem(lastIndex)
            }
        }
    }

    selectedEvent?.let { event ->
        val rawEventDetails = remember(event) { event.rawDetails() }
        AlertDialog(
            onDismissRequest = { selectedEvent = null },
            title = {
                Text(
                    text = "#${event.sequence} ${event.payload.typeName()}",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                SelectionContainer {
                    Crossfade(
                        targetState = viewMode,
                        animationSpec = tween(CaptureCrossfadeDurationMillis),
                        label = "captureEventDetailMode",
                    ) { mode ->
                        val eventDetails = when (mode) {
                            CaptureViewMode.SUMMARY -> event.summaryDetails()
                            CaptureViewMode.RAW -> rawEventDetails
                        }
                        Text(
                            text = eventDetails,
                            modifier = Modifier
                                .heightIn(max = 520.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedEvent = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = {
                Text(
                    text = stringResource(R.string.developer_options_clear_diagnostics),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(stringResource(R.string.developer_options_capture_clear_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        scope.launch { DeveloperDiagnostics.clear() }
                    },
                ) {
                    Text(stringResource(R.string.developer_options_capture_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val captureRunning = snapshot.state == DiagnosticCaptureState.RUNNING
    val captureActionLabel = if (captureRunning) pauseLabel else playLabel

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.developer_options_capture),
        onBack = onBack,
        listState = listState,
        actions = {
            Box {
                CaptureTooltip(label = moreActionsLabel) {
                    IconButton(onClick = { showActionsMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = moreActionsLabel,
                        )
                    }
                }
                AgoraDropdownMenu(
                    expanded = showActionsMenu,
                    onDismissRequest = { showActionsMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp,
                ) {
                    AgoraDropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.developer_options_clear_diagnostics_action,
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        },
                        enabled = hasCaptureData,
                        onClick = {
                            showActionsMenu = false
                            showClearConfirmation = true
                        },
                    )
                    CaptureExportMenuItem(
                        label = stringResource(
                            R.string.developer_options_capture_export_redacted_json,
                        ),
                        enabled = hasCaptureData,
                        onClick = {
                            showActionsMenu = false
                            scope.launch {
                                exportCapture(
                                    context = context,
                                    format = DiagnosticExportFormat.REDACTED_JSON,
                                    chooserTitle = chooserTitle,
                                    onExportFailed = onExportFailed,
                                )
                            }
                        },
                    )
                    CaptureExportMenuItem(
                        label = stringResource(
                            R.string.developer_options_capture_export_summary_text,
                        ),
                        enabled = hasCaptureData,
                        onClick = {
                            showActionsMenu = false
                            scope.launch {
                                exportCapture(
                                    context = context,
                                    format = DiagnosticExportFormat.SUMMARY_TEXT,
                                    chooserTitle = chooserTitle,
                                    onExportFailed = onExportFailed,
                                )
                            }
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            val captureActionEnabled = !snapshot.capacityLimitReached
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 24.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                ) {
                    AnimatedVisibility(
                        visible = hasNavigableEvents && canScrollUp,
                        enter = if (motionPolicy.allowSpatialTransitions) {
                            fadeIn(tween(CaptureCrossfadeDurationMillis)) +
                                expandVertically(tween(CaptureCrossfadeDurationMillis))
                        } else {
                            fadeIn(tween(CaptureCrossfadeDurationMillis))
                        },
                        exit = if (motionPolicy.allowSpatialTransitions) {
                            fadeOut(tween(CaptureCrossfadeDurationMillis)) +
                                shrinkVertically(tween(CaptureCrossfadeDurationMillis))
                        } else {
                            fadeOut(tween(CaptureCrossfadeDurationMillis))
                        },
                    ) {
                        Box(modifier = Modifier.padding(bottom = 12.dp)) {
                            CaptureTooltip(label = scrollUpLabel) {
                                SmallFloatingActionButton(
                                    onClick = { requestDirectionalScroll(toTop = true) },
                                    shape = CircleShape,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = scrollUpLabel,
                                    )
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = hasNavigableEvents && canScrollDown,
                        enter = if (motionPolicy.allowSpatialTransitions) {
                            fadeIn(tween(CaptureCrossfadeDurationMillis)) +
                                expandVertically(tween(CaptureCrossfadeDurationMillis))
                        } else {
                            fadeIn(tween(CaptureCrossfadeDurationMillis))
                        },
                        exit = if (motionPolicy.allowSpatialTransitions) {
                            fadeOut(tween(CaptureCrossfadeDurationMillis)) +
                                shrinkVertically(tween(CaptureCrossfadeDurationMillis))
                        } else {
                            fadeOut(tween(CaptureCrossfadeDurationMillis))
                        },
                    ) {
                        Box(modifier = Modifier.padding(bottom = 12.dp)) {
                            CaptureTooltip(label = scrollDownLabel) {
                                SmallFloatingActionButton(
                                    onClick = { requestDirectionalScroll(toTop = false) },
                                    shape = CircleShape,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = scrollDownLabel,
                                    )
                                }
                            }
                        }
                    }
                    CaptureTooltip(label = captureActionLabel) {
                        FloatingActionButton(
                            onClick = {
                                if (captureActionEnabled) {
                                    scope.launch {
                                        if (captureRunning) {
                                            DeveloperDiagnostics.pauseCapture()
                                        } else {
                                            DeveloperDiagnostics.startCapture()
                                        }
                                    }
                                }
                            },
                            modifier = if (captureActionEnabled) {
                                Modifier
                            } else {
                                Modifier.semantics { disabled() }
                            },
                            shape = CircleShape,
                            containerColor = if (captureActionEnabled) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (captureActionEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ) {
                            Crossfade(
                                targetState = captureRunning,
                                animationSpec = tween(CaptureCrossfadeDurationMillis),
                                label = "capturePlayPause",
                            ) { running ->
                                val label = if (running) pauseLabel else playLabel
                                Icon(
                                    imageVector = if (running) {
                                        Icons.Default.Pause
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    contentDescription = label,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        item(key = "capture-header") {
            Column(modifier = Modifier.fillMaxWidth()) {
                PillTabSwitcher(
                    tabs = listOf(
                        stringResource(R.string.developer_options_capture_summary),
                        stringResource(R.string.developer_options_capture_raw),
                    ),
                    selectedIndex = viewMode.ordinal,
                    onSelect = { index ->
                        CaptureViewMode.entries.getOrNull(index)?.let { viewMode = it }
                    },
                )
                Spacer(Modifier.height(8.dp))
                CaptureSnapshotSummary(snapshot)
                Spacer(Modifier.height(12.dp))
            }
        }

        if (snapshot.events.isEmpty()) {
            item(key = "capture-empty") {
                Text(
                    text = stringResource(R.string.developer_options_capture_empty),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(snapshot.events, key = DiagnosticEvent::sequence) { event ->
                CaptureEventCard(
                    event = event,
                    viewMode = viewMode,
                    onClick = { selectedEvent = event },
                )
            }
        }

        item(key = "capture-fab-spacer") {
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun CaptureExportMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AgoraDropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
        enabled = enabled,
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureTooltip(
    label: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = { content() },
    )
}

@Composable
private fun CaptureSnapshotSummary(snapshot: DiagnosticSnapshot) {
    val stateLabel = stringResource(
        when (snapshot.state) {
            DiagnosticCaptureState.IDLE -> R.string.developer_options_capture_state_idle
            DiagnosticCaptureState.RUNNING -> R.string.developer_options_capture_state_running
            DiagnosticCaptureState.PAUSED -> R.string.developer_options_capture_state_paused
        },
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(
                R.string.developer_options_capture_status_summary,
                stateLabel,
                snapshot.events.size,
                formatBytes(snapshot.retainedPayloadBytes),
            ),
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(
                R.string.developer_options_capture_counters,
                snapshot.droppedEventCount,
                snapshot.evictedEventCount,
                snapshot.truncatedPayloadCount,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (snapshot.capacityLimitReached) {
            Text(
                text = stringResource(
                    R.string.developer_options_capture_capacity_incomplete,
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        snapshot.session?.let { session ->
            Text(
                text = stringResource(
                    R.string.developer_options_capture_session,
                    session.id.take(12),
                    snapshot.nextSequence,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CaptureEventCard(
    event: DiagnosticEvent,
    viewMode: CaptureViewMode,
    onClick: () -> Unit,
) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        SettingsItem(
            modifier = Modifier.clickable(onClick = onClick),
            headlineContent = {
                AnimatedContent(
                    targetState = viewMode,
                    transitionSpec = {
                        val fade = fadeIn(
                            animationSpec = tween(CaptureCrossfadeDurationMillis),
                        ) togetherWith fadeOut(
                            animationSpec = tween(CaptureCrossfadeDurationMillis),
                        )
                        fade.using(
                            SizeTransform(
                                clip = false,
                                sizeAnimationSpec = { _, _ ->
                                    if (allowSpatialTransitions) {
                                        tween(CaptureCrossfadeDurationMillis)
                                    } else {
                                        snap()
                                    }
                                },
                            ),
                        )
                    },
                    label = "captureEventMode",
                ) { mode ->
                    CaptureEventContent(event = event, viewMode = mode)
                }
            },
        )
    }
}

@Composable
private fun CaptureEventContent(
    event: DiagnosticEvent,
    viewMode: CaptureViewMode,
) {
    when (viewMode) {
        CaptureViewMode.SUMMARY -> {
            Column {
                Text(
                    text = "#${event.sequence} ${event.payload.summary()}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                event.context.summary().takeIf(String::isNotBlank)?.let { context ->
                    Text(
                        text = context,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        CaptureViewMode.RAW -> {
            val rawDetails = remember(event) { event.rawDetails() }
            Text(
                text = rawDetails,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun DiagnosticSnapshot.hasCaptureData(): Boolean =
    session != null || events.isNotEmpty() || droppedEventCount > 0L ||
        evictedEventCount > 0L || truncatedPayloadCount > 0L

@Composable
private fun DiagnosticEventPayload.summary(): String = when (this) {
    is DiagnosticEventPayload.RuntimeTransition -> "$commandType · $oldState -> $newState"
    is DiagnosticEventPayload.HttpStage -> "$stage · $elapsedMillis ms"
    is DiagnosticEventPayload.HttpRequest -> stringResource(
        R.string.developer_options_capture_http_request_summary,
        method,
        body.originalLength,
    )
    is DiagnosticEventPayload.HttpResponseBody -> stringResource(
        R.string.developer_options_capture_http_response_summary,
        code,
        body.originalLength,
    )
    is DiagnosticEventPayload.WireLine -> stringResource(
        R.string.developer_options_capture_wire_line_summary,
        lineNumber,
        line.originalLength,
    )
    is DiagnosticEventPayload.ParsedStreamEvent -> stringResource(
        R.string.developer_options_capture_parsed_event_summary,
        eventType,
    )
}

private fun DiagnosticEventPayload.typeName(): String = when (this) {
    is DiagnosticEventPayload.RuntimeTransition -> "RuntimeTransition"
    is DiagnosticEventPayload.HttpStage -> "HttpStage"
    is DiagnosticEventPayload.HttpRequest -> "HttpRequest"
    is DiagnosticEventPayload.HttpResponseBody -> "HttpResponseBody"
    is DiagnosticEventPayload.WireLine -> "WireLine"
    is DiagnosticEventPayload.ParsedStreamEvent -> "ParsedStreamEvent"
}

private fun DiagnosticRequestContext.summary(): String = listOfNotNull(
    requestKind?.let { "kind=$it" },
    provider?.let { "provider=$it" },
    model?.let { "model=$it" },
    requestId?.let { "request=${it.take(12)}" },
    conversationIdHash?.let { "conversation=${it.take(12)}" },
    runId?.let { "run=${it.take(12)}" },
    pass?.let { "pass=$it" },
).joinToString(" · ")

@Composable
private fun DiagnosticEvent.summaryDetails(): String = buildString {
    appendLine("#$sequence ${payload.summary()}")
    context.summary().takeIf(String::isNotBlank)?.let(::appendLine)
    append("timestampMillis=$timestampMillis")
}

private val captureEventJson = Json {
    classDiscriminator = "payloadType"
    encodeDefaults = true
    prettyPrint = true
}

private fun DiagnosticEvent.rawDetails(): String =
    captureEventJson.encodeToString(DiagnosticEvent.serializer(), this)

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format("%.1f KiB", bytes / 1024.0)
    else -> String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
}

private suspend fun exportCapture(
    context: Context,
    format: DiagnosticExportFormat,
    chooserTitle: String,
    onExportFailed: () -> Unit,
) {
    try {
        val snapshot = DeveloperDiagnostics.flush()
        shareDiagnosticCapture(
            context = context,
            snapshot = snapshot,
            format = format,
            chooserTitle = chooserTitle,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onExportFailed()
    }
}

private suspend fun shareDiagnosticCapture(
    context: Context,
    snapshot: DiagnosticSnapshot,
    format: DiagnosticExportFormat,
    chooserTitle: String,
) {
    val content = withContext(Dispatchers.Default) {
        com.newoether.agora.diagnostics.DiagnosticBundleExporter.export(snapshot, format)
    }
    val sendIntent = withContext(Dispatchers.IO) {
        val extension = if (format == DiagnosticExportFormat.SUMMARY_TEXT) "txt" else "json"
        val mimeType = if (extension == "txt") "text/plain" else "application/json"
        val formatName = format.name.lowercase().replace('_', '-')
        val shareDirectory = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(shareDirectory, "agora-diagnostics-$formatName.$extension").apply {
            writeText(content, Charsets.UTF_8)
        }
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Agora diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    withContext(Dispatchers.Main.immediate) {
        val chooser = Intent.createChooser(sendIntent, chooserTitle)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
