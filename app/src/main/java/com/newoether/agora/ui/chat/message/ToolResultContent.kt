package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.ui.chat.MEDIA_LOADING_INDICATOR_STROKE_WIDTH
import com.newoether.agora.ui.chat.MEDIA_STATE_CROSSFADE_MILLIS
import com.newoether.agora.ui.chat.MediaLoadPresentation
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.theme.MonoFamily
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import com.newoether.agora.ui.motion.Crossfade
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text

internal val LocalToolImageLoader =
    staticCompositionLocalOf<(suspend (String, String) -> ToolImageAttachment)?> { null }

@Composable
internal fun ToolDetailContent(
    segment: MessageSegment,
    onMediaClick: (List<String>, Int) -> Unit,
) {
    val presentation = currentResources().toolDetailPresentation(segment)
    val contentAlignmentModifier = if (presentation.kind == ToolKind.WEB_SEARCH) {
        Modifier.padding(horizontal = 8.dp)
    } else {
        Modifier
    }
    if (presentation.arguments != null || presentation.codeArgument != null) {
        Column(modifier = contentAlignmentModifier.fillMaxWidth()) {
            ToolSectionLabel(presentation.argumentsLabel)
            Spacer(Modifier.height(5.dp))
            presentation.arguments?.let { JsonOrPlainView(it) }
            presentation.codeArgument?.let { code ->
                if (presentation.arguments != null) Spacer(Modifier.height(8.dp))
                ToolCodeArgument(code)
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    if (presentation.kind == ToolKind.MCP) {
        Column(modifier = contentAlignmentModifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaPill(text = "MCP", emphasized = true)
                presentation.mcpDevice?.let { MetaPill(it) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = contentAlignmentModifier.fillMaxWidth()) {
            ToolSectionLabel(presentation.resultLabel)
            Spacer(Modifier.height(6.dp))
        }
        if (segment.toolImages.isNotEmpty() || segment.toolImageRequestKey != null) {
            Column(modifier = contentAlignmentModifier.fillMaxWidth()) {
                ToolImageResults(
                    images = segment.toolImages,
                    squareCrop = segment.isImageGenerationSegment(),
                    onMediaClick = onMediaClick,
                    toolCallId = segment.toolCallId,
                    requestKey = segment.toolImageRequestKey,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    val body = presentation.body
    if (body is ToolDetailBody.Search) {
        WebSearchResult(body)
        return
    }
    if (presentation.kind == ToolKind.WEB_SEARCH && body is ToolDetailBody.Muted) {
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            ToolMutedContent(body.text)
        }
        return
    }
    Column(modifier = contentAlignmentModifier.fillMaxWidth()) {
        when (body) {
            is ToolDetailBody.Active -> ToolActiveContent(body.text, body.output)
            is ToolDetailBody.Failed -> {
                ToolErrorContent(body.text)
                if (!body.output.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    TerminalOutput(body.output)
                }
            }
            is ToolDetailBody.Stopped -> GenerationTerminalText(text = body.text, fillWidth = true)
            is ToolDetailBody.Muted -> ToolMutedContent(body.text)
            is ToolDetailBody.JsonContent -> body.values.forEachIndexed { index, value ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                JsonOrPlainView(value)
            }
            is ToolDetailBody.Shell -> ShellResult(body)
            is ToolDetailBody.Paths -> FileGlobResult(body)
            is ToolDetailBody.Grep -> FileGrepResult(body)
            is ToolDetailBody.FileContent -> FileReadResult(body)
            is ToolDetailBody.Edit -> FileEditResult(body)
            is ToolDetailBody.Search -> Unit
        }
    }
}

internal fun toolDetailHorizontalPadding(segment: MessageSegment): Dp =
    when (ToolPresentationResolver.resolve(segment).kind) {
        ToolKind.WEB_SEARCH -> 16.dp
        else -> 24.dp
    }

@Composable
private fun ToolImageResults(
    images: List<ToolImageAttachment>,
    squareCrop: Boolean,
    onMediaClick: (List<String>, Int) -> Unit,
    toolCallId: String?,
    requestKey: String?,
) {
    val displayImages = remember(images) {
        images.filter { it.path.isNotBlank() }
    }
    val paths = remember(displayImages) {
        displayImages.map(ToolImageAttachment::path)
    }
    val loader = LocalToolImageLoader.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (requestKey != null && toolCallId != null && displayImages.isEmpty()) {
            key(toolCallId, requestKey) {
                ToolImagePreview(image = null, squareCrop = false,
                    loadImage = { checkNotNull(loader).invoke(toolCallId, requestKey) },
                    onClick = { onMediaClick(listOf(it.path), 0) })
            }
        }
        displayImages.forEachIndexed { index, image ->
            key(image.path, image.sha256) {
                ToolImagePreview(
                    image = image,
                    squareCrop = squareCrop,
                    onClick = { onMediaClick(paths, index) },
                )
            }
        }
    }
}

@Composable
private fun ToolImagePreview(
    image: ToolImageAttachment?,
    squareCrop: Boolean,
    loadImage: (suspend () -> ToolImageAttachment)? = null,
    onClick: (ToolImageAttachment) -> Unit,
) {
    var attachment by remember(image) { mutableStateOf(image) }
    val aspectRatio = remember(image?.width, image?.height) {
        val width = image?.width?.takeIf { it > 0 }
        val height = image?.height?.takeIf { it > 0 }
        if (width == null || height == null) {
            1f
        } else {
            (width.toFloat() / height.toFloat()).coerceIn(0.55f, 2.2f)
        }
    }
    var loadState by remember(image) {
        mutableStateOf(MediaLoadPresentation.LOADING)
    }
    var presentedState by remember(image) {
        mutableStateOf(MediaLoadPresentation.LOADING)
    }
    LaunchedEffect(loadState) {
        presentedState = loadState
    }
    LaunchedEffect(image) {
        if (loadImage != null) {
            try { attachment = loadImage() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { loadState = MediaLoadPresentation.FAILED }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val previewHeight = if (squareCrop) {
            maxWidth
        } else if (loadImage != null) {
            maxWidth
        } else {
            (maxWidth / aspectRatio).coerceIn(140.dp, 420.dp)
        }
        val previewModifier = Modifier.fillMaxWidth().height(previewHeight)
        Box(
            modifier = previewModifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                )
                .clickable(
                    enabled = presentedState == MediaLoadPresentation.LOADED,
                    onClick = { attachment?.let(onClick) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val displayedImage = attachment
            if (displayedImage != null) coil.compose.AsyncImage(
                model = displayedImage.path,
                contentDescription = stringResource(R.string.tool_view_image),
                contentScale = if (squareCrop) ContentScale.Crop else ContentScale.Fit,
                alignment = Alignment.Center,
                onLoading = { loadState = MediaLoadPresentation.LOADING },
                onSuccess = { loadState = MediaLoadPresentation.LOADED },
                onError = { loadState = MediaLoadPresentation.FAILED },
                modifier = previewModifier,
            )
            Crossfade(
                targetState = presentedState,
                animationSpec = tween(MEDIA_STATE_CROSSFADE_MILLIS),
                label = "toolImagePreview",
                modifier = Modifier.fillMaxSize(),
            ) { state ->
                when (state) {
                    MediaLoadPresentation.LOADING -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    MediaLoadPresentation.LOADED -> Spacer(Modifier.fillMaxSize())
                    MediaLoadPresentation.FAILED -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = stringResource(R.string.attachment_copy_failed_image),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolSectionLabel(text: String) {
    Text(
        text = text,
        style = ChatType.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ToolActiveContent(text: String, output: String?) {
    Text(
        text = text,
        style = ChatType.metaNormal,
        color = MaterialTheme.colorScheme.primary,
    )
    if (!output.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        TerminalOutput(output)
    }
}

@Composable
private fun ToolErrorContent(message: String) {
    GenerationTerminalText(
        text = message,
        selectable = true,
        fillWidth = true,
    )
}

@Composable
internal fun ToolMutedContent(message: String) {
    Text(
        text = message,
        style = ChatType.metaNormal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}


@Composable
private fun ShellResult(body: ToolDetailBody.Shell) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetaPill(text = body.status, emphasized = true)
        MetaPill(body.device)
    }
    body.error?.let {
        Spacer(Modifier.height(8.dp))
        ToolErrorContent(it)
    }
    body.output?.let {
        Spacer(Modifier.height(8.dp))
        TerminalOutput(it)
    }
}

@Composable
private fun WebSearchResult(
    body: ToolDetailBody.Search,
) {
    val uriHandler = LocalUriHandler.current
    val resultShape = RoundedCornerShape(12.dp)
    Column {
        body.results.forEachIndexed { index, item ->
            val title = item.title
            val url = item.url
            val safeUrl = item.safeUrl
            val snippet = item.snippet
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(resultShape)
                    .clickable(
                        enabled = safeUrl != null,
                        onClick = {
                            safeUrl?.let { destination ->
                                runCatching { uriHandler.openUri(destination) }
                            }
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                Text(
                    text = title,
                    style = ChatType.body,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!snippet.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = snippet,
                        style = ChatType.thoughtBody,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!url.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = url,
                        style = ChatType.micro,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (index < body.results.lastIndex) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
internal fun TerminalOutput(output: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        NoAutoScrollSelectionContainer {
            Text(
                text = output,
                style = ChatType.thoughtCodeLarge,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
internal fun MetaPill(
    text: String,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = ChatType.meta,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}
