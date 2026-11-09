package com.newoether.agora.data.local

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

class MaintenanceDebtDaoTest {
    @Test
    fun enqueueInsertsNewDebtWithoutRenewal() = runTest {
        val dao = mockk<MaintenanceDebtDao>()
        val inserted = pending("identity", revision = 1, updatedAt = 10)
        coEvery { dao.enqueue(any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.insertDebt(inserted) } returns 1L
        coEvery { dao.getDebt(inserted.kind, inserted.identity) } returns inserted

        assertEquals(inserted, dao.enqueue(inserted.kind, inserted.identity, inserted.updatedAt))

        coVerifyOrder {
            dao.insertDebt(inserted)
            dao.getDebt(inserted.kind, inserted.identity)
        }
        coVerify(exactly = 0) { dao.renewDebt(any(), any(), any()) }
    }

    @Test
    fun enqueueCoalescesExistingDebtAndFencesPriorClaim() = runTest {
        val dao = mockk<MaintenanceDebtDao>()
        val oldClaim = claimed("identity", revision = 4, claimId = "old", claimedAt = 5)
        val renewed = pending("identity", revision = 5, updatedAt = 20)
        coEvery { dao.enqueue(any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.complete(any()) } coAnswers { callOriginal() }
        coEvery { dao.release(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.insertDebt(renewed.copy(revision = 1)) } returns -1L
        coEvery { dao.renewDebt(renewed.kind, renewed.identity, renewed.updatedAt) } returns 1
        coEvery { dao.getDebt(renewed.kind, renewed.identity) } returns renewed
        coEvery {
            dao.completeClaimed(oldClaim.kind, oldClaim.identity, oldClaim.revision, "old")
        } returns 0
        coEvery {
            dao.releaseClaimed(oldClaim.kind, oldClaim.identity, oldClaim.revision, "old", 21)
        } returns 0

        assertEquals(renewed, dao.enqueue(renewed.kind, renewed.identity, renewed.updatedAt))
        assertFalse(dao.complete(oldClaim))
        assertFalse(dao.release(oldClaim, updatedAt = 21))

        coVerifyOrder {
            dao.insertDebt(renewed.copy(revision = 1))
            dao.renewDebt(renewed.kind, renewed.identity, renewed.updatedAt)
            dao.getDebt(renewed.kind, renewed.identity)
            dao.completeClaimed(oldClaim.kind, oldClaim.identity, oldClaim.revision, "old")
            dao.releaseClaimed(oldClaim.kind, oldClaim.identity, oldClaim.revision, "old", 21)
        }
    }

    @Test
    fun claimPreservesCandidateOrderAndSkipsLostCas() = runTest {
        val dao = mockk<MaintenanceDebtDao>()
        val first = pending("first", revision = 2, updatedAt = 10)
        val second = pending("second", revision = 7, updatedAt = 11)
        val third = pending("third", revision = 3, updatedAt = 12)
        val claimedFirst = first.claimedBy("claim", claimedAt = 100)
        val claimedThird = third.claimedBy("claim", claimedAt = 100)
        coEvery { dao.claim(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.getClaimCandidates(staleBefore = 50, limit = 3) } returns
            listOf(first, second, third)
        coEvery {
            dao.claimCandidate(first.kind, first.identity, first.revision, "claim", 100, 50)
        } returns 1
        coEvery {
            dao.claimCandidate(second.kind, second.identity, second.revision, "claim", 100, 50)
        } returns 0
        coEvery {
            dao.claimCandidate(third.kind, third.identity, third.revision, "claim", 100, 50)
        } returns 1
        coEvery { dao.getDebt(first.kind, first.identity) } returns claimedFirst
        coEvery { dao.getDebt(third.kind, third.identity) } returns claimedThird

        assertEquals(
            listOf(claimedFirst, claimedThird),
            dao.claim(claimId = "claim", claimedAt = 100, staleBefore = 50, limit = 3),
        )

        coVerifyOrder {
            dao.getClaimCandidates(staleBefore = 50, limit = 3)
            dao.claimCandidate(first.kind, first.identity, first.revision, "claim", 100, 50)
            dao.getDebt(first.kind, first.identity)
            dao.claimCandidate(second.kind, second.identity, second.revision, "claim", 100, 50)
            dao.claimCandidate(third.kind, third.identity, third.revision, "claim", 100, 50)
            dao.getDebt(third.kind, third.identity)
        }
    }

    @Test
    fun maximumClaimBatchIsAccepted() = runTest {
        val dao = mockk<MaintenanceDebtDao>()
        coEvery { dao.claim(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery {
            dao.getClaimCandidates(staleBefore = 50, limit = MaintenanceDebtDao.MAX_CLAIM_BATCH)
        } returns emptyList()

        assertTrue(
            dao.claim(
                claimId = "claim",
                claimedAt = 100,
                staleBefore = 50,
                limit = MaintenanceDebtDao.MAX_CLAIM_BATCH,
            ).isEmpty(),
        )
        coVerify(exactly = 1) {
            dao.getClaimCandidates(staleBefore = 50, limit = MaintenanceDebtDao.MAX_CLAIM_BATCH)
        }
    }

    @Test
    fun staleClaimIsReclaimedAtTheSameRevisionWithANewClaimIdentity() = runTest {
        val dao = mockk<MaintenanceDebtDao>()
        val stale = claimed("stale", revision = 9, claimId = "old", claimedAt = 40)
        val reclaimed = stale.copy(claimId = "new", claimedAt = 100)
        coEvery { dao.claim(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.getClaimCandidates(staleBefore = 50, limit = 1) } returns listOf(stale)
        coEvery {
            dao.claimCandidate(stale.kind, stale.identity, stale.revision, "new", 100, 50)
        } returns 1
        coEvery { dao.getDebt(stale.kind, stale.identity) } returns reclaimed

        assertEquals(
            listOf(reclaimed),
            dao.claim(claimId = "new", claimedAt = 100, staleBefore = 50, limit = 1),
        )
        assertEquals(stale.revision, reclaimed.revision)
    }

    @Test
    fun completeAndReleaseUseTheClaimRevisionAndIdentity() = runTest {
        val dao = mockk<MaintenanceDebtDao>()
        val completed = claimed("complete", revision = 4, claimId = "owner", claimedAt = 90)
        val released = claimed("release", revision = 6, claimId = "owner", claimedAt = 91)
        val staleCompleted = claimed("stale-complete", revision = 8, claimId = "old", claimedAt = 92)
        val staleReleased = claimed("stale-release", revision = 9, claimId = "old", claimedAt = 93)
        coEvery { dao.complete(any()) } coAnswers { callOriginal() }
        coEvery { dao.release(any(), any()) } coAnswers { callOriginal() }
        coEvery {
            dao.completeClaimed(completed.kind, completed.identity, completed.revision, "owner")
        } returns 1
        coEvery {
            dao.releaseClaimed(released.kind, released.identity, released.revision, "owner", 120)
        } returns 1
        coEvery {
            dao.completeClaimed(
                staleCompleted.kind,
                staleCompleted.identity,
                staleCompleted.revision,
                "old",
            )
        } returns 0
        coEvery {
            dao.releaseClaimed(
                staleReleased.kind,
                staleReleased.identity,
                staleReleased.revision,
                "old",
                121,
            )
        } returns 0

        assertTrue(dao.complete(completed))
        assertTrue(dao.release(released, updatedAt = 120))
        assertFalse(dao.complete(staleCompleted))
        assertFalse(dao.release(staleReleased, updatedAt = 121))

        coVerifyOrder {
            dao.completeClaimed(completed.kind, completed.identity, completed.revision, "owner")
            dao.releaseClaimed(released.kind, released.identity, released.revision, "owner", 120)
            dao.completeClaimed(
                staleCompleted.kind,
                staleCompleted.identity,
                staleCompleted.revision,
                "old",
            )
            dao.releaseClaimed(
                staleReleased.kind,
                staleReleased.identity,
                staleReleased.revision,
                "old",
                121,
            )
        }
    }

    @Test
    fun invalidClaimArgumentsFailBeforeDatabaseAccess() = runTest {
        val dao = mockk<MaintenanceDebtDao>()
        val pending = pending("pending", revision = 1, updatedAt = 1)
        coEvery { dao.enqueue(any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.claim(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.complete(any()) } coAnswers { callOriginal() }
        coEvery { dao.release(any(), any()) } coAnswers { callOriginal() }

        assertIllegalArgument { dao.enqueue("", "identity", updatedAt = 1) }
        assertIllegalArgument { dao.enqueue(pending.kind, "", updatedAt = 1) }
        assertIllegalArgument {
            dao.claim("", claimedAt = 10, staleBefore = 0, limit = 1)
        }
        assertIllegalArgument {
            dao.claim("claim", claimedAt = 10, staleBefore = 0, limit = 0)
        }
        assertIllegalArgument {
            dao.claim(
                "claim",
                claimedAt = 10,
                staleBefore = 0,
                limit = MaintenanceDebtDao.MAX_CLAIM_BATCH + 1,
            )
        }
        assertIllegalArgument {
            dao.claim("claim", claimedAt = 9, staleBefore = 10, limit = 1)
        }
        assertIllegalArgument { dao.complete(pending) }
        assertIllegalArgument { dao.release(pending, updatedAt = 2) }

        coVerify(exactly = 0) { dao.insertDebt(any()) }
        coVerify(exactly = 0) { dao.renewDebt(any(), any(), any()) }
        coVerify(exactly = 0) { dao.getClaimCandidates(any(), any()) }
        coVerify(exactly = 0) { dao.claimCandidate(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.getDebt(any(), any()) }
        coVerify(exactly = 0) { dao.completeClaimed(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.releaseClaimed(any(), any(), any(), any(), any()) }
    }

    @Test
    fun roomQueriesCarryOrderingStaleAndRevisionFences() {
        val source = sourceFile(
            "data/database/src/main/kotlin/com/newoether/agora/data/local/MaintenanceDebt.kt",
        ).replace("\r\n", "\n")
        val candidates = source.substringBefore("suspend fun getClaimCandidates")
            .substringAfterLast("@Query(")
        val claim = source.substringBefore("suspend fun claimCandidate")
            .substringAfterLast("@Query(")
        val complete = source.substringBefore("suspend fun completeClaimed")
            .substringAfterLast("@Query(")
        val release = source.substringBefore("suspend fun releaseClaimed")
            .substringAfterLast("@Query(")
        val renew = source.substringBefore("suspend fun renewDebt")
            .substringAfterLast("@Query(")

        assertTrue(candidates.contains("ORDER BY updatedAt, kind, identity"))
        assertTrue(candidates.contains("LIMIT :limit"))
        assertTrue(candidates.contains("claimedAt <= :staleBefore"))
        assertTrue(claim.contains("state = 'PENDING'"))
        assertTrue(claim.contains("revision = :revision"))
        assertTrue(claim.contains("claimId = :claimId"))
        assertTrue(claim.contains("claimedAt <= :staleBefore"))
        assertTrue(complete.contains("kind = :kind AND identity = :identity"))
        assertTrue(complete.contains("state = 'CLAIMED'"))
        assertTrue(complete.contains("revision = :revision AND claimId = :claimId"))
        assertTrue(release.contains("kind = :kind AND identity = :identity"))
        assertTrue(release.contains("state = 'CLAIMED'"))
        assertTrue(release.contains("revision = :revision AND claimId = :claimId"))
        assertTrue(renew.contains("revision = revision + 1"))
        assertTrue(renew.contains("claimId = NULL, claimedAt = NULL"))
    }

    private fun pending(identity: String, revision: Long, updatedAt: Long) =
        MaintenanceDebtEntity(
            kind = MaintenanceDebtEntity.KIND_ATTACHMENT_ORPHANS,
            identity = identity,
            revision = revision,
            updatedAt = updatedAt,
        )

    private fun claimed(
        identity: String,
        revision: Long,
        claimId: String,
        claimedAt: Long,
    ) = pending(identity, revision, updatedAt = claimedAt).claimedBy(claimId, claimedAt)

    private fun MaintenanceDebtEntity.claimedBy(
        claimId: String,
        claimedAt: Long,
    ) = copy(
        state = MaintenanceDebtEntity.STATE_CLAIMED,
        claimId = claimId,
        claimedAt = claimedAt,
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
}
