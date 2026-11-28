package com.newoether.agora.phone

import com.newoether.agora.model.RunEffectIdentity
import org.junit.Assert.*
import org.junit.Test

class PhoneControlLeaseTest {
    private var time = 0L
    private val lease = PhoneControlLease { time }
    private fun identity(conversation: String = "a", run: String = "r", pass: Int = 0) =
        RunEffectIdentity(conversation, 1, run, pass, "batch-$pass")
    private fun permit(id: RunEffectIdentity = identity()) =
        (lease.claim(id, "Title") as PhoneControlLease.Decision.Granted).permit

    @Test fun claimOccupiedRenewAndExpire() {
        val first = permit()
        time = 20_000
        val occupied = lease.claim(identity("b"), "B") as PhoneControlLease.Decision.Refused
        assertEquals("occupied", occupied.code)
        assertEquals("Title", occupied.title)
        assertEquals(280_000L, occupied.remainingMs)
        assertNull(lease.complete(first))
        time = 319_999
        assertNull(lease.validate(first))
        time++
        assertEquals("lease_expired", lease.validate(first))
        assertNull(lease.held.value)
    }

    @Test fun pausePreservesRemainingBudgetAndResumeContinuesIt() {
        val call = permit()
        time = 10_000
        lease.pause(identity().owner(), true)
        time = 900_000
        lease.tick()
        assertEquals(290_000L, lease.held.value?.remainingMs)
        lease.pause(identity().owner(), false)
        time += 290_000
        lease.tick()
        assertNull(lease.held.value)
        assertEquals("lease_expired", lease.validate(call))
    }

    @Test fun releaseReclaimRejectsOldPermitEvenForSameRun() {
        val old = permit()
        assertNull(lease.release(old))
        val fresh = permit()
        assertNotEquals(old.epoch, fresh.epoch)
        assertEquals("not_claimed", lease.validate(old))
        assertNull(lease.validate(fresh))
        assertEquals("not_claimed", (lease.authorize(identity("other")) as PhoneControlLease.Decision.Refused).code)
    }

    @Test fun userStopFencesInflightAndAllLaterCallsUntilNextRun() {
        val call = permit()
        lease.revokeByUser(call.identity.owner(), call.epoch)
        assertEquals("released_by_user", lease.validate(call))
        assertEquals("released_by_user", (lease.claim(identity(), "A") as PhoneControlLease.Decision.Refused).code)
        assertEquals("released_by_user", (lease.authorize(identity(pass = 1)) as PhoneControlLease.Decision.Refused).code)
        assertTrue(lease.claim(identity(run = "next"), "A") is PhoneControlLease.Decision.Granted)
    }

    @Test fun staleNotificationCannotRevokeNewLease() {
        val old = permit()
        lease.release(old)
        val fresh = permit()
        lease.revokeByUser(old.identity.owner(), old.epoch)
        assertNull(lease.validate(fresh))
    }

    @Test fun eachReleaseTriggerClosesLeaseAndRejectsInflight() {
        listOf("run_stopped", "run_ended", "conversation_deleted", "permission_lost", "screen_locked").forEach {
            val call = permit()
            lease.releaseOwner(call.identity.owner(), it)
            assertNull(lease.held.value)
            assertEquals(it, lease.validate(call))
        }
    }

    @Test fun onlyOneConcurrentClaimWins() {
        val start = java.util.concurrent.CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<PhoneControlLease.Decision>())
        val threads = (1..16).map { index -> Thread {
            start.await()
            results += lease.claim(identity("c$index"), "C$index")
        }.apply { start() } }
        start.countDown()
        threads.forEach(Thread::join)
        assertEquals(1, results.count { it is PhoneControlLease.Decision.Granted })
        assertEquals(15, results.count { it is PhoneControlLease.Decision.Refused })
    }
}

class PhoneRulesTest {
    @Test fun bothUntrustedOverlayWindowsStayWithinTheCombinedOpacityLimit() {
        for (maximum in listOf(0f, 0.1f, 0.5f, 0.8f, 0.99f, 1f)) {
            val alpha = phoneOverlayWindowAlpha(maximum)
            val combined = 1f - (1f - alpha) * (1f - alpha)
            assertTrue("$combined exceeds $maximum", combined <= maximum)
            assertTrue(alpha in 0f..1f)
            if (maximum > 0f) assertTrue(alpha > 0f)
        }
    }
    @Test fun admissionMatrix() {
        assertTrue(phoneCallerAdmitted(false, false))
        assertTrue(phoneCallerAdmitted(false, true))
        assertFalse(phoneCallerAdmitted(true, false))
        assertTrue(phoneCallerAdmitted(true, true))
    }
    @Test fun appRulesExemptAgoraAndImplicitNavigationOnlyInAllowlist() {
        for (mode in listOf("off", "blocklist", "allowlist")) {
            assertTrue(phoneAppAllowed("agora", "agora", mode, setOf("agora"), "launcher"))
        }
        assertTrue(phoneAppAllowed("launcher", "agora", "allowlist", emptySet(), "launcher"))
        assertTrue(phoneAppAllowed("com.android.systemui", "agora", "allowlist", emptySet(), "launcher"))
        assertFalse(phoneAppAllowed("private", "agora", "allowlist", emptySet(), "launcher"))
        assertTrue(phoneAppAllowed("private", "agora", "allowlist", setOf("private"), "launcher"))
        assertFalse(phoneAppAllowed("launcher", "agora", "blocklist", setOf("launcher"), "launcher"))
        assertTrue(phoneAppAllowed("other", "agora", "blocklist", setOf("private"), "launcher"))
    }
    @Test fun coordinateMappingAndBounds() {
        val space = PhoneScreenSpace(1024, 2048, 1440, 2880, 0)
        assertEquals(720 to 1440, space.physical(512f, 1024f))
        assertEquals(0 to 0, space.physical(0f, 0f))
        assertThrows(IllegalArgumentException::class.java) { space.physical(1024f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { space.physical(Float.NaN, 0f) }
    }
    @Test fun nonAsciiUsesClipboardAndAccessibilityAlwaysUsesSetText() {
        for (mode in listOf("shizuku", "root")) {
            assertEquals("clipboard", phoneInputPath(mode, "你好 👋"))
            assertEquals("input_text", phoneInputPath(mode, "ASCII 123"))
        }
        assertEquals("set_text", phoneInputPath("accessibility", "你好"))
        assertEquals("'a'\\''b'", phoneShellQuote("a'b"))
    }
}
