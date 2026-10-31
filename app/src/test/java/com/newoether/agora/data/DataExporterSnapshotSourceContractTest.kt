package com.newoether.agora.data
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class DataExporterSnapshotSourceContractTest {
    @Test
    fun independentDeferredReaderStreamsTheConversationGraphWithPagedMessages() {
        val exporter = sourceFile(
            "app/src/main/java/com/newoether/agora/data/DataExporter.kt",
        )
        val reader = sourceFile(
            "app/src/main/java/com/newoether/agora/data/ConversationExportSnapshotReader.kt",
        )
        val capture = section(
            exporter,
            "private suspend fun captureConversationSnapshot(",
            "private suspend fun writeConversationArchive(",
        )
        val snapshot = sourceFile("app/src/main/java/com/newoether/agora/data/ChatDatabaseReadSnapshot.kt")
        val transaction = reader.indexOf("withChatDatabaseReadSnapshot(context, background = true)")
        val conversations = reader.indexOf("snapshotDao.getAllConversationsList()")
        val runs = reader.indexOf("snapshotDao.getRunsForConversationSnapshot(conversation.id)")
        val messages = reader.indexOf("snapshotDao.getConversationMessagesPage(")
        val loops = reader.indexOf("snapshotDao.getLoopsForConversationSnapshot(conversation.id)")
        val tasks = reader.indexOf("snapshotDao.getAllTasksList()")
        assertTrue(transaction >= 0)
        assertTrue(snapshot.contains("queryExecutor = executor"))
        assertTrue(snapshot.contains("transactionExecutor = executor"))
        assertTrue(snapshot.contains("reader.useReaderConnection"))
        assertTrue(snapshot.contains("connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED)"))
        assertTrue(snapshot.contains("database?.close()"))
        assertTrue(snapshot.contains("executor.shutdown()"))
        assertTrue(conversations > transaction)
        assertTrue(runs > conversations)
        assertTrue(messages > runs)
        assertTrue(loops > messages)
        assertTrue(tasks > loops)
        assertTrue(capture.contains("conversationSettings = conversationSettings[conversation.id]"))
        assertTrue(capture.contains("draftAttachments = conversation.draftAttachments"))
        assertTrue(capture.contains("images = message.images"))
        assertTrue(capture.contains("toolCallJson = message.toolCallJson"))
        assertTrue(capture.contains("attachmentMeta = message.attachmentMeta"))
        assertTrue(reader.contains("onRecord(SnapshotRecord.Message(message))"))
        assertTrue(reader.contains("afterMessageId = page.last().id"))
        assertTrue(reader.contains("if (page.size < MESSAGE_PAGE_SIZE) break"))
        assertFalse(exporter.contains("database.withTransaction"))
        assertFalse(exporter.contains("private val database: ChatDatabase"))
        assertFalse(exporter.contains("private val chatDao: ChatDao"))
    }
    @Test
    fun destinationMediaAndFinalArchiveIoStartAfterTheRoomSnapshotReturns() {
        val exporter = sourceFile(
            "app/src/main/java/com/newoether/agora/data/DataExporter.kt",
        )
        val capture = section(
            exporter,
            "private suspend fun captureConversationSnapshot(",
            "private suspend fun writeConversationArchive(",
        )
        assertFalse(capture.contains("openOutputStream("))
        assertFalse(capture.contains("ZipOutputStream("))
        assertFalse(capture.contains("openImageStream("))
        assertFalse(capture.contains("copyStreamToZipEntry("))
        val export = exporter.substringAfter("suspend fun export(")
        val captureCall = export.indexOf(
            "captureConversationSnapshot(settingsManager.conversationSettings.first(), baseline)",
        )
        val destinationOpen = export.indexOf("context.contentResolver.openOutputStream(uri)")
        val archiveWrite = export.indexOf("writeConversationArchive(")
        assertTrue(captureCall >= 0)
        assertTrue(destinationOpen > captureCall)
        assertTrue(archiveWrite > destinationOpen)
    }
    @Test
    fun archiveWriterStreamsOneSliceAtATimeAndCleanupCoversFailureAndCancellation() {
        val exporter = sourceFile(
            "app/src/main/java/com/newoether/agora/data/DataExporter.kt",
        )
        val reader = sourceFile(
            "app/src/main/java/com/newoether/agora/data/ConversationExportSnapshotReader.kt",
        )
        val archiveWriter = section(
            exporter,
            "private suspend fun writeConversationArchive(",
            "suspend fun export(",
        )
        assertFalse(archiveWriter.contains("chatDao."))
        val videoCopy = archiveWriter.indexOf(
            "copySource(source, NativeBackupFormat.VIDEO_MEDIA_PREFIX, null)",
        )
        val metadataRewrite = archiveWriter.indexOf("rewriteAttachmentMetaForExport(")
        assertTrue(videoCopy >= 0 && metadataRewrite > videoCopy)
        // The whole-database JsonObject lists that caused the export OOM must stay gone.
        assertFalse(archiveWriter.contains("mutableListOf<kotlinx.serialization.json.JsonObject>"))
        assertFalse(archiveWriter.contains("Json.parseToJsonElement"))
        assertFalse(exporter.contains("getMessageAttachmentReferencesPage("))
        assertFalse(exporter.contains("getMessageToolMediaReferencesPage("))
        assertFalse(exporter.contains("getRunsForConversation("))
        val capture = section(
            exporter,
            "private suspend fun captureConversationSnapshot(",
            "private suspend fun writeConversationArchive(",
        )
        assertTrue(capture.contains("catch (error: Throwable)"))
        assertTrue(capture.contains("spool.delete()\n            throw error"))
        val cleanup = exporter.substringAfterLast("finally {").substringBefore("\n        }")
        assertTrue(cleanup.contains("baseline?.close()"))
        assertTrue(cleanup.contains("conversationSpool?.delete()"))
        assertTrue(
            Regex("currentCoroutineContext\\(\\)\\.ensureActive\\(\\)")
                .findAll(exporter + reader)
                .count() >= 4,
        )
    }
    @Test
    fun spoolIndexIsSliceMetadataOnlyAndSizedByRecordCount() {
        val spool = sourceFile(
            "app/src/main/java/com/newoether/agora/data/ExportSpool.kt",
        )
        assertTrue(spool.contains("class ExportSpoolIndex"))
        assertTrue(spool.contains("class ExportSpoolWriter"))
        assertTrue(spool.contains("class ExportSpoolReader"))
        assertTrue(spool.contains("internal data class ExportSpoolSlice"))
        assertFalse(spool.contains("mutableListOf<MessageEntity>"))
        assertFalse(spool.contains("readLines()"))
        assertTrue(spool.contains("writer.write('\\n'.code)"))
    }
    @Test
    fun manualAndAutomaticExportsDoNotReceiveTheProcessDatabase() {
        val exporter = sourceFile(
            "app/src/main/java/com/newoether/agora/data/DataExporter.kt",
        )
        val manager = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/ImportExportManager.kt",
        )
        val backup = sourceFile(
            "app/src/main/java/com/newoether/agora/data/AutoBackupManager.kt",
        )
        val container = sourceFile(
            "app/src/main/java/com/newoether/agora/di/AppContainer.kt",
        )
        assertFalse(exporter.contains("private val database: ChatDatabase"))
        assertFalse(exporter.contains("private val chatDao: ChatDao"))
        assertTrue(Regex("DataExporter\\(\\s*app,\\s*settingsManager,").containsMatchIn(manager))
        assertFalse(backup.contains("private val database: ChatDatabase"))
        assertFalse(backup.contains("private val chatDao: ChatDao"))
        assertTrue(Regex("DataExporter\\(\\s*context,\\s*settingsManager,").containsMatchIn(backup))
        assertTrue(
            container.contains(
                "AutoBackupManager(appContext, settingsManager, memoryManager, skillManager)",
            ),
        )
    }
    @Test
    fun contractDefinesTheSnapshotAndTransactionIoBoundary() {
        val contract = sourceFile("development/import-export.md")
        assertTrue(contract.contains("temporary typed JSONL spool"))
        assertTrue(contract.contains("independent `ChatDatabase` instance"))
        assertTrue(Regex("one DEFERRED read\\s+transaction").containsMatchIn(contract))
        assertTrue(
            Regex("never\\s+occupies the process Room transaction executor")
                .containsMatchIn(contract),
        )
        assertTrue(contract.contains("The read transaction performs no destination, ZIP, or media I/O."))
        assertTrue(contract.contains("The spool is deleted on success, failure, and coroutine cancellation."))
        assertTrue(contract.contains("byte-range slice"))
        assertTrue(contract.contains("one conversation at a time"))
    }
    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue("Missing source section start: $start", startIndex >= 0)
        assertTrue("Missing source section end: $end", endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }
    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::isFile)?.let {
                return it.readText().replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
