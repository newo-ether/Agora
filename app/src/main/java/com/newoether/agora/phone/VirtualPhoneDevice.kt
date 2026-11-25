package com.newoether.agora.phone

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.delay

/**
 * Shizuku device that drives [screen] instead of the phone's own display. Input and launches carry
 * the display id, so nothing reaches the screen the user is holding. Reads come from the
 * accessibility service's per-display windows.
 */
internal class VirtualPhoneDevice(
    private val context: Context,
    private val screen: PhoneVirtualScreen,
    private val privileged: PhonePrivilegedConnection,
) : PhoneDevice {
    private val main = AndroidPhoneDevice(context, MODE, privileged)
    private fun accessibility() = PhoneAccessibilityService.instance
        ?: throw PhoneFailure("permission_lost:accessibility_unavailable")
    private suspend fun command(value: String) = privileged.command(MODE, value)

    // An empty display shows nothing to protect; Agora's own package is always admitted.
    override suspend fun foreground(): String =
        accessibility().foregroundPackage(screen.ensureOpen()) ?: context.packageName
    override suspend fun screenshot(): Bitmap = screen.capture()
    override suspend fun nodes(): List<PhoneNode> = accessibility().snapshot(screen.ensureOpen())
    override suspend fun tap(x: Int, y: Int, durationMs: Long) {
        val id = screen.ensureOpen()
        if (durationMs <= 100) command("input -d $id tap $x $y")
        else command("input -d $id swipe $x $y $x $y $durationMs")
    }
    override suspend fun swipe(x: Int, y: Int, endX: Int, endY: Int, durationMs: Long) {
        command("input -d ${screen.ensureOpen()} swipe $x $y $endX $endY $durationMs")
    }
    override suspend fun type(text: String) {
        val id = screen.ensureOpen()
        if (accessibility().setText(text, id)) return
        // No accessibility focus: plain ASCII can still go through the display's key input.
        if (phoneInputPath(MODE, text) != "input_text") throw PhoneFailure("no_focused_field")
        command("input -d $id text ${phoneShellQuote(text.replace(" ", "%s"))}")
    }
    override suspend fun key(key: String) {
        when (key) {
            "back" -> command("input -d ${screen.ensureOpen()} keyevent 4")
            // The background display has no launcher; Home clears it to an empty screen.
            "home" -> screen.reset()
            "recents" -> throw PhoneFailure("key_unsupported_in_background", "Use phone_app_launch to switch apps.")
            else -> throw PhoneFailure("invalid_key")
        }
    }
    override suspend fun launch(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: throw PhoneFailure("app_unavailable")
        val component = intent.component?.flattenToString() ?: throw PhoneFailure("app_unavailable")
        val id = screen.ensureOpen()
        // NEW_TASK | MULTIPLE_TASK: a task the user already has open stays on their screen.
        command("am start --user current --display $id -f 0x18000000 -n ${phoneShellQuote(component)} " +
            "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
        repeat(LAUNCH_POLLS) {
            if (accessibility().foregroundPackage(id) == packageName) return
            delay(LAUNCH_POLL_MS)
        }
        throw PhoneFailure("background_launch_unconfirmed",
            "$packageName did not appear on the background screen; it may refuse secondary displays.")
    }
    override suspend fun apps(): List<PhoneApp> = main.apps()
    override fun dimensions(): Triple<Int, Int, Int> = Triple(screen.width, screen.height, 0)

    private companion object {
        const val MODE = "shizuku"
        const val LAUNCH_POLLS = 20
        const val LAUNCH_POLL_MS = 150L
    }
}
