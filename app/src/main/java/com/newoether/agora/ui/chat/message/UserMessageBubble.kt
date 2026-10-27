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
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.subagentResultDisplay
import com.newoether.agora.ui.chat.AttachmentThumbnailItem
import com.newoether.agora.ui.chat.ThumbnailClickHandlers
import com.newoether.agora.ui.chat.USER_BUBBLE_CONTENT_PADDING
import com.newoether.agora.ui.chat.resolveAttachmentType
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem

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

private fun storedMediaUnavailable(
    imagePath: String,
    item: AttachmentItem?,
    imageArtifacts: Map<String, ImageArtifact>,
): Boolean {
    val occurrences = item?.imageArtifacts.orEmpty()
    if (item?.type == "pdf" && occurrences.isNotEmpty()) {
        return occurrences.all { it.unavailable || it.path.isBlank() }
    }
    if (item?.unavailable == true) return true
    return if (occurrences.isNotEmpty()) {
        occurrences.any(ImageArtifact::unavailable)
    } else imageArtifacts[imagePath]?.unavailable == true
}

internal fun projectStoredMediaOccurrences(
    displayItems: List<Pair<String, AttachmentItem?>>,
    imageArtifacts: Map<String, ImageArtifact> = emptyMap(),
): StoredMediaOccurrenceProjection {
    val urls = mutableListOf<String>()
    val indices = displayItems.map { (imagePath, item) ->
        val unavailable = storedMediaUnavailable(imagePath, item, imageArtifacts)
        val url = when (resolveAttachmentType(imagePath, item)) {
            "image" -> imagePath.takeIf {
                it.isNotEmpty() && !unavailable
            }
            "video" -> item
                ?.takeUnless { unavailable }
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
    val showEditor = isEditing && message.source == null
    val editEnabled = isEditingAllowed && message.source == null
    val subagentResult = remember(message.source, message.text) {
        message.source?.subagentResultDisplay(message.text)
    }
    var showMenu by remember { mutableStateOf(false) }
    val editFocusRequester = remember(message.id) { FocusRequester() }
    LaunchedEffect(showEditor, editFocusRequester) {
        if (showEditor) editFocusRequester.requestFocus()
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
        message.source?.let { source ->
            MessageSourceLabel(source = source, modifier = contextAlpha)
        }
        Box {
            Surface(
            shape = shape,
            color = backgroundColor,
            modifier = Modifier
                .widthIn(min = USER_BUBBLE_MIN_WIDTH, max = 300.dp)
                .then(contextAlpha)
                .clip(shape)
                .combinedClickable(
                    enabled = !showEditor && showActions,
                    hapticFeedbackEnabled = false,
                    onClick = {},
                    onLongClick = {
                        haptics.longPress()
                        showMenu = true
                    },
                )
        ) {
            if (showEditor) {
                val editState = rememberTextFieldState(message.text)
                val editScrollState = rememberScrollState()
                // Same inset as the read-only bubble, so the text stays put when editing starts;
                // the field keeps only a bottom gap above its indicator line.
                // The action buttons are 40dp tall around a 20sp label, so each carries 10dp of
                // invisible height below its text; the bottom inset drops by that amount (and 1dp
                // more, tuned by eye) so the visible gap under Cancel/Send matches the other sides.
                Column(
                    modifier = Modifier.padding(
                        start = USER_BUBBLE_CONTENT_PADDING,
                        top = USER_BUBBLE_CONTENT_PADDING,
                        end = USER_BUBBLE_CONTENT_PADDING,
                        bottom = EDIT_BOTTOM_INSET,
                    ),
                ) {
                    Box(modifier = Modifier.noOpBringIntoView()) {
                        TextField(
                            state = editState,
                            scrollState = editScrollState,
                            // TextField applies a 56dp default min height only when no min
                            // height is set; any explicit min lets the text decide the height, so a
                            // single line keeps its indicator 8dp below the text.
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 1.dp)
                                .focusRequester(editFocusRequester),
                            contentPadding = PaddingValues(bottom = 8.dp),
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
                // Content narrower than the minimum width sits centered; wider content fills the
                // bubble and keeps its start alignment.
                Box(contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.padding(USER_BUBBLE_CONTENT_PADDING).noOpBringIntoView(),
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

                        val mediaProjection = remember(displayItems, message.imageArtifacts) {
                            projectStoredMediaOccurrences(
                                displayItems.map { (_, imagePath, metaItem) ->
                                    imagePath to metaItem
                                },
                                message.imageArtifacts,
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
                                        if (start in 0 until message.images.size) {
                                            message.images.subList(start, end).mapIndexed { index, path ->
                                                val artifact = metaItem.imageArtifacts.getOrNull(index) ?: message.imageArtifacts[path]
                                                path.takeUnless { artifact?.unavailable == true } ?: ""
                                            }
                                        } else emptyList()
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
                                    unavailable = storedMediaUnavailable(
                                        imagePath, metaItem, message.imageArtifacts,
                                    ),
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
                    val askUser = message.source?.takeIf { it.kind == MessageSource.Kind.ASK_USER }
                    if (subagentResult != null) {
                        SubagentResultSummary(result = subagentResult, textColor = textColor)
                    } else if (askUser != null) {
                        AskUserAnswerBlocks(
                            source = askUser,
                            unansweredLabel = stringResource(R.string.message_source_unanswered),
                            bodyStyle = ChatType.userBody,
                            textColor = textColor,
                            searchHighlight = searchHighlight,
                        )
                    } else if (message.text.isNotEmpty()) {
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

            AgoraDropdownMenu(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 16.dp,
                expanded = showMenu && showActions && !showEditor,
                onDismissRequest = { showMenu = false },
            ) {
                if (!actionCopyText.isNullOrBlank()) {
                    AgoraDropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        onClick = {
                            clipboardManager.setText(AnnotatedString(actionCopyText))
                            haptics.confirm()
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    )
                }
                if (allowMutations) AgoraDropdownMenuItem(
                    text = { Text(stringResource(R.string.edit)) },
                    onClick = {
                        showMenu = false
                        if (editEnabled) onStartEdit()
                    },
                    enabled = editEnabled,
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                )
                if (message.text.isNotBlank()) {
                    AgoraDropdownMenuItem(
                        text = { Text(stringResource(R.string.select_text)) },
                        onClick = {
                            showMenu = false
                            onSelectText()
                        },
                        leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                    )
                }
                AgoraDropdownMenuItem(
                    text = { Text(stringResource(R.string.info)) },
                    onClick = {
                        showMenu = false
                        onShowInfo()
                    },
                    leadingIcon = { Icon(Icons.Default.Info, null) },
                )
                if (allowMutations) AgoraDropdownMenuItem(
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
        if (showBranchSelector && totalBranches > 1 && !showEditor) {
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

/**
 * Bottom inset of the edit-mode bubble: the 15dp content padding minus the 10dp a Material
 * TextButton (40dp min height, 20sp label) leaves below its label, minus 1dp tuned by eye.
 */
private val EDIT_BOTTOM_INSET = 4.dp

/** Width of a single-line bubble's height (15dp padding twice plus one 24.2sp line), so the
 * shortest message is a circle rather than a pinched capsule. */
private val USER_BUBBLE_MIN_WIDTH = 54.dp
