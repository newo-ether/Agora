package com.newoether.agora.phone

import com.newoether.agora.model.RunEffectIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneLeaseDeadlineTest {
    private val identity = RunEffectIdentity("conversation", 1, "run", 0, "batch")
    @Test fun idleDeadlineResetsOnCompletionWithoutPeriodicTicks() = runTest {
        val lease = PhoneControlLease { testScheduler.currentTime }
        lease.watchIdle(backgroundScope) { testScheduler.currentTime }
        val permit = (lease.claim(identity, "Title") as PhoneControlLease.Decision.Granted).permit
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(300_000L, lease.held.value!!.remainingMs)
        assertTrue(lease.recentReleases().isEmpty())
        assertNull(lease.complete(permit))
        runCurrent()
        advanceTimeBy(299_999); runCurrent()
        assertNotNull(lease.held.value)
        advanceTimeBy(1); runCurrent()
        assertNull(lease.held.value)
        assertEquals("lease_expired", lease.recentReleases().single().reason)
    }
    @Test fun askPauseKeepsBudgetAndOldDeadlineCannotExpireReclaim() = runTest {
        val lease = PhoneControlLease { testScheduler.currentTime }
        lease.watchIdle(backgroundScope) { testScheduler.currentTime }
        val old = (lease.claim(identity, "Title") as PhoneControlLease.Decision.Granted).permit
        runCurrent()
        advanceTimeBy(100_000)
        lease.pause(identity.owner(), true); runCurrent()
        advanceTimeBy(900_000); runCurrent()
        assertNotNull(lease.held.value)
        assertEquals(200_000L, lease.held.value!!.remainingMs)
        lease.pause(identity.owner(), false); runCurrent()
        advanceTimeBy(199_999); runCurrent()
        assertNotNull(lease.held.value)
        lease.release(old)
        lease.claim(identity, "Fresh"); runCurrent()
        advanceTimeBy(1); runCurrent()
        assertNotNull(lease.held.value)
        advanceTimeBy(299_999); runCurrent()
        assertNull(lease.held.value)
    }
    @Test fun everyReleaseIsRecordedExactlyOnceWithBoundedHistory() {
        var time = 0L
        val records = mutableListOf<PhoneControlLease.Release>()
        val lease = PhoneControlLease(recordRelease = records::add, now = { time })
        for (index in 1..40) {
            val permit = (lease.claim(identity, "Title", "root") as PhoneControlLease.Decision.Granted).permit
            time += 30_000
            lease.releaseEpoch(permit.epoch, "permission_lost:root_denied")
            lease.releaseEpoch(permit.epoch, "duplicate")
        }
        assertEquals(40, records.size)
        assertEquals(32, lease.recentReleases().size)
        assertEquals(9L, lease.recentReleases().first().epoch)
        assertEquals(30_000L, records.last().heldMs)
        assertEquals("root", records.last().mode)
    }
    @Test fun staleEventCannotReleaseANewEpoch() {
        val lease = PhoneControlLease { 0L }
        val old = (lease.claim(identity, "Old") as PhoneControlLease.Decision.Granted).permit
        lease.release(old)
        val fresh = (lease.claim(identity, "Fresh") as PhoneControlLease.Decision.Granted).permit
        lease.releaseEpoch(old.epoch, "permission_lost:shizuku_binder_dead")
        assertNull(lease.validate(fresh))
    }
}
