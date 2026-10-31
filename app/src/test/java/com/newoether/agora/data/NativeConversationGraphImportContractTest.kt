package com.newoether.agora.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.automation.LoopPolicy
import com.newoether.agora.data.DataImporter.ImportStrategy
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.semanticModelSnapshot
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.ImageArtifactProcessor
import com.newoether.agora.viewmodel.AttachmentImportProcessor
import com.newoether.agora.viewmodel.ComposerDraftController
import com.newoether.agora.viewmodel.hasCanonicalReadyArtifact
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Keep archive APIs on the declared test runtime, outside Robolectric's sandbox lookup. */
class NativeConversationGraphImportTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquirePackage("org.apache.commons.compress.")
            .build()
}

@RunWith(NativeConversationGraphImportTestRunner::class)
class NativeConversationGraphImportContractTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val importJson = Json { ignoreUnknownKeys = true }

    private val convOne = """{"id":"c1","title":"One","lastUpdated":11}"""
    private val convTwo = """{"id":"c2","title":"Two","lastUpdated":22}"""
    private val runOne = """{"id":"r1","conversationId":"c1","startedAt":5,""" +
        """"lastCheckpointAt":6,"endedAt":6,"status":"COMPLETED"}"""
    private val messageOne = """{"id":"m1","conversationId":"c1","text":"hello","timestamp":7}"""
    private val messageTwo = """{"id":"m2","conversationId":"c2","text":"deux","timestamp":8}"""
    private val loopTwo = """{"conversationId":"c2","intervalMs":${LoopPolicy.MIN_INTERVAL_MS},""" +
        """"prompt":"tick","cycleCount":0,"maxCycles":3,"active":true,"revision":1}"""
    private val loopGhost = """{"conversationId":"ghost","intervalMs":${LoopPolicy.MIN_INTERVAL_MS},""" +
        """"prompt":"tick","cycleCount":0,"maxCycles":3,"active":true,"revision":1}"""
    private val taskOne = """{"id":"t1","name":"Task","prompt":"P","cronExpr":"","createdAt":1}"""

    private class FakeEntries(entries: Map<String, String>) : NativeGraphEntrySource {
        private val data = entries.mapValues { it.value.toByteArray() }
        override fun has(name: String): Boolean = data.containsKey(name)
        override fun bytes(name: String): ByteArray? = data[name]?.copyOf()
        override fun stream(name: String): InputStream? = data[name]?.let(::ByteArrayInputStream)
    }

    private fun legacySource(): FakeEntries = FakeEntries(
        mapOf(
            NativeBackupFormat.CONVERSATIONS_ENTRY to
                """{"conversations":[$convOne,$convTwo],"runs":[$runOne],""" +
                    """"messages":[$messageOne,$messageTwo],"loops":[$loopTwo,$loopGhost],""" +
                    """"tasks":[$taskOne]}""",
        ),
    )

    private fun v5Source(): FakeEntries {
        fun item(id: String, body: String) =
            """{"conversations":[${if (id == "c1") convOne else convTwo}],$body}"""
        val index = """{"conversations":[""" +
            """{"id":"c1","dataChangedAt":11,"entry":"${NativeBackupFormat.conversationEntry("c1")}","mediaEntries":[]},""" +
            """{"id":"c2","dataChangedAt":22,"entry":"${NativeBackupFormat.conversationEntry("c2")}","mediaEntries":[]}""" +
            """]}"""
        return FakeEntries(
            mapOf(
                NativeBackupFormat.CONVERSATION_INDEX_ENTRY to index,
                NativeBackupFormat.conversationEntry("c1") to
                    item("c1", """"runs":[$runOne],"messages":[$messageOne],"loops":[]"""),
                NativeBackupFormat.conversationEntry("c2") to
                    item("c2", """"runs":[],"messages":[$messageTwo],"loops":[$loopTwo,$loopGhost]"""),
                NativeBackupFormat.TASKS_ENTRY to """{"tasks":[$taskOne]}""",
            ),
        )
    }

    private fun importer(chatDao: ChatDao): NativeConversationGraphImporter {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return NativeConversationGraphImporter(
            database = mockk<ChatDatabase>(),
            chatDao = chatDao,
            importJson = importJson,
            mediaRestorer = NativeConversationMediaRestorer(context, importJson),
            scheduleMaintenance = {},
        )
    }

    private fun emptyRestoredMedia() = NativeConversationMediaRestorer.RestoredMedia(
        archiveFiles = emptyMap(),
        legacyImagesByMessage = emptyMap(),
        legacyVideosByMessage = emptyMap(),
        createdFiles = emptyList(),
    )

    private suspend fun headersFor(
        source: FakeEntries,
        version: Int,
        strategy: ImportStrategy,
        chatDao: ChatDao = mockk(),
        existingConversationIds: List<String> = emptyList(),
        existingTaskIds: List<String> = emptyList(),
    ): NativeConversationGraphImporter.ConversationGraphHeaders {
        coEvery { chatDao.getAllConversationIds() } returns existingConversationIds
        coEvery { chatDao.getAllTaskIds() } returns existingTaskIds
        val cacheDir = tempFolder.newFolder("cache-$version-${System.nanoTime()}")
        val graphImporter = importer(chatDao)
        return NativeConversationGraphSource.open(source, version, cacheDir).use { graphSource ->
            graphSource.open().use { stream ->
                graphImporter.readConversationGraphHeaders(
                    stream = stream,
                    strategy = strategy,
                    restoredMedia = emptyRestoredMedia(),
                    resolveSystemPromptId = { it },
                )
            }
        }
    }

    @Test
    fun previewCountsTasksFromTasksEntryAndLegacyFromConversationsJson() {
        val graphImporter = importer(mockk())
        val tasks = v5Source().stream(NativeBackupFormat.TASKS_ENTRY)!!.use {
            graphImporter.countConversationGraph(it)
        }
        assertEquals(1, tasks.tasks)
        assertEquals(0, tasks.conversations)
        val legacy = legacySource().stream(NativeBackupFormat.CONVERSATIONS_ENTRY)!!.use {
            graphImporter.countConversationGraph(it)
        }
        assertEquals(2, legacy.conversations)
        assertEquals(1, legacy.tasks)
    }
    @Test
    fun v5PreviewCountsMatchMergedGraph() = runTest {
        val cacheDir = tempFolder.newFolder("cache-count")
        NativeConversationGraphSource.open(v5Source(), version = 5, cacheDir = cacheDir).use {
            graphSource ->
            graphSource.open().use { stream ->
                val counts = importer(mockk()).countConversationGraph(stream)
                assertEquals(2, counts.conversations)
                assertEquals(1, counts.tasks)
                // Raw archive counts include the ghost loop; orphan filtering happens at headers.
                assertEquals(2, counts.loops)
            }
        }
        NativeConversationGraphSource.open(legacySource(), version = 4, cacheDir = cacheDir).use {
            graphSource ->
            graphSource.open().use { stream ->
                val legacyCounts = importer(mockk()).countConversationGraph(stream)
                assertEquals(2, legacyCounts.conversations)
                assertEquals(1, legacyCounts.tasks)
                assertEquals(2, legacyCounts.loops)
            }
        }
    }

    @Test
    fun v5HeadersEqualLegacyHeadersForEquivalentGraph() = runTest {
        val legacy = headersFor(legacySource(), version = 4, strategy = ImportStrategy.MERGE)
        val v5 = headersFor(v5Source(), version = 5, strategy = ImportStrategy.MERGE)
        assertEquals(legacy.conversations, v5.conversations)
        assertEquals(listOf("c1", "c2"), v5.conversations.map { it.id })
        assertEquals(legacy.runs, v5.runs)
        assertEquals(legacy.loops, v5.loops)
        assertEquals(listOf("c2"), v5.loops.map { it.conversationId })
        assertEquals(legacy.tasks, v5.tasks)
        assertEquals(legacy.sourceRunIdsWereUnique, v5.sourceRunIdsWereUnique)
        assertEquals(legacy.availableConversationIds, v5.availableConversationIds)
    }

    @Test
    fun pinStateRestoresFromArchiveAndDefaultsToUnpinned() = runTest {
        val pinnedOne = """{"id":"c1","title":"One","lastUpdated":11,"isPinned":true}"""
        val source = FakeEntries(
            mapOf(
                NativeBackupFormat.CONVERSATIONS_ENTRY to
                    """{"conversations":[$pinnedOne,$convTwo],"runs":[],"messages":[],""" +
                        """"loops":[],"tasks":[]}""",
            ),
        )
        val headers = headersFor(source, version = 4, strategy = ImportStrategy.REPLACE)
        assertEquals(
            mapOf("c1" to true, "c2" to false),
            headers.conversations.associate { it.id to it.isPinned },
        )
    }

    @Test
    fun todoRoundTripsThroughNativePayloadAndLegacyArchivesDefaultToEmpty() = runTest {
        val items = listOf(
            com.newoether.agora.model.ConversationTodoItem("Check **literal** text", "completed"),
            com.newoether.agora.model.ConversationTodoItem("Check\nnext step", "in_progress"),
            com.newoether.agora.model.ConversationTodoItem("Pending"),
        )
        val raw = com.newoether.agora.model.ConversationTodo.encode(items)
        val payload = NativeConversationPayload(
            conversations = listOf(NativeExportChatEntity("c1", "One", 11L, todoJson = raw)),
            runs = emptyList(), messages = emptyList(), loops = emptyList(),
        )
        val source = FakeEntries(mapOf(
            NativeBackupFormat.CONVERSATIONS_ENTRY to kotlinx.serialization.json.Json.encodeToString(
                NativeConversationPayload.serializer(), payload,
            ),
        ))
        val restored = headersFor(source, version = 4, strategy = ImportStrategy.REPLACE)
        assertEquals(raw, restored.conversations.single().todoJson)
        assertEquals(items, com.newoether.agora.model.ConversationTodo.decode(restored.conversations.single().todoJson))
        val legacy = headersFor(legacySource(), version = 4, strategy = ImportStrategy.REPLACE)
        assertTrue(legacy.conversations.all { it.todoJson == null })
    }

    @Test
    fun malformedTodoFailsHeadersBeforeAnyGraphWrites() = runTest {
        val chatDao = mockk<ChatDao>()
        val invalid = kotlinx.serialization.json.Json.encodeToString(
            NativeConversationPayload.serializer(),
            NativeConversationPayload(
                listOf(NativeExportChatEntity("c1", "One", 11L,
                    todoJson = """[{"content":"Task","status":"invalid"}]""")),
                emptyList(), emptyList(), emptyList(),
            ),
        )
        val source = FakeEntries(mapOf(NativeBackupFormat.CONVERSATIONS_ENTRY to invalid))
        val error = runCatching { headersFor(source, 4, ImportStrategy.REPLACE, chatDao) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        coVerify(exactly = 0) { chatDao.upsertConversation(any()) }
    }

    @Test
    fun mergeHeadersIncludeExistingConversations() = runTest {
        val chatDao = mockk<ChatDao>()
        coEvery { chatDao.getAllConversationIds() } returns listOf("old-conv")
        coEvery { chatDao.getAllTaskIds() } returns listOf("old-task")
        val headers = headersFor(
            v5Source(),
            version = 5,
            strategy = ImportStrategy.MERGE,
            chatDao = chatDao,
            existingConversationIds = listOf("old-conv"),
            existingTaskIds = listOf("old-task"),
        )
        assertEquals(setOf("old-conv", "c1", "c2"), headers.availableConversationIds)
    }

    @Test
    fun replaceHeadersIgnoreExistingConversationsAndTasks() = runTest {
        val chatDao = mockk<ChatDao>()
        val headers = headersFor(
            v5Source(),
            version = 5,
            strategy = ImportStrategy.REPLACE,
            chatDao = chatDao,
        )
        assertEquals(setOf("c1", "c2"), headers.availableConversationIds)
        coVerify(exactly = 0) { chatDao.getAllConversationIds() }
        coVerify(exactly = 0) { chatDao.getAllTaskIds() }
    }

    @Test
    fun restoreKeepsRawBytesAndCoherentPrivateMessageMetadataToolAndDraftPaths() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageEntry = "media/images/recorded.png"
        val draftEntry = "media/drafts/source.pdf"
        val imageBytes = byteArrayOf(-119, 80, 78, 71, 0, 12, 34, -1)
        val draftBytes = "raw document bytes".encodeToByteArray()
        val media = restoreArchive(mapOf(imageEntry to imageBytes, draftEntry to draftBytes))
        val recorded = ImageArtifact(imageEntry, "image/png", imageBytes.size.toLong(), 321, 987, 1)
        val missing = recorded.copy(path = "media/images/missing.png", width = 77, height = 88)
        val metadata = AttachmentMeta(listOf(AttachmentItem(
            type = "pdf", imageIndex = 0, pageCount = 4, imageArtifacts = listOf(recorded, missing, recorded, recorded),
        )))
        val tool = MessageSegment(type = "tool", toolCallId = "call", signature = "opaque",
            toolImages = listOf(recorded, missing, missing))
        val draft = SelectedAttachment(
            uri = draftEntry, localPath = draftEntry, type = "pdf", pageCount = 4, selectedPages = setOf(1, 3),
            preRenderedPaths = listOf(imageEntry, missing.path, imageEntry, ""),
            imageArtifacts = listOf(recorded, missing, recorded, recorded),
        )
        val payload = NativeConversationPayload(
            conversations = listOf(NativeExportChatEntity("media-conversation", "Media", 1L,
                draftAttachments = importJson.encodeToString(listOf(draft)))),
            runs = listOf(NativeExportRunEntity("media-run", "media-conversation", startedAt = 1L,
                lastCheckpointAt = 2L, endedAt = 2L)),
            messages = listOf(NativeExportMessageEntity(
                id = "media-message", conversationId = "media-conversation", text = "image", participant = "USER",
                images = listOf(imageEntry, missing.path, imageEntry, ""), timestamp = 1L, runId = "media-run", runSequence = 0L,
                attachmentMeta = importJson.encodeToString(metadata), toolCallJson = importJson.encodeToString(listOf(tool)),
            )), loops = emptyList(),
        )
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        try {
            importMediaPayload(database, payload, media, 4)
            val row = checkNotNull(database.chatDao().getMessage("media-message"))
            val path = media.archiveFiles.getValue(imageEntry).absolutePath
            assertTrue(File(path).isAbsolute)
            assertTrue(File(path).canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            assertEquals(listOf(path, "", path, ""), row.images)
            assertArrayEquals(imageBytes, File(path).readBytes())
            val restoredMeta = importJson.decodeFromString<AttachmentMeta>(row.attachmentMeta!!)
            val expectedArtifacts = listOf(recorded.copy(path = path), missing.asUnavailablePlaceholder(),
                recorded.copy(path = path), recorded.asUnavailablePlaceholder())
            assertEquals(expectedArtifacts, restoredMeta.items.single().imageArtifacts)
            assertEquals(0, restoredMeta.items.single().imageIndex)
            assertEquals(4, restoredMeta.items.single().pageCount)
            assertTrue(restoredMeta.items.single().unavailable)
            val restoredTool = importJson.decodeFromString<List<MessageSegment>>(row.toolCallJson!!).single()
            assertEquals(tool.copy(toolImages = listOf(recorded.copy(path = path),
                missing.asUnavailablePlaceholder(), missing.asUnavailablePlaceholder())), restoredTool)
            assertFalse(row.toolCallJson!!.contains("/old-device/"))
            val restoredDraft = importJson.decodeFromString<List<SelectedAttachment>>(
                database.chatDao().getConversation("media-conversation")!!.draftAttachments!!,
            ).single()
            assertEquals(listOf(path, "", path, ""), restoredDraft.preRenderedPaths)
            assertEquals(setOf(1, 3), restoredDraft.selectedPages)
            assertEquals(expectedArtifacts, restoredDraft.imageArtifacts)
            assertTrue(restoredDraft.unavailable)
            assertArrayEquals(draftBytes, File(restoredDraft.localPath!!).readBytes())
        } finally {
            database.close()
            media.createdFiles.forEach { it.delete() }
        }
    }

    @Test
    fun legacyRestoreSortsNumericSlotsAndStoresAbsoluteImagePathsWithoutChangingBytes() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val media = restoreArchive(linkedMapOf(
            "images/legacy-message/10" to byteArrayOf(10, 11),
            "images/legacy-message/2" to byteArrayOf(2, 3),
        ))
        val payload = NativeConversationPayload(
            listOf(NativeExportChatEntity("legacy-conversation", "Legacy", 1L)), emptyList(),
            listOf(NativeExportMessageEntity("legacy-message", "legacy-conversation", text = "legacy",
                participant = "USER", timestamp = 1L)), emptyList(),
        )
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        try {
            importMediaPayload(database, payload, media, 3)
            val row = checkNotNull(database.chatDao().getMessage("legacy-message"))
            val expected = media.legacyImagesByMessage.getValue("legacy-message").toSortedMap().values.toList()
            assertEquals(expected, row.images)
            assertTrue(row.images.all { File(it).isAbsolute && !it.startsWith("file:") && !it.startsWith("content:") })
            assertArrayEquals(byteArrayOf(2, 3), File(row.images[0]).readBytes())
            assertArrayEquals(byteArrayOf(10, 11), File(row.images[1]).readBytes())
            val items = importJson.decodeFromString<AttachmentMeta>(row.attachmentMeta!!).items
            assertEquals(listOf(0, 1), items.map { it.imageIndex })
            assertEquals(expected, items.flatMap { it.imageArtifacts }.map { it.path })
            assertTrue(items.none { it.unavailable })
        } finally {
            database.close()
            media.createdFiles.forEach { it.delete() }
        }
    }

    @Test
    fun exporterZipRoundTripPreservesMissingAndRepeatedImageOccurrences() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = tempFolder.newFile("source.png").also { it.writeBytes(byteArrayOf(1, 4, 9, -1)) }
        val document = tempFolder.newFile("source.pdf").also { it.writeBytes("original PDF".encodeToByteArray()) }
        val missing = File(tempFolder.root, "missing.png").absolutePath
        val sources = listOf(source.absolutePath, missing, source.absolutePath, "")
        val artifacts = sources.mapIndexed { index, path ->
            ImageArtifact(path, "image/png", 4, 100 + index, 200 + index, 1)
        }
        val meta = AttachmentMeta(listOf(AttachmentItem(type = "pdf", imageIndex = 0,
            pageCount = 4, imageArtifacts = artifacts)))
        val draft = SelectedAttachment(uri = document.absolutePath, localPath = document.absolutePath, type = "pdf",
            pageCount = 4, selectedPages = setOf(1, 3), preRenderedPaths = sources, imageArtifacts = artifacts)
        val tool = MessageSegment(type = "tool", toolCallId = "export-call",
            toolImages = listOf(artifacts[0], artifacts[1], artifacts[1]))
        val conversation = NativeExportChatEntity("export-conversation", "Export", 1L,
            draftAttachments = importJson.encodeToString(listOf(draft)))
        val run = NativeExportRunEntity("export-run", conversation.id, startedAt = 1L, lastCheckpointAt = 2L, endedAt = 2L)
        val message = NativeExportMessageEntity("export-message", conversation.id, text = "pages", images = sources,
            participant = "USER", timestamp = 1L, runId = run.id, runSequence = 0L,
            attachmentMeta = importJson.encodeToString(meta), toolCallJson = importJson.encodeToString(listOf(tool)))
        val spoolFile = tempFolder.newFile("export.jsonl")
        val index = ExportSpoolWriter(spoolFile).use { writer ->
            writer.writeConversation(conversation.id, 0L, importJson.encodeToString(conversation))
            writer.writeRun(Json { encodeDefaults = true }.encodeToString(run))
            writer.writeMessage(importJson.encodeToString(message))
            writer.finish()
        }
        val archiveFile = tempFolder.newFile("export.zip")
        val exporter = DataExporter(context, mockk(), mockk(), mockk())
        val result = ZipArchiveOutputStream(archiveFile.outputStream()).use { zip ->
            writeExportSpool(exporter, zip, spoolFile, index)
        }
        assertEquals(3, result.imagesExported)
        assertEquals(5, result.missingResourceCount)
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        var media: NativeConversationMediaRestorer.RestoredMedia? = null
        try {
            NativeBackupArchive.open(archiveFile).use { archive ->
                val payload = archive.stream(NativeBackupFormat.conversationEntry(conversation.id))!!.use {
                    importJson.decodeFromString<NativeConversationPayload>(it.readBytes().decodeToString())
                }
                val archivedMessage = payload.messages.single()
                assertEquals(4, archivedMessage.images.size)
                assertEquals("", archivedMessage.images[1])
                assertEquals("", archivedMessage.images[3])
                assertEquals(archivedMessage.images[0], archivedMessage.images[2])
                val archivedMeta = importJson.decodeFromString<AttachmentMeta>(archivedMessage.attachmentMeta!!).items.single()
                assertEquals(4, archivedMeta.pageCount)
                assertEquals(archivedMessage.images, archivedMeta.imageArtifacts.map { it.path })
                assertTrue(archivedMeta.unavailable)
                assertEquals(artifacts[1].asUnavailablePlaceholder(), archivedMeta.imageArtifacts[1])
                assertFalse(importJson.encodeToString(payload).contains(tempFolder.root.absolutePath.replace("\\", "\\\\")))
                val restoredMedia = NativeConversationMediaRestorer(context, importJson).restoreConversationMedia(archive)
                media = restoredMedia
                val graphImporter = NativeConversationGraphImporter(database, database.chatDao(), importJson,
                    NativeConversationMediaRestorer(context, importJson), scheduleMaintenance = {})
                NativeConversationGraphSource.open(archive, 6, tempFolder.root).use { graph ->
                    val headers = graph.open().use {
                        graphImporter.readConversationGraphHeaders(it, ImportStrategy.REPLACE, restoredMedia) { id -> id }
                    }
                    graphImporter.importConversationGraph(graph, ImportStrategy.REPLACE, headers, restoredMedia, 6,
                        semanticModelSnapshot("", emptyList()))
                }
                val row = checkNotNull(database.chatDao().getMessage(message.id))
                assertEquals(4, row.images.size)
                assertEquals(listOf(row.images[0], "", row.images[0], ""), row.images)
                assertTrue(File(row.images[0]).isAbsolute)
                assertArrayEquals(source.readBytes(), File(row.images[0]).readBytes())
                val restoredMeta = importJson.decodeFromString<AttachmentMeta>(row.attachmentMeta!!).items.single()
                assertEquals(row.images, restoredMeta.imageArtifacts.map { it.path })
                assertEquals(artifacts.map { it.width }, restoredMeta.imageArtifacts.map { it.width })
                assertTrue(restoredMeta.unavailable)
                val restoredDraft = importJson.decodeFromString<List<SelectedAttachment>>(
                    database.chatDao().getConversation(conversation.id)!!.draftAttachments!!).single()
                assertEquals(setOf(1, 3), restoredDraft.selectedPages)
                assertEquals(listOf(restoredDraft.preRenderedPaths!![0], "", restoredDraft.preRenderedPaths!![0], ""),
                    restoredDraft.preRenderedPaths)
                assertTrue(restoredDraft.unavailable)
                assertArrayEquals(document.readBytes(), File(restoredDraft.localPath!!).readBytes())
                val restoredTool = importJson.decodeFromString<List<MessageSegment>>(row.toolCallJson!!).single()
                assertEquals(listOf(row.images[0], "", ""), restoredTool.toolImages.map { it.path })
            }
        } finally {
            database.close()
            media?.createdFiles?.forEach { it.delete() }
        }
    }

    @Test
    @Config(sdk = [35], application = Application::class)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun selectedLegacyDraftLoadMeasuresRestoredImagePagesAndFramesWithoutReencoding() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val landscape = tempFolder.newFile("legacy-landscape.png")
        val portrait = tempFolder.newFile("legacy-portrait.png")
        writeBitmap(landscape, 93, 41)
        writeBitmap(portrait, 41, 137)
        val imageEntry = "media/drafts/legacy.png"
        val pageEntry = "media/drafts/page.png"
        val pdfEntry = "media/drafts/legacy.pdf"
        val videoEntry = "media/drafts/legacy.mp4"
        val entries = linkedMapOf(
            imageEntry to landscape.readBytes(), pageEntry to portrait.readBytes(),
            pdfEntry to "completed document bytes".encodeToByteArray(),
            videoEntry to "completed video bytes".encodeToByteArray(),
        )
        val media = restoreArchive(entries)
        val originals = listOf(
            SelectedAttachment(localId = "image", uri = imageEntry, localPath = imageEntry, type = "image"),
            SelectedAttachment(
                localId = "pdf", uri = pdfEntry, localPath = pdfEntry, type = "pdf", pageCount = 8,
                selectedPages = setOf(1, 6), preRenderedPaths = listOf(pageEntry, imageEntry),
            ),
            SelectedAttachment(
                localId = "video", uri = videoEntry, localPath = videoEntry, type = "video", frameCount = 2,
                sliceIntervalMs = 500L, processedFrames = listOf(imageEntry, pageEntry),
            ),
        )
        val payload = NativeConversationPayload(
            listOf(NativeExportChatEntity("legacy-draft", "Legacy", 1L,
                draftText = "continue", draftAttachments = importJson.encodeToString(originals))),
            emptyList(), emptyList(), emptyList(),
        )
        val database = Room.inMemoryDatabaseBuilder(app, ChatDatabase::class.java).allowMainThreadQueries().build()
        try {
            importMediaPayload(database, payload, media, 4)
            val restored = checkNotNull(database.chatDao().getConversation("legacy-draft"))
            val legacy = importJson.decodeFromString<List<SelectedAttachment>>(restored.draftAttachments!!)
            assertTrue(legacy.all { !it.unavailable && it.imageArtifacts.isNotEmpty() })
            assertTrue(legacy.flatMap { it.imageArtifacts }.all { it.sizeBytes == 0L })
            val beforeFiles = app.filesDir.walkTopDown().filter(File::isFile).map(File::getAbsolutePath).toSet()
            val processor = AttachmentImportProcessor(
                app = app,
                normalizeImage = { _, _ -> error("READY image must not be normalized") },
                extractVideoFrames = { _, _, _ -> error("READY frames must not be extracted") },
                renderPdf = { _, _, _ -> error("READY pages must not be rendered") },
                renderAllPdfPages = { _, _, _, _ -> error("READY pages must not be rendered") },
            )
            for (encodeDefaults in listOf(false, true)) {
                val repository = mockk<ConversationRepository>()
                coEvery { repository.getConversation("legacy-draft") } returns restored.copy(
                    draftAttachments = Json { this.encodeDefaults = encodeDefaults }.encodeToString(legacy),
                )
                coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
                val loaded = ComposerDraftController(
                    repository, hydrateAttachment = processor::hydrateDraftAttachment,
                ).load("legacy-draft")

                assertEquals(1L, loaded.revision)
                assertTrue(loaded.attachments.all { it.importState == AttachmentImportState.READY })
                assertTrue(loaded.attachments.all { it.hasCanonicalReadyArtifact() })
                assertEquals(legacy.map { it.localPath }, loaded.attachments.map { it.localPath })
                assertEquals(setOf(1, 6), loaded.attachments[1].selectedPages)
                assertEquals(8, loaded.attachments[1].pageCount)
                assertEquals(500L, loaded.attachments[2].sliceIntervalMs)
                assertEquals(legacy[1].preRenderedPaths, loaded.attachments[1].preRenderedPaths)
                assertEquals(legacy[2].processedFrames, loaded.attachments[2].processedFrames)
                assertEquals(listOf(listOf(93), listOf(41, 93), listOf(93, 41)),
                    loaded.attachments.map { it.imageArtifacts.map(ImageArtifact::width) })
                assertEquals(listOf(listOf(41), listOf(137, 41), listOf(41, 137)),
                    loaded.attachments.map { it.imageArtifacts.map(ImageArtifact::height) })
                val artifacts = loaded.attachments.flatMap { it.imageArtifacts }
                assertTrue(artifacts.all { it.policyVersion == 0 })
                artifacts.forEach { artifact ->
                    assertEquals(ImageArtifactProcessor(app).inspect(artifact.path), artifact)
                }
                coVerify(exactly = 1) {
                    repository.updateDraft("legacy-draft", "continue", Json.encodeToString(loaded.attachments))
                }
            }
            entries.forEach { (entry, bytes) ->
                assertArrayEquals(bytes, File(media.archiveFiles.getValue(entry).absolutePath).readBytes())
            }
            assertEquals(beforeFiles, app.filesDir.walkTopDown().filter(File::isFile).map(File::getAbsolutePath).toSet())
        } finally {
            database.close()
            media.createdFiles.forEach { it.delete() }
        }
    }

    private fun writeBitmap(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun writeExportSpool(
        exporter: DataExporter,
        zip: ZipArchiveOutputStream,
        file: File,
        index: ExportSpoolIndex,
    ): DataExporter.ExportResult = suspendCoroutineUninterceptedOrReturn { continuation ->
        val spoolType = DataExporter::class.java.declaredClasses.single { it.simpleName == "ExportedSpool" }
        val spool = spoolType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(file, index, emptySet<String>())
        val write = DataExporter::class.java.declaredMethods.single { it.name == "writeConversationArchive" }
        write.isAccessible = true
        write.invoke(exporter, zip, spool, null, continuation)
    }

    private fun restoreArchive(entries: Map<String, ByteArray>): NativeConversationMediaRestorer.RestoredMedia {
        val archive = tempFolder.newFile("media-${System.nanoTime()}.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return NativeBackupArchive.open(archive).use {
            NativeConversationMediaRestorer(ApplicationProvider.getApplicationContext(), importJson)
                .restoreConversationMedia(it)
        }
    }

    private suspend fun importMediaPayload(
        database: ChatDatabase,
        payload: NativeConversationPayload,
        media: NativeConversationMediaRestorer.RestoredMedia,
        version: Int,
    ) {
        val entries = FakeEntries(mapOf(NativeBackupFormat.CONVERSATIONS_ENTRY to importJson.encodeToString(payload)))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val importer = NativeConversationGraphImporter(database, database.chatDao(), importJson,
            NativeConversationMediaRestorer(context, importJson), scheduleMaintenance = {})
        NativeConversationGraphSource.open(entries, version, tempFolder.root).use { graph ->
            val headers = graph.open().use {
                importer.readConversationGraphHeaders(it, ImportStrategy.REPLACE, media) { id -> id }
            }
            importer.importConversationGraph(graph, ImportStrategy.REPLACE, headers, media, version,
                semanticModelSnapshot("", emptyList()))
        }
    }

    @Test
    fun ghostLoopOnMissingConversationIsDroppedFromBothPaths() = runTest {
        val legacy = headersFor(legacySource(), version = 4, strategy = ImportStrategy.MERGE)
        val v5 = headersFor(v5Source(), version = 5, strategy = ImportStrategy.MERGE)
        assertEquals(1, legacy.loops.size)
        assertEquals(1, v5.loops.size)
        assertTrue(v5.loops.none { it.conversationId == "ghost" })
    }
}
