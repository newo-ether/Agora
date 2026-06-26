package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.*
import androidx.compose.foundation.text.input.*

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.StableModelAliases
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.components.*
import com.mikepenz.markdown.compose.components.markdownComponents

private const val STREAMING_MARKDOWN_FLUSH_MS = 250L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: ChatMessage, 
    onEdit: (String, String) -> Unit, 
    isStreaming: Boolean = false,
    isLoading: Boolean = false,
    isEditingAllowed: Boolean = true,
    isEditing: Boolean = false,
    isSwitching: Boolean = false,
    isInContext: Boolean = false,
    modelAliases: StableModelAliases = StableModelAliases(),
    visualizeContextRollout: Boolean = false,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    branchIndex: Int = 0,
    totalBranches: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    onRegenerate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onHeightChanged: (Int) -> Unit = {},
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { isFirstComposition = false }

    val isThoughtExpanded by remember(message.id) {
        derivedStateOf { thoughtExpandedStates[message.id] ?: false }
    }
    var showSegmentDetail by remember { mutableStateOf(false) }
    var selectedSegmentIndex by remember { mutableIntStateOf(-1) }
    var selectedSegmentIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var currentThoughtBlockHeight by remember { mutableIntStateOf(0) }
    var stableCollapsedThoughtHeight by remember { mutableIntStateOf(0) }
    // Capture the fully-settled collapsed height after collapse animation finishes.
    // This lets calculateReportedHeight immediately report the post-collapse height
    // even mid-animation, so extraPadding doesn't "chase" the shrinking thought block.
    LaunchedEffect(isThoughtExpanded) {
        if (!isThoughtExpanded) {
            delay(500) // slightly longer than the 400ms collapse tween + mergedBottomPadding tween
            stableCollapsedThoughtHeight = currentThoughtBlockHeight
        }
    }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val haptics = LocalAgoraHaptics.current

    if (showInfoDialog) {
        MessageInfoDialog(
            message = message,
            modelAliases = modelAliases.map,
            onDismiss = { showInfoDialog = false }
        )
    }

    if (showDeleteConfirm) {
        MessageDeleteDialog(
            onConfirm = {
                showDeleteConfirm = false
                haptics.reject()
                onDelete(message.id)
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    var currentTotalHeight by remember { mutableIntStateOf(0) }
    // No-op modifier that suppresses bring-into-view auto-scrolling on focus

    fun calculateReportedHeight(totalPx: Int, thoughtPx: Int): Int {
        // When we are NOT expanded, the thought block is animating down from its large height 
        // to its stableCollapsedThoughtHeight. We want the outer list padding to behave as if
        // the thought block INSTANTLY hit stableCollapsedThoughtHeight, avoiding the final "jump".
        // But we ONLY do this if the thought block is currently larger than its collapsed height 
        // AND we know what the collapsed height is.
        if (!isThoughtExpanded && stableCollapsedThoughtHeight > 0 && thoughtPx > stableCollapsedThoughtHeight) {
            val excessHeight = thoughtPx - stableCollapsedThoughtHeight
            return totalPx - excessHeight
        }
        return totalPx
    }

    LaunchedEffect(message.text, message.status, isEditing, isThoughtExpanded) {
        kotlinx.coroutines.delay(50)
        onHeightChanged(calculateReportedHeight(currentTotalHeight, currentThoughtBlockHeight))
    }

    val alignment = when (message.participant) {
        Participant.USER -> Alignment.End
        Participant.MODEL -> Alignment.Start
        Participant.ERROR -> Alignment.CenterHorizontally
    }

    val backgroundColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.primaryContainer
        Participant.MODEL -> Color.Transparent
        Participant.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    val textColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        Participant.MODEL -> MaterialTheme.colorScheme.onSurface
        Participant.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    val shape = when (message.participant) {
        Participant.USER -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
        Participant.MODEL -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
        Participant.ERROR -> RoundedCornerShape(12.dp)
    }

    val markdownAssets = rememberChatMarkdownAssets(textColor)
    val markdownRenderContext = markdownAssets.renderContext
    val customMarkdownColors = markdownAssets.colors
    val thoughtTypography = markdownAssets.thoughtTypography
    val thoughtMarkdownPadding = markdownAssets.thoughtPadding
    val customMarkdownComponents = markdownAssets.components
    val markdownFlavour = markdownAssets.flavour

    val shouldAnimate = !isFirstComposition && !isSwitching

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged {
                currentTotalHeight = it.height
                onHeightChanged(calculateReportedHeight(it.height, currentThoughtBlockHeight))
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = alignment
    ) {
        val contextAlpha = if (visualizeContextRollout && !isInContext) Modifier.alpha(0.38f) else Modifier
        if (message.participant == Participant.USER) {
            UserMessageBubble(
                message = message,
                shape = shape,
                backgroundColor = backgroundColor,
                textColor = textColor,
                contextAlpha = contextAlpha,
                shouldAnimate = shouldAnimate,
                isEditing = isEditing,
                isLoading = isLoading,
                isEditingAllowed = isEditingAllowed,
                branchIndex = branchIndex,
                totalBranches = totalBranches,
                onEdit = onEdit,
                onCancelEdit = onCancelEdit,
                onStartEdit = onStartEdit,
                onSwitchBranch = onSwitchBranch,
                onMediaClick = onMediaClick,
                onFileContentClick = onFileContentClick,
                onPdfPagesClick = onPdfPagesClick,
                onShowInfo = { showInfoDialog = true },
                onShowDelete = { showDeleteConfirm = true },
            )
        } else {
            AssistantMessageContent(
                message = message,
                contextAlpha = contextAlpha,
                isStreaming = isStreaming,
                isLoading = isLoading,
                isEditingAllowed = isEditingAllowed,
                toolCallDisplayMode = toolCallDisplayMode,
                thoughtExpandedStates = thoughtExpandedStates,
                isThoughtExpanded = isThoughtExpanded,
                renderContext = markdownRenderContext,
                markdownFlavour = markdownFlavour,
                branchIndex = branchIndex,
                totalBranches = totalBranches,
                onSwitchBranch = onSwitchBranch,
                onRegenerate = onRegenerate,
                onMediaClick = onMediaClick,
                onShowInfo = { showInfoDialog = true },
                onShowDelete = { showDeleteConfirm = true },
                onSegmentSelected = { indices ->
                    selectedSegmentIndices = indices
                    selectedSegmentIndex = indices.firstOrNull() ?: -1
                    showSegmentDetail = true
                },
                setThoughtBlockHeight = { currentThoughtBlockHeight = it },
            )
        }
    }

    // Segment detail bottom sheet (self-contained draggable sheet + FSM).
    if (showSegmentDetail && selectedSegmentIndex >= 0) {
        SegmentDetailSheet(
            message = message,
            selectedSegmentIndex = selectedSegmentIndex,
            selectedSegmentIndices = selectedSegmentIndices,
            isStreaming = isStreaming,
            markdownColors = customMarkdownColors,
            thoughtTypography = thoughtTypography,
            thoughtMarkdownPadding = thoughtMarkdownPadding,
            markdownComponents = customMarkdownComponents,
            markdownFlavour = markdownFlavour,
            onDismiss = { showSegmentDetail = false }
        )
    }
}

