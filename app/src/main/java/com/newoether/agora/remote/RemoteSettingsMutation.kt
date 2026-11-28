package com.newoether.agora.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Owns one active native write and one merged, undispatched patch per conversation. */
internal class RemoteSettingsMutation(
    private val scope: CoroutineScope,
    private val mutableState: MutableStateFlow<RemoteState>,
    private val clientForState: (RemoteState) -> FiloClient?,
    private val connectionForDevice: (String?) -> FiloClient?,
    private val sameSource: (RemoteState, FiloClient) -> Boolean,
    private val selectionEpoch: () -> Long,
    private val readEpoch: () -> Long,
    private val acceptPage: suspend (FiloClient, String, Long, RemoteConversationPage) -> Unit,
    private val readsSettled: () -> Unit,
    private val report: (Exception) -> RemoteFailure?,
) {
    private data class Patch(val settings: RemoteSettings, val selection: Long)
    private class Owner(
        val id: String,
        val snapshot: RemoteState,
        val canonical: String,
        val access: String?,
        val device: String,
        val session: String,
        val client: FiloClient,
        val connection: FiloClient,
        var pending: Patch?,
        var active: Patch? = null,
        var confirmed: RemoteRuntime? = null,
    )
    private val owners = mutableMapOf<String, Owner>()
    private val pendingConfirmations = mutableMapOf<String, Pair<Owner, String>>()
    private var settingsVersion = 0L
    fun snapshotVersion(): Long = settingsVersion
    fun acceptsSnapshot(version: Long, ownerId: String): Boolean =
        version == settingsVersion && owners[ownerId]?.active == null
    fun confirmedRuntime(ownerId: String): RemoteRuntime? = owners[ownerId]?.takeIf {
        connectionForDevice(it.device) === it.connection
    }?.confirmed

    /** Settles delayed Claude request evidence through the existing failure notice owner. */
    fun observeRuntime(snapshot: RemoteState, runtime: RemoteRuntime?) {
        val key = snapshot.settingsKey ?: return
        val (owner, changeId) = pendingConfirmations[key] ?: return
        if (connectionForDevice(owner.device) !== owner.connection || !sameSource(owner.snapshot, owner.client)) {
            pendingConfirmations.remove(key)
            return
        }
        val change = runtime?.settingsChange ?: return
        if (change.id != changeId || change.state == "pending") return
        pendingConfirmations.remove(key)
        if (change.state == "failed" && isSelected(owner)) {
            report(FiloHttpException(409, "settings_unavailable"))
        }
    }

    fun hasConflict(snapshot: RemoteState): Boolean = owners.values.any {
        it.canonical == snapshot.owner && it.id != snapshot.settingsKey && (it.active != null || it.pending != null)
    }

    fun setThinkingEnabled(enabled: Boolean) {
        val snapshot = mutableState.value
        if (enabled == (snapshot.selectedEffort != null && snapshot.selectedEffort != "none")) return
        val effort = if (enabled) snapshot.settingsModel?.defaultReasoningEffort?.takeIf {
            it != "none" && it in snapshot.effortChoices
        } ?: snapshot.effortChoices.firstOrNull { it != "none" } else "none"
        effort?.let(::setThinkingLevel)
    }

    fun setThinkingLevel(effort: String) {
        val snapshot = mutableState.value
        if ("effort" !in snapshot.capabilities.settings || effort == snapshot.selectedEffort || effort !in snapshot.effortChoices) return
        change(RemoteSettings(effort = effort))
    }

    fun setServiceTierEnabled(enabled: Boolean) {
        val snapshot = mutableState.value
        if (enabled == (snapshot.selectedServiceTier != null)) return
        val model = snapshot.settingsModel ?: return
        val tier = if (enabled) model.defaultServiceTier?.takeIf { value -> model.serviceTiers.orEmpty().any { it.id == value } }
            ?: model.serviceTiers?.firstOrNull()?.id ?: return else null
        setServiceTier(tier)
    }

    fun setServiceTier(tier: String?) {
        val snapshot = mutableState.value
        if ("serviceTier" !in snapshot.capabilities.settings || snapshot.settingsModel?.serviceTiers == null || (tier == snapshot.selectedServiceTier && snapshot.serviceTierKnown) ||
            tier != null && snapshot.settingsModel?.serviceTiers.orEmpty().none { it.id == tier }) return
        change(RemoteSettings(serviceTier = tier, updateServiceTier = true))
    }

    private fun change(settings: RemoteSettings) {
        val snapshot = mutableState.value
        if (!snapshot.canEditSettings) return
        if (snapshot.isDraft) {
            mutableState.value = snapshot.copy(draftSettings = snapshot.draftSettings.merge(settings))
            return
        }
        val ownerId = snapshot.settingsKey ?: return
        val canonical = snapshot.owner ?: return
        if (hasConflict(snapshot)) return
        val device = snapshot.deviceId ?: return
        val session = snapshot.session ?: return
        val client = clientForState(snapshot) ?: return
        val connection = connectionForDevice(device) ?: return
        val existing = owners[ownerId]
        // A replaced connection cannot inherit a write admitted with different credentials.
        if (existing != null && existing.connection !== connection) return
        val patch = Patch(settings, selectionEpoch())
        if (existing != null) {
            existing.pending = patch.copy(settings = (existing.pending?.settings ?: RemoteSettings()).merge(settings))
            publish(existing)
            return
        }
        val owner = Owner(ownerId, snapshot, canonical, snapshot.accessOwner, device, session.id, client, connection,
            patch, confirmed = snapshot.runtime)
        owners[ownerId] = owner
        publish(owner)
        scope.launch { drain(owner) }
    }

    /** Model changes retain the ordinary control barrier rather than becoming optimistic. */
    suspend fun changeModel(client: FiloClient, session: String, settings: RemoteSettings, snapshot: RemoteState) {
        val ownerId = snapshot.settingsKey ?: return
        val canonical = snapshot.owner ?: return
        if (hasConflict(snapshot)) return
        val device = snapshot.deviceId ?: return
        val connection = connectionForDevice(device) ?: return
        val selected = selectionEpoch()
        val generation = readEpoch()
        val owner = Owner(ownerId, snapshot, canonical, snapshot.accessOwner, device, session, client, connection, null)
        writeAndRead(owner, Patch(settings, selected), generation)
    }

    private suspend fun drain(owner: Owner) {
        var succeeded = false
        try {
            while (connectionForDevice(owner.device) === owner.connection && sameSource(owner.snapshot, owner.client)) {
                val patch = owner.pending ?: break
                owner.pending = null
                owner.active = patch
                publish(owner)
                try {
                    succeeded = false
                    val generation = readEpoch()
                    writeAndRead(owner, patch, generation)
                    succeeded = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isSelected(owner)) {
                        val failure = report(error)
                        mutableState.value = mutableState.value.copy(failure = failure)
                    }
                } finally {
                    // The pending patch may contain a newer choice for either field.
                    owner.active = null
                    publish(owner)
                    if (isSelected(owner)) {
                        mutableState.value = mutableState.value.copy(
                            settingsRevision = mutableState.value.settingsRevision + 1,
                        )
                    }
                }
            }
        } finally {
            owner.active = null
            owner.pending = null
            publish(owner)
            owners.remove(owner.id)
            // Fence already-buffered stream pages before releasing the final presentation.
            if (succeeded && isSelected(owner)) readsSettled()
        }
    }

    private suspend fun writeAndRead(owner: Owner, patch: Patch, generation: Long) {
        if (!sameSource(owner.snapshot, owner.client)) return
        settingsVersion++
        try {
            pendingConfirmations.remove(owner.id)
            owner.client.updateSettings(owner.session, patch.settings)
            if (!sameSource(owner.snapshot, owner.client)) return
            val page = owner.client.conversation(owner.session)
            if (!sameSource(owner.snapshot, owner.client)) return
            owner.confirmed = page.runtime
            if (isCurrent(owner, patch.selection)) acceptPage(owner.client, owner.session, generation, page)
            val change = page.runtime?.settingsChange
            if (owner.snapshot.selectedAdapter.family == "claude" && change != null) {
                check((patch.settings.model == null || change.requested.model == patch.settings.model) &&
                    (patch.settings.effort == null || change.requested.effort == patch.settings.effort)) {
                    "Native settings evidence belongs to another change"
                }
                check(change.state != "failed") { "Native settings differ: model=${change.actualModel}, effort=${change.actualEffort}" }
                if (change.state == "pending") {
                    pendingConfirmations[owner.id] = owner to change.id
                    return
                }
            }
            pendingConfirmations.remove(owner.id)
            check(confirms(page.runtime, patch.settings)) { "Native settings were not confirmed" }
        } finally {
            settingsVersion++
        }
    }

    private fun isCurrent(owner: Owner, selection: Long): Boolean =
        selection == selectionEpoch() && isSelected(owner)

    private fun isSelected(owner: Owner): Boolean {
        val snapshot = mutableState.value
        return sameSource(owner.snapshot, owner.client) && snapshot.settingsKey == owner.id && snapshot.accessOwner == owner.access && snapshot.deviceId == owner.device &&
            snapshot.session?.id == owner.session && connectionForDevice(owner.device) === owner.connection
    }

    private fun publish(owner: Owner) {
        val snapshot = mutableState.value
        val active = owner.active?.settings
        val pending = owner.pending?.settings
        val projection = if (connectionForDevice(owner.device) !== owner.connection) null else when {
            active != null && pending != null -> active.merge(pending)
            else -> pending ?: active
        }
        mutableState.value = snapshot.copy(
            optimisticSettings = if (projection == null) snapshot.optimisticSettings - owner.id
            else snapshot.optimisticSettings + (owner.id to projection),
        )
    }

    private fun confirms(runtime: RemoteRuntime?, settings: RemoteSettings): Boolean {
        runtime ?: return false
        return (settings.model == null || settings.model == runtime.model) &&
            (settings.effort == null || settings.effort == runtime.effort) &&
            (!settings.updateServiceTier ||
                (runtime.serviceTierKnown || runtime.serviceTier != null) &&
                settings.serviceTier.takeUnless { it == "default" } == runtime.serviceTier.takeUnless { it == "default" })
    }
}
