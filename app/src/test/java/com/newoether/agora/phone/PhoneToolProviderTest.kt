package com.newoether.agora.phone

import android.graphics.Bitmap
import android.graphics.Rect
import io.mockk.every
import io.mockk.mockk
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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
    private var beforeOperation: suspend () -> Unit = {}
    private var statusUpdate: suspend (String?) -> Unit = {}
    private var releaseControl: suspend (Long) -> Boolean = { true }
    private var operationStarts = 0
    private var operationEnds = 0
    private var foreground: suspend () -> String = { "allowed" }
    private val keys = mutableListOf<String>()
    private val taps = mutableListOf<Triple<Int, Int, Long>>()
    private var nodes = emptyList<PhoneNode>()
    private var dimensions = Triple(1080, 2400, 0)
    private val backend = object : PhoneDevice {
        override suspend fun foreground(): String { platformEffects++; return foreground.invoke() }
        override suspend fun screenshot(): Bitmap = error("Unexpected capture")
        override suspend fun nodes(): List<PhoneNode> { platformEffects++; return nodes }
        override suspend fun tap(x: Int, y: Int, durationMs: Long) { platformEffects++; taps += Triple(x, y, durationMs) }
        override suspend fun swipe(x: Int, y: Int, endX: Int, endY: Int, durationMs: Long) { platformEffects++ }
        override suspend fun type(text: String) { platformEffects++ }
        override suspend fun key(key: String) { platformEffects++; keys += key }
        override suspend fun launch(packageName: String) { platformEffects++ }
        override suspend fun apps(): List<PhoneApp> { platformEffects++; return listOf(PhoneApp("allowed", "Allowed"), PhoneApp("private", "Private")) }
        override fun dimensions() = dimensions
    }
    private val provider = PhoneToolProvider(
        lease = lease, settings = { config }, accepts = { accepted && it == identity },
        title = { platformEffects++; "Title" }, checkEntry = { _, _ ->
            probes++; if (readiness()) null else "permission_lost:not_ready:notifications"
        }, locked = { isLocked },
        device = { platformEffects++; backend }, ownPackage = "agora", launcher = { "launcher" },
        persist = { error("Unexpected persistence") }, captured = { error("Unexpected capture") },
        showControl = { platformEffects++ }, action = { _, _, _, _, _, _ -> platformEffects++ },
        status = { platformEffects++; statusUpdate(it) },
        releaseControl = { releaseControl(it) },
        operating = { work ->
            operationStarts++
            try { beforeOperation(); work() } finally { operationEnds++ }
        },
    )
    private suspend fun claim() = provider.executeOneShotResult("phone_claim", "{}", context)
    private fun assertError(code: String, result: com.newoether.agora.tool.ToolExecutionResult) {
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("\"error\":\"$code\""))
    }

    @Test fun explicitReleaseWaitsForPlatformRestorationAndReportsItsFailure() = runTest {
        assertFalse(claim().isError)
        val entered = CompletableDeferred<Long>()
        val restored = CompletableDeferred<Boolean>()
        releaseControl = { epoch -> entered.complete(epoch); restored.await() }
        val epoch = lease.held.value!!.epoch
        val released = async { provider.executeOneShotResult("phone_release", "{}", context) }
        assertEquals(epoch, entered.await())
        assertNull(lease.held.value)
        assertFalse(released.isCompleted)
        restored.complete(false)
        assertError("screen_timeout_restore_failed", released.await())
    }
    @Test fun explicitReleaseReturnsSuccessOnlyAfterRestorationCompletes() = runTest {
        assertFalse(claim().isError)
        val restored = CompletableDeferred<Boolean>()
        releaseControl = { restored.await() }
        val released = async { provider.executeOneShotResult("phone_release", "{}", context) }
        runCurrent()
        assertFalse(released.isCompleted)
        restored.complete(true)
        assertFalse(released.await().isError)
        assertNull(lease.held.value)
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
        assertError("permission_lost:not_ready:notifications", provider.executeOneShotResult("phone_key", "{\"key\":\"back\",\"screenshot\":false}", context))
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
    @Test fun nodeTargetIgnoresOptionalCoordinatesForTapAndLongPress() = runTest {
        assertFalse(claim().isError)
        val bounds = mockk<Rect>()
        every { bounds.centerX() } returns 320
        every { bounds.centerY() } returns 640
        nodes = listOf(PhoneNode(0, bounds, "Target", "Button", true, false, false, false))
        assertFalse(provider.executeOneShotResult("phone_ui_tree", "{}", context).isError)
        for ((name, duration) in listOf("phone_tap" to 60L, "phone_long_press" to 800L)) {
            val result = provider.executeOneShotResult(name,
                """{"node":0,"x":0,"y":0,"screenshot":false}""", context)
            assertFalse(result.text, result.isError)
            assertEquals(Triple(320, 640, duration), taps.last())
            assertFalse(provider.executeOneShotResult(name,
                """{"node":0,"x":0,"y":0,"screenshot":false}""", context).isError)
        }
        assertEquals(4, taps.size)
    }

    @Test fun nodePriorityRetainsSnapshotAndIndexValidationWithoutCoordinateFallback() = runTest {
        assertFalse(claim().isError)
        val args = """{"node":0,"x":0,"y":0,"screenshot":false}"""
        assertError("ui_tree_required", provider.executeOneShotResult("phone_tap", args, context))
        assertFalse(provider.executeOneShotResult("phone_ui_tree", "{}", context).isError)
        assertError("invalid_node", provider.executeOneShotResult("phone_tap", args, context))
        dimensions = Triple(2400, 1080, 1)
        assertError("ui_tree_required", provider.executeOneShotResult("phone_tap", args, context))
        dimensions = Triple(1080, 2400, 0)
        foreground = { "another.allowed.app" }
        assertError("ui_tree_required", provider.executeOneShotResult("phone_tap", args, context))
        assertError("screenshot_required", provider.executeOneShotResult("phone_tap",
            """{"x":0,"y":0,"screenshot":false}""", context))
        assertTrue(taps.isEmpty())
    }

    @Test fun visibilityBarrierPrecedesDeviceWorkAndRechecksRevocation() = runTest {
        assertFalse(claim().isError)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        beforeOperation = { entered.complete(Unit); resume.await() }
        val before = platformEffects
        val pending = async { provider.executeOneShotResult("phone_key", """{"key":"home","screenshot":false}""", context) }
        entered.await()
        // Status is a local projection before the suspended window boundary.
        assertEquals(before + 1, platformEffects)
        lease.held.value!!.let { lease.revokeByUser(it.owner, it.epoch) }
        resume.complete(Unit)
        assertError("released_by_user", pending.await())
        assertTrue(keys.isEmpty())
        assertEquals(1, operationStarts)
        assertEquals(1, operationEnds)
        assertEquals(before + 2, platformEffects)
    }
    @Test fun visibilityScopeRestoresOnFailureAndCancellationAndDoesNotWrapClaimOrRelease() = runTest {
        assertFalse(claim().isError)
        assertEquals(0, operationStarts)
        foreground = { throw PhoneFailure("device_failure") }
        assertError("device_failure", provider.executeOneShotResult("phone_ui_tree", "{}", context))
        assertEquals(1, operationEnds)
        val entered = CompletableDeferred<Unit>()
        beforeOperation = { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val pending = async { provider.executeOneShotResult("phone_screenshot", "{}", context) }
        entered.await(); pending.cancel(); pending.join()
        assertEquals(2, operationEnds)
        assertFalse(provider.executeOneShotResult("phone_release", "{}", context).isError)
        assertEquals(2, operationStarts)
    }
    @Test fun permissionChecksRunOncePerToolAndNeverInsideFences() = runTest {
        assertFalse(claim().isError)
        assertEquals(1, probes)
        beforeOperation = { readiness = { false } }
        assertFalse(provider.executeOneShotResult("phone_app_list", "{}", context).isError)
        assertEquals(2, probes)
        assertNotNull(lease.held.value)
        time = 30_000
        assertNotNull(lease.held.value)
        assertEquals(2, probes)
    }
    @Test fun unexpectedReleaseIsVisibleOnNextRefusalAndSuccessfulReclaim() = runTest {
        assertFalse(claim().isError)
        time = 31_000
        lease.releaseOwner(identity.owner(), "permission_lost:shizuku_binder_dead")
        val refused = provider.executeOneShotResult("phone_app_list", "{}", context)
        assertError("not_claimed", refused)
        assertTrue(refused.text.contains("\"last_release\""))
        assertTrue(refused.text.contains("permission_lost:shizuku_binder_dead"))
        assertTrue(refused.text.contains("\"held_ms\":31000"))
        val reclaimed = claim()
        assertFalse(reclaimed.isError)
        assertTrue(reclaimed.text.contains("permission_lost:shizuku_binder_dead"))
    }
    @Test fun actualPermissionFailureReleasesButCommandErrorsAndTimeoutsKeepLease() = runTest {
        for (code in listOf("device_timeout", "device_command_failed", "permission_lost:root_denied",
            "permission_lost:accessibility_unavailable", "permission_lost:shizuku_security_exception")) {
            assertFalse(claim().isError)
            foreground = { throw PhoneFailure(code) }
            assertError(code, provider.executeOneShotResult("phone_app_list", "{}", context))
            if (code.startsWith("permission_lost:")) assertEquals(code, lease.recentReleases().last().reason)
            assertEquals(code, !code.startsWith("permission_lost:"), lease.held.value != null)
            foreground = { "allowed" }
        }
    }
    @Test fun platformSecurityExceptionReleasesWithModeSpecificDiagnostics() = runTest {
        assertFalse(claim().isError)
        foreground = { throw SecurityException("Accessibility dispatch denied") }
        assertError("permission_lost:accessibility_security_exception",
            provider.executeOneShotResult("phone_app_list", "{}", context))
        assertNull(lease.held.value)
        assertEquals("permission_lost:accessibility_security_exception", lease.recentReleases().single().reason)
    }
    @Test fun reclaimEntryFailureReleasesTheExistingEpoch() = runTest {
        assertFalse(claim().isError)
        val epoch = lease.held.value!!.epoch
        readiness = { false }
        assertError("permission_lost:not_ready:notifications", claim())
        assertNull(lease.held.value)
        assertEquals(epoch, lease.recentReleases().single().epoch)
    }
    @Test fun settingsChangeDuringSuspensionIsFencedWithoutAnotherPermissionProbe() = runTest {
        assertFalse(claim().isError)
        beforeOperation = { config = config.copy(enabled = false) }
        assertError("permission_lost:disabled", provider.executeOneShotResult("phone_app_list", "{}", context))
        assertNull(lease.held.value)
        assertEquals(2, probes)
    }
    @Test fun targetDefinitionsExplainNodePriority() {
        for (name in listOf("phone_tap", "phone_long_press")) {
            val definition = provider.definitions(context).single { it.function.name == name }
            assertTrue(definition.function.description.contains("If node is given, x/y are ignored."))
        }
    }

    @Test fun statusPacingPrecedesWindowAdmissionAndFencesRevocationBeforeDeviceWork() = runTest {
        assertFalse(claim().isError)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        statusUpdate = { if (it != null) { entered.complete(Unit); resume.await() } }
        val pending = async { provider.executeOneShotResult("phone_key",
            """{"key":"home","screenshot":false}""", context) }
        entered.await()
        assertEquals(0, operationStarts)
        lease.held.value!!.let { lease.revokeByUser(it.owner, it.epoch) }
        resume.complete(Unit)
        assertError("released_by_user", pending.await())
        assertTrue(keys.isEmpty())
        assertEquals(1, operationStarts)
        assertEquals(1, operationEnds)
    }

    @Test fun reusedNodeSnapshotStillRejectsAReplacementEpoch() = runTest {
        assertFalse(claim().isError)
        assertFalse(provider.executeOneShotResult("phone_ui_tree", "{}", context).isError)
        assertFalse(provider.executeOneShotResult("phone_release", "{}", context).isError)
        assertFalse(claim().isError)
        assertError("ui_tree_required", provider.executeOneShotResult("phone_tap",
            """{"node":0,"screenshot":false}""", context))
        assertTrue(taps.isEmpty())
    }

    @Test fun consecutiveCoordinatesReuseOneCalibratedScreenAndRejectRotation() = runTest {
        assertFalse(claim().isError)
        // Seed the provider's exact persisted screenshot calibration without a bitmap fixture.
        PhoneToolProvider::class.java.getDeclaredField("screen").apply { isAccessible = true }
            .set(provider, lease.held.value!!.epoch to PhoneScreenSpace(540, 1200, 1080, 2400, 0))
        repeat(3) {
            assertFalse(provider.executeOneShotResult("phone_tap",
                """{"x":160,"y":320,"screenshot":false}""", context).isError)
        }
        assertEquals(List(3) { Triple(320, 640, 60L) }, taps)
        dimensions = Triple(2400, 1080, 1)
        assertError("screen_changed", provider.executeOneShotResult("phone_tap",
            """{"x":160,"y":320,"screenshot":false}""", context))
        assertEquals(3, taps.size)
    }

    @Test fun definitionsDescribeOptionalObservationAndConsecutiveActions() {
        val definition = provider.definitions(context).single { it.function.name == "phone_tap" }
        assertTrue(definition.function.description.contains("reading the screen between actions is optional"))
        assertTrue(definition.function.parameters.properties.getValue("screenshot").description.contains("set false"))
    }

    @Test fun definitionsRespectFrozenReadinessAndAutomationLineage() {
        assertEquals(11, provider.definitions(context).size)
        assertTrue(provider.definitions(context.copy(phoneReady = false)).isEmpty())
        assertTrue(provider.definitions(context.copy(automationCaller = true)).isEmpty())
        assertEquals(11, provider.definitions(context.copy(automationCaller = true, phoneAllowAutomation = true)).size)
        assertTrue(provider.definitions(context.copy(phoneEnabled = false)).isEmpty())
    }
}
