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
