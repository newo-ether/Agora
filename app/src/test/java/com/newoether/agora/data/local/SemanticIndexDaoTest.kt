package com.newoether.agora.data.local

import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SemanticIndexDaoTest {
    @Test
    fun sourceFingerprintUsesOnlyCanonicalEmbeddingPrefix() {
        val prefix = "a".repeat(Constants.MAX_EMBEDDING_TEXT_LENGTH)

        assertEquals(
            semanticSourceFingerprint(prefix),
            semanticSourceFingerprint(prefix + "ignored suffix"),
        )
        assertFalse(
            semanticSourceFingerprint(prefix) ==
                semanticSourceFingerprint(prefix.dropLast(1) + "b"),
        )
    }

    @Test
    fun finalMultiMessageCompletionUsesCurrentLedgerRevision() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val first = workFor("first", "first-fingerprint", revision = 5, updatedAt = 20)
        val second = workFor("second", "second-fingerprint", revision = 6, updatedAt = 21)
        val ledger = pending(revision = 6, completedRevision = 4, updatedAt = 21)
        coEvery { dao.completeExactWork(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.deleteMatchingWork(MODEL_ID, "first", "first-fingerprint", 5) } returns 1
        coEvery { dao.deleteMatchingWork(MODEL_ID, "second", "second-fingerprint", 6) } returns 1
        coEvery { dao.getLedger(MODEL_ID) } returns ledger
        coEvery { dao.markCurrentAfterExactWork(MODEL_ID, 6, 30) } returns 0
        coEvery { dao.markCurrentAfterExactWork(MODEL_ID, 6, 31) } returns 1

        assertTrue(dao.completeExactWork(first, updatedAt = 30))
        assertTrue(dao.completeExactWork(second, updatedAt = 31))

        coVerify(exactly = 2) { dao.markCurrentAfterExactWork(MODEL_ID, 6, any()) }
        coVerify(exactly = 0) { dao.markCurrentAfterExactWork(MODEL_ID, 5, any()) }
    }

    @Test
    fun admitModelCreatesNeedsReconcileLedgerWithoutScanningContent() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val ledger = needsReconcile(revision = 0, updatedAt = 10)
        coEvery { dao.admitModel(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.insertLedger(ledger) } returns 1L
        coEvery { dao.getLedger(MODEL_ID) } returns ledger

        assertEquals(ledger, dao.admitModel(MODEL_ID, updatedAt = 10))

        coVerifyOrder {
            dao.insertLedger(ledger)
            dao.getLedger(MODEL_ID)
        }
    }

    @Test
    fun reconcileInvalidationAdvancesRevisionAndClearsExactWork() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val admission = needsReconcile(revision = 0, updatedAt = 20)
        val current = current(revision = 4, updatedAt = 10)
        val invalidated = needsReconcile(revision = 5, completedRevision = 4, updatedAt = 20)
        coEvery { dao.requestReconcile(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.admitModel(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.insertLedger(admission) } returns -1L
        coEvery { dao.getLedger(MODEL_ID) } returnsMany listOf(current, invalidated)
        coEvery { dao.advanceForReconcile(MODEL_ID, 20) } returns 1
        coEvery { dao.deleteWorkForModel(MODEL_ID) } returns 3

        assertEquals(invalidated, dao.requestReconcile(MODEL_ID, updatedAt = 20))

        coVerifyOrder {
            dao.insertLedger(admission)
            dao.getLedger(MODEL_ID)
            dao.advanceForReconcile(MODEL_ID, 20)
            dao.deleteWorkForModel(MODEL_ID)
            dao.getLedger(MODEL_ID)
        }
    }

    @Test
    fun exactInvalidationPersistsNewestWorkDuringReconcile() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val admitted = needsReconcile(revision = 2, completedRevision = 1, updatedAt = 10)
        val advanced = needsReconcile(
            revision = 3,
            completedRevision = 1,
            reconcileRevision = 2,
            updatedAt = 20,
        )
        val work = work("fingerprint", revision = 3, updatedAt = 20)
        coEvery { dao.enqueueExactWork(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.admitModel(any(), any()) } coAnswers { callOriginal() }
        coEvery {
            dao.insertLedger(needsReconcile(revision = 0, updatedAt = 20))
        } returns -1L
        coEvery { dao.getLedger(MODEL_ID) } returnsMany listOf(admitted, advanced)
        coEvery { dao.advanceForExactWork(MODEL_ID, 20) } returns 1
        coEvery { dao.upsertWork(work) } returns Unit

        assertEquals(
            advanced,
            dao.enqueueExactWork(MODEL_ID, MESSAGE_ID, "fingerprint", updatedAt = 20),
        )

        coVerify(exactly = 1) { dao.upsertWork(work) }
    }

    @Test
    fun exactInvalidationUpsertsNewestFingerprintAndTombstone() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val current = current(revision = 4, updatedAt = 10)
        val firstPending = pending(revision = 5, completedRevision = 4, updatedAt = 20)
        val secondPending = pending(revision = 6, completedRevision = 4, updatedAt = 21)
        val firstWork = work("fingerprint", revision = 5, updatedAt = 20)
        val tombstone = work(null, revision = 6, updatedAt = 21)
        coEvery { dao.enqueueExactWork(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.admitModel(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.insertLedger(any()) } returns -1L
        coEvery { dao.getLedger(MODEL_ID) } returnsMany listOf(
            current,
            firstPending,
            firstPending,
            secondPending,
        )
        coEvery { dao.advanceForExactWork(MODEL_ID, 20) } returns 1
        coEvery { dao.advanceForExactWork(MODEL_ID, 21) } returns 1
        coEvery { dao.upsertWork(firstWork) } returns Unit
        coEvery { dao.upsertWork(tombstone) } returns Unit

        assertEquals(
            firstPending,
            dao.enqueueExactWork(MODEL_ID, MESSAGE_ID, "fingerprint", updatedAt = 20),
        )
        assertEquals(
            secondPending,
            dao.enqueueExactWork(MODEL_ID, MESSAGE_ID, null, updatedAt = 21),
        )

        coVerifyOrder {
            dao.upsertWork(firstWork)
            dao.upsertWork(tombstone)
        }
    }

    @Test
    fun exactCompletionRequiresMatchingRevisionAndFingerprint() = runTest {
        val dao = mockk<SemanticIndexDao>()
        val stale = work("old", revision = 4, updatedAt = 10)
        val current = work("new", revision = 5, updatedAt = 20)
        coEvery { dao.completeExactWork(any(), any()) } coAnswers { callOriginal() }
        coEvery {
            dao.deleteMatchingWork(MODEL_ID, MESSAGE_ID, "old", 4)
        } returns 0
        coEvery {
            dao.deleteMatchingWork(MODEL_ID, MESSAGE_ID, "new", 5)
        } returns 1
        coEvery { dao.getLedger(MODEL_ID) } returns pending(
            revision = 5,
            completedRevision = 4,
            updatedAt = 20,
        )
        coEvery { dao.markCurrentAfterExactWork(MODEL_ID, 5, 30) } returns 1

        assertFalse(dao.completeExactWork(stale, updatedAt = 29))
        assertTrue(dao.completeExactWork(current, updatedAt = 30))

        coVerify(exactly = 0) { dao.markCurrentAfterExactWork(MODEL_ID, 4, any()) }
        coVerify(exactly = 1) { dao.markCurrentAfterExactWork(MODEL_ID, 5, 30) }
    }

    @Test
    fun reconcileCompletionUsesExpectedRevisionCas() = runTest {
        val dao = mockk<SemanticIndexDao>()
        coEvery { dao.completeReconcile(any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.markAfterReconcile(MODEL_ID, 7, 30) } returns 1
        coEvery { dao.markAfterReconcile(MODEL_ID, 6, 31) } returns 0

        assertTrue(dao.completeReconcile(MODEL_ID, expectedReconcileRevision = 7, updatedAt = 30))
        assertFalse(dao.completeReconcile(MODEL_ID, expectedReconcileRevision = 6, updatedAt = 31))
    }

    @Test
    fun invalidArgumentsFailBeforeDatabaseAccess() = runTest {
        val dao = mockk<SemanticIndexDao>()
        coEvery { dao.admitModel(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.enqueueExactWork(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.completeReconcile(any(), any(), any()) } coAnswers { callOriginal() }

        assertIllegalArgument { dao.admitModel("", updatedAt = 1) }
        assertIllegalArgument { dao.enqueueExactWork(MODEL_ID, "", "fingerprint", 1) }
        assertIllegalArgument { dao.enqueueExactWork(MODEL_ID, MESSAGE_ID, "", 1) }
        assertIllegalArgument { dao.completeReconcile("", expectedReconcileRevision = 0, updatedAt = 1) }
        assertIllegalArgument { dao.completeReconcile(MODEL_ID, expectedReconcileRevision = -1, updatedAt = 1) }

        coVerify(exactly = 0) { dao.insertLedger(any()) }
        coVerify(exactly = 0) { dao.advanceForExactWork(any(), any()) }
        coVerify(exactly = 0) { dao.markAfterReconcile(any(), any(), any()) }
    }

    @Test
    fun roomQueriesCarryQueueAndCompletionFences() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/data/local/SemanticIndexLedger.kt",
        ).replace("\r\n", "\n")
        val delete = source.substringBefore("suspend fun deleteMatchingWork")
            .substringAfterLast("@Query(")
        val exactCurrent = source.substringBefore("suspend fun markCurrentAfterExactWork")
            .substringAfterLast("@Query(")
        val reconcileCurrent = source.substringBefore("suspend fun markAfterReconcile")
            .substringAfterLast("@Query(")

        assertTrue(delete.contains("sourceRevision = :sourceRevision"))
        assertTrue(delete.contains("sourceFingerprint IS NULL AND :sourceFingerprint IS NULL"))
        assertTrue(exactCurrent.contains("state = 'PENDING'"))
        assertTrue(exactCurrent.contains("sourceRevision = :sourceRevision"))
        assertTrue(exactCurrent.contains("NOT EXISTS"))
        assertTrue(reconcileCurrent.contains("state = 'NEEDS_RECONCILE'"))
        assertTrue(
            reconcileCurrent.contains("reconcileRevision = :expectedReconcileRevision"),
        )
        assertTrue(reconcileCurrent.contains("sourceRevision = :expectedReconcileRevision"))
        assertTrue(reconcileCurrent.contains("NOT EXISTS"))
    }

    private fun needsReconcile(
        revision: Long,
        completedRevision: Long = 0,
        reconcileRevision: Long = revision,
        updatedAt: Long,
    ) = SemanticIndexLedgerEntity(
        modelId = MODEL_ID,
        sourceRevision = revision,
        completedRevision = completedRevision,
        reconcileRevision = reconcileRevision,
        updatedAt = updatedAt,
    )

    private fun pending(
        revision: Long,
        completedRevision: Long,
        updatedAt: Long,
    ) = SemanticIndexLedgerEntity(
        modelId = MODEL_ID,
        state = SemanticIndexLedgerEntity.STATE_PENDING,
        sourceRevision = revision,
        completedRevision = completedRevision,
        updatedAt = updatedAt,
    )

    private fun current(revision: Long, updatedAt: Long) = SemanticIndexLedgerEntity(
        modelId = MODEL_ID,
        state = SemanticIndexLedgerEntity.STATE_CURRENT,
        sourceRevision = revision,
        completedRevision = revision,
        updatedAt = updatedAt,
    )

    @Test
    fun fingerprintMatchesDurableSha256IncludingSignedBytesAndUtf16PrefixBoundary() {
        val inputs = listOf("", "abc", "\u0000中文\uD83D\uDE00", "a".repeat(7_999) + "\uD83D\uDE00tail")
        inputs.forEach { text ->
            val expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.take(8_000).toByteArray(Charsets.UTF_8)))
            assertEquals(expected, semanticSourceFingerprint(text))
        }
    }

    private fun work(fingerprint: String?, revision: Long, updatedAt: Long) =
        workFor(MESSAGE_ID, fingerprint, revision, updatedAt)

    private fun workFor(
        messageId: String,
        fingerprint: String?,
        revision: Long,
        updatedAt: Long,
    ) = SemanticIndexWorkEntity(
        modelId = MODEL_ID,
        messageId = messageId,
        sourceFingerprint = fingerprint,
        sourceRevision = revision,
        updatedAt = updatedAt,
    )

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::isFile)?.let { return it.readText() }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }

    companion object {
        private const val MODEL_ID = "model"
        private const val MESSAGE_ID = "message"
    }
}
