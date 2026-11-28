package com.newoether.agora.tool

import com.newoether.agora.api.HttpClient
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.viewmodel.StreamScope
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.util.ShellClient
import com.newoether.agora.util.shellFileSha256
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellFileTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun local(
        root: File,
        publishNoReplace: (File, File) -> Unit = { staging, destination ->
            // Ordinary JVM fixtures explicitly use their host's atomic hardlink primitive.
            java.nio.file.Files.createLink(destination.toPath(), staging.toPath())
        },
    ): FileTransferEndpoint {
        val manager = Proxy.newProxyInstance(SandboxManager::class.java.classLoader, arrayOf(SandboxManager::class.java)) { _, method, args ->
            when (method.name) {
                "isAvailable" -> true
                "getSandboxHomeDir" -> root
                "resolveHostFile" -> File(root, (args!![0] as String).removePrefix("/"))
                else -> error("Unexpected sandbox call ${method.name}")
            }
        } as SandboxManager
        return FileTransferEndpoint("Local Sandbox", sandbox = manager, publishNoReplace = publishNoReplace)
    }

    @Test fun binaryAndEmptyCopiesKeepSourceAndReturnHashes() = runBlocking {
        val root = temporary.newFolder()
        val endpoint = local(root)
        for (data in listOf(byteArrayOf(), ByteArray(700_001) { (it * 137).toByte() })) {
            File(root, "source").writeBytes(data)
            File(root, "destination").delete()
            val receipt = Json.parseToJsonElement(transferShellFile(endpoint, "/source", endpoint, "/destination", false)) as JsonObject
            assertArrayEquals(data, File(root, "source").readBytes())
            assertArrayEquals(data, File(root, "destination").readBytes())
            assertEquals(data.size.toLong(), receipt.transferLong("bytes"))
            assertEquals(shellFileSha256(data), receipt.transferString("destination_sha256"))
            assertFalse(root.listFiles()!!.any { it.name.startsWith("agora-transfer-") || it.name.startsWith(".agora-transfer-") })
        }
    }

    @Test fun overwriteIsExplicitAndSameFileIsUnchanged() = runBlocking {
        val root = temporary.newFolder()
        val endpoint = local(root)
        File(root, "source").writeText("new")
        File(root, "destination").writeText("old")
        var failure: Exception? = null
        try { transferShellFile(endpoint, "/source", endpoint, "/destination", false) }
        catch (error: Exception) { failure = error }
        assertNotNull(failure)
        assertEquals("old", File(root, "destination").readText())
        transferShellFile(endpoint, "/source", endpoint, "/destination", true)
        assertEquals("new", File(root, "destination").readText())
        val result = Json.parseToJsonElement(transferShellFile(endpoint, "/source", endpoint, "/source", false)) as JsonObject
        assertEquals("unchanged", result.transferString("status"))
        assertEquals("new", File(root, "source").readText())
    }

    @Test fun competingLocalCopiesPublishExactlyOneCompleteFile() = runBlocking {
        val root = temporary.newFolder()
        val ready = java.util.concurrent.CountDownLatch(2)
        val endpoint = local(root) { staging, destination ->
            ready.countDown()
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Competing publisher did not arrive" }
            java.nio.file.Files.createLink(destination.toPath(), staging.toPath())
        }
        val left = ByteArray(300_005) { (it * 83).toByte() }
        val right = ByteArray(300_005) { (it * 137 + 1).toByte() }
        File(root, "left").writeBytes(left)
        File(root, "right").writeBytes(right)
        val results = listOf("/left", "/right").map { source ->
            async(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { transferShellFile(endpoint, source, endpoint, "/target", false) }
            }
        }.map { it.await() }
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is java.nio.file.FileAlreadyExistsException)
        assertArrayEquals(if (results[0].isSuccess) left else right, File(root, "target").readBytes())
        assertArrayEquals(left, File(root, "left").readBytes())
        assertArrayEquals(right, File(root, "right").readBytes())
        assertEquals(setOf("left", "right", "target"), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun destinationCreatedAfterPrecheckIsNotOverwritten() = runBlocking {
        val root = temporary.newFolder()
        val endpoint = local(root) { staging, destination ->
            destination.writeText("competing writer")
            java.nio.file.Files.createLink(destination.toPath(), staging.toPath())
        }
        File(root, "source").writeText("source bytes")
        try {
            transferShellFile(endpoint, "/source", endpoint, "/target", false)
            fail("Competing target was overwritten")
        } catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertEquals("competing writer", File(root, "target").readText())
        assertEquals("source bytes", File(root, "source").readText())
        assertEquals(setOf("source", "target"), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun unsupportedPublicationFailsOnceAndCleansStagingWithoutFallback() = runBlocking {
        val root = temporary.newFolder()
        val expected = java.io.IOException("Atomic no-replace publication is unsupported (errno=38)")
        var calls = 0
        val endpoint = local(root) { _, _ -> calls++; throw expected }
        val source = File(root, "source").apply { writeText("retained") }
        try {
            transferShellFile(endpoint, "/source", endpoint, "/target", false)
            fail("Unsupported publication succeeded")
        } catch (actual: java.io.IOException) {
            assertEquals(expected.message, actual.message)
        }
        assertEquals(1, calls)
        assertEquals("retained", source.readText())
        assertEquals(listOf("source"), root.listFiles()!!.map { it.name })
    }

    @Test fun conchDirectionsAndLostResponsesAreReconciledWithoutReplay() = runBlocking {
        val root = temporary.newFolder()
        val local = local(root)
        val data = ByteArray(600_007) { (it * 83).toByte() }
        File(root, "source").writeBytes(data)
        Fixture(data).use { first ->
            Fixture(data).use { second ->
                transferShellFile(local, "/source", first.endpoint, "/upload", false)
                assertArrayEquals(data, first.published)
                transferShellFile(first.endpoint, "/source", local, "/download", false)
                assertArrayEquals(data, File(root, "download").readBytes())
                transferShellFile(first.endpoint, "/source", second.endpoint, "/upload", false)
                assertArrayEquals(data, second.published)
                assertEquals(1, first.beginCalls)
                assertEquals(1, second.beginCalls)
                assertEquals(3, first.chunkCalls)
                assertEquals(3, second.chunkCalls)
                assertEquals(1, first.commitCalls)
                assertEquals(1, second.commitCalls)
            }
        }
    }

    @Test fun sourceDriftDoesNotOpenDestinationAndOldServerIsExplicit() = runBlocking {
        val root = temporary.newFolder()
        val endpoint = local(root)
        Fixture(byteArrayOf(1, 2, 3), drift = true).use { fixture ->
            var failure: Exception? = null
            try { transferShellFile(fixture.endpoint, "/source", endpoint, "/target", false) }
            catch (error: Exception) { failure = error }
            assertTrue(failure?.message.orEmpty().contains("Source changed"))
            assertFalse(File(root, "target").exists())
            assertTrue(root.listFiles()!!.isEmpty())
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.sendResponseHeaders(404, -1); exchange.close() }
        server.start()
        try {
            val remote = FileTransferEndpoint("old", client = ShellClient("http://127.0.0.1:${server.address.port}", ""))
            var failure: Exception? = null
            try { remote.checkSupport() } catch (error: Exception) { failure = error }
            assertTrue(failure?.message.orEmpty().contains("update Conch"))
        } finally { server.stop(0) }
    }

    @Test fun localStagingRejectsWrongHashAndCleansOnCancellation() = runBlocking {
        val root = temporary.newFolder()
        val endpoint = local(root)
        val spool = File(root, "spool").apply { writeText("safe") }
        try {
            endpoint.publish(spool, "/target", spool.length(), "0".repeat(64), false)
            fail("Wrong local staged hash accepted")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("hash mismatch"))
        }
        val cancelled = Job().apply { cancel() }
        try {
            withContext(cancelled) {
                endpoint.publish(spool, "/target", spool.length(), shellFileSha256(spool.readBytes()), false)
            }
            fail("Cancelled publication accepted")
        } catch (_: CancellationException) { }
        assertFalse(File(root, "target").exists())
        assertEquals(listOf("spool"), root.listFiles()!!.map { it.name })
    }

    @Test fun denialAndInvalidArgumentsHaveNoNetworkEffects() = runBlocking {
        val config = ShellDeviceConfig(name = "remote", serverUrl = "http://127.0.0.1:1")
        val ctx = GenerationContext(shellEnabled = true, shellDevices = listOf(config))
        val arguments = """{"source_server":"remote","source_path":"/source","destination_server":"remote","destination_path":"/target"}"""
        var confirmations = 0
        val denied = executeShellFileTransfer(arguments, ctx, null) { _, _ -> confirmations++; false }
        assertTrue(denied.contains("denied_by_user"))
        assertEquals(1, confirmations)
        val invalid = executeShellFileTransfer(arguments.dropLast(1) + ",\"overwrite\":\"true\"}", ctx, null) { _, _ ->
            fail("Invalid argument reached confirmation"); true
        }
        assertTrue(invalid.contains("overwrite must be a boolean"))
    }

    @Test fun sameConchServerCopiesAndOversizedReadsAndTamperedReceiptsFail() = runBlocking {
        val root = temporary.newFolder()
        val local = local(root)
        File(root, "source").writeBytes(byteArrayOf(1, 2, 3))
        Fixture(byteArrayOf(1, 2, 3)).use { fixture ->
            val unchanged = Json.parseToJsonElement(transferShellFile(fixture.endpoint, "/source", fixture.endpoint, "/source", false)) as JsonObject
            assertEquals("unchanged", unchanged.transferString("status"))
            assertEquals(0, fixture.beginCalls)
            transferShellFile(fixture.endpoint, "/source", fixture.endpoint, "/copy", false)
            assertArrayEquals(byteArrayOf(1, 2, 3), fixture.published)
        }
        Fixture(byteArrayOf(1, 2, 3), oversizedRead = true).use { fixture ->
            try {
                transferShellFile(fixture.endpoint, "/source", local, "/target", false)
                fail("Oversized source chunk accepted")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("Oversized"))
            }
            assertFalse(File(root, "target").exists())
        }
        for ((badHash, badID) in listOf(true to false, false to true)) {
            Fixture(byteArrayOf(1, 2, 3), badReceipt = badHash, badReceiptID = badID).use { fixture ->
                try {
                    transferShellFile(local, "/source", fixture.endpoint, "/target", false)
                    fail("Invalid destination receipt accepted")
                } catch (expected: IllegalStateException) {
                    assertTrue(expected.message.orEmpty().contains("receipt"))
                }
            }
        }
    }

    @Test fun cancellationDuringUploadAbortsUnpublishedStaging() = runBlocking {
        val root = temporary.newFolder()
        val endpoint = local(root)
        File(root, "source").writeBytes(ByteArray(TRANSFER_CHUNK_BYTES + 1) { it.toByte() })
        val transferJob = Job()
        val streamScope = StreamScope()
        Fixture(File(root, "source").readBytes(), afterChunk = {
            transferJob.cancel()
            streamScope.cancelAll()
        }).use { fixture ->
            try {
                withContext(transferJob) {
                    HttpClient.withStreamScope(streamScope) {
                        transferShellFile(endpoint, "/source", fixture.endpoint, "/target", false)
                    }
                }
                fail("Cancelled transfer completed")
            } catch (_: CancellationException) { }
            assertNull(fixture.published)
            assertEquals(1, fixture.abortCalls)
            assertFalse(root.listFiles()!!.any { it.name.startsWith("agora-transfer-") })
        }
    }

    private class Fixture(
        val source: ByteArray,
        val drift: Boolean = false,
        val oversizedRead: Boolean = false,
        val badReceipt: Boolean = false,
        val badReceiptID: Boolean = false,
        val afterChunk: (() -> Unit)? = null,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint: FileTransferEndpoint
        var published: ByteArray? = null
        var beginCalls = 0
        var chunkCalls = 0
        var commitCalls = 0
        var abortCalls = 0
        private var staged = byteArrayOf()
        private var id = ""
        private var path = ""
        private var state = "writing"
        init {
            server.createContext("/file/transfer/") { exchange ->
                val args = Json.parseToJsonElement(exchange.requestBody.use { String(it.readBytes()) }) as JsonObject
                val action = exchange.requestURI.path.substringAfterLast('/')
                var failResponse = false
                val result = buildJsonObject {
                    put("ok", true)
                    when (action) {
                        "capabilities" -> { put("chunk_bytes", TRANSFER_CHUNK_BYTES); put("max_bytes", TRANSFER_MAX_BYTES) }
                        "info" -> { put("path", "/source"); put("size", source.size); put("sha256", shellFileSha256(source)) }
                        "read" -> {
                            val offset = args.transferLong("offset").toInt()
                            val data = source.copyOfRange(offset, minOf(source.size, offset + TRANSFER_CHUNK_BYTES))
                            if (drift && data.isNotEmpty()) data[0] = (data[0].toInt() xor 1).toByte()
                            val encoded = if (oversizedRead) "A".repeat(((TRANSFER_CHUNK_BYTES + 2) / 3) * 4 + 1)
                                else Base64.getEncoder().encodeToString(data)
                            put("offset", offset); put("size", source.size); put("data", encoded)
                        }
                        else -> {
                            when (action) {
                                "begin" -> { beginCalls++; id = args.transferString("id"); path = args.transferString("path"); staged = byteArrayOf(); state = "writing" }
                                "chunk" -> {
                                    chunkCalls++
                                    check(args.transferLong("offset") == staged.size.toLong())
                                    staged += Base64.getDecoder().decode(args.transferString("data"))
                                    afterChunk?.invoke()
                                    failResponse = true
                                }
                                "commit" -> { commitCalls++; state = "committed"; published = staged; failResponse = true }
                                "abort" -> { abortCalls++; if (state != "committed") { state = "aborted"; staged = byteArrayOf() } }
                                "status" -> Unit
                                else -> error("Unexpected fixture action $action")
                            }
                            put("id", if (badReceiptID && state == "committed") "invalid-id" else id)
                            put("path", path); put("size", source.size)
                            put("sha256", if (badReceipt && state == "committed") "0".repeat(64) else shellFileSha256(source))
                            put("offset", staged.size); put("state", state)
                        }
                    }
                }.toString().toByteArray()
                // An unsigned response failure after applying a mutation cannot authorize replay.
                exchange.sendResponseHeaders(if (failResponse) 500 else 200, result.size.toLong())
                exchange.responseBody.use { it.write(result) }
            }
            server.start()
            endpoint = FileTransferEndpoint("fixture-${server.address.port}", client = ShellClient("http://127.0.0.1:${server.address.port}", ""))
        }
        override fun close() { server.stop(0) }
    }
}
