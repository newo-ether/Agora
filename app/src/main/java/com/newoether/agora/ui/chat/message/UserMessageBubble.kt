package com.newoether.agora.ui.chat.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Shape
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.chat.AttachmentThumbnailItem
import com.newoether.agora.ui.chat.ThumbnailClickHandlers
import com.newoether.agora.ui.chat.resolveAttachmentType
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType

/**
 * The right-aligned user message bubble: attachment thumbnails, the message text
 * (or an inline editor), the branch switcher, and the copy/edit/overflow action row.
 * Extracted from [MessageItem]; the parent owns the info/delete dialogs, triggered
 * here via [onShowInfo] / [onShowDelete].
 */
internal fun userBubbleSizeAnimationEnabled(
    sizeAnimationReady: Boolean,
    allowSpatialTransitions: Boolean,
): Boolean = sizeAnimationReady && allowSpatialTransitions

internal data class StoredMediaOccurrenceProjection(
    val urls: List<String>,
    val indexByDisplayItem: List<Int?>,
)

internal fun projectStoredMediaOccurrences(
    displayItems: List<Pair<String, AttachmentItem?>>,
): StoredMediaOccurrenceProjection {
    val urls = mutableListOf<String>()
    val indices = displayItems.map { (imagePath, item) ->
        val url = when (resolveAttachmentType(imagePath, item)) {
            "image" -> imagePath.takeIf {
                it.isNotEmpty() && item?.unavailable != true
            }
            "video" -> item
                ?.takeUnless(AttachmentItem::unavailable)
                ?.originalUri
                ?.takeIf(String::isNotBlank)
            else -> null
        }
        url?.let {
            urls += it
            urls.lastIndex
        }
    }
    return StoredMediaOccurrenceProjection(urls, indices)
}

