package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.ToolImageAttachment
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarkdownImageSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun image(name: String, bytes: ByteArray): ToolImageAttachment {
        val file = temporary.newFile(name).apply { writeBytes(bytes) }
        return ToolImageAttachment(file.path, "image/png", bytes.size.toLong())
    }

    @Test fun reopeningRetainsFirstBytesAndDoesNotReadTheSourceAgain() = runTest {
        val directory = temporary.newFolder("receipts")
        val bytes = byteArrayOf(0, 127, -1, 4)
        val original = image("first.png", bytes)
        val first = MarkdownImageSnapshotStore(directory).load("m:answer:0\nshell:path") { original }
        val reopened = MarkdownImageSnapshotStore(directory).load("m:answer:0\nshell:path") {
            error("A committed occurrence must never fetch again")
        }
        assertEquals(first, reopened)
        assertArrayEquals(bytes, File(reopened.image.path).readBytes())
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(hash, first.sha256)
    }

    @Test fun repeatedPathsAtDifferentPositionsRetainIndependentSnapshots() = runTest {
        val store = MarkdownImageSnapshotStore(temporary.newFolder("receipts"))
        val first = store.load("m:answer:0\nsame-path") { image("old.png", byteArrayOf(1)) }
        val second = store.load("m:answer:1\nsame-path") { image("new.png", byteArrayOf(2)) }
        assertNotEquals(first.sha256, second.sha256)
        assertArrayEquals(byteArrayOf(1), File(first.image.path).readBytes())
        assertArrayEquals(byteArrayOf(2), File(second.image.path).readBytes())
    }

    @Test fun concurrentNativeAndBrowserReadsShareTheFirstRead() = runTest {
        val directory = temporary.newFolder("receipts")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val artifact = image("shared.png", byteArrayOf(3))
        val first = async {
            MarkdownImageSnapshotStore(directory).load("m:answer:0") {
                reads++
                entered.complete(Unit)
                release.await()
                artifact
            }
        }
        entered.await()
        val second = async {
            MarkdownImageSnapshotStore(directory).load("m:answer:0") { error("Duplicate source read") }
        }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, reads)
    }

    @Test fun missingCommittedBytesFailWithoutReplacingTheSnapshot() = runTest {
        val directory = temporary.newFolder("receipts")
        val artifact = image("lost.png", byteArrayOf(4))
        MarkdownImageSnapshotStore(directory).load("m:answer:0") { artifact }
        File(artifact.path).delete()
        val error = runCatching {
            MarkdownImageSnapshotStore(directory).load("m:answer:0") { error("Must not replace lost history") }
        }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test fun cancelledPublicationDeletesItsUncommittedArtifact() = runTest {
        val directory = temporary.newFolder("receipts")
        val artifact = image("cancelled-publication.png", byteArrayOf(9))
        val cancelled = async {
            MarkdownImageSnapshotStore(directory).load("m:answer:0") {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                artifact
            }
        }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertFalse(File(artifact.path).exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancelledFirstReadCanRetryWithoutPublishingAReceipt() = runTest {
        val directory = temporary.newFolder("receipts")
        val entered = CompletableDeferred<Unit>()
        val cancelled = async {
            MarkdownImageSnapshotStore(directory).load("m:answer:0") {
                entered.complete(Unit)
                CompletableDeferred<ToolImageAttachment>().await()
            }
        }
        entered.await()
        cancelled.cancel()
        cancelled.join()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val artifact = image("retry.png", byteArrayOf(5))
        assertEquals(artifact, MarkdownImageSnapshotStore(directory).load("m:answer:0") { artifact }.image)
    }
}
