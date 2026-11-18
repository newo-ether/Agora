package com.newoether.agora.tool

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.util.ImageArtifactProcessor
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellLocalServerTest {
    private lateinit var directory: File
    private lateinit var mediaDirectory: File
    private lateinit var manager: SandboxManager
    private lateinit var provider: ShellToolProvider
    private val context = GenerationContext(shellEnabled = true, sandboxEnabled = true)

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("agora-local-server-").toFile()
        mediaDirectory = directory.resolve("media")
        manager = mockk()
        every { manager.isAvailableSync() } returns true
        val factory = object : SandboxManagerFactory {
            override fun create() = manager
            override fun isAvailable() = true
        }
        provider = ShellToolProvider(
            factory,
            ToolImageStore(RuntimeEnvironment.getApplication(), mediaDirectory),
        )
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun imageEventsPublishMeasuredModelArtifactAndKeepImageMetadata() = runTest {
        assertImageResult(Entry.EVENTS, alpha = false)
    }

    @Test
    fun imageOneShotPublishesMeasuredModelArtifactAndKeepsImageMetadata() = runTest {
        assertImageResult(Entry.ONE_SHOT, alpha = true)
    }

    @Test
    fun imageRelativePathIsRejectedBeforeCallingTheSandboxResolver() = runTest {
        for (entry in Entry.entries) {
            val result = invoke(entry, "file_view_image", """{"path":"relative.png"}""")
            assertImageError(result)
            assertTrue(result.text.contains("absolute"))
        }
        verify(exactly = 0) { manager.resolveHostFile(any()) }
    }

    @Test
    fun imageUsesTheSharedResolverBoundaryWithoutFallingBackToHostPaths() = runTest {
        val path = "/mnt/shared/../private/escape.png"
        every { manager.resolveHostFile(path) } returns null
        for (entry in Entry.entries) {
            val result = invoke(entry, "file_view_image", imageArguments(path))
            assertImageError(result)
            assertTrue(result.text.contains("unavailable"))
        }
        verify(exactly = 2) { manager.resolveHostFile(path) }
        assertNoPublishedImages()
    }

    @Test
    fun imageMissingDirectoryInvalidBytesAndOversizeNeverPublishArtifacts() = runTest {
        val invalid = directory.resolve("invalid.png").apply { writeText("not an image") }
        val oversized = directory.resolve("oversized.png")
        RandomAccessFile(oversized, "rw").use { it.setLength(ToolImageStore.MAX_IMAGE_BYTES + 1) }
        val candidates = listOf(directory.resolve("missing.png"), directory, invalid, oversized)
        for ((index, file) in candidates.withIndex()) {
            val path = "/home/agora/image-$index.png"
            every { manager.resolveHostFile(path) } returns file
            for (entry in Entry.entries) {
                assertImageError(invoke(entry, "file_view_image", imageArguments(path)))
                assertNoPublishedImages()
            }
        }
    }

    @Test
    fun imageResolverCancellationPropagatesOnBothEntrypoints() = runTest {
        val cancelled = CancellationException("resolver cancelled")
        every { manager.resolveHostFile("/home/agora/image.png") } throws cancelled
        for (entry in Entry.entries) {
            try {
                invoke(entry, "file_view_image", imageArguments("/home/agora/image.png"))
                fail("Image cancellation must not become a completed error result")
            } catch (actual: CancellationException) {
                assertEquals(cancelled.message, actual.message)
            }
        }
        assertNoPublishedImages()
    }

    @Test
    fun cancellationAtImageIoReturnRemovesTheUndeliveredArtifact() = runTest {
        val sourceBytes = encodedImage(alpha = false)
        val source = directory.resolve("source.bin").apply { writeBytes(sourceBytes) }
        val path = "/home/agora/image.png"
        every { manager.resolveHostFile(path) } returns source
        for (entry in Entry.entries) {
            val returning = CompletableDeferred<Runnable>()
            val firstDispatch = java.util.concurrent.atomic.AtomicBoolean(true)
            val fallback = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    if (firstDispatch.compareAndSet(true, false)) returning.complete(block)
                    else fallback.dispatch(context, block)
                }
            }
            var completed = false
            val request = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                invoke(entry, "file_view_image", imageArguments(path))
                completed = true
            }
            val pausedReturn = returning.await()
            var resumed = false
            try {
                val artifact = mediaDirectory.listFiles().orEmpty().single()
                assertTrue(request.isActive)
                assertFalse(completed)
                request.cancel()
                resumed = true
                pausedReturn.run()
                request.join()
                assertTrue(request.isCancelled)
                assertFalse(completed)
                assertFalse(artifact.exists())
                assertNoPublishedImages()
                assertArrayEquals(sourceBytes, source.readBytes())
            } finally {
                request.cancel()
                if (!resumed) pausedReturn.run()
                request.join()
            }
        }
    }

    @Test
    fun backgroundStartUsesTheLocalManagerOnBothEntrypoints() = runTest {
        coEvery { manager.startJob("echo local", "/home/agora", 2000, "background fixture") } returns
            job("local-job", "running")
        val arguments = commandArguments(background = true, description = "background fixture")
        for (entry in Entry.entries) {
            val result = invoke(entry, "shell_exec", arguments)
            assertEquals("local-job", envelope(result).value("job_id"))
            assertEquals("running", envelope(result).value("state"))
            assertFalse(result.isError)
        }
        coVerify(exactly = 2) {
            manager.startJob("echo local", "/home/agora", 2000, "background fixture")
        }
        assertNoLegacyExecution()
    }

    @Test
    fun listGetWaitAndStopDelegateToTheLocalManager() = runTest {
        coEvery { manager.listJobs(4, 7) } returns """{"jobs":[],"offset":4,"has_more":false}"""
        coEvery { manager.getJob("local-job") } returns job("local-job", "succeeded", "done\n")
        coEvery { manager.stopJob("local-job") } returns job("local-job", "stopped")

        val listed = invoke(Entry.ONE_SHOT, "shell_job_list", """{"offset":4,"limit":7}""")
        assertEquals("4", envelope(listed).value("offset"))
        val fetched = invoke(Entry.ONE_SHOT, "shell_job_get", jobArguments())
        assertEquals("succeeded", envelope(fetched).value("state"))
        val events = provider.executeEvents("shell_job_wait", jobArguments(wait = true), context).toList()
        assertTrue(events.contains(ToolExecutionEvent.TargetResolved(LOCAL)))
        assertTrue(events.contains(ToolExecutionEvent.OutputSnapshot("done\n")))
        val waited = envelope(events.filterIsInstance<ToolExecutionEvent.Completed>().single().result)
        assertEquals("succeeded", waited.getValue("result").jsonObject.value("state"))
        val stopped = invoke(Entry.ONE_SHOT, "shell_job_stop", jobArguments())
        assertEquals("stopped", envelope(stopped).value("state"))

        coVerify(exactly = 1) { manager.listJobs(4, 7) }
        coVerify(exactly = 2) { manager.getJob("local-job") }
        coVerify(exactly = 1) { manager.stopJob("local-job") }
        coVerify(exactly = 0) { manager.acknowledgeJob(any()) }
        coVerify(exactly = 0) { manager.isAvailable() }
        assertNoLegacyExecution()
    }

    @Test
    fun foregroundStreamsIncrementalOutputFromOneDurableLocalJob() = runTest {
        coEvery { manager.startJob(any(), any(), any(), any()) } returns job("local-job", "running")
        coEvery { manager.getJob("local-job") } returnsMany listOf(
            job("local-job", "running", "hello\n"),
            job("local-job", "succeeded", "hello\nworld\n"),
        )
        val events = provider.executeEvents("shell_exec", commandArguments(), context).toList()
        assertTrue(events.contains(ToolExecutionEvent.TargetResolved(LOCAL)))
        assertEquals(
            listOf(ToolExecutionEvent.OutputDelta("hello\n"), ToolExecutionEvent.OutputDelta("world\n")),
            events.filterIsInstance<ToolExecutionEvent.OutputDelta>(),
        )
        val result = envelope(events.filterIsInstance<ToolExecutionEvent.Completed>().single().result)
        assertEquals("local-job", result.value("job_id"))
        assertEquals("true", result.value("durable"))
        assertEquals("succeeded", result.getValue("result").jsonObject.value("state"))
        coVerify(exactly = 1) {
            manager.startJob(
                "echo local", "/home/agora", ShellDurableJobExecutor.BACKGROUND_JOB_MAX_MS, "foreground fixture",
            )
        }
        coVerify(exactly = 0) { manager.stopJob(any()) }
        coVerify(exactly = 0) { manager.acknowledgeJob(any()) }
        assertNoLegacyExecution()
    }

    @Test
    fun foregroundExpiryReturnsTheSameJobWithoutStoppingOrReplayingIt() = runTest {
        coEvery { manager.startJob(any(), any(), any(), any()) } returns job("local-job", "running")
        coEvery { manager.getJob("local-job") } returns job("local-job", "running")
        // A zero wait exercises expiry without coupling virtual delay to the wall clock.
        val raw = ShellDurableJobExecutor(manager).executeDurableForeground(
            backend = SandboxBackend(manager), command = "echo local", workdir = "/home/agora", waitMs = 0,
        )
        val expired = Json.parseToJsonElement(raw).jsonObject
        assertEquals("local-job", expired.value("job_id"))
        assertEquals("running", expired.value("state"))
        assertEquals("true", expired.value("background"))
        coEvery { manager.getJob("local-job") } returns job("local-job", "succeeded")
        for (entry in Entry.entries) {
            val later = envelope(invoke(entry, "shell_job_get", jobArguments()))
            assertEquals("local-job", later.value("job_id"))
            assertEquals("succeeded", later.value("state"))
        }
        coVerify(exactly = 1) { manager.startJob(any(), any(), any(), any()) }
        coVerify(exactly = 3) { manager.getJob("local-job") }
        coVerify(exactly = 0) { manager.stopJob(any()) }
        assertNoLegacyExecution()
    }

    @Test
    fun foregroundCancellationStopsExactlyOnceAndWaitsForSettlement() = runTest {
        coEvery { manager.startJob(any(), any(), any(), any()) } returns job("local-job", "running")
        coEvery { manager.getJob("local-job") } coAnswers { awaitCancellation() }
        for (entry in Entry.entries) {
            val stopEntered = CompletableDeferred<Unit>()
            val settled = CompletableDeferred<Unit>()
            coEvery { manager.stopJob("local-job") } coAnswers {
                stopEntered.complete(Unit)
                settled.await()
                job("local-job", "stopped")
            }
            var completed = false
            val request = launch(start = CoroutineStart.UNDISPATCHED) {
                invoke(entry, "shell_exec", commandArguments())
                completed = true
            }
            assertTrue(request.isActive)
            request.cancel()
            runCurrent()
            assertTrue(stopEntered.isCompleted)
            assertFalse("Cancellation must await process settlement", request.isCompleted)
            settled.complete(Unit)
            request.join()
            assertTrue(request.isCancelled)
            assertFalse(completed)
        }
        coVerify(exactly = 2) { manager.startJob(any(), any(), any(), any()) }
        coVerify(exactly = 2) { manager.stopJob("local-job") }
        assertNoLegacyExecution()
    }

    @Test
    fun foregroundCancellationAfterAdmissionStopsBeforeTheFirstPoll() = runTest {
        coEvery { manager.startJob(any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            job("local-job", "running")
        }
        coEvery { manager.stopJob("local-job") } returns job("local-job", "stopped")
        var completed = false
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            ShellDurableJobExecutor(manager).executeDurableForeground(
                backend = SandboxBackend(manager), command = "echo local", workdir = "/home/agora", waitMs = 1000,
            )
            completed = true
        }
        request.join()
        assertTrue(request.isCancelled)
        assertFalse(completed)
        coVerify(exactly = 1) { manager.startJob(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.getJob(any()) }
        coVerify(exactly = 1) { manager.stopJob("local-job") }
    }

    @Test
    fun cancellingJobReadOrWaitOnlyCancelsObservation() = runTest {
        coEvery { manager.getJob("local-job") } coAnswers { awaitCancellation() }
        for (tool in listOf("shell_job_get", "shell_job_wait")) {
            for (entry in Entry.entries) {
                var completed = false
                val request = launch(start = CoroutineStart.UNDISPATCHED) {
                    invoke(entry, tool, jobArguments(wait = tool == "shell_job_wait"))
                    completed = true
                }
                assertTrue(request.isActive)
                request.cancelAndJoin()
                assertTrue(request.isCancelled)
                assertFalse(completed)
            }
        }
        coVerify(exactly = 4) { manager.getJob("local-job") }
        coVerify(exactly = 0) { manager.startJob(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.stopJob(any()) }
        coVerify(exactly = 0) { manager.acknowledgeJob(any()) }
        assertNoLegacyExecution()
    }

    @Test
    fun terminalResultsAreAcknowledgedOnlyThroughThePostCommitHook() = runTest {
        val states = listOf("succeeded", "failed", "stopped", "interrupted", "running")
        coEvery { manager.getJob(any()) } coAnswers {
            val state = firstArg<String>()
            job(state, state)
        }
        coEvery { manager.acknowledgeJob(any()) } returns """{"ok":true}"""
        val calls = states.map { state ->
            val arguments = jobArguments(id = state)
            val result = invoke(Entry.ONE_SHOT, "shell_job_get", arguments)
            assertNotNull(result.structuredContent)
            ToolCallData(
                toolName = "shell_job_get", arguments = arguments,
                result = result.text, structuredResult = result.structuredContent,
            )
        }
        coVerify(exactly = 0) { manager.acknowledgeJob(any()) }
        provider.acknowledgeCommittedJobs(calls + calls.first(), context)
        states.filter { it != "running" }.forEach { state ->
            coVerify(exactly = 1) { manager.acknowledgeJob(state) }
        }
        coVerify(exactly = 0) { manager.acknowledgeJob("running") }
        coVerify(exactly = 0) { manager.stopJob(any()) }
    }

    @Test
    fun sharedStorageDenialPreventsCommandStartAndStopOnBothEntrypoints() = runTest {
        val sharedContext = context.copy(sandboxSharedStorageEnabled = true, conversationId = "conversation")
        val confirmations = mutableListOf<Pair<String, String?>>()
        provider.confirm = { server, _, conversationId, _ ->
            confirmations += server to conversationId
            false
        }
        val operations = listOf(
            "shell_exec" to commandArguments(),
            "shell_exec" to commandArguments(background = true),
            "shell_job_stop" to jobArguments(),
        )
        for ((tool, arguments) in operations) {
            for (entry in Entry.entries) {
                val result = invoke(entry, tool, arguments, sharedContext)
                assertTrue(result.text, result.text.contains("denied_by_user"))
            }
        }
        assertEquals(List(6) { "Local Sandbox" to "conversation" }, confirmations)
        coVerify(exactly = 0) { manager.startJob(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.stopJob(any()) }
        coVerify(exactly = 0) { manager.getJob(any()) }
        assertNoLegacyExecution()
    }

    @Test
    fun sharedStorageJobReadsDoNotRequireMutationConfirmation() = runTest {
        val sharedContext = context.copy(sandboxSharedStorageEnabled = true)
        provider.confirm = { _, _, _, _ -> error("Read-only job observation must not request confirmation") }
        coEvery { manager.listJobs(0, 100) } returns """{"jobs":[]}"""
        coEvery { manager.getJob("local-job") } returns job("local-job", "succeeded")
        invoke(Entry.ONE_SHOT, "shell_job_list", "{}", sharedContext)
        val fetched = invoke(Entry.ONE_SHOT, "shell_job_get", jobArguments(), sharedContext)
        val waited = invoke(Entry.EVENTS, "shell_job_wait", jobArguments(wait = true), sharedContext)
        assertEquals("succeeded", envelope(fetched).value("state"))
        assertEquals("succeeded", envelope(waited).getValue("result").jsonObject.value("state"))
        coVerify(exactly = 1) { manager.listJobs(0, 100) }
        coVerify(exactly = 2) { manager.getJob("local-job") }
    }

    @Test
    fun omittedServerIsRejectedWhenLocalAndConchAreBothEligible() = runTest {
        val multiple = context.copy(shellDevices = listOf(ShellDeviceConfig(name = "remote", serverUrl = "http://unused")))
        val operations = listOf(
            "shell_exec" to commandArguments(),
            "shell_exec" to commandArguments(background = true),
            "shell_job_list" to "{}",
            "shell_job_get" to jobArguments(),
            "shell_job_wait" to jobArguments(wait = true),
            "shell_job_stop" to jobArguments(),
            "file_view_image" to imageArguments("/home/agora/image.png"),
        )
        for ((tool, arguments) in operations) {
            for (entry in Entry.entries) {
                val result = invoke(entry, tool, arguments, multiple)
                assertTrue(result.text, result.text.contains("Multiple servers available"))
                assertTrue(result.text.contains(LOCAL))
                assertTrue(result.text.contains("remote"))
            }
        }
        verify(exactly = 0) { manager.resolveHostFile(any()) }
        coVerify(exactly = 0) { manager.startJob(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.listJobs(any(), any()) }
        coVerify(exactly = 0) { manager.getJob(any()) }
        coVerify(exactly = 0) { manager.stopJob(any()) }
        assertNoLegacyExecution()
    }

    @Test
    fun standardToolsExcludeSshAndCanResolveTheSoleLocalStandardServer() = runTest {
        val mixed = context.copy(shellDevices = listOf(ShellDeviceConfig(name = "ssh-only", type = "ssh", sshHost = "unused")))
        coEvery { manager.listJobs(0, 100) } returns """{"jobs":[],"local_fixture":true}"""
        for (entry in Entry.entries) {
            assertEquals("true", envelope(invoke(entry, "shell_job_list", "{}", mixed)).value("local_fixture"))
            val image = invoke(entry, "file_view_image", imageArguments("relative.png"), mixed)
            assertImageError(image)
            assertTrue(image.text, image.text.contains("absolute"))
        }
        val operations = listOf(
            "shell_job_list" to "{}",
            "shell_job_get" to jobArguments(),
            "shell_job_wait" to jobArguments(wait = true),
            "shell_job_stop" to jobArguments(),
            "file_view_image" to imageArguments("/home/agora/image.png"),
            "shell_exec" to commandArguments(background = true),
        )
        for ((tool, arguments) in operations) {
            val explicit = Json.parseToJsonElement(arguments).jsonObject.toMutableMap().apply {
                put("server", kotlinx.serialization.json.JsonPrimitive("ssh-only"))
            }
            for (entry in Entry.entries) {
                val result = invoke(entry, tool, JsonObject(explicit).toString(), mixed)
                assertTrue(result.text, result.text.contains("Unknown or unsupported server"))
            }
        }
        coVerify(exactly = 2) { manager.listJobs(0, 100) }
        coVerify(exactly = 0) { manager.startJob(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.getJob(any()) }
        coVerify(exactly = 0) { manager.stopJob(any()) }
        verify(exactly = 0) { manager.resolveHostFile(any()) }
        assertNoLegacyExecution()
    }

    @Test
    fun omittedShellExecServerIsRejectedWithSshAndLocalEvenForBackground() = runTest {
        val mixed = context.copy(shellDevices = listOf(ShellDeviceConfig(name = "ssh-only", type = "ssh", sshHost = "unused")))
        val definition = provider.definitions(mixed).single { it.function.name == "shell_exec" }
        assertTrue("server" in definition.function.parameters.required)
        for (background in listOf(false, true)) {
            for (entry in Entry.entries) {
                val result = invoke(entry, "shell_exec", commandArguments(background = background), mixed)
                assertNotNull(result.text, envelope(result)["error"])
            }
        }
        coVerify(exactly = 0) { manager.startJob(any(), any(), any(), any()) }
        coVerify(exactly = 0) { manager.getJob(any()) }
        coVerify(exactly = 0) { manager.stopJob(any()) }
        assertNoLegacyExecution()
    }

    @Test
    fun explicitLocalBackgroundStillWorksWithSshAndLocal() = runTest {
        val mixed = context.copy(shellDevices = listOf(ShellDeviceConfig(name = "ssh-only", type = "ssh", sshHost = "unused")))
        coEvery { manager.startJob(any(), any(), any(), any()) } returns job("local-job", "running")
        for (entry in Entry.entries) {
            val result = invoke(entry, "shell_exec", commandArguments(background = true, server = LOCAL), mixed)
            assertEquals("local-job", envelope(result).value("job_id"))
        }
        coVerify(exactly = 2) { manager.startJob(any(), any(), any(), any()) }
        assertNoLegacyExecution()
    }

    @Test
    fun unavailableLocalServerDoesNotUseTheBlockingAvailabilityCheck() = runTest {
        every { manager.isAvailableSync() } returns false
        val result = invoke(Entry.ONE_SHOT, "shell_job_list", "{}")
        assertTrue(result.text, result.text.contains("unavailable"))
        coVerify(exactly = 0) { manager.isAvailable() }
        coVerify(exactly = 0) { manager.listJobs(any(), any()) }
        assertNoLegacyExecution()
    }

    private suspend fun assertImageResult(entry: Entry, alpha: Boolean) {
        val sourceBytes = encodedImage(alpha)
        val source = directory.resolve("source.bin").apply { writeBytes(sourceBytes) }
        val path = "/home/agora/image-without-extension"
        every { manager.resolveHostFile(path) } returns source
        val result = invoke(entry, "file_view_image", imageArguments(path))
        assertFalse(result.text, result.isError)
        assertTrue(result.transcribeImages)
        val attachment = result.images.single()
        val artifact = File(attachment.path)
        assertEquals(mediaDirectory.canonicalFile, artifact.parentFile.canonicalFile)
        assertTrue(artifact.name.startsWith("sandbox_"))
        val bytes = artifact.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(2048, attachment.width)
        assertEquals(1024, attachment.height)
        assertEquals(bounds.outWidth, attachment.width)
        assertEquals(bounds.outHeight, attachment.height)
        assertEquals(if (alpha) "image/png" else "image/jpeg", bounds.outMimeType)
        assertEquals(bounds.outMimeType, attachment.mimeType)
        assertEquals(bytes.size.toLong(), attachment.sizeBytes)
        assertEquals(ImageArtifactProcessor.POLICY_VERSION, attachment.policyVersion)
        assertEquals(listOf(artifact), mediaDirectory.listFiles()?.toList())
        assertArrayEquals(sourceBytes, source.readBytes())
        val metadata = envelope(result)
        assertEquals("true", metadata.value("ok"))
        assertEquals(LOCAL, metadata.value("server"))
        assertEquals(path, metadata.value("path"))
        assertEquals(attachment.mimeType, metadata.value("mime_type"))
        assertEquals("2048", metadata.value("width"))
        assertEquals("1024", metadata.value("height"))
        assertEquals(attachment.sizeBytes.toString(), metadata.value("size"))
        verify(exactly = 1) { manager.resolveHostFile(path) }
        coVerify(exactly = 0) { manager.isAvailable() }
    }

    private fun assertImageError(result: ToolExecutionResult) {
        assertTrue(result.text, result.isError)
        assertTrue(result.images.isEmpty())
        assertFalse(result.transcribeImages)
    }

    private fun assertNoPublishedImages() {
        assertTrue(mediaDirectory.listFiles().isNullOrEmpty())
    }

    private fun assertNoLegacyExecution() {
        coVerify(exactly = 0) { manager.executeCommand(any(), any(), any()) }
    }

    private suspend fun invoke(
        entry: Entry,
        tool: String,
        arguments: String,
        ctx: GenerationContext = context,
    ): ToolExecutionResult = when (entry) {
        Entry.ONE_SHOT -> provider.executeOneShotResult(tool, arguments, ctx)
        Entry.EVENTS -> provider.executeEvents(tool, arguments, ctx).toList()
            .filterIsInstance<ToolExecutionEvent.Completed>().single().result
    }

    private fun commandArguments(
        background: Boolean = false,
        description: String = "foreground fixture",
        server: String? = null,
    ) = buildJsonObject {
        server?.let { put("server", it) }
        put("command", "echo local")
        put("workdir", "/home/agora")
        put("timeout_ms", 2000)
        put("description", description)
        put("background", background)
    }.toString()

    private fun jobArguments(id: String = "local-job", wait: Boolean = false) = buildJsonObject {
        put("job_id", id)
        put("description", "job fixture")
        if (wait) put("timeout_ms", 2000)
    }.toString()

    private fun imageArguments(path: String) = buildJsonObject { put("path", path) }.toString()

    private fun job(id: String, state: String, output: String = "") = buildJsonObject {
        put("job_id", id)
        put("state", state)
        put("output", output)
        put("output_bytes", output.toByteArray(Charsets.UTF_8).size)
        put("truncated", false)
    }.toString()

    private fun envelope(result: ToolExecutionResult) =
        Json.parseToJsonElement(result.structuredContent ?: result.text).jsonObject

    private fun JsonObject.value(key: String) = get(key)?.jsonPrimitive?.content

    private fun encodedImage(alpha: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(2049, 1025, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setHasAlpha(alpha)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private enum class Entry { ONE_SHOT, EVENTS }

    private companion object {
        const val LOCAL = "Local Sandbox"
    }
}
