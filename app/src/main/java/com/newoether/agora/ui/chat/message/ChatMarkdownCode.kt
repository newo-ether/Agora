package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.model.markdownDimens
import com.newoether.agora.R
import com.newoether.agora.ui.chat.caseInsensitiveMatchRanges
import com.newoether.agora.ui.chat.visibleMarkdownMatchRanges
import kotlinx.coroutines.flow.distinctUntilChanged
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

@Composable
internal fun ChatMarkdownCodeBlock(
    code: String,
    autoWrap: Boolean,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    language: String? = null,
    copyEnabled: Boolean = true,
    canCopy: () -> Boolean = { true },
    copyModifier: Modifier = Modifier,
) {
    val assets = rememberChatMarkdownAssets(MaterialTheme.colorScheme.onSurface)
    CompositionLocalProvider(
        LocalMarkdownColors provides assets.renderContext.colors,
        LocalMarkdownDimens provides markdownDimens(),
    ) {
        ChatCodeBlockSurface(modifier = modifier.padding(vertical = 8.dp)) {
            if (showHeader) ChatCodeBlockHeader(
                language = language,
                code = code,
                enabled = copyEnabled,
                canCopy = canCopy,
                copyModifier = copyModifier,
            )
            val codeStyle = assets.renderContext.typography.code.copy(
                color = MaterialTheme.colorScheme.onSurface,
            )
            MarkdownBasicText(
                text = AnnotatedString(code),
                style = if (autoWrap) codeStyle.copy(lineBreak = LineBreak.Simple) else codeStyle,
                modifier = Modifier
                    .then(if (autoWrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(assets.renderContext.padding.codeBlock),
            )
        }
    }
}

@Composable
internal fun TrackStreamingHorizontalScroll(horizontalScrollState: ScrollState) {
    val interactionController = LocalStreamingMarkdownInteractionController.current
    val interactionOwner = remember { Any() }
    if (interactionController != null) {
        LaunchedEffect(interactionController, interactionOwner, horizontalScrollState) {
            snapshotFlow { horizontalScrollState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { active ->
                    interactionController.setCodeBlockScrolling(interactionOwner, active)
                }
        }
        DisposableEffect(interactionController, interactionOwner) {
            onDispose {
                interactionController.setCodeBlockScrolling(interactionOwner, active = false)
            }
        }
    }
}

@Composable
internal fun SearchHighlightedMarkdownCode(
    model: MarkdownComponentModel,
    fenced: Boolean,
    spec: SearchHighlightSpec?,
    highlightColor: Color,
    activeHighlightColor: Color,
    autoWrap: Boolean = true,
) {
    val streamingFadeSpec = LocalStreamingGlyphFadeSpec.current
    val nodeFade = remember(streamingFadeSpec, model.content, model.node) {
        streamingFadeSpec.nodeFade(
            blockContent = model.content,
            nodeStart = model.node.startOffset,
            nodeEnd = model.node.endOffset,
        )
    }
    // Every state — idle, streaming (fade), and search — renders through the header-bearing
    // [SearchHighlightedMarkdownCodeText] path so the copy button is always present. Falling back
    // to the library's plain fence at terminal is what made the button vanish once the streaming
    // fade stopped. With spec == null and no fade the highlight is a no-op, so the idle block is
    // just plain code text under the same copy header.

    val sourceRange = if (spec == null) {
        null
    } else {
        remember(model.content, model.node, fenced) {
            markdownCodeSourceRange(model.content, model.node, fenced)
        }
    }
    val sourceMatches = if (spec == null) {
        emptyList()
    } else {
        remember(model.content, sourceRange, spec.query) {
            sourceRange?.let { range ->
                val all = visibleMarkdownMatchRanges(model.content, spec.query)
                all.indices.filter { index ->
                    val match = all[index]
                    match.first >= range.first && match.last <= range.last
                }
            }.orEmpty()
        }
    }
    val block: @Composable (String, String?, TextStyle) -> Unit = { code, language, style ->
        val previewEligible = fenced && htmlPreviewLanguage(language) &&
            LocalShellMarkdownMedia.current != null &&
            completedHtmlFence(model.content, model.node, LocalHtmlPreviewStreaming.current)
        SearchHighlightedMarkdownCodeText(
            code = code,
            previewEligible = previewEligible,
            language = language,
            style = style,
            spec = spec,
            sourceMatches = sourceMatches,
            highlightColor = highlightColor,
            activeHighlightColor = activeHighlightColor,
            nodeFade = nodeFade,
            autoWrap = autoWrap,
        )
    }
    if (fenced) {
        MarkdownCodeFence(model.content, model.node, model.typography.code, block)
    } else {
        MarkdownCodeBlock(model.content, model.node, model.typography.code, block)
    }
}

@Composable
private fun SearchHighlightedMarkdownCodeText(
    code: String,
    previewEligible: Boolean,
    language: String?,
    style: TextStyle,
    spec: SearchHighlightSpec?,
    sourceMatches: List<Int>,
    highlightColor: Color,
    activeHighlightColor: Color,
    nodeFade: StreamingGlyphNodeFade?,
    autoWrap: Boolean,
) {
    val displayMatches = if (spec == null) {
        emptyList()
    } else {
        val displayRanges = remember(code, spec.query) {
            caseInsensitiveMatchRanges(code, spec.query)
        }
        remember(displayRanges, sourceMatches, spec.matchKeys) {
            displayRanges.mapIndexedNotNull { index, range ->
                val sourceOccurrence =
                    sourceMatches.getOrNull(index) ?: return@mapIndexedNotNull null
                spec.matchKeys.getOrNull(sourceOccurrence)?.let { key ->
                    DisplaySearchMatch(key, range)
                }
            }
        }
    }
    val highlighted = if (spec == null) {
        AnnotatedString(code)
    } else {
        val activeOccurrence = displayMatches.indexOfFirst { it.key == spec.activeKey }
            .takeIf { it >= 0 }
        remember(
            code,
            spec.query,
            activeOccurrence,
            highlightColor,
            activeHighlightColor,
        ) {
            highlightedSearchText(
                text = AnnotatedString(code),
                query = spec.query,
                activeOccurrence = activeOccurrence,
                highlightColor = highlightColor,
                activeHighlightColor = activeHighlightColor,
            ).first
        }
    }
    val fadeColor = style.color
        .takeUnless { it == Color.Unspecified }
        ?: LocalContentColor.current
    val renderedText = rememberStreamingGlyphFade(
        content = highlighted,
        color = fadeColor,
        fade = nodeFade,
    )
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    if (spec != null) {
        ReportSearchPositions(
            spec = spec,
            displayMatches = displayMatches,
            layoutResult = layoutResult,
            coordinates = coordinates,
        )
    }

    val horizontalScrollState = rememberScrollState()
    TrackStreamingHorizontalScroll(horizontalScrollState)

    ChatCodeBlockSurface(modifier = Modifier.padding(vertical = 8.dp)) {
        HtmlPreviewCodeContent(
            code = code,
            language = language,
            eligible = previewEligible,
            searching = spec != null,
        ) {
            MarkdownBasicText(
                text = renderedText,
                style = if (autoWrap) style.copy(lineBreak = LineBreak.Simple) else style,
                modifier = Modifier
                    .then(if (autoWrap) Modifier else Modifier.horizontalScroll(horizontalScrollState))
                    .padding(LocalMarkdownPadding.current.codeBlock)
                    .onGloballyPositioned { coordinates = it },
                onTextLayout = { layoutResult = it },
            )
        }
    }
}

/** Shared tonal shell for fenced code, standalone code and the fullscreen HTML preview. */
@Composable
internal fun ChatCodeBlockSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(content = content)
    }
}

/** The language yields width before actions; narrow headers wrap whole touch targets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatCodeBlockHeader(
    language: String?,
    code: String,
    enabled: Boolean = true,
    canCopy: () -> Boolean = { true },
    copyModifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    trailingActions: @Composable () -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current
    // Header controls and their popup/dialog roots are not part of the message's text selection.
    DisableSelection {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f).padding(start = 8.dp, end = 4.dp)) {
                    if (!language.isNullOrBlank()) {
                        Text(
                            text = language.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FlowRow(
                    modifier = Modifier.widthIn(max = 200.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    actions()
                    ChatCodeBlockAction(
                        icon = Icons.Default.ContentCopy,
                        label = stringResource(R.string.copy),
                        enabled = enabled,
                        modifier = copyModifier,
                        onClick = {
                            if (canCopy()) clipboardManager.setText(AnnotatedString(code))
                        },
                    )
                    trailingActions()
                }
            }
        }
    }
}

/** Shared uncontained actions retain fixed touch targets and the standard Material indication. */
@Composable
internal fun ChatCodeBlockAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(40.dp),
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (emphasized) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(icon, label, modifier = Modifier.size(16.dp))
        }
    }
}

private fun markdownCodeSourceRange(
    content: String,
    node: ASTNode,
    fenced: Boolean,
): IntRange? {
    if (node.children.isEmpty()) return null
    val start: Int
    val endExclusive: Int
    if (fenced) {
        if (node.children.size < 3) return null
        val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
        start = node.children[2].startOffset
        val minimumEndIndex = if (language != null && node.children.size > 3) 3 else 2
        endExclusive = node.children[
            (node.children.size - 2).coerceAtLeast(minimumEndIndex)
        ].endOffset
    } else {
        start = node.children.first().startOffset
        endExclusive = node.children.last().endOffset
    }
    val safeStart = start.coerceIn(0, content.length)
    val safeEnd = endExclusive.coerceIn(safeStart, content.length)
    return safeStart until safeEnd
}
