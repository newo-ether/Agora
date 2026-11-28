package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.newoether.agora.R
import com.newoether.agora.ui.chat.MEDIA_LOADING_INDICATOR_STROKE_WIDTH
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import com.newoether.agora.ui.theme.MonoFamily

/**
 * Recognizes a closed `html-preview` fence whose body is a single local, Local Sandbox, or
 * Conch `.html`/`.htm` file path rather than inline HTML source.
 */
internal fun embeddedHtmlFenceFileLink(code: String): String? {
    val trimmed = code.trim()
    if (trimmed.isEmpty() || '\n' in trimmed || '\r' in trimmed) return null
    return markdownHtmlLink(trimmed)
}

/** Header title shown on an embedded HTML file preview card. */
internal fun markdownHtmlPreviewTitle(link: String, title: String? = null): String {
    title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val raw = link.trim().removeSurrounding("<", ">")
    val path = parseShellMediaLink(raw)?.path
        ?: raw.removePrefix("file://").let { percentDecodePath(it) ?: it }
    val clean = path.substringBefore('?').substringBefore('#')
    val fileName = clean.substringAfterLast('/').substringAfterLast('\\').trim()
    return fileName.ifEmpty { "HTML-PREVIEW" }
}

private sealed interface HtmlFileLoadState {
    data object Loading : HtmlFileLoadState
    data class Ready(val html: String) : HtmlFileLoadState
    data class Failed(val messageResId: Int, val detail: String? = null) : HtmlFileLoadState
}

/**
 * Loads a local, Local Sandbox, or Conch `.html`/`.htm` file and renders it through the standard
 * sandboxed [HtmlPreviewCodeContent] preview card with source toggle, fullscreen, save, share, and
 * reload-from-disk actions.
 */
@Composable
internal fun MarkdownHtmlPreviewCard(
    link: String,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    val media = LocalShellMarkdownMedia.current
    val headerTitle = remember(link, title) { markdownHtmlPreviewTitle(link, title) }
    var loadTrigger by remember(link) { mutableIntStateOf(0) }
    var state by remember(link) { mutableStateOf<HtmlFileLoadState>(HtmlFileLoadState.Loading) }

    LaunchedEffect(media, link, loadTrigger) {
        if (media == null) {
            state = HtmlFileLoadState.Failed(R.string.markdown_link_file_unavailable)
            return@LaunchedEffect
        }
        state = HtmlFileLoadState.Loading
        state = when (val result = media.loadHtmlPreviewFile(link, forceRefresh = loadTrigger > 0)) {
            is ShellMarkdownMedia.HtmlPreviewFileResult.Ready -> HtmlFileLoadState.Ready(result.html)
            is ShellMarkdownMedia.HtmlPreviewFileResult.Failed ->
                HtmlFileLoadState.Failed(result.messageResId, result.detail)
        }
    }

    ChatCodeBlockSurface(modifier = modifier.padding(vertical = 8.dp)) {
        when (val current = state) {
            is HtmlFileLoadState.Ready -> {
                val horizontalScrollState = rememberScrollState()
                TrackStreamingHorizontalScroll(horizontalScrollState)
                val codeStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                HtmlPreviewCodeContent(
                    code = current.html,
                    language = "html-preview",
                    eligible = true,
                    searching = false,
                    headerTitle = headerTitle,
                    onReload = { loadTrigger++ },
                ) {
                    MarkdownBasicText(
                        text = AnnotatedString(current.html),
                        style = codeStyle.copy(lineBreak = LineBreak.Simple),
                        modifier = Modifier
                            .horizontalScroll(horizontalScrollState)
                            .padding(12.dp),
                    )
                }
            }

            HtmlFileLoadState.Loading -> {
                ChatCodeBlockHeader(
                    language = "html-preview",
                    code = link,
                    showCopy = false,
                    displayTitle = headerTitle,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MotionAwareCircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.html_preview_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is HtmlFileLoadState.Failed -> {
                ChatCodeBlockHeader(
                    language = "html-preview",
                    code = link,
                    showCopy = false,
                    displayTitle = headerTitle,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(current.messageResId),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!current.detail.isNullOrBlank()) {
                            Text(
                                text = current.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { loadTrigger++ }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
    }
}
