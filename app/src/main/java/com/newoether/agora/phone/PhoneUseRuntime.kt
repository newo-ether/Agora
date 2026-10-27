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
    val privileged = PhonePrivilegedConnection(context) { epoch ->
        lease.releaseEpoch(epoch, "permission_lost:shizuku_service_disconnected")
    }
    val permissions = PhonePermissions(context, privileged)
    private val actionStatus = PhoneActionStatus(
        CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate), SystemClock::elapsedRealtime,
    )
    val actionName = actionStatus.name
    val cursor = MutableStateFlow<PhoneCursor?>(null)
    private var overlayEpoch: Long? = null
    private var exitingOverlay: PhoneOverlay? = null
    var overlay: PhoneOverlay? = null
        private set
    private val images = ToolImageStore(context)
    private val screenTimeout = PhoneScreenTimeout.process(context)
    private var timeoutEpoch: Long? = null
    val provider = PhoneToolProvider(
        lease = lease,
        settings = { settings.phoneUse.value },
        accepts = { registry.get(it.conversationId)?.acceptsToolEffect(it) == true },
        title = { conversations.getConversation(it)?.title.orEmpty() },
        locked = permissions::locked,
        checkEntry = { mode, claiming ->
            if (claiming && mode == "root" && !permissions.claimRoot()) "permission_lost:root_denied"
            else permissions.entryFailure(mode, claiming)
        },
        device = { mode ->
            if (mode == "shizuku") privileged.leaseEpoch = lease.held.value?.epoch
            AndroidPhoneDevice(context, mode, privileged)
        },
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
        showControl = { mode ->
            val held = lease.held.value ?: throw PhoneFailure("not_claimed")
            try {
                withContext(Dispatchers.Main.immediate) { timeoutEpoch = held.epoch }
                screenTimeout.acquire(held.epoch) { lease.held.value?.epoch == held.epoch }
                withContext(Dispatchers.Main.immediate) {
                    if (lease.held.value?.epoch != held.epoch) throw PhoneFailure("not_claimed")
                    if (overlayEpoch != held.epoch) {
                        // Main-thread replacement is synchronous: an old exit never overlaps fresh windows.
                        if (overlayEpoch != null || exitingOverlay != null) clearResources(animate = false)
                        val next = PhoneOverlay(context.withAppLocale(settings.appLanguage.value), this@PhoneUseRuntime, held.epoch)
                        try { next.show(mode) } catch (error: Exception) { next.remove(); throw error }
                        overlay = next
                        overlayEpoch = held.epoch
                    }
                    ContextCompat.startForegroundService(context, Intent(context, PhoneControlService::class.java))
                }
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main.immediate) {
                        if (timeoutEpoch == held.epoch) timeoutEpoch = null
                        if (overlayEpoch == held.epoch) clearResources(animate = false)
                    }
                    screenTimeout.release(held.epoch)
                }
                throw error
            }
        },
        releaseControl = { epoch ->
            withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) {
                    if (timeoutEpoch == epoch) timeoutEpoch = null
                    if (overlayEpoch == epoch) clearResources(animate = true)
                }
                screenTimeout.release(epoch)
            }
        },
        action = { name, x, y, endX, endY, duration ->
            cursor.value = PhoneCursor(name, x, y, endX, endY, duration, SystemClock.elapsedRealtime())
        },
        status = { name ->
            if (name == null) withContext(NonCancellable + Dispatchers.Main.immediate) { actionStatus.update(null) }
            else withContext(Dispatchers.Main.immediate) { actionStatus.update(name) }
        },
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
                // StateFlow may conflate release/reclaim. Always compare the live epoch.
                if (overlayEpoch != null && overlayEpoch != lease.held.value?.epoch) clearResources(animate = true)
                timeoutEpoch?.takeIf { epoch -> epoch != lease.held.value?.epoch }?.let { epoch ->
                    timeoutEpoch = null
                    screenTimeout.release(epoch)
                }
            }
        }
        lease.watchIdle(scope, SystemClock::elapsedRealtime)
        lease.watchPermissions(context, scope, settings.phoneUse, askUser.requests, permissions::locked)
    }

    /** Called only on Main. Detach ownership before scheduling a cosmetic exit. */
    private fun clearResources(animate: Boolean) {
        exitingOverlay?.remove()
        exitingOverlay = null
        val previous = overlay
        overlay = null
        overlayEpoch = null
        privileged.close()
        actionStatus.reset()
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
        if (lease.held.value?.mode == mode) return permissions.entryFailure(mode, false) == null
        if (mode == "root") return permissions.probeRoot() &&
            permissions.granted(mode).filterKeys { it != "root" }.values.all { it }
        return permissions.ready(mode)
    }
    fun revoke(owner: PhoneControlLease.Owner, epoch: Long) = lease.revokeByUser(owner, epoch)
}

internal data class PhoneCursor(
    val kind: String, val x: Int, val y: Int, val endX: Int, val endY: Int,
    val durationMs: Long, val startedAt: Long,
)
