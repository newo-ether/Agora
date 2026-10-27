package com.newoether.agora.ui.chat

import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.DialogWindowNoSystemAnimation
import com.newoether.agora.ui.components.DialogWindowNoSystemDim

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
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

private class OpenedImageSource(
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

private fun openImageSource(
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

private fun imageSourceType(source: OpenedImageSource): Pair<String, String> {
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

private fun copyImageBytes(input: InputStream, output: OutputStream, checkActive: () -> Unit) {
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

/** Save the image into the device gallery (Pictures/Agora). Returns true on success. */
suspend fun saveImageToGallery(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val actionContext = currentCoroutineContext()
    var destination: Uri? = null
    try {
        val imageSource = openImageSource(context, url, actionContext::ensureActive)
            ?: return@withContext false
        imageSource.use { source ->
            val (mime, extension) = imageSourceType(source)
            actionContext.ensureActive()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "agora_${System.currentTimeMillis()}.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Agora")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            destination = uri
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("Unable to open gallery destination")
            output.use { sink -> copyImageBytes(source.input, sink, actionContext::ensureActive) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        }
        true
    } catch (failure: Exception) {
        destination?.let { runCatching { resolver.delete(it, null, null) } }
        if (failure is CancellationException) throw failure
        false
    }
}

/** Share the image via a content Uri (copied into the exposed cache dir for FileProvider). */
suspend fun shareImage(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
    val actionContext = currentCoroutineContext()
    var destination: File? = null
    try {
        val imageSource = openImageSource(context, url, actionContext::ensureActive)
            ?: return@withContext false
        val mime = imageSource.use { source ->
            val (mime, extension) = imageSourceType(source)
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

private data class ImageInfo(val width: Int, val height: Int, val sizeBytes: Long?)

private fun readImageInfo(context: Context, url: String): ImageInfo? {
    return try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val imageSource = openImageSource(context, url) ?: return null
        val reportedSize = imageSource.use { source ->
            android.graphics.BitmapFactory.decodeStream(source.input, null, opts)
            source.sizeBytes
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        ImageInfo(
            width = opts.outWidth,
            height = opts.outHeight,
            sizeBytes = reportedSize ?: countImageBytes(context, url),
        )
    } catch (_: Exception) {
        null
    }
}

private fun formatBytes(n: Long): String = when {
    n >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", n / (1024.0 * 1024.0))
    n >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", n / 1024.0)
    else -> "$n B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageActionsSheet(url: String, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showInfo by remember { mutableStateOf(false) }
    var imageInfo by remember(url) { mutableStateOf<ImageInfo?>(null) }
    var imageInfoLoading by remember(url) { mutableStateOf(false) }
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
    val savedMsg = stringResource(R.string.img_saved)
    val failMsg = stringResource(R.string.img_save_failed)
    fun doSave() {
        // Keep the sheet in composition until the save finishes — dismissing first would cancel
        // this scope (it's tied to the sheet) and abort both the save and the snackbar.
        scope.launch {
            val ok = saveImageToGallery(context, url)
            onMessage(if (ok) savedMsg else failMsg)
            onDismiss()
        }
    }
    fun doShare() {
        scope.launch {
            shareImage(context, url)
            onDismiss()
        }
    }
    LaunchedEffect(showInfo, url) {
        if (showInfo && imageInfo == null) {
            imageInfoLoading = true
            imageInfo = withContext(Dispatchers.IO) { readImageInfo(context, url) }
            imageInfoLoading = false
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
                ActionRow(Icons.Default.Download, stringResource(R.string.img_action_save)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) collapseThen { doSave() }
                    else permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                ActionRow(Icons.Default.Share, stringResource(R.string.img_action_share)) {
                    collapseThen { doShare() }
                }
                ActionRow(Icons.Default.Info, stringResource(R.string.info)) {
                    collapseThen { showInfo = true }
                }
            }
        }
    }

    if (showInfo) {
        StableImageInfoDialog(
            imageInfo = imageInfo,
            loading = imageInfoLoading,
            onDismissed = {
                showInfo = false
                onDismiss()
            },
        )
    }
}

@Composable
private fun StableImageInfoDialog(
    imageInfo: ImageInfo?,
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
                                InfoLine(
                                    stringResource(R.string.img_info_dimensions),
                                    imageInfo?.let { "${it.width} × ${it.height}" } ?: "—",
                                )
                                InfoLine(
                                    stringResource(R.string.img_info_size),
                                    imageInfo?.sizeBytes?.let(::formatBytes) ?: "—",
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
