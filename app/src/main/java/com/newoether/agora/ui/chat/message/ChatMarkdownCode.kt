package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.caseInsensitiveMatchRanges
import com.newoether.agora.ui.chat.visibleMarkdownMatchRanges
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun ChatMarkdownCodeBlock(
    code: String,
    autoWrap: Boolean,
    modifier: Modifier = Modifier,
) {
    val assets = rememberChatMarkdownAssets(MaterialTheme.colorScheme.onSurface)
    CompositionLocalProvider(
        LocalMarkdownColors provides assets.renderContext.colors,
        LocalMarkdownDimens provides markdownDimens(),
    ) {
        MarkdownCodeBackground(
            color = assets.renderContext.colors.codeBackground,
            shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            showHeader = false,
            language = null,
            code = code,
        ) {
            Column {
                ChatCodeBlockHeader(language = null, code = code)
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
        SearchHighlightedMarkdownCodeText(
            code = code,
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

    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        showHeader = false,
        language = language,
        code = code,
    ) {
        Column {
            ChatCodeBlockHeader(language = language, code = code)
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

/**
 * Code-block header mirroring the library's internal MarkdownCodeTopBar layout: monospace language
 * tag on the left, a 24.dp round copy target on the right, and the same 0.3-alpha 0.5.dp divider
 * below. The copy affordance is the standard Material icon instead of the library glyph, so it
 * matches the copy actions used elsewhere in the app. Rendered inside [MarkdownCodeBackground]'s
 * content slot while its built-in header stays disabled.
 */
@Composable
private fun ChatCodeBlockHeader(language: String?, code: String) {
    val clipboardManager = LocalClipboardManager.current
    val textColor = LocalMarkdownColors.current.text
    val copyLabel = stringResource(R.string.copy)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                text = language.uppercase(),
                style = TextStyle(
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = textColor.copy(alpha = 0.6f),
                ),
            )
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .semantics { role = Role.Button }
                .clickable(onClickLabel = copyLabel) {
                    clipboardManager.setText(AnnotatedString(code))
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = copyLabel,
                modifier = Modifier.size(14.dp),
                tint = textColor.copy(alpha = 0.6f),
            )
        }
    }
    HorizontalDivider(
        color = LocalMarkdownColors.current.dividerColor.copy(alpha = 0.3f),
        thickness = 0.5.dp,
    )
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
