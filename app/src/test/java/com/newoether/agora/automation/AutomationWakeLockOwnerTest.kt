package com.newoether.agora.automation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationWakeLockOwnerTest {
    @Test
    fun disabledExecutionNeverAcquiresLease() = runTest {
        var acquired = false
        val owner = AutomationWakeLockOwner(
            AutomationWakeLockLeaseFactory {
                acquired = true
                AutoCloseable {}
            },
        )

        val result = owner.whileHeld(enabled = false) { "done" }

        assertEquals("done", result)
        assertFalse(acquired)
    }

    @Test
    fun enabledExecutionReleasesLeaseAfterSuccessAndFailure() = runTest {
        var acquisitions = 0
        var releases = 0
        val owner = AutomationWakeLockOwner(
            AutomationWakeLockLeaseFactory {
                acquisitions += 1
                AutoCloseable { releases += 1 }
            },
        )

        assertEquals("done", owner.whileHeld(enabled = true) { "done" })
        assertEquals(1, acquisitions)
        assertEquals(1, releases)

        val failure = runCatching {
            owner.whileHeld(enabled = true) { error("boom") }
        }
        assertTrue(failure.isFailure)
        assertEquals(2, acquisitions)
        assertEquals(2, releases)
    }

    @Test
    fun cancellationStillReleasesLease() = runTest {
        var releases = 0
        val owner = AutomationWakeLockOwner(
            AutomationWakeLockLeaseFactory {
                AutoCloseable { releases += 1 }
            },
        )
        val execution = async {
            owner.whileHeld(enabled = true) { awaitCancellation() }
        }

        runCurrent()
        execution.cancelAndJoin()

        assertEquals(1, releases)
    }

    @Test
    fun acquireFailureDoesNotBlockAutomationExecution() = runTest {
        // The acquire failure path logs via DebugLog → android.util.Log, which is not
        // available in unit tests.
        io.mockk.mockkStatic(android.util.Log::class)
        io.mockk.every { android.util.Log.e(any(), any()) } returns 0
        io.mockk.every { android.util.Log.w(any(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.d(any(), any()) } returns 0
        try {
            val owner = AutomationWakeLockOwner(
                AutomationWakeLockLeaseFactory { error("not available") },
            )

            assertEquals("done", owner.whileHeld(enabled = true) { "done" })
        } finally {
            io.mockk.unmockkStatic(android.util.Log::class)
        }
    }
}
