package com.newoether.agora.ui.settings.datacontrol

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ConversationStorage
import com.newoether.agora.data.StorageBreakdown
import com.newoether.agora.ui.chat.ChatDeleteConfirmDialog
import com.newoether.agora.ui.chat.ChatDeleteDialogPhase
import com.newoether.agora.ui.chat.bottombar.LegendRow
import com.newoether.agora.ui.chat.bottombar.SegmentedBar
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.settings.CollapsingSettingsScaffold
import com.newoether.agora.ui.settings.SettingsGroup
import com.newoether.agora.ui.settings.SettingsGroupColumn
import com.newoether.agora.ui.settings.SettingsItem
import com.newoether.agora.ui.settings.StatsPlaceholder
import com.newoether.agora.ui.settings.StatsRefreshStatus
import com.newoether.agora.ui.settings.rememberSettingsScrollState
import com.newoether.agora.ui.settings.statsSegmentColors
import com.newoether.agora.ui.settings.statsStaleAlpha
import com.newoether.agora.viewmodel.ChatViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Storage page under Data Control: app-private usage by owner as a segmented bar, then
 * conversations by size with expandable breakdowns and multi-select delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsStoragePage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val report by viewModel.dataControl.storageReport.collectAsState()
    val refreshing by viewModel.dataControl.storageRefreshing.collectAsState()
    // TEMPORARY: forces the counting state so the owner can inspect it without a rescan.
    var debugCounting by remember { mutableStateOf(false) }
    val counting = refreshing || debugCounting

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var visibleCount by remember { mutableIntStateOf(PAGE_SIZE) }
    val scroll = rememberSettingsScrollState()
    // Bulk delete: null hides the dialog; the IDs are those still to delete (failures after a pass).
    var deletePhase by remember { mutableStateOf<ChatDeleteDialogPhase?>(null) }
    var deleteIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var deletedCount by remember { mutableIntStateOf(0) }
    val selecting = selected.isNotEmpty()
    val pending = deletePhase == ChatDeleteDialogPhase.PENDING

    LaunchedEffect(Unit) { viewModel.dataControl.refreshStorage() }
    BackHandler(enabled = selecting) { selected = emptySet() }

    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    CollapsingSettingsScaffold(
        title = if (selecting) {
            stringResource(R.string.storage_selected, selected.size)
        } else {
            stringResource(R.string.storage_title)
        },
        onBack = { if (selecting) selected = emptySet() else onBack() },
        scrollState = scroll,
        actions = {
            if (selecting) {
                IconButton(onClick = { selected = emptySet() }) {
                    Icon(Icons.Default.Close, stringResource(R.string.cancel))
                }
                IconButton(
                    onClick = {
                        deleteIds = selected.toList()
                        deletedCount = 0
                        deletePhase = ChatDeleteDialogPhase.CONFIRM
                    },
                    enabled = !pending,
                ) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete))
                }
            } else {
                IconButton(onClick = { debugCounting = !debugCounting }) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = "Debug counting",
                        tint = if (debugCounting) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
    ) {
        val current = report
        val staleAlpha = statsStaleAlpha(counting && current != null)
        SettingsGroupColumn {
            TotalGroup(current?.breakdown, counting, staleAlpha, ::size)

            val conversations = current?.conversations
            val rows: List<@Composable () -> Unit> = when {
                conversations == null -> List(PLACEHOLDER_ROWS) { @Composable { PlaceholderRow() } }
                conversations.isEmpty() -> listOf({
                    SettingsItem(headlineContent = {
                        Text(
                            stringResource(R.string.storage_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    })
                })
                else -> {
                    val shown = conversations.take(visibleCount)
                    shown.map { conversation ->
                        @Composable {
                            ConversationStorageRow(
                                conversation = conversation,
                                selecting = selecting,
                                isSelected = conversation.id in selected,
                                isExpanded = conversation.id in expanded,
                                size = ::size,
                                modifier = Modifier.alpha(staleAlpha),
                                onClick = {
                                    if (selecting) {
                                        selected = selected.toggle(conversation.id)
                                    } else {
                                        expanded = expanded.toggle(conversation.id)
                                    }
                                },
                                onLongClick = { selected = selected.toggle(conversation.id) },
                            )
                        }
                    }
                }
            }
            SettingsGroup(title = stringResource(R.string.storage_conversations), items = rows)

            val total = conversations?.size ?: 0
            // Reaching the end of the list loads the next page, as the Remote session list does.
            LaunchedEffect(scroll, total, visibleCount) {
                if (visibleCount >= total) return@LaunchedEffect
                val threshold = with(density) { AUTO_LOAD_THRESHOLD.toPx() }
                withFrameNanos { }
                snapshotFlow { scroll.value >= scroll.maxValue - threshold }
                    .filter { it }
                    .collect { visibleCount += PAGE_SIZE }
            }
        }
    }

    deletePhase?.let { phase ->
        val total = deletedCount + deleteIds.size
        ChatDeleteConfirmDialog(
            phase = phase,
            title = stringResource(R.string.storage_delete_title, total),
            message = stringResource(R.string.storage_delete_message),
            progress = if (total > 0) deletedCount.toFloat() / total else 0f,
            progressLabel = stringResource(R.string.storage_delete_progress, deletedCount, total),
            onConfirm = {
                if (deletePhase == ChatDeleteDialogPhase.PENDING) return@ChatDeleteConfirmDialog
                deletePhase = ChatDeleteDialogPhase.PENDING
                scope.launch {
                    // Let the pending dialog draw before the first delete starts, as the chat menu does.
                    withFrameNanos { }
                    val failed = ArrayList<String>()
                    for (id in deleteIds.toList()) {
                        if (viewModel.deleteConversationAwait(id)) {
                            deletedCount++
                            selected = selected - id
                            expanded = expanded - id
                        } else {
                            failed += id
                        }
                        deleteIds = deleteIds - id
                    }
                    viewModel.dataControl.refreshStorage()
                    if (failed.isEmpty()) {
                        deletePhase = null
                        deleteIds = emptyList()
                    } else {
                        deleteIds = failed
                        deletedCount = 0
                        deletePhase = ChatDeleteDialogPhase.FAILED
                    }
                }
            },
            onDismiss = {
                if (deletePhase != ChatDeleteDialogPhase.PENDING) {
                    deletePhase = null
                    deleteIds = emptyList()
                }
            },
        )
    }
}

/** Runs the UI's conversation delete and suspends until it reports its result. */
private suspend fun ChatViewModel.deleteConversationAwait(id: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        val accepted = deleteConversation(id) { ok ->
            if (continuation.isActive) continuation.resume(ok)
        }
        if (!accepted && continuation.isActive) continuation.resume(false)
    }

@Composable
private fun TotalGroup(
    breakdown: StorageBreakdown?,
    refreshing: Boolean,
    staleAlpha: Float,
    size: (Long) -> String,
) {
    val colors = statsSegmentColors()
    val parts = listOf(
        R.string.storage_database to breakdown?.databaseBytes,
        R.string.storage_attachments to breakdown?.attachmentBytes,
        R.string.storage_local_models to breakdown?.localModelBytes,
        R.string.storage_sandbox to breakdown?.sandboxBytes,
        R.string.storage_cache to breakdown?.cacheBytes,
    )
    SettingsGroup(
        title = stringResource(R.string.storage_total),
        items = listOf({
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (breakdown == null) {
                        StatsPlaceholder(120.dp, 28.dp, Modifier.padding(vertical = 4.dp))
                    } else {
                        Text(
                            size(breakdown.totalBytes),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.alpha(staleAlpha),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    StatsRefreshStatus(visible = refreshing)
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.alpha(staleAlpha)) {
                    val total = breakdown?.totalBytes?.coerceAtLeast(1) ?: 1
                    SegmentedBar(
                        fractions = parts.map { (_, bytes) -> (bytes ?: 0L).toFloat() / total },
                        colors = parts.indices.map { colors[it % colors.size] },
                    )
                    Spacer(Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        parts.forEachIndexed { index, (label, bytes) ->
                            LegendRow(
                                colors[index % colors.size],
                                stringResource(label),
                                bytes?.let(size) ?: "—",
                            )
                        }
                    }
                }
            }
        }),
    )
}

@Composable
private fun PlaceholderRow() {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsPlaceholder(160.dp, 14.dp)
            StatsPlaceholder(80.dp, 12.dp)
        }
        Spacer(Modifier.width(16.dp))
        StatsPlaceholder(48.dp, 14.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationStorageRow(
    conversation: ConversationStorage,
    selecting: Boolean,
    isSelected: Boolean,
    isExpanded: Boolean,
    size: (Long) -> String,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).then(modifier)) {
        SettingsItem(
            headlineContent = {
                Text(
                    conversation.title.ifBlank { stringResource(R.string.storage_untitled) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                val messages = stringResource(R.string.storage_message_count, conversation.messageCount)
                val text = if (conversation.isTaskExecution) {
                    "${stringResource(R.string.storage_task_badge)} · $messages"
                } else {
                    messages
                }
                Text(text)
            },
            leadingSpacing = 0.dp,
            leadingContent = {
                // The check appears with the selection, so a normal tap never shifts the title. The gap
                // after it animates inside this block; a slot-level spacer would indent every row.
                val motion = LocalAgoraMotionPolicy.current
                AnimatedVisibility(
                    visible = selecting,
                    enter = if (motion.allowSpatialTransitions) {
                        expandHorizontally(tween(200)) + fadeIn(tween(200))
                    } else {
                        fadeIn(tween(0))
                    },
                    exit = if (motion.allowSpatialTransitions) {
                        shrinkHorizontally(tween(150)) + fadeOut(tween(150))
                    } else {
                        fadeOut(tween(0))
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isSelected, onCheckedChange = null)
                        Spacer(Modifier.width(16.dp))
                    }
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        size(conversation.totalBytes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!selecting) {
                        Spacer(Modifier.width(4.dp))
                        val motion = LocalAgoraMotionPolicy.current
                        val rotation by animateFloatAsState(
                            targetValue = if (isExpanded) 180f else 0f,
                            animationSpec = if (motion.allowSpatialTransitions) tween(200) else snap(),
                            label = "storageRowArrow",
                        )
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp).rotate(rotation),
                        )
                    }
                }
            },
        )
        AnimatedVisibility(visible = isExpanded && !selecting) {
            val colors = statsSegmentColors()
            val parts = listOf(
                R.string.storage_text to conversation.textBytes,
                R.string.storage_tool_calls to conversation.toolBytes,
                R.string.storage_attachments to conversation.attachmentBytes,
                R.string.storage_embeddings to conversation.embeddingBytes,
                R.string.storage_metadata to conversation.metadataBytes,
            )
            val total = conversation.totalBytes.coerceAtLeast(1)
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                SegmentedBar(
                    fractions = parts.map { (_, bytes) -> bytes.toFloat() / total },
                    colors = parts.indices.map { colors[it % colors.size] },
                )
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    parts.forEachIndexed { index, (label, bytes) ->
                        LegendRow(colors[index % colors.size], stringResource(label), size(bytes))
                    }
                }
            }
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

private const val PAGE_SIZE = 100
private const val PLACEHOLDER_ROWS = 3
private val AUTO_LOAD_THRESHOLD = 320.dp
