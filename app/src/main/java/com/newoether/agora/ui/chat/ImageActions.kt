package com.newoether.agora.ui.chat

import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.DialogWindowNoSystemAnimation
import com.newoether.agora.ui.components.DialogWindowNoSystemDim

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.newoether.agora.R
import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.executeWithNetworkRetry
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import com.newoether.agora.ui.components.sheetOptionClickable

private fun directImageFile(url: String): File? {
    val path = if (url.startsWith("file://", ignoreCase = true)) {
        Uri.parse(url).path
    } else {
        url
    }
    return path?.let(::File)?.takeIf(File::isFile)
}

internal class OpenedImageSource(
    input: InputStream,
    val sizeBytes: Long?,
    private val reopenInput: () -> InputStream,
    private val closeSource: () -> Unit = {},
) : Closeable {
    var input: InputStream = input
        private set

    fun rewind() {
        input.close()
        input = reopenInput()
    }

    override fun close() {
        try {
            input.close()
        } finally {
            closeSource()
        }
    }
}

internal fun openImageSource(
    context: Context,
    url: String,
    checkActive: () -> Unit = {},
): OpenedImageSource? {
    checkActive()
    directImageFile(url)?.let { file ->
        return OpenedImageSource(
            file.inputStream(), file.length().takeIf { it >= 0L }, file::inputStream,
        )
    }
    if (url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)
    ) {
        val temporary = File.createTempFile("image-download-", ".tmp", context.cacheDir)
        try {
            val downloaded = HttpClient.client.executeWithNetworkRetry(
                Request.Builder().url(url).get().build(),
            ) { response ->
                if (!response.isSuccessful) false else {
                    temporary.outputStream().use { output ->
                        response.body.byteStream().use { copyImageBytes(it, output, checkActive) }
                    }
                    true
                }
            }
            if (!downloaded) { temporary.delete(); return null }
            return OpenedImageSource(
                input = temporary.inputStream(),
                sizeBytes = temporary.length(),
                reopenInput = temporary::inputStream,
                closeSource = { temporary.delete() },
            )
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

    val uri = Uri.parse(url)
    val reopenInput = {
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to reopen source image")
    }
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.let { descriptor ->
        return OpenedImageSource(
            input = descriptor.createInputStream(),
            sizeBytes = descriptor.length.takeIf { it >= 0L },
            reopenInput = reopenInput,
            closeSource = descriptor::close,
        )
    }
    return context.contentResolver.openInputStream(uri)?.let { input ->
        OpenedImageSource(input = input, sizeBytes = null, reopenInput = reopenInput)
    }
}

internal fun imageSourceType(source: OpenedImageSource): Pair<String, String> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(source.input, null, bounds)
    val mime = bounds.outMimeType?.lowercase(Locale.ROOT)
        ?: throw IOException("Unable to determine source image type")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || !mime.startsWith("image/")) {
        throw IOException("Unable to determine source image type")
    }
    val extension = if (mime == "image/jpeg") "jpg" else
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: throw IOException("Unsupported source image type: $mime")
    source.rewind()
    return mime to extension
}

internal fun copyImageBytes(input: InputStream, output: OutputStream, checkActive: () -> Unit) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        checkActive()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
    }
    checkActive()
}

private fun countImageBytes(context: Context, url: String): Long? =
    openImageSource(context, url)?.use { source ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = source.input.read(buffer)
            if (read < 0) break
            total += read
        }
        total
    }

private enum class MediaActionKind {
    IMAGE,
    VIDEO,
    AUDIO,
}

internal data class MediaInfo(
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long? = null,
    val formatLabel: String? = null,
    val sizeBytes: Long? = null,
)

