package com.newoether.agora.remote

/** Old installations persisted the address as the temporary title. */
internal fun remoteDeviceName(name: String): String = name.trim().takeUnless {
    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
}.orEmpty()

internal enum class RemoteDeviceStatus { IDLE, CONNECTING, CONNECTED, ERROR }
internal data class RemoteDevice(
    val id: String, val name: String, val address: String,
    val status: RemoteDeviceStatus = RemoteDeviceStatus.IDLE, val failure: RemoteFailure? = null,
    val desktopStatus: RemoteDeviceStatus = RemoteDeviceStatus.IDLE,
    val adapters: List<RemoteAdapter> = emptyList(),
    val multiAdapter: Boolean = false,
)
internal data class RemoteNotice(val stage: String, val failure: RemoteFailure, val selection: Long, val detail: String? = null, val code: String? = null) {
    val canRetryRead: Boolean get() = stage in setOf("restore_failed", "check_failed", "read_failed", "page_failed", "payload_failed", "models_failed")
}
internal enum class RemoteDelivery { SUBMITTING, ACCEPTED, DELIVERED, REJECTED, UNKNOWN }
internal data class RemoteAttempt(
    val clientId: String, val text: String, val delivery: RemoteDelivery, val turnId: String? = null,
    val accessKey: String? = null, val receiptKind: String = "",
)
internal data class RemoteState(
    val devices: List<RemoteDevice> = emptyList(), val deviceId: String? = null,
    val adapterSelections: Map<String, String> = emptyMap(),
    val sessions: List<RemoteSession> = emptyList(), val sessionCursor: String? = null,
    val session: RemoteSession? = null, val nodes: List<RemoteMessageNode> = emptyList(),
    val messageGroups: List<RemoteMessageGroup> = emptyList(),
    val hydrationEnabled: Boolean = false, val hydrationRevision: Long = 0,
    val historyCursor: String? = null,
    val queued: List<RemoteQueuedMessage> = emptyList(),
    val drafts: Map<String, String> = emptyMap(), val attempts: Map<String, RemoteAttempt> = emptyMap(),
    val attachments: Map<String, List<com.newoether.agora.model.SelectedAttachment>> = emptyMap(),
    val saving: Boolean = false, val loading: Boolean = false, val loadingMore: Boolean = false, val failure: RemoteFailure? = null,
    val restoring: Boolean = true, val storageError: Boolean = false, val addingDevice: Boolean = false,
    val editedDeviceId: String? = null,
    val runtime: RemoteRuntime? = null, val lastKnownModel: String? = null,
    val models: List<RemoteModel> = emptyList(),
    val controlling: Boolean = false, val composerFocusOwner: String? = null,
    val draftSessionId: String? = null, val draftSettings: RemoteSettings = RemoteSettings(),
    val modelsLoading: Boolean = false,
    val sessionOwners: Map<String, String> = emptyMap(),
    val sessionStatuses: Map<String, RemoteSessionStatus> = emptyMap(),
    val viewedTurns: Map<String, String> = emptyMap(),
    val settingsRevision: Long = 0,
    val optimisticSettings: Map<String, RemoteSettings> = emptyMap(),
    val stoppingOwner: String? = null, val stoppingTurnId: String? = null, val stoppingTurnEnded: Boolean = false,
    val stoppingAccessKey: String? = null,
    val pendingControls: Map<String, String?> = emptyMap(),
) {
    val error: Boolean get() = failure != null
    val selectedAdapter: RemoteAdapter get() {
        val device = devices.firstOrNull { it.id == deviceId }
        val adapters = device?.adapters.orEmpty()
        if (device?.multiAdapter != true) return RemoteAdapter.legacy(device?.desktopStatus ?: RemoteDeviceStatus.IDLE)
        return adapters.firstOrNull { it.id == adapterSelections[deviceId] }
            ?: adapterSelections[deviceId]?.let { RemoteAdapter(it, "", "", "unavailable") }
            ?: adapters.firstOrNull { it.family == "codex" && it.origin == "desktop" }
            ?: adapters.firstOrNull() ?: RemoteAdapter("unconfigured", "", "", "unavailable")
    }
    fun capabilitiesFor(session: RemoteSession): RemoteCapabilities {
        if (selectedAdapter.legacy) return selectedAdapter.capabilities
        val access = session.access?.takeIf { it.nativeScopeId.isNotBlank() &&
            it.nativeScopeId == selectedAdapter.nativeScopeId && it.canonicalSessionRef.isNotBlank() && it.binding.isNotBlank() }
        return access?.capabilities?.intersect(selectedAdapter.capabilities)
            ?: RemoteCapabilities(history = selectedAdapter.capabilities.history)
    }
    val capabilities: RemoteCapabilities get() = session?.takeUnless { isDraft }?.let(::capabilitiesFor)
        ?: selectedAdapter.capabilities
    val accessPath: RemoteAccessPath? get() = selectedAdapter.takeUnless { it.legacy }
        ?.let { RemoteAccessPath(it.id, session?.access?.binding, it.nativeScopeId) }
    fun sessionKey(id: String): String = if (selectedAdapter.legacy) "$deviceId/$id"
        else "$deviceId/@access/" + remoteIdentity(selectedAdapter.id, selectedAdapter.nativeScopeId, id)
    fun canonicalOwner(session: RemoteSession): String {
        val access = session.access?.takeIf { !selectedAdapter.legacy &&
            it.nativeScopeId == selectedAdapter.nativeScopeId && it.binding.isNotBlank() }
        return if (access != null && access.nativeScopeId.isNotBlank() && access.canonicalSessionRef.isNotBlank())
            "$deviceId/@canonical/" + remoteIdentity(access.nativeScopeId, access.canonicalSessionRef)
        else sessionKey(session.id)
    }
    val owner: String? get() = session?.let { draftOwner(it) }
    fun draftOwner(session: RemoteSession): String = sessionOwners[sessionKey(session.id)]
        ?: sessionOwners[canonicalOwner(session)] ?: canonicalOwner(session)
    val payloadOwner: String? get() = session?.let { if (selectedAdapter.legacy) owner else sessionKey(it.id) }
    val accessOwner: String? get() = session?.let {
        if (selectedAdapter.legacy) owner else sessionKey(it.id) + "/" + remoteIdentity(it.access?.binding.orEmpty())
    }
    val confirmationPath: String? get() = if (selectedAdapter.legacy) null else
        "$deviceId/@source/" + remoteIdentity(selectedAdapter.id, selectedAdapter.nativeScopeId)
    val attemptAccessKey: String? get() = confirmationPath
    val verifiedSessionAccess: Boolean get() = selectedAdapter.legacy || isDraft || session?.access?.let {
        it.nativeScopeId.isNotBlank() && it.nativeScopeId == selectedAdapter.nativeScopeId &&
            it.canonicalSessionRef.isNotBlank() && it.binding.isNotBlank()
    } == true
    fun hasUnreadGeneration(sessionId: String): Boolean {
        val status = sessionStatuses[sessionKey(sessionId)] ?: return false
        val completed = status.completedTurnId ?: return false
        val session = sessions.firstOrNull { it.id == sessionId } ?: RemoteSession(sessionId, "", "", 0)
        return status.hasUnreadTurn && viewedTurns[canonicalOwner(session)] != completed
    }
    val isStopping: Boolean get() = stoppingOwner != null && stoppingOwner == owner &&
        stoppingAccessKey == attemptAccessKey
    val isDraft: Boolean get() = session != null && session.id == draftSessionId
    val controlPending: Boolean get() = owner != null && pendingControls.containsKey(owner)
    val selectedModel: String? get() = if (isDraft) {
        draftSettings.model ?: models.firstOrNull { it.isDefault }?.id
    } else runtime?.model ?: lastKnownModel
    val settingsModel: RemoteModel? get() = models.firstOrNull { it.id == selectedModel }
    val effortChoices: List<String> get() = when {
        settingsModel != null -> settingsModel?.reasoningEfforts.orEmpty()
        // Claude's session request API supports named efforts independently of model discovery.
        !isDraft && selectedAdapter.family == "claude" && "effort" in capabilities.settings ->
            models.flatMap { it.reasoningEfforts.orEmpty() }.distinct().ifEmpty {
                listOf("low", "medium", "high", "xhigh", "max")
            }
        else -> emptyList()
    }
    val settingsKey: String? get() = accessOwner?.let {
        if (selectedAdapter.legacy) it else "${owner}/@settings/$it"
    }
    private val settingsProjection: RemoteSettings? get() = if (isDraft) null else optimisticSettings[settingsKey]
    val settingsPending: Boolean get() = settingsProjection != null ||
        optimisticSettings.keys.any { it != settingsKey && it.startsWith("${owner.orEmpty()}/@settings/") }
    val selectedEffort: String? get() = if (isDraft) draftSettings.effort ?: settingsModel?.defaultReasoningEffort
        else settingsProjection?.effort ?: runtime?.settingsChange?.takeIf {
            selectedAdapter.family == "claude" && verifiedSessionAccess &&
                "effort" in capabilities.settings && it.state == "pending"
        }?.requested?.effort ?: runtime?.effort
    val selectedServiceTier: String? get() {
        val tier = when {
            isDraft -> if (draftSettings.updateServiceTier) draftSettings.serviceTier else settingsModel?.defaultServiceTier
            settingsProjection?.updateServiceTier == true -> settingsProjection?.serviceTier
            else -> runtime?.serviceTier
        }
        return tier.takeUnless { it == "default" }
    }
    val serviceTierKnown: Boolean get() = isDraft || settingsProjection?.updateServiceTier == true ||
        runtime?.serviceTierKnown == true || runtime?.serviceTier != null
    private val canControlSettings: Boolean get() = session != null && capabilities.settings.isNotEmpty() &&
        (isDraft || runtime?.status in setOf("idle", "active", "running", "ready", "notLoaded") ||
            selectedAdapter.family == "claude" && verifiedSessionAccess &&
                capabilities.settings == listOf("effort") && runtime?.status == "unknown") &&
        !controlling && !controlPending && !isStopping && attempts[owner]?.delivery !in
            setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED, RemoteDelivery.UNKNOWN)
    val canChooseModel: Boolean get() = capabilities.models && "model" in capabilities.settings && canControlSettings && !settingsPending
    val canEditSettings: Boolean get() = canControlSettings && (settingsModel != null || effortChoices.isNotEmpty())
    val historyBounded: Boolean get() = !selectedAdapter.legacy && capabilities.historyMode == "bounded"
    val canRead: Boolean get() = selectedAdapter.capabilities.history
    val canCreate: Boolean get() = selectedAdapter.origin != "cli" && selectedAdapter.capabilities.create
    val canSend: Boolean get() = if (isDraft) canCreate else if (runtime?.isRunning == true)
        capabilities.activeSend == "native-steer" else capabilities.idleSend
    val canStop: Boolean get() = capabilities.stop && runtime?.activeTurnId != null
    fun settingsForModel(model: RemoteModel) = RemoteSettings(model.id,
        selectedEffort?.takeIf { it in model.reasoningEfforts.orEmpty() } ?: model.defaultReasoningEffort,
        selectedServiceTier?.takeIf { tier -> model.serviceTiers.orEmpty().any { it.id == tier } }, true)
}
