package com.newoether.agora.phone

import android.graphics.Bitmap
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhoneToolProviderTest {
    private var time = 0L
    private val lease = PhoneControlLease { time }
    private val identity = RunEffectIdentity("conversation", 1, "run", 0, "batch")
    private val context = GenerationContext(phoneEnabled = true, phoneReady = true, toolEffectIdentity = identity)
    private var config = PhoneUseSettings(enabled = true)
    private var accepted = true
    private var isLocked = false
    private var probes = 0
    private var platformEffects = 0
    private var readiness: suspend () -> Boolean = { true }
    private var foreground: suspend () -> String = { "allowed" }
    private val keys = mutableListOf<String>()
    private val backend = object : PhoneDevice {
        override suspend fun foreground(): String { platformEffects++; return foreground.invoke() }
        override suspend fun screenshot(): Bitmap = error("Unexpected capture")
        override suspend fun nodes(): List<PhoneNode> { platformEffects++; return emptyList() }
        override suspend fun tap(x: Int, y: Int, durationMs: Long) { platformEffects++ }
        override suspend fun swipe(x: Int, y: Int, endX: Int, endY: Int, durationMs: Long) { platformEffects++ }
        override suspend fun type(text: String) { platformEffects++ }
        override suspend fun key(key: String) { platformEffects++; keys += key }
        override suspend fun launch(packageName: String) { platformEffects++ }
        override suspend fun apps(): List<PhoneApp> { platformEffects++; return listOf(PhoneApp("allowed", "Allowed"), PhoneApp("private", "Private")) }
        override fun dimensions() = Triple(1080, 2400, 0)
    }
    private val provider = PhoneToolProvider(
        lease = lease, settings = { config }, accepts = { accepted && it == identity },
        title = { platformEffects++; "Title" }, ready = { probes++; readiness() }, locked = { isLocked },
        device = { platformEffects++; backend }, ownPackage = "agora", launcher = { "launcher" },
        persist = { error("Unexpected persistence") }, captured = { error("Unexpected capture") },
        showControl = { platformEffects++ }, action = { _, _, _, _, _, _ -> platformEffects++ },
        status = { platformEffects++ },
    )
    private suspend fun claim() = provider.executeOneShotResult("phone_claim", "{}", context)
    private fun assertError(code: String, result: com.newoether.agora.tool.ToolExecutionResult) {
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("\"error\":\"$code\""))
    }

    @Test fun everyNonClaimToolWithoutLeaseHasNoEffectEvenWithMalformedArguments() = runTest {
        (PHONE_TOOL_NAMES - "phone_claim").forEach { name ->
            assertError("not_claimed", provider.executeOneShotResult(name, "malformed", context))
        }
        assertEquals(0, platformEffects)
        assertEquals(0, probes)
    }

    @Test fun occupiedAndRevokedCallsNeverProbeOrTouchDevice() = runTest {
        val other = identity.copy(conversationId = "other")
        lease.claim(other, "Other title")
        assertError("occupied", claim())
        assertEquals(0, probes)
        assertEquals(0, platformEffects)
        lease.releaseOwner(other.owner(), "released")
        val permit = (lease.claim(identity, "Title") as PhoneControlLease.Decision.Granted).permit
        lease.revokeByUser(identity.owner(), permit.epoch)
        PHONE_TOOL_NAMES.forEach { name -> assertError("released_by_user", provider.executeOneShotResult(name, "{", context)) }
        assertEquals(0, probes)
        assertEquals(0, platformEffects)
    }

    @Test fun staleExactBatchAndDisabledAdmissionHaveNoEffect() = runTest {
        assertError("stale_identity", provider.executeOneShotResult("phone_claim", "{}", context.copy(
            toolEffectIdentity = identity.copy(effectId = "old"),
        )))
        assertError("not_admitted", provider.executeOneShotResult("phone_claim", "{}", context.copy(phoneEnabled = false)))
        assertEquals(0, probes)
        assertEquals(0, platformEffects)
    }

    @Test fun permissionLossAndLockReleaseInflightLease() = runTest {
        assertFalse(claim().isError)
        readiness = { false }
        assertError("permission_lost", provider.executeOneShotResult("phone_key", "{\"key\":\"back\",\"screenshot\":false}", context))
        assertNull(lease.held.value)
        readiness = { true }
        assertFalse(claim().isError)
        isLocked = true
        assertError("screen_locked", provider.executeOneShotResult("phone_app_list", "{}", context))
        assertNull(lease.held.value)
    }

    @Test fun userRevocationDuringReadinessPreventsDeviceEffects() = runTest {
        assertFalse(claim().isError)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        readiness = { entered.complete(Unit); resume.await(); true }
        val before = platformEffects
        val result = async { provider.executeOneShotResult("phone_key", "{\"key\":\"home\",\"screenshot\":false}", context) }
        entered.await()
        lease.held.value!!.let { lease.revokeByUser(it.owner, it.epoch) }
        resume.complete(Unit)
        assertError("released_by_user", result.await())
        assertTrue(keys.isEmpty())
        // The status reset is a local projection; no backend was constructed or invoked.
        assertEquals(before + 1, platformEffects)
    }

    @Test fun blockedForegroundPrecedesCaptureAndFallsBackThroughBackThenHome() = runTest {
        assertFalse(claim().isError)
        config = config.copy(accessMode = "blocklist", blocklist = setOf("private"))
        foreground = { "private" }
        assertError("app_blocked", provider.executeOneShotResult("phone_screenshot", "{}", context))
        assertEquals(listOf("back", "home"), keys)
    }

    @Test fun backToAllowedAppAvoidsHomeAndAppListFiltersPackages() = runTest {
        assertFalse(claim().isError)
        config = config.copy(accessMode = "allowlist", allowlist = setOf("allowed"))
        foreground = { if (keys.isEmpty()) "private" else "allowed" }
        assertError("app_blocked", provider.executeOneShotResult("phone_ui_tree", "{}", context))
        assertEquals(listOf("back"), keys)
        val result = provider.executeOneShotResult("phone_app_list", "{}", context)
        assertFalse(result.isError)
        assertTrue(result.text.contains("Allowed"))
        assertFalse(result.text.contains("Private"))
    }

    @Test fun successfulCallsRenewAndReleaseRequiresNoBackend() = runTest {
        assertFalse(claim().isError)
        time = 200_000
        val result = provider.executeOneShotResult("phone_key", "{\"key\":\"back\",\"screenshot\":false}", context)
        assertFalse(result.isError)
        assertEquals(PhoneControlLease.IDLE_MS, lease.held.value!!.remainingMs)
        val before = keys.toList()
        assertFalse(provider.executeOneShotResult("phone_release", "{}", context).isError)
        assertNull(lease.held.value)
        assertEquals(before, keys)
    }

    @Test fun concurrentDeviceCallsRefuseWithoutWaitingOrRepeatingEffects() = runTest {
        assertFalse(claim().isError)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        readiness = { entered.complete(Unit); resume.await(); true }
        val inflight = async { provider.executeOneShotResult("phone_app_list", "{}", context) }
        entered.await()
        val before = platformEffects
        assertError("phone_busy", provider.executeOneShotResult("phone_app_list", "{}", context))
        assertEquals(before, platformEffects)
        resume.complete(Unit)
        assertFalse(inflight.await().isError)
        readiness = { true }
        assertFalse(provider.executeOneShotResult("phone_app_list", "{}", context).isError)
    }
    @Test fun definitionsRespectFrozenReadinessAndAutomationLineage() {
        assertEquals(11, provider.definitions(context).size)
        assertTrue(provider.definitions(context.copy(phoneReady = false)).isEmpty())
        assertTrue(provider.definitions(context.copy(automationCaller = true)).isEmpty())
        assertEquals(11, provider.definitions(context.copy(automationCaller = true, phoneAllowAutomation = true)).size)
        assertTrue(provider.definitions(context.copy(phoneEnabled = false)).isEmpty())
    }
}
