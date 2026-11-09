package com.newoether.agora.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/** Shared logging gate for provider code and the application forwarding facade. */
object CloudDebugLog {
    @Volatile
    var forceEnabled = false
    @Volatile
    private var enabled = true

    fun init(context: Context) {
        enabled = forceEnabled || (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val active: Boolean get() = forceEnabled || enabled

    fun d(tag: String, msg: String) { if (active) Log.d(tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable) {
        if (active) Log.d(tag, "$msg ${safeThrowableSummary(tr)}")
    }
    fun e(tag: String, msg: String) { if (active) Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) {
        if (active) Log.e(tag, "$msg ${safeThrowableSummary(tr)}")
    }
    fun w(tag: String, msg: String) { if (active) Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable) {
        if (active) Log.w(tag, "$msg ${safeThrowableSummary(tr)}")
    }

    /** Exclude uncontrolled throwable messages and cause chains from Logcat. */
    fun safeThrowableSummary(tr: Throwable): String = buildString {
        append("exception=")
        append(tr.javaClass.name)
        tr.stackTrace
            .asSequence()
            .take(MAX_SAFE_STACK_FRAMES)
            .forEach { frame ->
                append("\n\tat ")
                append(frame)
            }
    }

    private const val MAX_SAFE_STACK_FRAMES = 24
}
