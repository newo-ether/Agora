package com.newoether.agora.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-side half of the background screen for one lease. The app owns the ImageReader and hands its
 * Surface to the privileged process, which owns the display. Frames are converted at most
 * [FRAME_INTERVAL_MS] apart; the virtual display only produces frames when its content changes.
 */
internal class PhoneVirtualScreen(
    context: Context,
    private val privileged: PhonePrivilegedConnection,
    spec: PhoneBackgroundSpec,
    /** Runs after every (re)open; must return quickly, since the display lock is held. */
    private val opened: suspend (Int) -> Unit = {},
) {
    val width: Int = spec.width
    val height: Int = spec.height
    private val dpi: Int = spec.dpi
    private val thread = HandlerThread("AgoraBackgroundScreen").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Mutex()
    private var reader: ImageReader? = null
    private var scheduled = false
    private var lastFrameAt = 0L
    @Volatile private var released = false
    @Volatile var displayId: Int = Display.INVALID_DISPLAY
        private set
    private val _frame = MutableStateFlow<Bitmap?>(null)
    /** Latest frame for the preview. Bitmaps are never recycled here, since a view may still draw one. */
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()
    private val _frames = MutableStateFlow(0L)
    /** Counts converted frames, so a capture can wait for one drawn after an overlay change. */
    val frames: StateFlow<Long> = _frames.asStateFlow()
    /** Shorter while the full-screen view is open. */
    @Volatile var frameIntervalMs: Long = FRAME_INTERVAL_MS
    private val displays = context.getSystemService(DisplayManager::class.java)
    // The CPU must keep running for the display to draw while the phone's own screen is off.
    private val wake = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Agora:backgroundScreen").apply { setReferenceCounted(false) }

    /** Returns the live display id, creating a fresh empty display when none exists. */
    suspend fun ensureOpen(): Int = lock.withLock {
        if (released) throw PhoneFailure("not_claimed")
        val current = displayId
        if (current != Display.INVALID_DISPLAY && displays.getDisplay(current) != null) {
            wake.acquire(WAKE_MS)
            return@withLock current
        }
        openLocked()
    }

    /** Destroys every task on the display and starts an empty one; the background screen's Home. */
    suspend fun reset(): Int = lock.withLock {
        if (released) throw PhoneFailure("not_claimed")
        // The host releases its previous display inside open. A separate one-way close could be
        // delivered after that open and destroy the new display.
        openLocked()
    }

    private suspend fun openLocked(): Int {
        closeReader()
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        next.setOnImageAvailableListener({ schedule() }, handler)
        reader = next
        _frame.value = null
        val id = try {
            privileged.openDisplay(Bundle().apply {
                putParcelable(PhoneVirtualDisplayHost.KEY_SURFACE, next.surface)
                putInt(PhoneVirtualDisplayHost.KEY_WIDTH, width)
                putInt(PhoneVirtualDisplayHost.KEY_HEIGHT, height)
                putInt(PhoneVirtualDisplayHost.KEY_DPI, dpi)
            })
        } catch (error: Exception) {
            closeReader()
            throw error
        }
        displayId = id
        wake.acquire(WAKE_MS)
        // release() may have run during the binder call; it must not leave the lock held.
        if (released) runCatching { wake.release() }
        runCatching { opened(id) }
        return id
    }

    /** Waits briefly for a frame newer than [after]; an unchanged display produces none. */
    suspend fun awaitFrameAfter(after: Long, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { frames.first { it > after } }
    }

    /** A copy of the latest frame; an empty display that never drew yields black. */
    suspend fun capture(): Bitmap {
        ensureOpen()
        val latest = withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) { frame.filterNotNull().first() }
        return latest?.copy(Bitmap.Config.ARGB_8888, false)
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /** Main-safe and idempotent: called from lease cleanup before the binding goes away. */
    fun release() {
        if (released) return
        released = true
        displayId = Display.INVALID_DISPLAY
        runCatching { if (wake.isHeld) wake.release() }
        privileged.closeDisplay()
        handler.post {
            reader?.close()
            reader = null
            _frame.value = null
        }
        thread.quitSafely()
    }

    private fun closeReader() {
        displayId = Display.INVALID_DISPLAY
        val previous = reader ?: return
        reader = null
        handler.post { previous.close() }
    }

    /** Handler thread only. An unacquired image waits in the queue until the delayed conversion. */
    private fun schedule() {
        if (scheduled) return
        scheduled = true
        val wait = (lastFrameAt + frameIntervalMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
        handler.postDelayed(::convert, wait)
    }

    private fun convert() {
        scheduled = false
        val image = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return
        try {
            lastFrameAt = SystemClock.uptimeMillis()
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            _frame.value = if (rowPixels == image.width) padded else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            }
            _frames.value += 1
        } finally { image.close() }
    }

    companion object {
        const val FRAME_INTERVAL_MS = 66L
        const val LIVE_FRAME_INTERVAL_MS = 33L
        // Re-armed on every use; the lease's idle limit ends a forgotten screen well before this.
        private const val WAKE_MS = 30 * 60 * 1000L
        private const val FIRST_FRAME_TIMEOUT_MS = 1500L
    }
}
