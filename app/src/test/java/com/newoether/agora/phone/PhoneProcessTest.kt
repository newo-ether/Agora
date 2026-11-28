package com.newoether.agora.phone

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.junit.Assert.*
import org.junit.Test

class PhoneProcessTest {
    @Test fun nonzeroExitKeepsStdoutStderrAndExitCode() {
        withProcess("dump failed on stdout".toByteArray(), "permission denied".toByteArray(), 7) {
            try {
                phoneProcess(listOf("sh", "-c", "dump"))
                fail("Expected command failure")
            } catch (error: PhoneFailure) {
                assertEquals("device_command_failed", error.code)
                assertTrue(error.message!!.contains("exit 7"))
                assertTrue(error.message!!.contains("dump failed on stdout"))
                assertTrue(error.message!!.contains("permission denied"))
            }
        }
    }

    @Test fun successfulStderrOnlyOutputRemainsAvailableForIdleDetection() {
        val diagnostic = "ERROR: could not get idle state.".toByteArray()
        withProcess(byteArrayOf(), diagnostic, 0) {
            assertArrayEquals(diagnostic, phoneProcess(listOf("sh", "-c", "dump")))
        }
    }

    @Test fun binaryStdoutIsUnchangedEvenWhenStderrContainsWarnings() {
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0xff.toByte())
        withProcess(image, "warning".toByteArray(), 0) {
            assertArrayEquals(image, phoneProcess(listOf("sh", "-c", "screencap")))
        }
    }

    @Test fun failedOutputRemainsBoundedAndIncludesBothStreams() {
        withProcess(("stdout:" + "o".repeat(2000)).toByteArray(),
            ("stderr:" + "e".repeat(2000)).toByteArray(), 1) {
            try {
                phoneProcess(listOf("sh", "-c", "dump"))
                fail("Expected command failure")
            } catch (error: PhoneFailure) {
                assertTrue(error.message!!.length <= 1008)
                assertTrue(error.message!!.contains("stdout:"))
                assertTrue(error.message!!.contains("stderr:"))
            }
        }
    }

    @Test fun timedOutProcessIsDestroyedWithoutReturningPartialOutput() {
        withProcess("partial".toByteArray(), byteArrayOf(), 0, finished = false) { process ->
            try {
                phoneProcess(listOf("sh", "-c", "dump"))
                fail("Expected timeout")
            } catch (error: PhoneFailure) {
                assertEquals("device_timeout", error.code)
            }
            io.mockk.verify(exactly = 1) { process.destroyForcibly() }
        }
    }

    @Test fun rootDenialIsDistinctFromCommandFailureAfterShellEntry() {
        val marker = "test-root-entry"
        withProcess(byteArrayOf(), "Permission denied".toByteArray(), 1) {
            val error = assertThrows(PhoneFailure::class.java) { phoneProcess(listOf("su", "-c", "test"), marker) }
            assertEquals("permission_lost:root_denied", error.code)
        }
        withProcess(byteArrayOf(), "$marker\npermission denied on dump file".toByteArray(), 1) {
            val error = assertThrows(PhoneFailure::class.java) { phoneProcess(listOf("su", "-c", "test"), marker) }
            assertEquals("device_command_failed", error.code)
            assertFalse(error.message!!.contains(marker))
        }
    }
    @Test fun rootTimeoutIsNotDenialAndMarkerNeverLeaksIntoOutput() {
        val marker = "test-root-entry"
        withProcess(byteArrayOf(), byteArrayOf(), 0, finished = false) {
            val error = assertThrows(PhoneFailure::class.java) { phoneProcess(listOf("su", "-c", "test"), marker) }
            assertEquals("device_timeout", error.code)
        }
        withProcess("0\n".toByteArray(), "$marker\n".toByteArray(), 0) {
            assertArrayEquals("0\n".toByteArray(), phoneProcess(listOf("su", "-c", "test"), marker))
        }
    }
    @Test fun rootWarningsBeforeTheEntryMarkerDoNotBecomePermissionLoss() {
        val marker = "test-root-entry"
        withProcess(byteArrayOf(), "su warning\n$marker\ncommand warning\n".toByteArray(), 0) {
            assertEquals("su warning\ncommand warning\n",
                phoneProcess(listOf("su", "-c", "test"), marker).toString(Charsets.UTF_8))
        }
    }
    @Test fun rootStartFailureOnlyRevokesForUnavailableOrDeniedSu() {
        mockkConstructor(ProcessBuilder::class)
        every { anyConstructed<ProcessBuilder>().redirectError(ProcessBuilder.Redirect.PIPE) } answers { self as ProcessBuilder }
        try {
            for ((message, expected) in listOf(
                "Cannot run program su: error=2, No such file or directory" to "permission_lost:root_denied",
                "Cannot run program su: error=13, Permission denied" to "permission_lost:root_denied",
                "Cannot run program su: error=1, Operation not permitted" to "permission_lost:root_denied",
                "Cannot run program su: error=24, Too many open files" to "device_command_failed",
            )) {
                every { anyConstructed<ProcessBuilder>().start() } throws java.io.IOException(message)
                val failure = assertThrows(PhoneFailure::class.java) {
                    phoneProcess(listOf("su", "-c", "test"), "test-root-entry")
                }
                assertEquals(message, expected, failure.code)
            }
            every { anyConstructed<ProcessBuilder>().start() } throws java.io.IOException("error=2, No such file")
            assertEquals("device_command_failed", assertThrows(PhoneFailure::class.java) {
                phoneProcess(listOf("sh", "-c", "test"))
            }.code)
        } finally { unmockkConstructor(ProcessBuilder::class) }
    }
    private fun withProcess(
        stdout: ByteArray,
        stderr: ByteArray,
        exit: Int,
        finished: Boolean = true,
        block: (Process) -> Unit,
    ) {
        val process = mockk<Process>()
        every { process.inputStream } returns ByteArrayInputStream(stdout)
        every { process.errorStream } returns ByteArrayInputStream(stderr)
        every { process.waitFor(30, TimeUnit.SECONDS) } returns finished
        every { process.exitValue() } returns exit
        every { process.destroyForcibly() } returns process
        mockkConstructor(ProcessBuilder::class)
        every { anyConstructed<ProcessBuilder>().redirectError(ProcessBuilder.Redirect.PIPE) } answers { self as ProcessBuilder }
        every { anyConstructed<ProcessBuilder>().start() } returns process
        try { block(process) } finally { unmockkConstructor(ProcessBuilder::class) }
    }
}