private suspend fun shareMedia(
    context: Context,
    url: String,
    resolveType: (Context, OpenedImageSource) -> Pair<String, String>,
): Boolean = withContext(Dispatchers.IO) {
    val actionContext = currentCoroutineContext()
    var destination: File? = null
    try {
        val mediaSource = openImageSource(context, url, actionContext::ensureActive)
            ?: return@withContext false
        val mime = mediaSource.use { source ->
            val (mime, extension) = resolveType(context, source)
            actionContext.ensureActive()
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File.createTempFile("agora_${System.currentTimeMillis()}_", ".$extension", dir)
            destination = file
            file.outputStream().use { sink ->
                copyImageBytes(source.input, sink, actionContext::ensureActive)
            }
            mime
        }
        val file = checkNotNull(destination)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.img_action_share))
            )
        }
        true
    } catch (failure: Exception) {
        destination?.delete()
        if (failure is CancellationException) throw failure
        false
    }
}

/** Share the image via a content Uri (copied into the exposed cache dir for FileProvider). */
suspend fun shareImage(context: Context, url: String): Boolean = shareMedia(
    context = context,
    url = url,
    resolveType = { _, source -> imageSourceType(source) },
)

/** Share a video via a content Uri (copied into the exposed cache dir for FileProvider). */
suspend fun shareVideo(context: Context, url: String): Boolean = shareMedia(
    context = context,
    url = url,
    resolveType = { ctx, _ -> videoMimeAndExtension(ctx, url) },
)

/** Share an audio clip via a content Uri (copied into the exposed cache dir for FileProvider). */
suspend fun shareAudio(context: Context, url: String): Boolean = shareMedia(
    context = context,
    url = url,
    resolveType = { ctx, _ -> audioMimeAndExtension(ctx, url) },
)

private fun readImageInfo(context: Context, url: String): MediaInfo? {
    return try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val imageSource = openImageSource(context, url) ?: return null
        val reportedSize = imageSource.use { source ->
            android.graphics.BitmapFactory.decodeStream(source.input, null, opts)
            source.sizeBytes
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        MediaInfo(
            width = opts.outWidth,
            height = opts.outHeight,
            sizeBytes = reportedSize ?: countImageBytes(context, url),
        )
    } catch (_: Exception) {
        null
    }
}

private fun readVideoInfo(context: Context, url: String): MediaInfo? = try {
    val retriever = android.media.MediaMetadataRetriever()
    try {
        val localFile = directImageFile(url)
        when {
            localFile != null -> retriever.setDataSource(localFile.absolutePath)
            url.startsWith("content://", true) || url.startsWith("file://", true) ->
                retriever.setDataSource(context, Uri.parse(url))
            else -> retriever.setDataSource(url, emptyMap())
        }
        val width = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
        )?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
        )?.toIntOrNull() ?: 0
        val size = openImageSource(context, url)?.use { it.sizeBytes } ?: countImageBytes(context, url)
        if (width <= 0 || height <= 0) null else MediaInfo(width = width, height = height, sizeBytes = size)
    } finally {
        retriever.release()
    }
} catch (_: Exception) {
    null
}

internal fun readAudioInfo(context: Context, url: String): MediaInfo? {
    var durationMs: Long? = null
    var bitrateBps: Int? = null
    var retrievedMime: String? = null
    runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            val localFile = directImageFile(url)
            when {
                localFile != null -> retriever.setDataSource(localFile.absolutePath)
                url.startsWith("content://", true) || url.startsWith("file://", true) ->
                    retriever.setDataSource(context, Uri.parse(url))
                else -> retriever.setDataSource(url, emptyMap())
            }
            durationMs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull()?.takeIf { it > 0L }
            bitrateBps = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE,
            )?.toIntOrNull()?.takeIf { it > 0 }
            retrievedMime = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE,
            )
        } finally {
            retriever.release()
        }
    }
    val size = runCatching {
        openImageSource(context, url)?.use { it.sizeBytes } ?: countImageBytes(context, url)
    }.getOrNull()
    val ext = mediaExtension(url).ifEmpty {
        retrievedMime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }.orEmpty()
    }.uppercase(Locale.ROOT)
    val formatParts = buildList {
        if (ext.isNotEmpty()) add(ext)
        bitrateBps?.let { bps ->
            val kbps = (bps + 500) / 1000
            if (kbps > 0) add("$kbps kbps")
        }
    }
    val formatLabel = formatParts.joinToString(" · ").ifEmpty { null }
    if (durationMs == null && formatLabel == null && size == null) return null
    return MediaInfo(durationMs = durationMs, formatLabel = formatLabel, sizeBytes = size)
}