@Composable
internal fun UserMessageBubble(
    message: ChatMessage,
    shape: Shape,
    backgroundColor: Color,
    textColor: Color,
    contextAlpha: Modifier,
    isEditing: Boolean,
    sizeAnimationReady: Boolean,
    isLoading: Boolean,
    isEditingAllowed: Boolean,
    showActions: Boolean,
    actionCopyText: String?,
    showBranchSelector: Boolean,
    branchIndex: Int,
    totalBranches: Int,
    onEdit: (String, String) -> Unit,
    onCancelEdit: () -> Unit,
    onStartEdit: () -> Unit,
    onSelectText: () -> Unit,
    onSwitchBranch: (Int) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onFileContentClick: ((fileName: String, content: String) -> Unit)?,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)?,
    onShowInfo: () -> Unit,
    onShowDelete: () -> Unit,
    searchHighlight: SearchHighlightSpec?,
    allowMutations: Boolean = true,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalAgoraHaptics.current
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    var showMenu by remember { mutableStateOf(false) }
    val editFocusRequester = remember(message.id) { FocusRequester() }
    LaunchedEffect(isEditing, editFocusRequester) {
        if (isEditing) editFocusRequester.requestFocus()
    }

    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.then(
            if (userBubbleSizeAnimationEnabled(sizeAnimationReady, allowSpatialTransitions)) {
                Modifier.animateContentSize(animationSpec = tween(durationMillis = 500))
            } else {
                Modifier
            },
        ),
    ) {
        Box {
            Surface(
            shape = shape,
            color = backgroundColor,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .then(contextAlpha)
                .clip(shape)
                .combinedClickable(
                    enabled = !isEditing && showActions,
                    hapticFeedbackEnabled = false,
                    onClick = {},
                    onLongClick = {
                        haptics.longPress()
                        showMenu = true
                    },
                )
        ) {
            if (isEditing) {
                val editState = rememberTextFieldState(message.text)
                val editScrollState = rememberScrollState()
                Column(modifier = Modifier.padding(8.dp)) {
                    Box(modifier = Modifier.noOpBringIntoView()) {
                        TextField(
                            state = editState,
                            scrollState = editScrollState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(editFocusRequester),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { onCancelEdit() },
                            enabled = !isLoading,
                        ) { Text(stringResource(R.string.cancel)) }
                        TextButton(
                            onClick = { onEdit(message.id, editState.text.toString()) },
                            enabled = !isLoading && editState.text.isNotBlank(),
                        ) { Text(stringResource(R.string.send)) }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(16.dp).noOpBringIntoView(),
                    horizontalAlignment = Alignment.Start
                ) {
                    val hasMetaItems = message.attachmentMeta?.items?.isNotEmpty() == true
                if (message.images.isNotEmpty() || hasMetaItems) {
                        val meta = remember(message.attachmentMeta) {
                            message.attachmentMeta
                        }
                        // Metadata owns attachment order. Images not claimed by metadata are legacy
                        // entries and are appended in their stored order.
                        val displayItems = remember(message.images, meta) {
                            val claimedImageIndices = mutableSetOf<Int>()
                            val metadataItems = meta?.items.orEmpty().map { item ->
                                val start = item.imageIndex
                                if (start != null) {
                                    val count = item.pageCount?.coerceAtLeast(1) ?: 1
                                    claimedImageIndices += start until start + count
                                }
                                Triple(
                                    start ?: -1,
                                    start?.let(message.images::getOrNull).orEmpty(),
                                    item,
                                )
                            }
                            val legacyItems = message.images.mapIndexedNotNull { index, path ->
                                if (index in claimedImageIndices) null else Triple(index, path, null)
                            }
                            metadataItems + legacyItems
                        }

                        val mediaProjection = remember(displayItems) {
                            projectStoredMediaOccurrences(
                                displayItems.map { (_, imagePath, metaItem) ->
                                    imagePath to metaItem
                                },
                            )
                        }
                        val allMediaUrls = mediaProjection.urls

                        LazyRow(
                            modifier = Modifier.padding(bottom = if (message.text.isNotEmpty()) 8.dp else 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(displayItems) { displayIndex, (_, imagePath, metaItem) ->
                                val type = remember(imagePath, metaItem?.type) {
                                    resolveAttachmentType(imagePath, metaItem)
                                }
                                val isPdf = type == "pdf"

                                val fileName = metaItem?.fileName ?: imagePath.substringAfterLast("/")
                                val pdfPages = if (type == "pdf") {
                                    metaItem?.imageIndex?.let { start ->
                                        val count = metaItem.pageCount ?: 1
                                        val end = (start + count).coerceAtMost(message.images.size)
                                        if (start in 0 until message.images.size) message.images.subList(start, end) else emptyList()
                                    } ?: emptyList()
                                } else emptyList()

                                val mediaIndex =
                                    mediaProjection.indexByDisplayItem[displayIndex] ?: 0

                                AttachmentThumbnailItem(
                                    type = type,
                                    imagePath = imagePath,
                                    fileName = fileName,
                                    originalUri = metaItem?.originalUri,
                                    textContent = metaItem?.textContent,
                                    unavailable = metaItem?.unavailable == true,
                                    pdfPages = pdfPages,
                                    allMediaUrls = allMediaUrls,
                                    mediaIndex = mediaIndex,
                                    handlers = ThumbnailClickHandlers(
                                        onMediaClick = onMediaClick,
                                        onFileClick = onFileContentClick,
                                        onPdfClick = onPdfPagesClick
                                    )
                                )
                                if (type == "pdf" && metaItem?.warning != null) {
                                    Text(metaItem.warning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (message.text.isNotEmpty()) {
                        if (isLargeForMarkdown(message.text)) {
                            LargeMessageView(text = message.text, textColor = textColor)
                        } else {
                            SearchHighlightedPlainText(
                                text = message.text,
                                style = ChatType.userBody,
                                color = textColor,
                                spec = searchHighlight,
                            )
                        }
                    }
                }
            }
        }

            DropdownMenu(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 16.dp,
                shape = RoundedCornerShape(12.dp),
                expanded = showMenu && showActions && !isEditing,
                onDismissRequest = { showMenu = false },
            ) {
                if (!actionCopyText.isNullOrBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        onClick = {
                            clipboardManager.setText(AnnotatedString(actionCopyText))
                            haptics.confirm()
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    )
                }
                if (allowMutations) DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit)) },
                    onClick = {
                        showMenu = false
                        onStartEdit()
                    },
                    enabled = isEditingAllowed,
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                )
                if (message.text.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.select_text)) },
                        onClick = {
                            showMenu = false
                            onSelectText()
                        },
                        leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.info)) },
                    onClick = {
                        showMenu = false
                        onShowInfo()
                    },
                    leadingIcon = { Icon(Icons.Default.Info, null) },
                )
                if (allowMutations) DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.delete),
                            color = if (!isLoading) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            },
                        )
                    },
                    onClick = {
                        showMenu = false
                        onShowDelete()
                    },
                    enabled = !isLoading,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            tint = if (!isLoading) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            },
                        )
                    },
                )
            }
        }
        if (showBranchSelector && totalBranches > 1 && !isEditing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .then(contextAlpha)
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(100))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 4.dp)
            ) {
                IconButton(onClick = { onSwitchBranch(-1) }, enabled = branchIndex > 0 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(16.dp))
                }
                Text("${branchIndex + 1} / $totalBranches", style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { onSwitchBranch(1) }, enabled = branchIndex < totalBranches - 1 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                }
            }
        }


    }
}
