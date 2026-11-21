package com.newoether.agora.data
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
class NativeBackupV5WriterTest {
    @get:Rule
    val temp = TemporaryFolder()
    @Test
    fun streamsConversationEntriesIndexTasksAndMediaReferences() {
        val scratchDir = temp.newFolder("scratch")
        val checksums = mutableMapOf<String, Long>()
        val bytes = ByteArrayOutputStream().also { sink ->
            ZipArchiveOutputStream(sink).use { zip ->
                val one = NativeBackupV5Writer.writeConversationEntry(
                    zip = zip,
                    conversationId = "one",
                    dataChangedAt = 11L,
                    conversationJson = """{"id":"one","dataChangedAt":11}""",
                    runs = listOf("""{"id":"run-one","conversationId":"one"}""").iterator(),
                    messages = listOf(
                        """{"id":"message-one","conversationId":"one",""" +
                            """"image":"media/images/image-one.png"}""",
                    ).iterator(),
                    loops = emptyList<String>().iterator(),
                    mediaEntries = setOf("media/images/image-one.png"),
                    scratchDir = scratchDir,
                    checksums = checksums,
                )
                val two = NativeBackupV5Writer.writeConversationEntry(
                    zip = zip,
                    conversationId = "two",
                    dataChangedAt = 22L,
                    conversationJson = """{"id":"two","dataChangedAt":22}""",
                    runs = emptyList<String>().iterator(),
                    messages = emptyList<String>().iterator(),
                    loops = listOf("""{"conversationId":"two"}""").iterator(),
                    mediaEntries = emptySet(),
                    scratchDir = scratchDir,
                    checksums = checksums,
                )
                NativeBackupV5Writer.writeTasksEntry(
                    zip = zip,
                    tasks = listOf("""{"id":"task-one"}""").iterator(),
                )
                NativeBackupV5Writer.writeConversationIndex(zip, listOf(one, two))
            }
        }.toByteArray()
        val entries = readEntries(bytes)
        val oneEntry = NativeBackupFormat.conversationEntry("one")
        val twoEntry = NativeBackupFormat.conversationEntry("two")
        assertEquals(setOf(oneEntry, twoEntry), checksums.keys)
        assertTrue(scratchDir.listFiles().orEmpty().isEmpty())
        assertEquals(
            setOf(NativeBackupFormat.CONVERSATION_INDEX_ENTRY, NativeBackupFormat.TASKS_ENTRY, oneEntry, twoEntry),
            entries.keys,
        )
        assertFalse(entries.containsKey(NativeBackupFormat.CONVERSATIONS_ENTRY))
        assertEquals(
            """{"conversations":[{"id":"one","dataChangedAt":11}],"runs":[""" +
                """{"id":"run-one","conversationId":"one"}],"messages":[""" +
                """{"id":"message-one","conversationId":"one","image":"media/images/image-one.png"}"""+
                """],"loops":[]}""",
            entries.getValue(oneEntry),
        )
        assertFalse(entries.getValue(oneEntry).contains("\"two\""))
        assertTrue(entries.getValue(twoEntry).contains(""""loops":[{"conversationId":"two"}]"""))
        assertEquals(
            """{"tasks":[{"id":"task-one"}]}""",
            entries.getValue(NativeBackupFormat.TASKS_ENTRY),
        )
        val index = Json.decodeFromString<NativeConversationIndex>(
            entries.getValue(NativeBackupFormat.CONVERSATION_INDEX_ENTRY),
        )
        assertEquals(listOf("one", "two"), index.conversations.map { it.id })
        assertEquals(listOf(11L, 22L), index.conversations.map { it.dataChangedAt })
        assertEquals(listOf("media/images/image-one.png"), index.conversations.first().mediaEntries)
        assertTrue(index.conversations.last().mediaEntries.isEmpty())
    }
    @Test
    fun emptySnapshotWritesOnlyEmptyIndexAndTasks() {
        val bytes = ByteArrayOutputStream().also { sink ->
            ZipArchiveOutputStream(sink).use { zip ->
                NativeBackupV5Writer.writeTasksEntry(zip, emptyList<String>().iterator())
                NativeBackupV5Writer.writeConversationIndex(zip, emptyList())
            }
        }.toByteArray()
        val entries = readEntries(bytes)
        assertEquals(
            setOf(NativeBackupFormat.CONVERSATION_INDEX_ENTRY, NativeBackupFormat.TASKS_ENTRY),
            entries.keys,
        )
        assertEquals(
            emptyList<NativeConversationIndexEntry>(),
            Json.decodeFromString<NativeConversationIndex>(
                entries.getValue(NativeBackupFormat.CONVERSATION_INDEX_ENTRY),
            ).conversations,
        )
    }
    private fun readEntries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes().decodeToString())
                entry = zip.nextEntry
            }
        }
    }
}