private fun formatMediaDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private fun formatBytes(n: Long): String = when {
    n >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", n / (1024.0 * 1024.0))
    n >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", n / 1024.0)
    else -> "$n B"
}

@Composable
fun ImageActionsSheet(url: String, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    MediaActionsSheet(url = url, kind = MediaActionKind.IMAGE, onMessage = onMessage, onDismiss = onDismiss)
}

/** The same Save/Share/Info sheet for a playing video. */
@Composable
fun VideoActionsSheet(url: String, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    MediaActionsSheet(url = url, kind = MediaActionKind.VIDEO, onMessage = onMessage, onDismiss = onDismiss)
}

/** The same Save/Share/Info sheet for an inline audio clip. */
@Composable
fun AudioActionsSheet(url: String, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    MediaActionsSheet(url = url, kind = MediaActionKind.AUDIO, onMessage = onMessage, onDismiss = onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaActionsSheet(
    url: String,
    kind: MediaActionKind,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showInfo by remember { mutableStateOf(false) }
    var mediaInfo by remember(url, kind) { mutableStateOf<MediaInfo?>(null) }
    var mediaInfoLoading by remember(url, kind) { mutableStateOf(false) }
    var sheetVisible by remember(url) { mutableStateOf(true) }
    var actionInFlight by remember(url) { mutableStateOf(false) }
    val motionPolicy = LocalAgoraMotionPolicy.current
    val sheetState = rememberModalBottomSheetState()

    // Dispose the sheet window before opening another modal. Keeping a hidden sheet Dialog alive
    // while creating the Info Dialog lets their independent window animations race.
    fun collapseThen(action: () -> Unit) {
        if (actionInFlight) return
        actionInFlight = true
        scope.launch {
            if (motionPolicy.allowSpatialTransitions) {
                sheetState.hide()
            }
            sheetVisible = false
            withFrameNanos { }
            action()
        }
    }

    // Routed through the single global snackbar host (a new message dismisses the previous one).
    val saveLabel = stringResource(
        if (kind == MediaActionKind.AUDIO) R.string.audio_action_save else R.string.img_action_save,
    )
    val savedMsg = stringResource(
        if (kind == MediaActionKind.AUDIO) R.string.audio_saved else R.string.img_saved,
    )
    val failMsg = stringResource(R.string.img_save_failed)
    fun doSave() {
        // Keep the sheet in composition until the save finishes — dismissing first would cancel
        // this scope (it's tied to the sheet) and abort both the save and the snackbar.
        scope.launch {
            val savedPath = when (kind) {
                MediaActionKind.IMAGE -> saveImageToGallery(context, url)
                MediaActionKind.VIDEO -> saveVideoToGallery(context, url)
                MediaActionKind.AUDIO -> saveAudioToMusic(context, url)
            }
            // Name the exact file so the user knows where it landed.
            onMessage(savedPath?.let { "$savedMsg\n$it" } ?: failMsg)
            onDismiss()
        }
    }
    fun doShare() {
        scope.launch {
            when (kind) {
                MediaActionKind.IMAGE -> shareImage(context, url)
                MediaActionKind.VIDEO -> shareVideo(context, url)
                MediaActionKind.AUDIO -> shareAudio(context, url)
            }
            onDismiss()
        }
    }
    LaunchedEffect(showInfo, url, kind) {
        if (showInfo && mediaInfo == null) {
            mediaInfoLoading = true
            mediaInfo = withContext(Dispatchers.IO) {
                when (kind) {
                    MediaActionKind.IMAGE -> readImageInfo(context, url)
                    MediaActionKind.VIDEO -> readVideoInfo(context, url)
                    MediaActionKind.AUDIO -> readAudioInfo(context, url)
                }
            }
            mediaInfoLoading = false
        }
    }
    // Pre-Q gallery writes need WRITE_EXTERNAL_STORAGE; request it then save.
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) doSave() else onMessage(failMsg) }

    if (sheetVisible) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            DialogWindowEdgeToEdge()
            DialogWindowNoSystemDim()
            Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                ActionRow(Icons.Rounded.Download, saveLabel) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) collapseThen { doSave() }
                    else permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                ActionRow(Icons.Rounded.Share, stringResource(R.string.img_action_share)) {
                    collapseThen { doShare() }
                }
                ActionRow(Icons.Rounded.Info, stringResource(R.string.info)) {
                    collapseThen { showInfo = true }
                }
            }
        }
    }

    if (showInfo) {
        StableImageInfoDialog(
            kind = kind,
            mediaInfo = mediaInfo,
            loading = mediaInfoLoading,
            onDismissed = {
                showInfo = false
                onDismiss()
            },
        )
    }
}

