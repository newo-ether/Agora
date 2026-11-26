package com.newoether.agora.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface

/**
 * Runs inside the Shizuku UserService (shell or root uid) and owns at most one background display.
 * The app process supplies the Surface, so frames reach the app without crossing this process.
 *
 * Approach after dsh-Go's PrivilegedDisplayCore (MIT, CikeSeven): a trusted public display with its
 * own focus, so input and launches on it never move focus from the phone's own screen.
 */
internal class PhoneVirtualDisplayHost(private val context: Context?) {
    private val lock = Any()
    private var display: VirtualDisplay? = null
    private val tree by lazy { PhoneDisplayTree(privileged) }

    private val privileged: Context by lazy {
        val base = context ?: systemContext()
        // DisplayManager checks the caller's package against its uid; the shell uid owns com.android.shell.
        if (Process.myUid() == Process.SHELL_UID) {
            base.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        } else base
    }

    fun open(request: Bundle): Int = synchronized(lock) {
        releaseDisplay()
        request.classLoader = Surface::class.java.classLoader
        @Suppress("DEPRECATION")
        val surface = request.getParcelable<Surface>(KEY_SURFACE) ?: throw IllegalArgumentException("surface")
        val width = request.getInt(KEY_WIDTH)
        val height = request.getInt(KEY_HEIGHT)
        val dpi = request.getInt(KEY_DPI)
        require(width in 200..4096 && height in 200..4096 && dpi in 72..1000) { "bad_display_size" }
        // Every required flag must resolve; never fall back to a display that can steal the user's focus.
        val required = FLAG_NAMES.fold(0) { bits, name -> bits or flag(name) }
        // Optional: keep the display awake and unlocked while the phone itself sleeps (after scrcpy).
        val optional = if (Build.VERSION.SDK_INT >= 33) OPTIONAL_FLAG_NAMES.fold(0) { bits, name ->
            bits or (runCatching { flag(name) }.getOrDefault(0))
        } else 0
        val manager = privileged.getSystemService(DisplayManager::class.java)
        fun create(flags: Int) = manager.createVirtualDisplay("agora-background", width, height, dpi, surface, flags)
        val created = (if (optional == 0) create(required) else {
            val extended = try { create(required or optional) } catch (denied: SecurityException) { null }
            extended ?: create(required)
        }) ?: throw IllegalStateException("display_unsupported")
        display = created
        val id = created.display.displayId
        // Keep a keyboard raised on the background display off the phone's own screen.
        runCatching { setImePolicyHide(id) }
        id
    }

    private fun displayId(): Int = synchronized(lock) {
        display?.display?.displayId ?: throw IllegalStateException("display_missing")
    }

    fun foreground(): String = tree.foreground(displayId()).orEmpty()
    fun nodes(): String = tree.nodes(displayId())
    fun setText(text: String): Boolean = tree.setText(displayId(), text)

    /** One finger on the background display; the live full-screen view forwards the user's touches. */
    fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        val id = displayId()
        val properties = MotionEvent.PointerProperties().apply { this.id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties),
            arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            setDisplayId.invoke(event, id)
            injector(event)
        } finally { event.recycle() }
    }

    private val setDisplayId by lazy {
        MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    private val injector: (InputEvent) -> Unit by lazy {
        val target = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        val inject = target.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        // INJECT_INPUT_EVENT_MODE_ASYNC: never block the binder thread on the target app.
        val call: (InputEvent) -> Unit = { event -> inject.invoke(target, event, 0) }
        call
    }

    private fun releaseDisplay() {
        display?.let { runCatching { it.release() } }
        display = null
    }

    /** Ends the background screen; also frees the single UiAutomation slot for other tools. */
    fun close() = synchronized(lock) {
        releaseDisplay()
        runCatching { tree.close() }
    }

    private fun setImePolicyHide(displayId: Int) {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "window") as IBinder
        val wm = Class.forName("android.view.IWindowManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        // WindowManager.DISPLAY_IME_POLICY_HIDE
        wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(wm, displayId, 2)
    }

    private fun flag(name: String): Int =
        DisplayManager::class.java.getDeclaredField("VIRTUAL_DISPLAY_FLAG_$name").getInt(null)

    private fun systemContext(): Context {
        if (Looper.myLooper() == null) Looper.prepare()
        val thread = Class.forName("android.app.ActivityThread")
        val instance = thread.getMethod("systemMain").invoke(null)
        return thread.getMethod("getSystemContext").invoke(instance) as Context
    }

    companion object {
        const val KEY_SURFACE = "surface"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_DPI = "dpi"
        private val FLAG_NAMES = listOf(
            "PUBLIC", "OWN_CONTENT_ONLY", "SUPPORTS_TOUCH", "DESTROY_CONTENT_ON_REMOVAL",
            "TRUSTED", "OWN_FOCUS", "STEAL_TOP_FOCUS_DISABLED",
        )
        private val OPTIONAL_FLAG_NAMES = listOf("OWN_DISPLAY_GROUP", "ALWAYS_UNLOCKED", "TOUCH_FEEDBACK_DISABLED")
    }
}
