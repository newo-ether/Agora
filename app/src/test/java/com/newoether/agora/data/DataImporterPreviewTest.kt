package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import io.mockk.mockk
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Plain JVM test: Robolectric's classpath lacks the commons-compress API the archive uses, and
 * task or legacy counting needs android.util.JsonReader, so those counts are covered by
 * NativeConversationGraphImportContractTest.
 */
class DataImporterPreviewTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun importer(): DataImporter = DataImporter(
        context = mockk<Context>(relaxed = true),
        database = mockk<ChatDatabase>(),
        chatDao = mockk<ChatDao>(),
        settingsManager = mockk<SettingsManager>(),
        memoryManager = mockk<MemoryManager>(),
        skillManager = mockk<SkillManager>(),
        conversationSettingsTransfers = mockk<ConversationSettingsTransferCoordinator>(),
    )

    private fun archive(name: String, entries: Map<String, String>): NativeBackupArchive {
        val file = File(tempFolder.root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entry, body) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return NativeBackupArchive.open(file)
    }

    private fun manifest(version: Int) = """{"agora_export_version":$version}"""

    @Test
    fun v5PreviewCountsConversationsFromIndexWithoutReadingItems() {
        val one = NativeBackupFormat.conversationEntry("c1")
        val two = NativeBackupFormat.conversationEntry("c2")
        val index = """{"conversations":[""" +
            """{"id":"c1","dataChangedAt":1,"entry":"$one","mediaEntries":[]},""" +
            """{"id":"c2","dataChangedAt":2,"entry":"$two","mediaEntries":[]}]}"""
        val backup = archive(
            "v5.agora",
            mapOf(
                NativeBackupFormat.MANIFEST_ENTRY to manifest(5),
                NativeBackupFormat.CONVERSATION_INDEX_ENTRY to index,
                // Items are not valid graphs: a preview that opened them could not count them.
                one to "not-json",
                two to "not-json",
            ),
        )
        val preview = backup.use { importer().preview(it) }
        assertEquals(5, preview.manifest.version)
        assertEquals(2, preview.conversationCount)
        assertEquals(0, preview.taskCount)
        assertTrue(preview.hasConversationGraph)
    }

    @Test
    fun missingOrInvalidManifestYieldsVersionZero() {
        val missing = archive("missing.agora", mapOf("settings.json" to "{}"))
        assertEquals(0, missing.use { importer().preview(it) }.manifest.version)
        val invalid = archive("invalid.agora", mapOf(NativeBackupFormat.MANIFEST_ENTRY to "not-json"))
        assertEquals(0, invalid.use { importer().preview(it) }.manifest.version)
    }
    @Test fun templateOnlyArchiveIsImportableWithoutOtherCategories() {
        val backup = archive("templates-only.agora", mapOf(
            NativeBackupFormat.MANIFEST_ENTRY to manifest(6),
            com.newoether.agora.templates.InteractiveTemplateRepository.ARCHIVE_ENTRY to "[]",
        ))
        val preview = backup.use { importer().preview(it) }
        assertTrue(preview.templatesPresent)
        assertTrue(preview.hasImportableData)
        assertEquals(0, preview.memoryCount)
        assertEquals(0, preview.conversationCount)
        org.junit.Assert.assertFalse(DataImporter.ImportPreview(DataImporter.ImportManifest(version = 6)).hasImportableData)
    }

}