@Composable
private fun StableImageInfoDialog(
    kind: MediaActionKind,
    mediaInfo: MediaInfo?,
    loading: Boolean,
    onDismissed: () -> Unit,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    var visible by remember { mutableStateOf(false) }
    val transition = updateTransition(visible, label = "imageInfoDialog")
    var dismissalRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    fun requestDismiss() {
        if (dismissalRequested) return
        dismissalRequested = true
        visible = false
    }

    LaunchedEffect(
        dismissalRequested,
        transition.currentState,
        transition.isRunning,
    ) {
        if (dismissalRequested && !transition.currentState && !transition.isRunning) {
            currentOnDismissed()
        }
    }

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DialogWindowEdgeToEdge()
        DialogWindowNoSystemDim()
        DialogWindowNoSystemAnimation()
        Box(modifier = Modifier.fillMaxSize()) {
            transition.AnimatedVisibility(
                visible = { it },
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.fillMaxSize(),
            ) {
                val scrimInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = scrimInteraction,
                            indication = null,
                            onClick = ::requestDismiss,
                        ),
                )
            }
            transition.AnimatedVisibility(
                visible = { it },
                enter = if (motionPolicy.allowSpatialTransitions) {
                    fadeIn(tween(180)) + scaleIn(
                        initialScale = 0.94f,
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                    )
                } else {
                    fadeIn(tween(180))
                },
                exit = if (motionPolicy.allowSpatialTransitions) {
                    fadeOut(tween(140)) + scaleOut(
                        targetScale = 0.96f,
                        animationSpec = tween(160, easing = FastOutSlowInEasing),
                    )
                } else {
                    fadeOut(tween(140))
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp),
            ) {
                val dialogInteraction = remember { MutableInteractionSource() }
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 560.dp)
                        .clickable(
                            interactionSource = dialogInteraction,
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = stringResource(R.string.info),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(20.dp))
                        if (loading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (kind == MediaActionKind.AUDIO) {
                                    InfoLine(
                                        stringResource(R.string.audio_info_duration),
                                        mediaInfo?.durationMs?.let(::formatMediaDuration) ?: "—",
                                    )
                                    InfoLine(
                                        stringResource(R.string.audio_info_format),
                                        mediaInfo?.formatLabel ?: "—",
                                    )
                                } else {
                                    InfoLine(
                                        stringResource(R.string.img_info_dimensions),
                                        mediaInfo?.takeIf { it.width > 0 && it.height > 0 }
                                            ?.let { "${it.width} × ${it.height}" } ?: "—",
                                    )
                                }
                                InfoLine(
                                    stringResource(R.string.img_info_size),
                                    mediaInfo?.sizeBytes?.let(::formatBytes) ?: "—",
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = ::requestDismiss) {
                                Text(stringResource(R.string.provider_close))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().sheetOptionClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    // Matches the message info dialog: single "Label: value" line at bodyMedium 14/20.
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
    )
}
