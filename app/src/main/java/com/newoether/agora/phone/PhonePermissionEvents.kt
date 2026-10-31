package com.newoether.agora.phone
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.newoether.agora.viewmodel.AskUserController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
/** Shared immediate settings decision for tool fences and settings-flow events. */
internal fun phoneSettingsFailure(config: PhoneUseSettings, mode: String): String? = when {
    !config.enabled -> "permission_lost:disabled"
    config.mode != mode -> "permission_lost:mode_changed"
    else -> null
}
/** Owns process event registrations and flow subscriptions, without periodic permission probes. */
internal fun PhoneControlLease.watchPermissions(
    context: Context,
    scope: CoroutineScope,
    settings: StateFlow<PhoneUseSettings>,
    questions: StateFlow<List<AskUserController.Request>>,
    locked: () -> Boolean,
) = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
    fun release(reason: String, mode: String? = null) {
        held.value?.takeIf { mode == null || it.mode == mode }?.let { releaseEpoch(it.epoch, reason) }
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF || locked()) release("screen_locked")
        }
    }
    val dead = Shizuku.OnBinderDeadListener { release("permission_lost:shizuku_binder_dead", "shizuku") }
    val permission = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result != PackageManager.PERMISSION_GRANTED) release("permission_lost:shizuku_permission_denied", "shizuku")
    }
    val accessibility: () -> Unit = { release("permission_lost:accessibility_disconnected", "accessibility") }
    ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
    }, ContextCompat.RECEIVER_NOT_EXPORTED)
    Shizuku.addBinderDeadListener(dead)
    Shizuku.addRequestPermissionResultListener(permission)
    PhoneAccessibilityService.onDisconnected = accessibility
    try {
        coroutineScope {
            launch {
                settings.map { it.enabled to it.mode }.distinctUntilChanged().collect { (enabled, mode) ->
                    held.value?.let { current ->
                        phoneSettingsFailure(PhoneUseSettings(enabled = enabled, mode = mode), current.mode)
                            ?.let { releaseEpoch(current.epoch, it) }
                    }
                }
            }
            launch {
                combine(held.map { it?.let { current -> current.epoch to current.owner } }.distinctUntilChanged(),
                    questions) { _, requests -> requests }
                    .collect { requests ->
                        held.value?.let { current -> pause(current.owner, requests.any {
                            it.conversationId == current.owner.conversationId && it.runId == current.owner.runId
                        }) }
                    }
            }
            awaitCancellation()
        }
    } finally {
        context.unregisterReceiver(receiver)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        if (PhoneAccessibilityService.onDisconnected === accessibility) PhoneAccessibilityService.onDisconnected = null
    }
}
