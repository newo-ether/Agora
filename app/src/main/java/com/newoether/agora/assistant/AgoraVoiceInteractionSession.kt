package com.newoether.agora.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * The assistant session, triggered by the assist gesture / power-button handoff
 * when Agora holds the ASSISTANT role.
 *
 * v1 (stable core) behaviour:
 *  - On assist, capture the foreground app's visible text (AssistStructure) as
 *    screen context, exactly like AIOPE's session.
 *  - Hand the captured context to [AssistInvocation], which launches the
 *    assistant service to run a turn through Agora's engine and surface the
 *    result.
 *
 * It does NOT read the screen outside an invoked session, and does not inject
 * input into other apps (that would be the later, opt-in AccessibilityService).
 */
class AgoraVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context) {

    private var capturedScreen: String = ""
    private var handleAssistDone = false
    private var shown = false
    private var launched = false

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        try {
            val text = StringBuilder()
            state.assistStructure?.let { extractText(it, text) }
            state.assistContent?.let { c -> appendContent(c, text) }
            val screen = text.toString().trim().take(MAX_CHARS)
            // onHandleAssist can fire multiple times (partial structures); keep
            // the richest capture seen.
            if (screen.length >= capturedScreen.length) capturedScreen = screen
            handleAssistDone = true
            Log.i(TAG, "onHandleAssist: capturedScreenChars=${capturedScreen.length}")
            maybeLaunch()
        } catch (e: Exception) {
            Log.e(TAG, "onHandleAssist failed: ${e.message}", e)
            handleAssistDone = true
            maybeLaunch()
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "onShow (showFlags=$showFlags)")
        shown = true
        maybeLaunch()
    }

    /**
     * The assist callbacks arrive in an unspecified order: [onHandleAssist]
     * (which populates the screen context) may run before or after [onShow].
     * We launch the turn exactly once, only after the session is shown AND the
     * capture has completed, so screen context isn't lost to a race.
     *
     * If the platform never delivers [onHandleAssist] (some devices/surfaces
     * suppress it, e.g. FLAG_SECURE windows), we still launch after [onShow]
     * via a short timeout, with whatever context we have (possibly empty).
     */
    private fun maybeLaunch() {
        if (launched || !shown) return
        if (handleAssistDone) {
            doLaunch()
        } else {
            // Give onHandleAssist a brief window to arrive after onShow.
            mainHandler.postDelayed({
                if (!launched) doLaunch()
            }, HANDLE_ASSIST_GRACE_MS)
        }
    }

    private fun doLaunch() {
        if (launched) return
        launched = true
        try {
            Log.i(TAG, "launch turn: screenChars=${capturedScreen.length}")
            AssistInvocation.launch(context, capturedScreen)
        } catch (e: Exception) {
            Log.e(TAG, "launch failed: ${e.message}", e)
        }
        // We don't draw a session UI in v1; hand off and dismiss.
        hide()
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun appendContent(c: AssistContent, out: StringBuilder) {
        c.structuredData?.let { if (it.isNotBlank()) out.append('\n').append(it) }
        c.webUri?.let { out.append("\nURL: ").append(it) }
    }

    private fun extractText(structure: AssistStructure, out: StringBuilder) {
        val n = structure.windowNodeCount
        for (i in 0 until n) {
            walk(structure.getWindowNodeAt(i).rootViewNode, out)
        }
    }

    private fun walk(node: AssistStructure.ViewNode?, out: StringBuilder) {
        node ?: return
        // Skip obvious secret fields (password inputs) from the captured context.
        val isPassword = node.inputType and INPUT_PASSWORD_MASK != 0 &&
            node.className?.contains("EditText") == true
        if (!isPassword) {
            node.text?.let { if (it.isNotBlank()) out.append(it).append(' ') }
            node.contentDescription?.let { if (it.isNotBlank()) out.append(it).append(' ') }
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), out)
    }

    private companion object {
        const val TAG = "AgoraAssist"
        const val MAX_CHARS = 4000
        const val HANDLE_ASSIST_GRACE_MS = 400L
        // TYPE_TEXT_VARIATION_PASSWORD / VISIBLE_PASSWORD / WEB_PASSWORD bits.
        const val INPUT_PASSWORD_MASK = 0x000000f0
    }
}
