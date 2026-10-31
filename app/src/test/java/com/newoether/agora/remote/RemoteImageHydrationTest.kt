package com.newoether.agora.remote

import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MarkdownImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteImageHydrationTest {
    private val record = RemoteMessage("image", "turn", null, "assistant", "", 1,
        activity = RemoteActivity("tool", "view_image", imagePath = "C:/native/image.png"), groupId = "group")
    private fun snapshot() = RemoteState(deviceId = "device",
        session = RemoteSession("session", "Task", "", 1), hydrationEnabled = true,
        messageGroups = projectRemoteTopology(listOf(RemoteMessageNode("image", "turn", null, "assistant",
            1, "a".repeat(64), 0, groupId = "group", activity = RemoteNodeActivity("tool", hasImage = true))), null))

    @Test fun toolImageLoadsOnlyOnExplicitPreviewRequest() = runTest {
        val file = File.createTempFile("filo-image-", ".png")
        try {
            val state = MutableStateFlow(snapshot())
            var imageReads = 0
            val attachment = ToolImageAttachment(file.path, "image/png", 128, 128, 64)
            val hydration = RemoteMessageHydration(state, { _, _ -> RemoteConversationPage(listOf(record), null, emptyList(), nodes = state.value.messageGroups.single().nodes) }, { throw it },
                { owner, request ->
                    assertEquals(state.value.owner, owner); assertEquals("image", request.id)
                    imageReads++; attachment
                })
            val before = state.value.messageGroups
            val owner = state.value.owner!!
            val searched = hydration.loadMessages(owner, listOf("group")).single().segments!!.single()
            assertTrue(searched.toolImages.isEmpty())
            assertEquals("a".repeat(64), searched.toolImageRequestKey)
            assertEquals(0, imageReads)
            val shown = hydration.observeMessage(owner, "group").filterNotNull().first()
            assertTrue(shown.segments!!.single().toolImages.isEmpty())
            assertEquals(0, imageReads)
            assertEquals(attachment, hydration.loadToolImage(owner, "image", "a".repeat(64)))
            assertEquals(1, imageReads)
            assertEquals(before, state.value.messageGroups)
            val answer = RemoteMessage("answer", "turn", null, "assistant", "next", 2, groupId = "group")
            val nodes = before.single().nodes + RemoteMessageNode("answer", "turn", null, "assistant",
                2, "b".repeat(64), 4, groupId = "group")
            val groups = projectRemoteTopology(nodes, null)
            hydration.accept(owner, RemoteConversationPage(listOf(record, answer), null, emptyList(), nodes = nodes), groups)
            state.value = state.value.copy(messageGroups = groups)
            val cached = hydration.cachedMessage(owner, groups.single())!!.segments!!.first()
            assertTrue(cached.toolImages.isEmpty())
            assertEquals("a".repeat(64), cached.toolImageRequestKey)
            assertEquals(1, imageReads)
        } finally { file.delete() }
    }

    @Test fun collapsedToolObservationDoesNotDownloadItsImage() = runTest {
        val state = MutableStateFlow(snapshot())
        var imageReads = 0
        val hydration = RemoteMessageHydration(state,
            { _, _ -> RemoteConversationPage(listOf(record), null, emptyList(), nodes = state.value.messageGroups.single().nodes) },
            { throw it }, { _, _ ->
                imageReads++
                ToolImageAttachment("/private/tool.png", "image/png", 128)
            })
        val message = hydration.observeMessage(state.value.owner!!, "group").filterNotNull().first()
        assertEquals("view_image", message.segments!!.single().toolName)
        assertEquals(0, imageReads)
        assertTrue(message.segments.single().toolImages.isEmpty())
    }

    @Test fun inlinePicturesPreserveMarkdownAndUseSeparateIndexedImagesWithoutSearchDownloads() = runTest {
        val file = File.createTempFile("filo-inline-", ".png")
        try {
            val link = "C:/native/picture.png"
            val text = "Picture:\n![caption]($link)"
            val node = RemoteMessageNode("answer", "turn", null, "assistant", 1, "a".repeat(64),
                text.length, groupId = "group", imageCount = 1)
            val message = RemoteMessage("answer", "turn", null, "assistant", text, 1,
                groupId = "group", imageLinks = listOf(link))
            val state = MutableStateFlow(snapshot().copy(messageGroups = projectRemoteTopology(listOf(node), null)))
            val attachment = ToolImageAttachment(file.path, "image/png", 128, 128, 64)
            var reads = 0
            val hydration = RemoteMessageHydration(state,
                { _, _ -> RemoteConversationPage(listOf(message), null, emptyList(), nodes = listOf(node)) },
                { throw it }, { _, request -> assertEquals(0, request.imageIndex); reads++; attachment })
            val owner = state.value.owner!!
            assertEquals(text, hydration.loadMessages(owner, listOf("group")).single().text)
            assertEquals(0, reads)
            val shown = hydration.observeMessage(owner, "group").filterNotNull().first { it.markdownImages[link]?.attachment != null }
            assertEquals(text, shown.text)
            assertEquals(mapOf(link to MarkdownImage(attachment)), shown.markdownImages)
            assertEquals(1, reads)
            assertEquals(shown, hydration.observeMessage(owner, "group").filterNotNull().first())
            assertEquals(1, reads)
            hydration.accept(owner, RemoteConversationPage(listOf(message), null, emptyList(), nodes = listOf(node)),
                state.value.messageGroups)
            assertEquals(shown.markdownImages, hydration.cachedMessage(owner, state.value.messageGroups.single())!!.markdownImages)
        } finally { file.delete() }
    }

    @Test fun inlineSlotsExistBeforeDownloadAndEachImageSettlesWithoutWaitingForItsNeighbor() = runTest {
        val links = listOf("C:/first.png", "C:/second.png")
        val text = links.joinToString("\n") { "![image]($it)" }
        val node = RemoteMessageNode("answer", "turn", null, "assistant", 1, "a".repeat(64),
            text.length, groupId = "group", imageCount = 2)
        val message = RemoteMessage("answer", "turn", null, "assistant", text, 1,
            groupId = "group", imageLinks = links)
        val state = MutableStateFlow(snapshot().copy(messageGroups = projectRemoteTopology(listOf(node), null)))
        val before = state.value.messageGroups
        val gates = List(2) { CompletableDeferred<ToolImageAttachment>() }
        val seen = Channel<ChatMessage>(Channel.UNLIMITED)
        var failures = 0
        val hydration = RemoteMessageHydration(state,
            { _, _ -> RemoteConversationPage(listOf(message), null, emptyList(), nodes = listOf(node)) },
            { failures++ }, { _, request -> gates[request.imageIndex!!].await() })
        val owner = state.value.owner!!
        val observation = backgroundScope.launch {
            hydration.observeMessage(owner, "group").filterNotNull().collect { seen.send(it) }
        }
        suspend fun next(predicate: (ChatMessage) -> Boolean): ChatMessage {
            while (true) { val value = seen.receive(); if (predicate(value)) return value }
        }
        val pending = next { it.markdownImages.size == 2 }
        assertTrue(pending.markdownImages.values.all { it.attachment == null && !it.failed })
        val first = ToolImageAttachment("/private/first.png", "image/png", 128, 128, 64)
        gates[0].complete(first)
        val partial = next { it.markdownImages[links[0]]?.attachment != null }
        assertEquals(MarkdownImage(first), partial.markdownImages[links[0]])
        assertEquals(MarkdownImage(), partial.markdownImages[links[1]])
        gates[1].completeExceptionally(java.io.IOException("Unavailable image"))
        val settled = next { it.markdownImages[links[1]]?.failed == true }
        assertEquals(text, settled.text)
        assertEquals(partial.markdownImages.keys, settled.markdownImages.keys)
        assertEquals(MarkdownImage(first), settled.markdownImages[links[0]])
        assertEquals(1, failures)
        assertEquals(before, state.value.messageGroups)
        assertEquals(settled.markdownImages, hydration.cachedMessage(owner, before.single())!!.markdownImages)
        observation.cancel()
    }

    @Test fun lateInlineImageCannotReplaceTheNewSelectionAfterCancellation() = runTest {
        val link = "C:/late.png"
        val message = RemoteMessage("answer", "turn", null, "assistant", "![image]($link)", 1,
            groupId = "group", imageLinks = listOf(link))
        val node = RemoteMessageNode("answer", "turn", null, "assistant", 1, "a".repeat(64),
            message.text.length, groupId = "group", imageCount = 1)
        val state = MutableStateFlow(snapshot().copy(messageGroups = projectRemoteTopology(listOf(node), null)))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<ToolImageAttachment>()
        val seen = Channel<ChatMessage?>(Channel.UNLIMITED)
        val hydration = RemoteMessageHydration(state,
            { _, _ -> RemoteConversationPage(listOf(message), null, emptyList(), nodes = listOf(node)) },
            { throw it }, { _, _ -> entered.complete(Unit); withContext(NonCancellable) { gate.await() } })
        val owner = state.value.owner!!
        val observation = backgroundScope.launch { hydration.observeMessage(owner, "group").collect { seen.send(it) } }
        entered.await()
        state.value = state.value.copy(session = RemoteSession("new", "Other", "", 2), messageGroups = emptyList())
        testScheduler.runCurrent()
        gate.complete(ToolImageAttachment("/private/late.png", "image/png", 128))
        while (true) {
            val value = seen.receive() ?: break
            assertTrue(value.markdownImages.values.all { it.attachment == null })
        }
        assertNull(hydration.cachedMessage(state.value.owner!!, projectRemoteTopology(listOf(node), null).single()))
        observation.cancel()
    }

    @Test fun missingImageDoesNotDiscardToolCardOrConversationTopology() = runTest {
        val state = MutableStateFlow(snapshot())
        var failures = 0
        val hydration = RemoteMessageHydration(state, { _, _ -> RemoteConversationPage(listOf(record), null, emptyList(), nodes = state.value.messageGroups.single().nodes) }, { failures++ },
            { _, _ -> throw java.io.IOException("Missing image") })
        val message = hydration.observeMessage(state.value.owner!!, "group").filterNotNull().first()
        assertEquals("view_image", message.segments!!.single().toolName)
        assertEquals(0, failures)
        try {
            hydration.loadToolImage(state.value.owner!!, "image", "a".repeat(64))
            fail("Missing image should fail")
        } catch (_: java.io.IOException) { }
        assertEquals(1, failures)
        assertEquals(listOf("group"), state.value.messageGroups.map { it.stub.id })
    }

    @Test fun lateToolImageCannotCrossOwnerRevisionBoundary() = runTest {
        val state = MutableStateFlow(snapshot())
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<ToolImageAttachment>()
        val hydration = RemoteMessageHydration(state,
            { _, _ -> RemoteConversationPage(listOf(record), null, emptyList(), nodes = state.value.messageGroups.single().nodes) },
            { throw it }, { _, _ -> entered.complete(Unit); withContext(NonCancellable) { gate.await() } })
        val owner = state.value.owner!!
        val load = backgroundScope.launch {
            try {
                hydration.loadToolImage(owner, "image", "a".repeat(64))
                fail("Stale image should not publish")
            } catch (_: kotlinx.coroutines.CancellationException) { }
        }
        entered.await()
        state.value = state.value.copy(session = RemoteSession("new", "Other", "", 2), messageGroups = emptyList())
        gate.complete(ToolImageAttachment("/private/late-tool.png", "image/png", 128))
        load.join()
    }

    @Test fun disposableCacheIsBoundedAndEvictedImageCanBeFetchedAgain() = runTest {
        val directory = Files.createTempDirectory("filo-cache-").toFile()
        try {
            val cache = RemoteImageCache(directory, maxBytes = 12)
            var reads = 0
            suspend fun load(key: String) = cache.load(key) {
                reads++
                val file = File(directory, "image-" + reads).apply { writeBytes(ByteArray(8)) }
                ToolImageAttachment(file.path, "image/png", 8)
            }
            val first = load("one")
            assertEquals(first, load("one"))
            assertEquals(1, reads)
            load("two")
            assertFalse(File(first.path).exists())
            load("one")
            assertEquals(3, reads)
            assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 12)
        } finally { directory.deleteRecursively() }
    }
    @Test fun evictionUsesAccessOrderWhenFileTimesTieOrPutCurrentImageFirst() = runTest {
        val directory = Files.createTempDirectory("filo-cache-order-").toFile()
        try {
            val cache = RemoteImageCache(directory, maxBytes = 16)
            var reads = 0
            suspend fun load(key: String) = cache.load(key) {
                val file = File(directory, "image-${++reads}").apply { writeBytes(ByteArray(8)) }
                ToolImageAttachment(file.path, "image/png", 8)
            }
            val first = load("one")
            val second = load("two")
            directory.listFiles().orEmpty().forEach { assertTrue(it.setLastModified(1)) }
            assertEquals(first, load("one"))
            load("three")
            assertTrue(File(first.path).exists())
            assertFalse(File(second.path).exists())
            File(first.path).setLastModified(System.currentTimeMillis() + 60_000)
            load("four")
            assertFalse(File(first.path).exists())
            assertEquals(16, directory.listFiles().orEmpty().sumOf { it.length() }.toInt())
        } finally { directory.deleteRecursively() }
    }
}
