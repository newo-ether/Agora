package com.newoether.agora.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
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

    private val privileged: Context by lazy {
        val base = context ?: systemContext()
        // DisplayManager checks the caller's package against its uid; the shell uid owns com.android.shell.
        if (Process.myUid() == Process.SHELL_UID) {
            base.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        } else base
    }

    fun open(request: Bundle): Int = synchronized(lock) {
        close()
        request.classLoader = Surface::class.java.classLoader
        @Suppress("DEPRECATION")
        val surface = request.getParcelable<Surface>(KEY_SURFACE) ?: throw IllegalArgumentException("surface")
        val width = request.getInt(KEY_WIDTH)
        val height = request.getInt(KEY_HEIGHT)
        val dpi = request.getInt(KEY_DPI)
        require(width in 200..4096 && height in 200..4096 && dpi in 72..1000) { "bad_display_size" }
        // Every flag must resolve; never fall back to a display that can steal the user's focus.
        val flags = FLAG_NAMES.fold(0) { bits, name ->
            bits or DisplayManager::class.java.getDeclaredField("VIRTUAL_DISPLAY_FLAG_$name").getInt(null)
        }
        val created = privileged.getSystemService(DisplayManager::class.java)
            .createVirtualDisplay("agora-background", width, height, dpi, surface, flags)
            ?: throw IllegalStateException("display_unsupported")
        display = created
        val id = created.display.displayId
        // Keep a keyboard raised on the background display off the phone's own screen.
        runCatching { setImePolicyHide(id) }
        id
    }

    fun close() = synchronized(lock) {
        display?.let { runCatching { it.release() } }
        display = null
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
    }
}
