package com.newoether.agora.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped owner of a single warm [SherpaSttEngine].
 *
 * Unlike AIOPE — which builds a brand-new [com.k2fsa.sherpa.onnx.OnlineRecognizer] on every
 * dictation and releases it immediately after (so each session pays the full model-load cost) —
 * Agora preloads the recognizer once at startup and keeps it resident. Capture surfaces
 * (VoiceInputManager, AgoraRecognitionService) borrow the same warm engine via [acquire], run a
 * session, then call [release]; the recognizer stays loaded for the next session.
 *
 * Concurrency: sherpa's streaming recognizer serves one capture stream at a time, so [acquire] is
 * single-owner — a second caller while one session is active is refused. After an idle period with
 * no sessions the native recognizer is freed to reclaim memory and re-preloaded on next use.
 */
object SttEngineHolder {

    private const val TAG = "SttEngineHolder"
    private const val IDLE_RELEASE_MS = 5 * 60_000L // free native memory after 5 min idle

    private val lock = Any()
    private var engine: SherpaSttEngine? = null
    private val inUse = AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleReleaseRunnable = Runnable { releaseIfIdle() }

    /**
     * Ensure the recognizer is loaded and resident. Call off the main thread (it does blocking
     * native model loading). No-op if the model isn't installed. Idempotent.
     */
    fun preload(context: Context): Boolean {
        val app = context.applicationContext
        if (!SherpaSttBootstrap.isInstalled(app)) {
            Log.d(TAG, "preload skipped: model not installed")
            return false
        }
        synchronized(lock) {
            val e = engine ?: buildEngine(app).also { engine = it }
            val warm = e.preload()
            Log.d(TAG, "preload warm=$warm")
            return warm
        }
    }

    /**
     * Borrow the warm engine for one capture session. Returns null if the model isn't installed,
     * the recognizer can't load, or another session already holds it. On success a fresh stream is
     * ready; the caller MUST pair this with [release] when the session ends.
     */
    fun acquire(context: Context): SherpaSttEngine? {
        val app = context.applicationContext
        if (!SherpaSttBootstrap.isInstalled(app)) return null
        if (!inUse.compareAndSet(false, true)) {
            Log.w(TAG, "acquire refused: a session is already active")
            return null
        }
        mainHandler.removeCallbacks(idleReleaseRunnable)
        synchronized(lock) {
            val e = engine ?: buildEngine(app).also { engine = it }
            if (!e.beginSession()) {
                inUse.set(false)
                return null
            }
            return e
        }
    }

    /**
     * Return the engine after a session. The stream is released but the recognizer stays warm.
     * Schedules an idle-release so memory is reclaimed if STT goes unused.
     */
    fun release() {
        synchronized(lock) { engine?.endSession() }
        inUse.set(false)
        mainHandler.removeCallbacks(idleReleaseRunnable)
        mainHandler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_MS)
    }

    /** True if the recognizer is currently resident. */
    fun isWarm(): Boolean = synchronized(lock) { engine?.isWarm() == true }

    private fun releaseIfIdle() {
        synchronized(lock) {
            if (inUse.get()) return
            engine?.let { e -> runCatching { e.close() } }
            engine = null
            Log.d(TAG, "idle: recognizer released to reclaim memory")
        }
    }

    private fun buildEngine(context: Context): SherpaSttEngine = SherpaSttEngine(
        encoder = SherpaSttBootstrap.encoderFile(context),
        decoder = SherpaSttBootstrap.decoderFile(context),
        joiner = SherpaSttBootstrap.joinerFile(context),
        tokens = SherpaSttBootstrap.tokensFile(context),
    )
}
