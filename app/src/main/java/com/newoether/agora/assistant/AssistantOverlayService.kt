package com.newoether.agora.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.stt.SherpaSttBootstrap
import com.newoether.agora.stt.VoiceInputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interactive floating assistant box, drawn over other apps via SYSTEM_ALERT_WINDOW.
 *
 * Collapsed, it is a small draggable bubble. Tapping expands it into a panel that:
 *  - shows Agora's responses (scrollable),
 *  - takes typed input (keyboard) AND on-device STT dictation (mic button),
 *  - sends the prompt to the same engine the rest of the assistant uses
 *    ([AssistantController.ask]) and prints the reply in the box.
 *
 * Agora has no live/always-on speech yet, so input here is explicit: type or tap
 * the mic to dictate, then Send. The box is a full Agora turn on its own foreground
 * service, so the engine's foreground-importance gate is satisfied.
 *
 * It does not read or inject input into other apps — it is Agora's own window.
 * Only started when the user enables the feature AND granted draw-over-other-apps.
 */
class AssistantOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var bubble: View? = null
    private var panel: View? = null
    private var responseView: TextView? = null
    private var inputField: EditText? = null
    private var micButton: ImageButton? = null
    private var sendButton: Button? = null

    private var expanded = false
    private var listening = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val voice by lazy { VoiceInputManager(applicationContext) }
    private var sttBasePrefix = ""
    private var sttCommitted = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        if (canDraw(this)) addOverlay() else stopSelf()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addOverlay() {
        if (root != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val container = FrameLayout(this)
        container.addView(buildBubble())
        container.addView(buildPanel().also { it.visibility = View.GONE })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Collapsed: not focusable (so touches elsewhere pass through). When expanded we
            // clear NOT_FOCUSABLE so the EditText can receive the soft keyboard.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }
        params = lp
        root = container
        runCatching { wm.addView(container, lp) }
    }

    private fun buildBubble(): View {
        val size = dp(56)
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
        val iv = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = bg
            setPadding(size / 4, size / 4, size / 4, size / 4)
            alpha = 0.95f
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        attachDragAndTap(iv, onLongPress = { dismiss() }) { expand() }
        bubble = iv
        return iv
    }

    private fun buildPanel(): View {
        val panelWidth = minOf(dp(320), (resources.displayMetrics.widthPixels * 0.9f).toInt())
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(PANEL_BG)
            }
            layoutParams = FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Header: title + drag handle + close.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = getString(R.string.settings_assistant)
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val collapseBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            background = null
            setColorFilter(Color.WHITE)
            contentDescription = getString(R.string.collapse)
            setOnClickListener { collapse() }
        }
        val dismissBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setColorFilter(Color.WHITE)
            contentDescription = getString(R.string.assistant_overlay_dismiss)
            setOnClickListener { dismiss() }
        }
        attachDrag(header) // drag the panel by its header
        header.addView(title)
        header.addView(collapseBtn)
        header.addView(dismissBtn)
        col.addView(header)

        // Response area (scrollable).
        responseView = TextView(this).apply {
            text = getString(R.string.assistant_overlay_prompt_hint)
            setTextColor(0xFFE0E0E0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            movementMethod = ScrollingMovementMethod()
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160),
            )
        }
        col.addView(responseView)

        // Input row: mic + text field + send.
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        micButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = null
            setColorFilter(Color.WHITE)
            setOnClickListener { toggleStt() }
        }
        inputField = EditText(this).apply {
            hint = getString(R.string.ask_agora)
            setHintTextColor(0xFF9E9E9E.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 4
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF2A2A2A.toInt())
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(4); marginEnd = dp(4) }
        }
        sendButton = Button(this).apply {
            text = getString(R.string.send)
            setOnClickListener { send() }
        }
        inputRow.addView(micButton)
        inputRow.addView(inputField)
        inputRow.addView(sendButton)
        col.addView(inputRow)

        panel = col
        return col
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        bubble?.visibility = View.GONE
        panel?.visibility = View.VISIBLE
        // Make the window focusable so the soft keyboard works for the EditText.
        params?.let { lp ->
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            runCatching { windowManager?.updateViewLayout(root, lp) }
        }
        inputField?.requestFocus()
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        if (listening) voice.stop()
        panel?.visibility = View.GONE
        bubble?.visibility = View.VISIBLE
        params?.let { lp ->
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { windowManager?.updateViewLayout(root, lp) }
        }
    }

    /**
     * Fully dismiss the overlay: clear the user's enable preference (so Settings reflects it and
     * it does not reappear) and stop the service, which removes the window. Reachable via the
     * panel's dismiss (×) button or a long-press on the collapsed bubble.
     */
    private fun dismiss() {
        runCatching {
            getSharedPreferences(ASSISTANT_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OVERLAY_ENABLED, false).apply()
        }
        stopSelf()
    }

    private fun toggleStt() {
        if (listening) { voice.stop(); return }
        if (!SherpaSttBootstrap.isInstalled(this)) {
            responseView?.text = getString(R.string.stt_model_needed)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // The overlay can't request runtime permission; point the user to the app.
            responseView?.text = getString(R.string.assistant_voice_needs_mic)
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        sttBasePrefix = inputField?.text?.toString()?.trimEnd().orEmpty()
        sttCommitted = ""
        listening = true
        micButton?.setColorFilter(0xFFEF5350.toInt()) // red while listening
        voice.start(
            onPartial = { partial -> applyStt((sttCommitted + " " + partial).trim()) },
            onFinal = { finalText ->
                sttCommitted = (sttCommitted + " " + finalText).trim(); applyStt(sttCommitted)
            },
            onError = { msg -> responseView?.text = msg },
            onEnd = { listening = false; micButton?.setColorFilter(Color.WHITE) },
        )
    }

    private fun applyStt(current: String) {
        val joined = buildString {
            append(sttBasePrefix)
            if (sttBasePrefix.isNotEmpty() && !sttBasePrefix.endsWith(" ")) append(' ')
            append(current)
        }
        inputField?.setText(joined)
        inputField?.setSelection(joined.length)
    }

    private fun send() {
        val text = inputField?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (listening) voice.stop()
        inputField?.setText("")
        responseView?.text = getString(R.string.assistant_overlay_thinking)
        sendButton?.isEnabled = false
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                try {
                    AssistantController.from(this@AssistantOverlayService)
                        .ask(screenContext = "", userIntent = text)
                } catch (e: Exception) {
                    Log.e(TAG, "overlay turn failed: ${e.message}", e)
                    "Agora error: ${e.message}"
                }
            }
            responseView?.text = reply
            sendButton?.isEnabled = true
        }
    }

    /**
     * Drag-to-move on [handle]; a tap (no significant movement) runs [onTap]; a press held past
     * the long-press timeout without moving runs [onLongPress]. Used so the collapsed bubble can
     * be tapped to open, dragged to reposition, or long-pressed to dismiss the overlay.
     */
    private fun attachDragAndTap(handle: View, onLongPress: (() -> Unit)? = null, onTap: () -> Unit) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        var longFired = false
        val longPress = Runnable { if (!moved) { longFired = true; onLongPress?.invoke() } }
        handle.setOnTouchListener { v, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; longFired = false
                    if (onLongPress != null) v.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {
                        moved = true; v.removeCallbacks(longPress)
                    }
                    lp.x = startX + dx; lp.y = startY + dy
                    runCatching { windowManager?.updateViewLayout(root, lp) }; true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!moved && !longFired) onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); true }
                else -> false
            }
        }
    }

    /** Drag-to-move only (no tap action), for the panel header. */
    private fun attachDrag(handle: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                    runCatching { windowManager?.updateViewLayout(root, lp) }; true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        if (listening) runCatching { voice.stop() }
        scope.cancel()
        root?.let { r -> runCatching { windowManager?.removeView(r) } }
        root = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Agora Assistant Button", NotificationManager.IMPORTANCE_MIN,
            ).apply { setShowBadge(false); setSound(null, null) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b
            .setContentTitle("Agora assistant button active")
            .setContentText("Tap the floating button to open Agora")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val TAG = "AgoraAssistOverlay"
        private const val CHANNEL_ID = "agora_assistant_overlay"
        private const val NOTIFICATION_ID = 73
        private const val LONG_PRESS_MS = 500L
        // Must match SettingsAssistantPage's PREFS / KEY_OVERLAY_ENABLED so a dismiss from the
        // overlay turns the Settings toggle off and prevents the bubble from reappearing.
        private const val ASSISTANT_PREFS = "assistant"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private val ACCENT = 0xFF1E88E5.toInt()
        private val PANEL_BG = 0xF21C1C1C.toInt()

        fun canDraw(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val i = Intent(context, AssistantOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantOverlayService::class.java))
        }
    }
}
