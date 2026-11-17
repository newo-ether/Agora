package com.newoether.agora.sandbox

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxJobStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retainedUtf8TailNeverSplitsCharactersAndCountsAllBytes() {
        val output = SandboxJobOutput()
        val text = "🙂汉".repeat(200_000) + "retained-tail"
        output.append(text)
        assertEquals(text.toByteArray().size.toLong(), output.totalBytes)
        assertTrue(output.truncated)
        assertTrue(output.text().toByteArray().size <= 1_048_576)
        assertFalse(output.text().contains('\uFFFD'))
        assertTrue(output.text().endsWith("retained-tail"))
    }

    @Test fun realProcessPublishesLiveOutputAndSurvivesReadAndCallerCancellation() = runBlocking {
        val directory = temporary.newFolder()
        val builder = fixtureBuilder()
        val store = SandboxJobStore(directory, Mutex(), builder)
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = parse(caller.async { store.start("running", "", 10_000, "live output") }.await())
        val id = job.string("job_id")
        caller.cancel()
        try {
            await { parse(store.get(id)).string("output").contains("ready") }
            val readScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val waiting = readScope.async { while (true) { store.get(id); delay(10) } }
            waiting.cancel()
            readScope.cancel()
            assertEquals("running", parse(store.get(id)).string("state"))
            val stopped = parse(store.stop(id))
            assertEquals("stopped", stopped.string("state"))
            assertTrue(stopped.string("output").contains("ready"))
            assertTrue(stopped.string("finished_at").isNotEmpty())
            val ack = parse(store.acknowledge(id))
            assertTrue(ack.string("acknowledged_at").isNotEmpty())
            assertEquals(stopped.string("output"), parse(store.get(id)).string("output"))
            val reopened = SandboxJobStore(directory, Mutex(), builder)
            assertEquals("stopped", parse(reopened.get(id)).string("state"))
            assertEquals(stopped.string("output"), parse(reopened.get(id)).string("output"))
        } finally { store.stop(id) }
    }

    @Test fun terminalOutputAndPaginationPersistAndRuntimeTimeoutFails() = runBlocking {
        val directory = temporary.newFolder()
        val store = SandboxJobStore(directory, Mutex(), fixtureBuilder())
        val success = parse(store.start("success", "/home/agora", 5_000, "success"))
        val successId = success.string("job_id")
        await { parse(store.get(successId)).string("state") == "succeeded" }
        assertEquals(0, parse(store.get(successId))["exit_code"]!!.jsonPrimitive.int)
        assertEquals("complete", parse(store.get(successId)).string("output"))
        val timed = parse(store.start("running", "", 100, "runtime timeout"))
        val timedId = timed.string("job_id")
        try {
            await { parse(store.get(timedId)).string("state") == "failed" }
            assertEquals("Timed out", parse(store.get(timedId)).string("error_message"))
            val page = parse(store.list(0, 1))
            assertEquals(1, page["jobs"]!!.jsonArray.size)
            assertEquals("true", page.string("has_more"))
            assertEquals(1, page["next_offset"]!!.jsonPrimitive.int)
            assertEquals(1, parse(store.list(1, 1))["jobs"]!!.jsonArray.size)
            val recovered = SandboxJobStore(directory, Mutex(), fixtureBuilder())
            assertEquals("complete", parse(recovered.get(successId)).string("output"))
        } finally { store.stop(timedId) }
    }

    @Test fun crashRecoveryInterruptsLiveRecordsWithoutReexecutingAndRetainsOutput() = runBlocking {
        val directory = temporary.newFolder()
        val id = "ce4dc322-cb2a-42ef-80e7-7d8a42f8f530"
        writeRecord(directory, id, "running", "durable partial output", identity =
            SandboxProcessIdentity(321, "99", "old"))
        var signalled: SandboxProcessIdentity? = null
        val store = SandboxJobStore(directory, Mutex(), { _, _ -> error("Recovery must not execute") },
            recoverProcess = { signalled = it })
        val recovered = parse(store.get(id))
        assertEquals("interrupted", recovered.string("state"))
        assertEquals("durable partial output", recovered.string("output"))
        assertEquals(321, signalled!!.pid)
        assertTrue(recovered.string("error_message").contains("not reexecuted"))
        val reopened = SandboxJobStore(directory, Mutex(), { _, _ -> error("Must remain terminal") })
        assertEquals("interrupted", parse(reopened.get(id)).string("state"))
    }

    @Test fun recoveryIgnoresAnOutputWrittenBeforeMetadataPublication() = runBlocking {
        for (publishedSlot in 0..1) {
            val directory = temporary.newFolder()
            val id = java.util.UUID.randomUUID().toString()
            val output = "published partial output"
            writeRecord(directory, id, "running", output)
            val metadataFile = File(directory, "$id.json")
            val metadata = parse(metadataFile.readText())
            metadataFile.writeText(buildJsonObject {
                metadata.forEach { (key, value) -> put(key, value) }
                put("outputSlot", publishedSlot)
            }.toString())
            val publishedSuffix = if (publishedSlot == 0) ".output" else ".output-next"
            val pendingSuffix = if (publishedSlot == 0) ".output-next" else ".output"
            File(directory, "$id$publishedSuffix").writeText(output)
            File(directory, "$id$pendingSuffix").writeText("unpublished longer output after a crash")
            val store = SandboxJobStore(directory, Mutex(), { _, _ -> error("Must not replay") })
            val result = parse(store.get(id))
            assertEquals("interrupted", result.string("state"))
            assertEquals(output, result.string("output"))
            assertEquals(output.toByteArray().size.toLong(), result["output_bytes"]!!.jsonPrimitive.long)
            assertEquals("false", result.string("truncated"))
        }
    }

    @Test fun selectionGateRejectsQueuedJobsAndClosesAdmissionWithoutDiscardingHistory(): Unit = runBlocking {
        val mutation = Mutex(locked = true)
        val store = SandboxJobStore(temporary.newFolder(), mutation, fixtureBuilder())
        val id = parse(store.start("success", "", 5_000, "queued before selection")).string("job_id")
        try {
            var selected = false
            assertTrue(runCatching { store.whileIdle { selected = true } }.isFailure)
            assertFalse(selected)
            store.stop(id)
            store.whileIdle {
                assertTrue(runCatching { store.start("success", "", 5_000, "during selection") }.isFailure)
                selected = true
            }
            assertTrue(selected)
            assertEquals(1, parse(store.list(0, 100))["jobs"]!!.jsonArray.size)
            assertTrue(runCatching { store.whileIdle { error("selection publication failed") } }.isFailure)
            val next = parse(store.start("success", "", 5_000, "after failed selection")).string("job_id")
            store.stop(next)
        } finally {
            mutation.unlock()
            store.stop(id)
        }
    }

    @Test fun resetStopsAndJoinsBeforeDestructionAndKeepsJobHistory() = runBlocking {
        val store = SandboxJobStore(temporary.newFolder(), Mutex(), fixtureBuilder())
        val id = parse(store.start("running", "", 10_000, "reset")).string("job_id")
        await { parse(store.get(id)).string("output").contains("ready") }
        store.reset {
            assertEquals("stopped", parse(store.get(id)).string("state"))
            assertTrue(parse(store.get(id)).string("finished_at").isNotEmpty())
            assertTrue(runCatching { store.start("success", "", 1000, "during reset") }.isFailure)
        }
        assertEquals(1, parse(store.list(0, 100))["jobs"]!!.jsonArray.size)
    }

    @Test fun cancelledStartNeverLeavesAnUnreportedLiveJobAndQueuedStopDoesNotNeedRootfsLock() = runBlocking {
        val mutation = Mutex()
        mutation.lock()
        val store = SandboxJobStore(temporary.newFolder(), mutation, fixtureBuilder())
        try {
            val id = parse(withTimeout(2_000) { store.start("running", "", 10_000, "queued") }).string("job_id")
            assertEquals("running", parse(withTimeout(2_000) { store.get(id) }).string("state"))
            assertEquals("stopped", parse(withTimeout(2_000) { store.stop(id) }).string("state"))
            val cancelledScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val start = cancelledScope.async(start = CoroutineStart.UNDISPATCHED) {
                store.start("running", "", 10_000, "cancelled")
            }
            start.cancel()
            start.join()
            cancelledScope.cancel()
            val records = parse(store.list(0, 100))["jobs"]!!.jsonArray
            assertTrue(records.all { it.jsonObject.string("state") !in setOf("running", "stopping") })
        } finally { mutation.unlock() }
    }

    @Test fun realProcessRetainsBoundedUtf8TailAcrossReopening() = runBlocking {
        val directory = temporary.newFolder()
        val store = SandboxJobStore(directory, Mutex(), fixtureBuilder())
        val id = parse(store.start("large", "", 10_000, "large output")).string("job_id")
        try {
            await { parse(store.get(id)).string("state") == "succeeded" }
            val result = parse(store.get(id))
            assertEquals(("🙂汉".repeat(200_000) + "retained-tail").toByteArray().size.toLong(),
                result["output_bytes"]!!.jsonPrimitive.long)
            assertEquals("true", result.string("truncated"))
            assertTrue(result.string("output").endsWith("retained-tail"))
            assertFalse(result.string("output").contains('\uFFFD'))
            val metadata = parse(File(directory, "$id.json").readText())
            val suffix = if (metadata["outputSlot"]!!.jsonPrimitive.int == 0) ".output" else ".output-next"
            val publishedOutput = File(directory, "$id$suffix")
            assertTrue(publishedOutput.isFile && publishedOutput.length() <= 1_048_576)
            val reopened = SandboxJobStore(directory, Mutex(), fixtureBuilder())
            assertEquals(result.string("output"), parse(reopened.get(id)).string("output"))
        } finally { store.stop(id) }
    }

    @Test fun moreThan64TerminalRecordsDoNotBlockAdmissionOrLoadOutputIntoLists() = runBlocking {
        val directory = temporary.newFolder()
        repeat(70) { index ->
            writeRecord(directory, java.util.UUID.randomUUID().toString(), "succeeded", "retained-$index")
        }
        // Listing/recovery needs metadata only, even when an output cannot currently be read.
        val unreadableId = java.util.UUID.randomUUID().toString()
        writeRecord(directory, unreadableId, "succeeded", "untouched")
        File(directory, "$unreadableId.output").apply { delete(); mkdir() }
        val store = SandboxJobStore(directory, Mutex(), fixtureBuilder())
        val page = parse(store.list(0, 100))
        assertEquals(71, page["jobs"]!!.jsonArray.size)
        assertTrue(page["jobs"]!!.jsonArray.all { "output" !in it.jsonObject })
        val id = parse(store.start("success", "", 5_000, "after retained history")).string("job_id")
        try {
            await { parse(store.get(id)).string("state") == "succeeded" }
            store.acknowledge(id)
            assertEquals(72, directory.listFiles { file -> file.extension == "json" }!!.size)
        } finally { store.stop(id) }
    }

    @Test fun persistenceFailureStopsTheProcessAndRemainsQueryable() = runBlocking {
        val directory = temporary.newFolder()
        val failCheckpoints = File(temporary.root, "fail-checkpoints")
        val store = SandboxJobStore(directory, Mutex(), fixtureBuilder())
        val id = parse(store.start("running:${failCheckpoints.absolutePath}", "", 30_000, "checkpoint failure")).string("job_id")
        try {
            await { parse(store.get(id)).string("output").contains("ready") }
            // Block the next atomic checkpoint without replacing any production implementation.
            File(directory, "$id.json.tmp").mkdir()
            File(directory, "$id.json.tmp/blocked").writeText("keep checkpoint blocked")
            failCheckpoints.writeText("resume output")
            await { parse(store.get(id)).string("state") == "failed" }
            val result = parse(store.get(id))
            assertEquals("false", result.string("durable"))
            assertTrue(result.string("persistence_error").isNotEmpty())
            assertTrue(result.string("output").contains("ready"))
            repeat(2) {
                assertTrue(runCatching { store.stop(id) }.exceptionOrNull() is java.io.IOException)
            }
            var destroyed = false
            assertTrue(runCatching { store.reset { destroyed = true } }.isFailure)
            assertFalse(destroyed)
            File(directory, "$id.json.tmp").deleteRecursively()
            store.acknowledge(id)
            Unit
        } finally { runCatching { store.stop(id) } }
    }

    @Test fun cancellingStopWaitDoesNotCancelRecoveryOrReplayACommand() = runBlocking {
        val directory = temporary.newFolder()
        val id = java.util.UUID.randomUUID().toString()
        writeRecord(directory, id, "running", "partial", SandboxProcessIdentity(321, "99", "old"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = SandboxJobStore(directory, Mutex(), { _, _ -> error("Must not replay") }, recoverProcess = {
            entered.complete(Unit)
            release.await()
        })
        val recovery = async { store.get(id) }
        entered.await()
        val observation = async { runCatching { withTimeout(100) { store.stop(id) } } }
        assertTrue(observation.await().exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        release.complete(Unit)
        assertEquals("interrupted", parse(recovery.await()).string("state"))
    }

    private fun writeRecord(
        directory: File,
        id: String,
        state: String,
        output: String,
        identity: SandboxProcessIdentity? = null,
    ) {
        File(directory, "$id.output").writeText(output)
        File(directory, "$id.json").writeText(buildJsonObject {
            put("jobId", id); put("command", "must not execute"); put("description", "retained")
            put("workdir", "/home/agora"); put("timeoutMs", 1000); put("createdAt", 1L)
            put("state", state); put("outputBytes", output.toByteArray().size)
            identity?.let { value -> put("process", buildJsonObject {
                put("pid", value.pid); put("startTicks", value.startTicks); put("bootId", value.bootId)
            }) }
        }.toString())
    }

    private fun fixtureBuilder(): (String, String) -> ProcessBuilder = { command, _ ->
        val windows = System.getProperty("os.name").startsWith("Windows")
        val java = File(System.getProperty("java.home"), if (windows) "bin/java.exe" else "bin/java")
        val source = File(temporary.root, "SandboxJobFixture.java")
        synchronized(this) {
            if (!source.exists()) source.writeText("""
            public class SandboxJobFixture {
                public static void main(String[] args) throws Exception {
                    if (args[0].equals("success")) { System.out.print("complete"); return; }
                    if (args[0].equals("large")) {
                        String text = "\uD83D\uDE42\u6C49".repeat(200000) + "retained-tail";
                        System.out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        return;
                    }
                    System.out.print("ready"); System.out.flush();
                    if (args[0].startsWith("running:")) {
                        java.io.File signal = new java.io.File(args[0].substring(8));
                        while (!signal.exists()) Thread.sleep(10);
                        for (int i = 0; i < 3000; i++) {
                            System.out.print("tick"); System.out.flush(); Thread.sleep(10);
                        }
                    } else Thread.sleep(30000);
                }
            }
            """.trimIndent())
        }
        ProcessBuilder(java.absolutePath, source.absolutePath, command)
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(20)
    }
    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.content.orEmpty()
}
