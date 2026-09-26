package com.newoether.agora.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySafeLoggingTest {
    @After
    fun restoreLogging() {
        DebugLog.forceEnabled = false
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply {
            flags = ApplicationInfo.FLAG_DEBUGGABLE
        }
        DebugLog.init(context)
        unmockkStatic(Log::class)
    }

    @Test
    fun `send diagnostics remain available in release without enabling ordinary logs`() {
        mockkStatic(Log::class)
        val lines = mutableListOf<String>()
        every { Log.i("SendDiagnostics", any()) } answers {
            lines.add(secondArg())
            0
        }
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.init(context)

        DebugLog.d("ordinary", "TOP_SECRET_MESSAGE")
        DebugLog.e("ordinary", "TOP_SECRET_MESSAGE", IOException("TOP_SECRET_EXCEPTION"))
        DebugLog.w("ordinary", "TOP_SECRET_MESSAGE")
        DebugLog.sendStage("run-id", "prepare", "await-provider", 10)
        DebugLog.sendStage("run-id", "composer", "finished", 15, "send", 5)

        assertEquals(
            listOf(
                "run=run-id component=prepare stage=await-provider elapsedMs=10",
                "run=run-id component=composer stage=finished previous=send previousMs=5 elapsedMs=15",
            ),
            lines,
        )
        verify(exactly = 0) { Log.d(any(), any<String>()) }
        verify(exactly = 0) { Log.e(any(), any<String>()) }
        verify(exactly = 0) { Log.w(any(), any<String>()) }
        assertFalse(lines.any { "TOP_SECRET" in it })
    }

    @Test
    fun `recovery stage diagnostics remain available in release with safe fields only`() {
        mockkStatic(Log::class)
        val lines = mutableListOf<String>()
        every { Log.i("RecoveryDiagnostics", any()) } answers {
            lines.add(secondArg())
            0
        }
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
        DebugLog.recoveryStage("conversation-id", "entered", 0)
        DebugLog.recoveryStage("conversation-id", "returned", 34, rows = 3)
        assertEquals(
            listOf(
                "conversation=conversation-id stage=entered elapsedMs=0",
                "conversation=conversation-id stage=returned rows=3 elapsedMs=34",
            ),
            lines,
        )
        assertFalse(lines.any { "TOP_SECRET" in it })
    }

    @Test
    fun `send stage logging failure does not escape or log its exception`() {
        mockkStatic(Log::class)
        every { Log.i("SendDiagnostics", any()) } throws IOException("TOP_SECRET_EXCEPTION")

        DebugLog.sendStage("run-id", "input", "graph-committed", 12)

        verify(exactly = 1) { Log.i("SendDiagnostics", any()) }
        verify(exactly = 0) { Log.e(any(), any<String>()) }
        verify(exactly = 0) { Log.w(any(), any<String>()) }
    }

    @Test
    fun `throwable summary keeps diagnostic type and frames without uncontrolled messages`() {
        val failure = IOException("TOP_SECRET_THROWABLE_MESSAGE").apply {
            initCause(IllegalStateException("TOP_SECRET_CAUSE_MESSAGE"))
            stackTrace = arrayOf(
                StackTraceElement("com.newoether.agora.SafeComponent", "run", "SafeComponent.kt", 42),
            )
        }

        val summary = DebugLog.safeThrowableSummary(failure)

        assertTrue(summary.contains("exception=java.io.IOException"))
        assertTrue(summary.contains("SafeComponent.kt:42"))
        assertFalse(summary.contains("TOP_SECRET_THROWABLE_MESSAGE"))
        assertFalse(summary.contains("TOP_SECRET_CAUSE_MESSAGE"))
    }

    @Test
    fun `throwable summary has a bounded stack frame count`() {
        val failure = IOException("omitted").apply {
            stackTrace = Array(100) { index ->
                StackTraceElement("SafeComponent$index", "run", "SafeComponent.kt", index)
            }
        }

        val summary = DebugLog.safeThrowableSummary(failure)

        assertTrue(summary.lineSequence().count { it.startsWith("\tat ") } <= 24)
        assertFalse(summary.contains("SafeComponent99"))
    }
}
