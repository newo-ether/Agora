package com.newoether.agora.voice.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream

/** Which live video source is currently streaming (for UI state). */
enum class LiveVideoSource { NONE, CAMERA, SCREEN }

/**
 * Captures camera (CameraX) or device screen (MediaProjection) frames, throttled to ~1 fps,
 * downscaled and JPEG-encoded, and hands each frame as NO_WRAP base64 to [onFrame] (which forwards
 * it to the Live session as realtimeInput.video). Only one source runs at a time.
 *
 * Headless by design: the camera binds to [ProcessLifecycleOwner] so it works from the overlay
 * service (no Activity/Compose lifecycle). Screen capture owns a [MediaProjection] obtained from a
 * consent Activity and renders into an [ImageReader] via a [VirtualDisplay].
 */
class VideoStreamManager(
    private val appContext: Context,
    private val onFrame: (String) -> Unit,
) {
    @Volatile private var source: LiveVideoSource = LiveVideoSource.NONE
    @Volatile private var lastSentAt = 0L
    @Volatile private var encoding = false

    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var cameraFront = false
    // Self-owned lifecycle so CameraX can bind headless from a Service (no Activity/Compose owner).
    private var lifecycleRegistry: LifecycleRegistry? = null
    private var lifecycleOwner: LifecycleOwner? = null

    // Screen
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenThread: HandlerThread? = null
    private var screenHandler: Handler? = null

    val currentSource: LiveVideoSource get() = source

    // ── Camera ────────────────────────────────────────────────────────────────
    fun startCamera(front: Boolean) {
        stop()
        Log.i(TAG, "startCamera front=$front")
        cameraFront = front
        source = LiveVideoSource.CAMERA
        val main = ContextCompat.getMainExecutor(appContext)
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (source != LiveVideoSource.CAMERA) return@addListener
            val provider = runCatching { future.get() }.getOrNull() ?: run {
                Log.e(TAG, "camera provider unavailable"); return@addListener
            }
            cameraProvider = provider
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysisUseCase.setAnalyzer(main) { proxy -> onCameraFrame(proxy) }
            analysis = analysisUseCase
            val owner = object : LifecycleOwner {
                override val lifecycle: Lifecycle get() = lifecycleRegistry!!
            }
            val registry = LifecycleRegistry(owner).also { lifecycleRegistry = it }
            lifecycleOwner = owner
            registry.currentState = Lifecycle.State.STARTED
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, analysisUseCase)
            }.onFailure { Log.e(TAG, "camera bind failed", it) }
        }, main)
    }

    private fun onCameraFrame(proxy: ImageProxy) {
        try {
            if (!throttleOk()) return
            val bmp = proxy.toBitmap()
            val rotated = rotate(bmp, proxy.imageInfo.rotationDegrees)
            emit(rotated)
        } catch (t: Throwable) {
            Log.w(TAG, "camera frame error: ${t.message}")
        } finally {
            proxy.close()
        }
    }

    // ── Screen (MediaProjection) ────────────────────────────────────────────────
    fun startScreen(projection: MediaProjection) {
        stop()
        source = LiveVideoSource.SCREEN
        this.projection = projection
        val metrics = DisplayMetrics().also {
            (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
        }
        // Downscale the capture surface itself to keep frames small; preserve aspect.
        val scale = (TARGET_MAX.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels)).coerceAtMost(1f)
        val w = (metrics.widthPixels * scale).toInt().coerceAtLeast(16)
        val h = (metrics.heightPixels * scale).toInt().coerceAtLeast(16)
        Log.i(TAG, "startScreen ${w}x$h dpi=${metrics.densityDpi}")
        val thread = HandlerThread("live-screen").also { it.start(); screenThread = it }
        val handler = Handler(thread.looper).also { screenHandler = it }
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }, handler)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { imageReader = it }
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (!throttleOk()) return@setOnImageAvailableListener
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
                emit(cropped)
            } catch (t: Throwable) {
                Log.w(TAG, "screen frame error: ${t.message}")
            } finally {
                image.close()
            }
        }, handler)
        virtualDisplay = projection.createVirtualDisplay(
            "live-screen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    // ── Shared encode/throttle ───────────────────────────────────────────────────
    private fun throttleOk(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (encoding || now - lastSentAt < MIN_INTERVAL_MS) return false
        lastSentAt = now
        return true
    }

    private fun emit(bitmap: Bitmap) {
        encoding = true
        try {
            val scaled = downscale(bitmap)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            if (source != LiveVideoSource.NONE) {
                Log.i(TAG, "send $source frame ${scaled.width}x${scaled.height} b64=${b64.length}B")
                onFrame(b64)
            }
        } finally {
            encoding = false
        }
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= TARGET_MAX) return src
        val s = TARGET_MAX.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun stop() {
        source = LiveVideoSource.NONE
        runCatching { analysis?.clearAnalyzer() }
        runCatching { cameraProvider?.unbindAll() }
        runCatching { lifecycleRegistry?.currentState = Lifecycle.State.DESTROYED }
        analysis = null; cameraProvider = null; lifecycleRegistry = null; lifecycleOwner = null
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null; imageReader = null; projection = null
        runCatching { screenThread?.quitSafely() }
        screenThread = null; screenHandler = null
    }

    companion object {
        private const val TAG = "LiveVideoStream"
        private const val MIN_INTERVAL_MS = 1000L // ~1 fps per Live API guidance
        private const val TARGET_MAX = 768 // longest edge px
        private const val JPEG_QUALITY = 50
    }
}
