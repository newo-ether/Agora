package com.newoether.agora.data

import android.app.Application
import com.newoether.agora.data.local.ConversationAttachmentDirectory
import com.newoether.agora.data.local.ConversationEmbeddingBytes
import com.newoether.agora.data.local.ConversationImageSources
import com.newoether.agora.data.local.ConversationMessageBytes
import com.newoether.agora.data.local.ConversationOwnership
import com.newoether.agora.data.local.StorageUsageDao
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric backs android.util.Log, which the cache logs through when it drops a bad file.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StorageUsageCalculatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun subagentsRollIntoTheirRootAndSharedImagesCountOncePerConversation() = runBlocking {
        val files = temporary.newFolder("files")
        val image = File(files, "img_a").apply { writeBytes(ByteArray(100)) }
        val managed = File(files, "sandbox-home/attachments/dir_1").apply { mkdirs() }
        File(managed, "doc.pdf").writeBytes(ByteArray(40))
        val dao = FakeDao(
            owners = mutableListOf(
                owner("root"),
                owner("child", parent = "root"),
                owner("grandchild", parent = "child"),
                owner("other", task = "task-1"),
            ),
            messages = mutableListOf(
                ConversationMessageBytes("root", 2, 10, 5, 1),
                ConversationMessageBytes("child", 1, 20, 0, 0),
                ConversationMessageBytes("grandchild", 1, 30, 0, 0),
                ConversationMessageBytes("other", 1, 1, 0, 0),
                ConversationMessageBytes("missing", 9, 999, 0, 0),
            ),
            embeddings = listOf(ConversationEmbeddingBytes("child", 7)),
            images = listOf(
                ConversationImageSources("root", listOf(image.path)),
                ConversationImageSources("child", listOf("file://${image.path}", "content://x/y")),
            ),
            directories = listOf(ConversationAttachmentDirectory("grandchild", "dir_1")),
        )
        val report = StorageUsageCalculator(files, temporary.newFolder("cache"), File(files, "db"), dao).compute()

        assertEquals(listOf("root", "other"), report.conversations.map { it.id })
        val root = report.conversations.first()
        assertEquals(4, root.messageCount)
        assertEquals(60L, root.textBytes)
        assertEquals(7L, root.embeddingBytes)
        assertEquals(140L, root.attachmentBytes)
        assertEquals(true, report.conversations.last().isTaskExecution)
        assertEquals(140L, report.breakdown.attachmentBytes)
    }

    @Test
    fun speechModelsCountAsLocalModels() = runBlocking {
        val files = temporary.newFolder("files")
        File(files, "chat_model_a.gguf").writeBytes(ByteArray(40))
        File(files, "speech-models/sense-voice").apply { mkdirs() }
            .let { File(it, "model.int8.onnx").writeBytes(ByteArray(300)) }
        val breakdown = StorageUsageCalculator(files, temporary.newFolder("cache"), File(files, "db"), FakeDao(mutableListOf()))
            .computeBreakdown()
        assertEquals(340L, breakdown.localModelBytes)
    }

    @Test
    fun parentCyclesTerminate() = runBlocking {
        val files = temporary.newFolder("files")
        val dao = FakeDao(owners = mutableListOf(owner("a", parent = "b"), owner("b", parent = "a")))
        val report = StorageUsageCalculator(files, temporary.newFolder("cache"), File(files, "db"), dao).compute()
        // Each member of a cycle resolves to itself, so nothing is dropped or double counted.
        assertEquals(setOf("a", "b"), report.conversations.map { it.id }.toSet())
    }

    @Test
    fun unchangedConversationsReuseCachedBytesAndChangedOnesRescan() = runBlocking {
        val files = temporary.newFolder("files")
        val cacheFile = File(temporary.newFolder("nobackup"), "storage-usage.json")
        val dao = FakeDao(
            owners = mutableListOf(owner("a", changedAt = 1), owner("b", changedAt = 1)),
            messages = mutableListOf(
                ConversationMessageBytes("a", 1, 10, 0, 0),
                ConversationMessageBytes("b", 1, 20, 0, 0),
            ),
        )
        fun calculator() = StorageUsageCalculator(
            files, temporary.root, File(files, "db"), dao, StorageUsageCache(cacheFile),
        )

        assertNull(calculator().cachedReport())
        calculator().compute()
        assertEquals(listOf(setOf("a", "b")), dao.scanned)

        // Rows change behind the cache; only "b" advances its watermark.
        dao.messages.replaceAll { it.copy(textBytes = it.textBytes + 100) }
        dao.owners[1] = owner("b", changedAt = 2)
        dao.scanned.clear()
        val report = calculator().compute()

        assertEquals(listOf(setOf("b")), dao.scanned)
        val bytes = report.conversations.associate { it.id to it.textBytes }
        assertEquals(10L, bytes["a"])
        assertEquals(120L, bytes["b"])
        assertEquals(bytes, calculator().cachedReport()?.conversations?.associate { it.id to it.textBytes })
    }

    @Test
    fun unreadableCacheFallsBackToAFullScan() = runBlocking {
        val files = temporary.newFolder("files")
        val cacheFile = File(temporary.newFolder("nobackup"), "storage-usage.json").apply { writeText("{not json") }
        val dao = FakeDao(
            owners = mutableListOf(owner("a")),
            messages = mutableListOf(ConversationMessageBytes("a", 1, 10, 0, 0)),
        )
        val calculator = StorageUsageCalculator(files, temporary.root, File(files, "db"), dao, StorageUsageCache(cacheFile))

        assertNull(calculator.cachedReport())
        assertEquals(10L, calculator.compute().conversations.single().textBytes)
        assertEquals(listOf(setOf("a")), dao.scanned)
    }

    private fun owner(id: String, parent: String? = null, task: String? = null, changedAt: Long = 0L) =
        ConversationOwnership(id, id, parent, task, 0L, changedAt)

    private class FakeDao(
        val owners: MutableList<ConversationOwnership>,
        val messages: MutableList<ConversationMessageBytes> = mutableListOf(),
        private val embeddings: List<ConversationEmbeddingBytes> = emptyList(),
        private val images: List<ConversationImageSources> = emptyList(),
        private val directories: List<ConversationAttachmentDirectory> = emptyList(),
    ) : StorageUsageDao {
        /** The ID batches passed to [messageBytes], in call order. */
        val scanned = mutableListOf<Set<String>>()

        override suspend fun messageBytes(conversationIds: List<String>): List<ConversationMessageBytes> {
            scanned += conversationIds.toSet()
            return messages.filter { it.conversationId in conversationIds }
        }

        override suspend fun embeddingBytes() = embeddings
        override suspend fun imageSources(conversationIds: List<String>) =
            images.filter { it.conversationId in conversationIds }
        override suspend fun attachmentDirectories() = directories
        override suspend fun conversations() = owners.toList()
    }
}
