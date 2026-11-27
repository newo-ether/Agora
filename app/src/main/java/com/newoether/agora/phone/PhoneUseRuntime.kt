package com.newoether.agora.phone

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import androidx.core.content.ContextCompat
import com.newoether.agora.R
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.util.appLanguageResources
import com.newoether.agora.util.withAppLocale
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ShellConfirmationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    private val confirmation: ShellConfirmationController,
) {
    val privileged = PhonePrivilegedConnection(context) { epoch ->
        lease.releaseEpoch(epoch, "permission_lost:shizuku_service_disconnected")
    }
    val permissions = PhonePermissions(context, privileged)
    private val _background = MutableStateFlow<PhoneVirtualScreen?>(null)
    /** Background screen of the current Shizuku lease, when the setting was on at claim. */
    val background: StateFlow<PhoneVirtualScreen?> = _background.asStateFlow()
    private var virtual: PhoneVirtualScreen?
        get() = _background.value
        set(value) { _background.value = value }
    private var preview: PhoneBackgroundPreview? = null
    /** True while [PhoneBackgroundActivity] shows the background screen; the floating card hides. */
    val fullscreen = MutableStateFlow(false)
    /** Screen bounds of the floating card, where the full-screen view grows from and returns to. */
    @Volatile var previewCard: android.graphics.Rect? = null
    /** True after the user closed the floating card; the notification offers to bring it back. */
    val previewClosed = MutableStateFlow(false)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val actionStatus = PhoneActionStatus(
        CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate), SystemClock::elapsedRealtime,
    )
    private var statusOwnership: Pair<Long, PhoneControlLease.Owner>? = null
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
            val screen = virtual.takeIf { mode == "shizuku" }
            if (screen != null) VirtualPhoneDevice(context, screen, privileged)
            else AndroidPhoneDevice(context, mode, privileged)
        },
        ownPackage = context.packageName,
        launcher = { context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName },
        persist = { source -> withContext(Dispatchers.IO) {
            images.persistBitmap(source, "phone")
        } },
        // On the background screen the controls stay on the phone; only the cursor is hidden.
        captured = { capture ->
            val screen = virtual
            val active = overlay
            when {
                screen != null && active != null && active.effectDisplayId != Display.INVALID_DISPLAY ->
                    active.captureOnDisplay(screen, cursorShown()) { capture() }
                screen != null -> capture()
                else -> (active ?: throw PhoneFailure("overlay_unavailable")).capture(capture)
            }
        },
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
                        val phoneUse = settings.phoneUse.value
                        val inBackground = mode == "shizuku" && phoneUse.backgroundActive
                        try { next.show(mode, effectOnPhone = !inBackground) } catch (error: Exception) { next.remove(); throw error }
                        overlay = next
                        overlayEpoch = held.epoch
                        if (inBackground) {
                            val spec = PhoneBackgroundGeometry.resolve(PhoneBackgroundGeometry.native(context), phoneUse)
                            // Each (re)open moves the border and cursor onto the new display id. The
                            // display lock is held here, so this must not wait for another thread.
                            val screen = PhoneVirtualScreen(context, privileged, spec) { id ->
                                scope.launch(Dispatchers.Main.immediate) {
                                    if (overlay === next) next.attachEffect(displays.getDisplay(id))
                                }
                            }
                            virtual = screen
                            preview = PhoneBackgroundPreview(context.withAppLocale(settings.appLanguage.value), screen, this@PhoneUseRuntime)
                                .also { it.show() }
                        }
                    }
                    ContextCompat.startForegroundService(context, Intent(context, PhoneControlService::class.java))
                }
                virtual?.ensureOpen()
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
        approve = { identity, title ->
            val res = context.appLanguageResources(settings.appLanguage.value)
            val summary = if (title.isBlank()) res.getString(R.string.phone_claim_request_untitled)
                else res.getString(R.string.phone_claim_request, title)
            confirmation.confirmPhone(res.getString(R.string.phone_title), summary, identity.conversationId)
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
            // Background coordinates only match the cursor once the effect draws on that display.
            if (virtual == null || overlay?.effectDisplayId?.let { it != Display.INVALID_DISPLAY } == true) {
                cursor.value = PhoneCursor(name, x, y, endX, endY, duration, SystemClock.elapsedRealtime())
            }
        },
        status = { name ->
            if (name != null) withContext(Dispatchers.Main.immediate) {
                val (epoch, owner) = syncActionStatus() ?: throw PhoneFailure("not_claimed")
                val state = registry.get(owner.conversationId) ?: throw PhoneFailure("stale_identity")
                val activity = PhoneActionStatus.activity(
                    state.streamingMessage.value, owner.runId,
                    context.appLanguageResources(settings.appLanguage.value), name,
                )
                    ?.takeIf { it.running }
                    ?: throw PhoneFailure("stale_identity")
                actionStatus.awaitVisible(activity) {
                    lease.held.value?.let { it.epoch == epoch && it.owner == owner } == true &&
                        PhoneActionStatus.isRunning(state.streamingMessage.value, activity)
                }
            }
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
            lease.held.map { it?.let { held -> held.epoch to held.owner } }.distinctUntilChanged()
                .collectLatest { ownership ->
                    if (syncActionStatus() != ownership) return@collectLatest
                    val (_, owner) = ownership ?: return@collectLatest
                    val state = registry.get(owner.conversationId) ?: return@collectLatest
                    // Notifications may contain old values; only live snapshots own the display.
                    combine(state.streamingMessage, state.generating, settings.appLanguage) { _, _, _ -> Unit }.collect {
                        if (syncActionStatus() == ownership) {
                            actionStatus.present(if (state.generating.value) {
                                PhoneActionStatus.activity(
                                    state.streamingMessage.value, owner.runId,
                                    context.appLanguageResources(settings.appLanguage.value),
                                )
                            } else null)
                        }
                    }
                }
        }
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

    /** Main-only, shared by synchronous effects, delayed collectors and old resource cleanup. */
    private fun syncActionStatus(): Pair<Long, PhoneControlLease.Owner>? {
        val ownership = lease.held.value?.let { it.epoch to it.owner }
        if (statusOwnership != ownership) {
            statusOwnership = ownership
            actionStatus.reset()
        }
        return ownership
    }

    /** Called only on Main. Detach ownership before scheduling a cosmetic exit. */
    private fun clearResources(animate: Boolean) {
        exitingOverlay?.remove()
        exitingOverlay = null
        val previous = overlay
        overlay = null
        overlayEpoch = null
        preview?.let { if (animate) it.dismiss() else it.remove() }
        preview = null
        previewClosed.value = false
        virtual?.release()
        virtual = null
        fullscreen.value = false
        privileged.close()
        syncActionStatus()
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

    /** Stop from the full-screen view, same as the notification's Stop. */
    fun stopByUser() {
        val held = lease.held.value ?: return
        lease.revokeEpochByUser(held.owner.conversationId, held.epoch)
    }

    /** Main only. Hides the floating card while the background screen keeps running. */
    fun closePreview() {
        val shown = preview ?: return
        preview = null
        previewClosed.value = true
        shown.dismiss()
    }

    /** Main only. Brings the floating card back where it was left. */
    fun reopenPreview() {
        val screen = virtual ?: return
        if (preview != null) return
        previewClosed.value = false
        preview = PhoneBackgroundPreview(context.withAppLocale(settings.appLanguage.value), screen, this)
            .also { it.show() }
    }

    /** Main-safe; forwards one touch of the full-screen view to the background display. */
    fun touchBackground(action: Int, x: Float, y: Float, downTime: Long) {
        if (virtual == null) return
        privileged.displayTouch(action, x, y, downTime)
    }

    suspend fun backgroundBack() {
        val screen = virtual ?: return
        runCatching { privileged.command("shizuku", "input -d ${screen.ensureOpen()} keyevent 4") }
    }

    private fun cursorShown(): Boolean = cursor.value?.let {
        SystemClock.elapsedRealtime() - it.startedAt < it.durationMs + 1000
    } == true
}

internal data class PhoneCursor(
    val kind: String, val x: Int, val y: Int, val endX: Int, val endY: Int,
    val durationMs: Long, val startedAt: Long,
)
