package com.newoether.agora.phone

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.util.withAppLocale
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ConversationStateRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process owner for platform resources only. Runs, Stop and ask_user retain their owners. */
internal class PhoneUseRuntime(
    private val context: Context,
    val settings: SettingsRepository,
    val lease: PhoneControlLease,
    val askUser: AskUserController,
    private val registry: ConversationStateRegistry,
    conversations: ConversationRepository,
    private val scope: CoroutineScope,
) {
    val privileged = PhonePrivilegedConnection(context)
    val permissions = PhonePermissions(context, privileged)
    val actionName = MutableStateFlow<String?>(null)
    val cursor = MutableStateFlow<PhoneCursor?>(null)
    private var activeMode = "accessibility"
    private var overlayEpoch: Long? = null
    private var exitingOverlay: PhoneOverlay? = null
    var overlay: PhoneOverlay? = null
        private set
    private val images = ToolImageStore(context)
    val provider = PhoneToolProvider(
        lease = lease,
        settings = { settings.phoneUse.value },
        accepts = { registry.get(it.conversationId)?.acceptsToolEffect(it) == true },
        title = { conversations.getConversation(it)?.title.orEmpty() },
        ready = ::readiness,
        locked = permissions::locked,
        currentReady = permissions::ready,
        device = { AndroidPhoneDevice(context, it, privileged) },
        ownPackage = context.packageName,
        launcher = { context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName },
        persist = { source -> withContext(Dispatchers.IO) {
            val scale = minOf(1f, 2048f / maxOf(source.width, source.height))
            val bitmap = if (scale < 1) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1), true) else source
            try {
                val bytes = java.io.ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
                images.persistBytes(bytes, "image/png", "phone")
            } finally { if (bitmap !== source) bitmap.recycle() }
        } },
        captured = { capture -> (overlay ?: throw PhoneFailure("overlay_unavailable")).capture(capture) },
        showControl = { mode -> withContext(Dispatchers.Main.immediate) {
            val held = lease.held.value ?: throw PhoneFailure("not_claimed")
            if (overlayEpoch != held.epoch) {
                // Main-thread replacement is synchronous: an old exit never overlaps fresh windows.
                if (overlayEpoch != null || exitingOverlay != null) clearResources(animate = false)
                activeMode = mode
                val next = PhoneOverlay(context.withAppLocale(settings.appLanguage.value), this@PhoneUseRuntime, held.epoch)
                next.show(mode)
                overlay = next
                overlayEpoch = held.epoch
            }
            ContextCompat.startForegroundService(context, Intent(context, PhoneControlService::class.java))
        } },
        action = { name, x, y, endX, endY, duration ->
            cursor.value = PhoneCursor(name, x, y, endX, endY, duration, SystemClock.elapsedRealtime())
        },
        status = { actionName.value = it },
        operating = { operation ->
            val active = withContext(Dispatchers.Main.immediate) {
                overlay ?: throw PhoneFailure("overlay_unavailable")
            }
            try {
                active.beforeOperation()
                operation()
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { active.afterOperation() }
            }
        },
    )

    init {
        scope.launch(Dispatchers.Main.immediate) {
            lease.held.map { it?.epoch }.distinctUntilChanged().collect {
                // StateFlow may conflate release/reclaim. Compare the live epoch, never an old null.
                if (overlayEpoch != null && overlayEpoch != lease.held.value?.epoch) clearResources(animate = true)
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            lease.held.map { it?.epoch }.distinctUntilChanged().collect { epoch ->
                if (epoch != null) scope.launch(Dispatchers.Main.immediate) { monitor(epoch) }
            }
        }
    }

    private suspend fun monitor(epoch: Long) {
        var rootProbeAt = 0L
        while (lease.held.value?.epoch == epoch) {
            val held = lease.held.value ?: break
            val owner = held.owner
            when {
                registry.get(owner.conversationId)?.controlsRun(owner.runId) != true -> lease.forget(owner)
                permissions.locked() -> lease.releaseOwner(owner, "screen_locked")
                overlayEpoch == epoch -> {
                    val config = settings.phoneUse.value
                    if (!config.enabled || config.mode != activeMode || !permissions.ready(activeMode)) {
                        lease.releaseOwner(owner, "permission_lost")
                    } else {
                        lease.pause(owner, askUser.requests.value.any {
                            it.conversationId == owner.conversationId && it.runId == owner.runId
                        })
                        lease.tick()
                        if (activeMode == "root" && SystemClock.elapsedRealtime() >= rootProbeAt) {
                            rootProbeAt = SystemClock.elapsedRealtime() + 5000
                            scope.launch {
                                val granted = permissions.probeRoot()
                                if (!granted && lease.held.value?.epoch == epoch) lease.releaseOwner(owner, "permission_lost")
                            }
                        }
                    }
                }
            }
            delay(250)
        }
    }

    /** Called only on Main. Detach ownership before scheduling a cosmetic exit. */
    private fun clearResources(animate: Boolean) {
        exitingOverlay?.remove()
        exitingOverlay = null
        val previous = overlay
        overlay = null
        overlayEpoch = null
        privileged.close()
        actionName.value = null
        cursor.value = null
        if (animate && previous != null) {
            exitingOverlay = previous
            scope.launch(Dispatchers.Main.immediate) {
                previous.hide()
                if (exitingOverlay === previous) exitingOverlay = null
            }
        } else previous?.remove()
    }

    suspend fun readiness(mode: String): Boolean {
        if (mode == "root") permissions.probeRoot()
        return permissions.ready(mode)
    }
    fun revoke(owner: PhoneControlLease.Owner, epoch: Long) = lease.revokeByUser(owner, epoch)
}

internal data class PhoneCursor(
    val kind: String, val x: Int, val y: Int, val endX: Int, val endY: Int,
    val durationMs: Long, val startedAt: Long,
)
