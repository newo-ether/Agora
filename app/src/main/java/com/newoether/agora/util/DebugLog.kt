package com.newoether.agora.util

import android.content.Context

object DebugLog {
    var forceEnabled: Boolean
        get() = CloudDebugLog.forceEnabled
        set(value) { CloudDebugLog.forceEnabled = value }

    fun init(context: Context) = CloudDebugLog.init(context)

    // Release diagnostics accept only generated run IDs, fixed stage labels and timings.
    internal fun sendStage(
        runId: String,
        component: String,
        stage: String,
        elapsedMs: Long,
        previous: String? = null,
        previousMs: Long? = null,
    ) {
        runCatching {
            val timing = if (previous != null && previousMs != null) {
                "previous=$previous previousMs=$previousMs "
            } else {
                ""
            }
            android.util.Log.i(
                "SendDiagnostics",
                "run=$runId component=$component stage=$stage ${timing}elapsedMs=$elapsedMs",
            )
        }
    }

    // Recovery diagnostics accept only the generated conversation ID, fixed stage labels and timings.
    internal fun recoveryStage(
        conversationId: String,
        stage: String,
        elapsedMs: Long,
        rows: Int? = null,
    ) {
        com.newoether.agora.data.local.DatabaseRecoveryDiagnostics.recoveryStage(
            conversationId, stage, elapsedMs, rows,
        )
    }

    // Phone release diagnostics contain fixed reason/mode labels and in-memory counters only.
    internal fun phoneRelease(reason: String, epoch: Long, heldMs: Long, mode: String) {
        runCatching {
            android.util.Log.i("AgoraPhone", "release reason=$reason epoch=$epoch held_ms=$heldMs mode=$mode")
        }
    }

    fun d(tag: String, msg: String) = CloudDebugLog.d(tag, msg)
    fun d(tag: String, msg: String, tr: Throwable) = CloudDebugLog.d(tag, msg, tr)
    fun e(tag: String, msg: String) = CloudDebugLog.e(tag, msg)
    fun e(tag: String, msg: String, tr: Throwable) = CloudDebugLog.e(tag, msg, tr)
    fun w(tag: String, msg: String) = CloudDebugLog.w(tag, msg)
    fun w(tag: String, msg: String, tr: Throwable) = CloudDebugLog.w(tag, msg, tr)

    internal fun safeThrowableSummary(tr: Throwable): String = CloudDebugLog.safeThrowableSummary(tr)
}
