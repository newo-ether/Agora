package com.newoether.agora.phone

import android.app.Application
import android.content.Context
import java.io.File
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidPhoneDeviceTest {
    private val context = mockk<Context>()
    private val privileged = mockk<PhonePrivilegedConnection>()
    private val xml = """<?xml version="1.0"?><hierarchy>
        <node text="Open" class="android.widget.Button" bounds="[10,20][110,220]" enabled="true" clickable="true" />
        <node text="secret" class="android.widget.EditText" bounds="[0,0][50,50]" enabled="true" password="true" />
        </hierarchy>"""

    @Test fun bothPrivilegedModesDumpReadAndCleanTheFileAndParseNodes() = runTest {
        val commands = mutableListOf<String>()
        for (mode in listOf("shizuku", "root")) {
            coEvery { privileged.command(mode, capture(commands)) } returns
                ("UI hierarchy dumped to: /data/local/tmp/agora_ui.xml\n" + xml).toByteArray()
            val nodes = AndroidPhoneDevice(context, mode, privileged).nodes()
            assertEquals(2, nodes.size)
            assertEquals(0, nodes[0].index)
            assertEquals("Open", nodes[0].text)
            assertEquals("Button", nodes[0].role)
            assertEquals(60, nodes[0].bounds.centerX())
            assertEquals(120, nodes[0].bounds.centerY())
            assertTrue(nodes[0].enabled)
            assertEquals("", nodes[1].text)
            assertTrue(nodes[1].editable)
            assertTrue(nodes[1].password)
        }
        assertEquals(2, commands.size)
        commands.forEach { command ->
            assertFalse(command.contains("/dev/tty"))
            assertTrue(command.startsWith("trap 'rm -f /data/local/tmp/agora_ui.xml' EXIT; "))
            assertTrue(command.contains("rm -f /data/local/tmp/agora_ui.xml && (uiautomator dump"))
            assertTrue(command.endsWith("uiautomator dump /data/local/tmp/agora_ui.xml && cat /data/local/tmp/agora_ui.xml) 2>&1"))
        }
    }

    @Test fun idleOutputRetriesExactlyOnceAndCanRecover() = runTest {
        for (mode in listOf("shizuku", "root")) {
            coEvery { privileged.command(mode, any()) } returnsMany listOf(
                "ERROR: could not get idle state.".toByteArray(), xml.toByteArray(),
            )
            assertEquals(2, AndroidPhoneDevice(context, mode, privileged).nodes().size)
            coVerify(exactly = 2) { privileged.command(mode, any()) }
        }
    }

    @Test fun idleCommandFailureRetriesOnceButNeverLoops() = runTest {
        val message = "exit 1: ERROR: could not get idle state."
        coEvery { privileged.command("root", any()) } throws PhoneFailure("device_command_failed", message)
        assertTreeFailure("root", message)
        coVerify(exactly = 2) { privileged.command("root", any()) }
    }

    @Test fun idleExceptionCanRecoverAndRepeatedIdleOutputStopsAfterTwoAttempts() = runTest {
        coEvery { privileged.command("root", any()) } throws
            PhoneFailure("device_command_failed", "could not get idle state") andThen xml.toByteArray()
        assertEquals(2, AndroidPhoneDevice(context, "root", privileged).nodes().size)
        coVerify(exactly = 2) { privileged.command("root", any()) }
        val diagnostic = "ERROR: could not get idle state."
        coEvery { privileged.command("shizuku", any()) } returns diagnostic.toByteArray()
        assertTreeFailure("shizuku", diagnostic)
        coVerify(exactly = 2) { privileged.command("shizuku", any()) }
    }

    @Test fun ordinaryCommandFailureKeepsRawOutputAndDoesNotRetry() = runTest {
        val message = "exit 1: permission denied on dump file"
        coEvery { privileged.command("shizuku", any()) } throws PhoneFailure("device_command_failed", message)
        assertTreeFailure("shizuku", message)
        coVerify(exactly = 1) { privileged.command("shizuku", any()) }
    }

    @Test fun invalidOutputAndMalformedXmlIncludeOnlyTheBoundedRawDiagnostic() = runTest {
        val outputs = listOf("ERROR: no active window " + "x".repeat(700),
            "<hierarchy><node></hierarchy>", "")
        for (output in outputs) {
            coEvery { privileged.command("root", any()) } returns output.toByteArray()
            assertTreeFailure("root", output.take(500))
        }
        coVerify(exactly = 3) { privileged.command("root", any()) }
    }

    @Test fun permissionErrorsReachTheLeaseOwnerWithoutTreeErrorConversionOrRetry() = runTest {
        for ((mode, error) in listOf(
            "root" to PhoneFailure("permission_lost:root_denied"),
            "shizuku" to PhoneFailure("permission_lost:shizuku_binder_dead"),
            "shizuku" to SecurityException("Dispatch permission revoked"),
        )) {
            coEvery { privileged.command(mode, any()) } throws error
            val failure = runCatching { AndroidPhoneDevice(context, mode, privileged).nodes() }.exceptionOrNull()
            assertSame(error, failure)
        }
        coVerify(exactly = 1) { privileged.command("root", any()) }
        coVerify(exactly = 2) { privileged.command("shizuku", any()) }
    }

    @Test fun cancellationPropagatesWithoutRetryOrErrorConversion() = runTest {
        val cancelled = CancellationException("Stopped")
        coEvery { privileged.command("root", any()) } throws cancelled
        try {
            AndroidPhoneDevice(context, "root", privileged).nodes()
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        coVerify(exactly = 1) { privileged.command("root", any()) }
    }

    @Test fun productionDumpCommandCleansFilesOnSuccessFailureAndIdleFailure() = runTest {
        val commands = mutableListOf<String>()
        coEvery { privileged.command("root", capture(commands)) } returns xml.toByteArray()
        AndroidPhoneDevice(context, "root", privileged).nodes()
        val directory = java.nio.file.Files.createTempDirectory("agora-ui-dump").toFile()
        val shell = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            sequenceOf("C:/Program Files/Git/bin/bash.exe", "D:/Program Files/Git/bin/bash.exe")
                .first { File(it).isFile }
        } else "/bin/sh"
        try {
            for (outcome in listOf("success", "failure", "idle")) {
                val dump = File(directory, "agora_ui.xml")
                dump.writeText("stale tree")
                val command = commands.single().replace("/data/local/tmp/agora_ui.xml", "\"\$dump\"")
                val script = "dump=\$1; outcome=\$2; uiautomator() { case \"\$outcome\" in " +
                    "success) printf '<hierarchy></hierarchy>' > \"\$2\"; printf 'dump complete\\n';; " +
                    "failure) printf 'dump stdout'; printf 'dump failed' >&2; return 1;; " +
                    "idle) printf 'ERROR: could not get idle state.' >&2; return 0;; esac; }; " + command
                val result = runCatching {
                    phoneProcess(listOf(shell, "-c", script, "agora", dump.absolutePath.replace('\\', '/'), outcome))
                }
                if (outcome == "success") result.getOrThrow()
                assertFalse("Temporary file must be removed for $outcome: ${result.exceptionOrNull()}", dump.exists())
                if (outcome == "success") {
                    assertEquals("dump complete\n<hierarchy></hierarchy>", String(result.getOrThrow(), Charsets.UTF_8))
                } else {
                    assertTrue(result.exceptionOrNull() is PhoneFailure)
                    val detail = result.exceptionOrNull()!!.message!!
                    if (outcome == "idle") {
                        assertTrue(detail.contains("could not get idle state"))
                        assertTrue(detail.contains("No such file"))
                    } else {
                        assertTrue(detail.contains("dump stdout"))
                        assertTrue(detail.contains("dump failed"))
                    }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private suspend fun assertTreeFailure(mode: String, detail: String) {
        try {
            AndroidPhoneDevice(context, mode, privileged).nodes()
            fail("Expected UI tree failure")
        } catch (error: PhoneFailure) {
            assertEquals("ui_tree_unavailable", error.code)
            assertEquals(detail, error.message)
        }
    }
}
