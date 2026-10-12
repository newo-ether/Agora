package com.newoether.agora.ui.chat.bottombar

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton

/**
 * Package visibility is intentionally handled here instead of assuming every OEM exposes the
 * standard image-capture activity. A false result routes to the in-app CameraX implementation.
 */
internal fun canLaunchSystemImageCapture(context: Context): Boolean =
    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        .resolveActivity(context.packageManager) != null

/**
 * Vendor-neutral full-resolution fallback for devices whose camera app does not expose
 * [MediaStore.ACTION_IMAGE_CAPTURE]. CameraX writes directly to [targetPath], which already lives
 * under Agora's private files directory.
 */
@Composable
internal fun InternalCameraCaptureDialog(
    targetPath: String,
    onCaptured: () -> Unit,
    onCancelled: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val latestOnCancelled by rememberUpdatedState(onCancelled)
    val latestOnFailure by rememberUpdatedState(onFailure)
    val previewView = remember(targetPath) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var provider by remember(targetPath) { mutableStateOf<ProcessCameraProvider?>(null) }
    var preview by remember(targetPath) { mutableStateOf<Preview?>(null) }
    var imageCapture by remember(targetPath) { mutableStateOf<ImageCapture?>(null) }
    var captureInProgress by remember(targetPath) { mutableStateOf(false) }
    var terminalDelivered by remember(targetPath) { mutableStateOf(false) }

    fun cancelOnce() {
        if (terminalDelivered) return
        terminalDelivered = true
        latestOnCancelled()
    }

    fun failOnce(error: Throwable) {
        if (terminalDelivered) return
        terminalDelivered = true
        latestOnFailure(error)
    }

    LaunchedEffect(targetPath, lifecycleOwner, previewView) {
        try {
            val acquiredProvider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get(10L, TimeUnit.SECONDS)
            }
            val acquiredPreview = Preview.Builder().build().also { useCase ->
                useCase.surfaceProvider = previewView.surfaceProvider
            }
            val acquiredCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            acquiredProvider.unbindAll()
            acquiredProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                acquiredPreview,
                acquiredCapture,
            )
            provider = acquiredProvider
            preview = acquiredPreview
            imageCapture = acquiredCapture
        } catch (error: Throwable) {
            failOnce(error)
        }
    }

    DisposableEffect(targetPath) {
        onDispose {
            val boundProvider = provider
            val boundPreview = preview
            val boundCapture = imageCapture
            if (boundProvider != null && boundPreview != null && boundCapture != null) {
                runCatching { boundProvider.unbind(boundPreview, boundCapture) }
            }
        }
    }

    Dialog(
        onDismissRequest = ::cancelOnce,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DialogWindowEdgeToEdge()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            IconButton(
                onClick = ::cancelOnce,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.42f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Color.White,
                )
            }

            if (imageCapture == null) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            val captureReady = imageCapture != null && !captureInProgress
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
                    .size(78.dp)
                    .border(3.dp, Color.White, CircleShape)
                    .padding(7.dp)
                    .background(
                        color = if (captureReady) Color.White else Color.White.copy(alpha = 0.45f),
                        shape = CircleShape,
                    )
                    .clickable(enabled = captureReady) {
                        val capture = imageCapture ?: return@clickable
                        captureInProgress = true
                        capture.targetRotation =
                            previewView.display?.rotation ?: Surface.ROTATION_0
                        val output = ImageCapture.OutputFileOptions.Builder(File(targetPath)).build()
                        capture.takePicture(
                            output,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(
                                    outputFileResults: ImageCapture.OutputFileResults,
                                ) {
                                    if (terminalDelivered) return
                                    terminalDelivered = true
                                    latestOnCaptured()
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    captureInProgress = false
                                    failOnce(exception)
                                }
                            },
                        )
                    },
            )
        }
    }
}
