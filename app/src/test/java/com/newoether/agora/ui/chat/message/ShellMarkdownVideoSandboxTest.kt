package com.newoether.agora.ui.chat.message

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.newoether.agora.sandbox.SandboxManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShellMarkdownVideoSandboxTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun copiesLocalSandboxRootfsMediaAndReusesReceipt() = runBlocking {
        val rootfs = temporary.newFolder("rootfs")
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70)
        val source = File(rootfs, "tmp/demo.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }
        val manager = mockk<SandboxManager>()
        coEvery { manager.isAvailable() } returns true
        every { manager.resolveHostFile("/tmp/demo.mp3") } returns source

        val context = isolatedContext()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val link = "shell://Local%20Sandbox/tmp/demo.mp3"
        try {
            val loader = ShellMarkdownVideo(
                context = context,
                devices = { emptyList() },
                scope = scope,
                cacheDirectoryName = "markdown-audio",
                sandboxEnabled = { true },
                sandbox = { manager },
            )
            assertEquals(MarkdownVideoDownload.Idle, loader.peek(link))
            loader.start(link)
            val ready = awaitTerminal(loader, link) as MarkdownVideoDownload.Ready
            assertTrue(ready.file.name.endsWith(".mp3"))
            assertArrayEquals(payload, ready.file.readBytes())

            // A fresh loader reopens the cached copy from its receipt without touching the sandbox.
            val reopened = ShellMarkdownVideo(
                context = context,
                devices = { emptyList() },
                scope = scope,
                cacheDirectoryName = "markdown-audio",
                sandboxEnabled = { false },
                sandbox = { null },
            )
            val cached = reopened.peek(link) as MarkdownVideoDownload.Ready
            assertEquals(ready.file.canonicalFile, cached.file.canonicalFile)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failsWhenLocalSandboxIsDisabled() = runBlocking {
        val context = isolatedContext()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val link = "shell://Local%20Sandbox/tmp/clip.mp4"
        try {
            val loader = ShellMarkdownVideo(
                context = context,
                devices = { emptyList() },
                scope = scope,
                cacheDirectoryName = "markdown-video",
                sandboxEnabled = { false },
                sandbox = { null },
            )
            loader.start(link)
            val failed = awaitTerminal(loader, link) as MarkdownVideoDownload.Failed
            assertTrue(failed.message.contains("Local Sandbox is disabled"))
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitTerminal(loader: ShellMarkdownVideo, link: String): MarkdownVideoDownload {
        repeat(100) {
            ShadowLooper.idleMainLooper()
            when (val state = loader.peek(link)) {
                is MarkdownVideoDownload.Ready, is MarkdownVideoDownload.Failed -> return state
                else -> delay(20)
            }
        }
        error("Timed out waiting for ShellMarkdownVideo state for $link: ${loader.peek(link)}")
    }

    private fun isolatedContext(): Context {
        val cache = temporary.newFolder("cache")
        val base = RuntimeEnvironment.getApplication()
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cache
        }
    }
}
