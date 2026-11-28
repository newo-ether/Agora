package com.newoether.agora.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.diagnostics.DiagnosticRequestContext
import com.newoether.agora.viewmodel.ScrollRequestCoordinator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/** Remote owns saved connections and presentation; native Codex owns durable execution. */
internal class RemoteViewModel(
    private val connections: RemoteConnectionStore,
    private val imageStore: com.newoether.agora.tool.ToolImageStore? = null,
    private val imageCache: RemoteImageCache? = null,
    projectionDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val attachmentStore: RemoteAttachmentStore? = null,
    createClient: (String, String) -> FiloClient = { address, token -> FiloClient(address, token) },
) : ViewModel() {
    private val mutableState = MutableStateFlow(RemoteState())
    private val attachmentDrafts = RemoteAttachmentDrafts(attachmentStore, viewModelScope, mutableState) { owner, error ->
        if (state.value.owner == owner) trace("attachment_failed", error)
    }
    fun addAttachments(owner: String, uris: List<android.net.Uri>) = attachmentDrafts.add(owner, uris)
    fun removeAttachment(owner: String, id: String) = attachmentDrafts.remove(owner, id)
    fun retryAttachment(owner: String, id: String) = attachmentDrafts.retry(owner, id)
    val state = mutableState.asStateFlow()
    private val noticeChannel = Channel<RemoteNotice>(Channel.BUFFERED)
    val notices = noticeChannel.receiveAsFlow()
    private val hydration = RemoteMessageHydration(state, { owner, cursor ->
        val snapshot = state.value
        if (snapshot.payloadOwner != owner) throw CancellationException()
        val client = clientFor(snapshot) ?: throw CancellationException()
        client.conversation(snapshot.session!!.id, cursor)
    }, { trace("payload_failed", it) }, { owner, request ->
        val snapshot = state.value
        if (snapshot.payloadOwner != owner) throw CancellationException()
        val client = clientFor(snapshot) ?: throw CancellationException()
        val store = imageStore ?: throw java.io.IOException("Image storage is unavailable")
        val cache = imageCache ?: throw java.io.IOException("Image cache is unavailable")
        cache.load(owner + "/" + request.id + "/" + request.revision + "/" + request.imageIndex) {
            client.image(snapshot.session!!.id, request, store::persistStream)
        }
    }, projectionDispatcher = projectionDispatcher)
    private val historyMutation = kotlinx.coroutines.sync.Mutex()
    private fun payloadOwner(owner: String): String = state.value.takeIf { it.owner == owner }?.payloadOwner
        ?: throw CancellationException()
    fun cachedMessage(owner: String, id: String) = state.value.messageGroups.firstOrNull { it.stub.id == id }
        ?.let { hydration.cachedMessage(payloadOwner(owner), it) }
    fun observeMessage(owner: String, id: String) = hydration.observeMessage(payloadOwner(owner), id)
    suspend fun loadToolImage(owner: String, id: String, revision: String) =
        hydration.loadToolImage(payloadOwner(owner), id, revision)
    suspend fun searchMessages(owner: String, ids: List<String>) = try {
        hydration.loadMessages(payloadOwner(owner), ids)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { trace("payload_failed", error); emptyList() }
    private val scrollRequests = ScrollRequestCoordinator()
    val animatedScrollRequest = scrollRequests.request
    fun completeAnimatedScroll(id: Long) = scrollRequests.complete(id)
    private val checkSlots = Semaphore(2)
    private var epoch = 0L
    private var selectionEpoch = 0L
    private var visible = false
    private var polling: Job? = null
    private var paging: Job? = null
    private var statusPolling: Job? = null
    private var visibleSessions: List<String> = emptyList()
    private var modelLoading: Job? = null
    private var modelEpoch = 0L
    private val createdAt = System.nanoTime()
    private val diagnosticContext = DiagnosticRequestContext(
        requestId = UUID.randomUUID().toString(), provider = "Filo", model = "Codex", requestKind = "remote",
    )

    private val deviceDirectory = RemoteDeviceDirectory(
        connections = connections,
        scope = viewModelScope,
        mutableState = mutableState,
        checkSlots = checkSlots,
        createClient = createClient,
        selectionEpoch = { selectionEpoch },
        selectDevice = ::selectDevice,
        refresh = ::refresh,
        updateDevice = ::updateDevice,
        sourcesChanged = ::sourcesChanged,
        report = { stage, error -> trace(stage, error) },
    )
    private val clients get() = deviceDirectory.clients
    private val scopedClients = mutableMapOf<Pair<FiloClient, RemoteAccessPath>, FiloClient>()
    private val scopedOrigins = java.util.WeakHashMap<FiloClient, FiloClient>()
    private fun clientFor(snapshot: RemoteState): FiloClient? {
        val base = clients[snapshot.deviceId] ?: return null
        val access = snapshot.accessPath ?: return base
        scopedClients.keys.removeAll { it.first !== base || it.second != access }
        return scopedClients.getOrPut(base to access) { base.scoped(access).also { scopedOrigins[it] = base } }
    }
    private fun sameConnection(id: String?, client: FiloClient): Boolean =
        clients[id]?.let { it === client || scopedOrigins[client] === it } == true
    private fun sameSource(snapshot: RemoteState, client: FiloClient): Boolean {
        if (!sameConnection(snapshot.deviceId, client)) return false
        if (snapshot.selectedAdapter.legacy) return state.value.devices.firstOrNull {
            it.id == snapshot.deviceId }?.multiAdapter != true
        val current = state.value.devices.firstOrNull { it.id == snapshot.deviceId }?.adapters
            ?.firstOrNull { it.id == snapshot.selectedAdapter.id } ?: return false
        return current.nativeScopeId.isNotBlank() && current.nativeScopeId == snapshot.selectedAdapter.nativeScopeId
    }
    private val settingsMutation = RemoteSettingsMutation(
        scope = viewModelScope, mutableState = mutableState,
        clientForState = ::clientFor, connectionForDevice = { clients[it] }, sameSource = ::sameSource,
        selectionEpoch = { selectionEpoch }, readEpoch = { epoch },
        acceptPage = { client, id, generation, page -> applyPage(client, id, generation, page, confirmedSettings = true) },
        readsSettled = { refresh(preserveRuntime = true) }, report = { trace("control_failed", it) },
    )

    private val controls = RemoteControl(viewModelScope, mutableState, ::clientFor, ::sameSource,
        settingsMutation::hasConflict, { selectionEpoch }, { trace("control_failed", it) })

    private val submission = RemoteSubmission(
        viewModelScope, mutableState, ::clientFor, ::sameSource, { selectionEpoch }, { visible }, ::refresh,
        attachmentStore, scrollRequests, { stage, error, notify -> trace(stage, error, notify) },
        { noticeChannel.trySend(it) }, settingsMutation::hasConflict,
    )

    init { trace("owner_created"); restoreConnections() }

    private fun trace(stage: String, error: Exception? = null, notify: Boolean = true): RemoteFailure? {
        val failure = error?.let(::classifyRemoteFailure)
        if (failure != null && notify) noticeChannel.trySend(RemoteNotice(stage, failure, selectionEpoch, remoteErrorDetail(error), remoteErrorCode(error)))
        val suffix = if (failure == null) "" else ".${failure.name}.${error.javaClass.simpleName}"
        // Preserve the existing privacy wrapper and diagnostic logging preferences.
        if (failure != null) runCatching {
            com.newoether.agora.util.DebugLog.w("AgoraRemote", "remote.$stage$suffix" +
                (if (error is FiloHttpException) " code=${error.status}" else "") +
                " cause=${error.cause?.javaClass?.simpleName.orEmpty()}")
        }
        DeveloperDiagnostics.recordHttpStage(diagnosticContext, "remote.$stage$suffix",
            (System.nanoTime() - createdAt) / 1_000_000,
            "addresses=${state.value.devices.size}" + if (error is FiloHttpException) " code=${error.status}" else "")
        return failure
    }

    fun isNoticeCurrent(notice: RemoteNotice): Boolean = notice.selection == selectionEpoch
    fun retryNotice(notice: RemoteNotice) {
        if (!isNoticeCurrent(notice)) return
        when (notice.stage) {
            "restore_failed" -> restoreConnections()
            "page_failed" -> loadMore()
            "payload_failed" -> mutableState.value = state.value.copy(hydrationRevision = state.value.hydrationRevision + 1)
            "models_failed" -> refreshModels()
            "check_failed", "read_failed" -> retryConnection()
        }
    }

    override fun onCleared() {
        trace("owner_cleared")
        noticeChannel.close()
        super.onCleared()
    }

    fun restoreConnections() = deviceDirectory.restoreConnections()
    suspend fun usage(): RemoteUsage? {
        val snapshot = state.value
        if (!snapshot.capabilities.usage) return null
        val selected = selectionEpoch
        return try { clientFor(snapshot)?.usage()?.takeIf { selected == selectionEpoch } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (selected == selectionEpoch) trace("usage_failed", error); null }
    }

    fun addDevice() = editDevice(null)

    fun editorConnection(): RemoteConnection? = deviceDirectory.editorConnection()

    fun editDevice(id: String?) {
        if (state.value.saving || state.value.restoring || (id != null && id !in clients)) return
        selectionEpoch++
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null, stoppingTurnEnded = false)
        invalidateReads()
        mutableState.value = state.value.copy(deviceId = null, session = null, addingDevice = true,
            editedDeviceId = id, storageError = false, failure = null, lastKnownModel = null)
    }

    fun saveDevice(address: String, token: String, name: String? = null) =
        deviceDirectory.saveDevice(address, token, name)

    fun removeDevice(id: String) = deviceDirectory.removeDevice(id)


    private fun updateDevice(id: String, update: (RemoteDevice) -> RemoteDevice) {
        mutableState.value = state.value.copy(devices = state.value.devices.map { if (it.id == id) update(it) else it })
    }

    fun selectDevice(id: String?) {
        if (id != null && id !in clients) return
        scrollRequests.clear()
        selectionEpoch++
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null, stoppingTurnEnded = false)
        invalidateReads()
        modelEpoch++
        modelLoading?.cancel()
        mutableState.value = state.value.copy(deviceId = id, addingDevice = false, editedDeviceId = null,
            sessions = emptyList(), sessionCursor = null,
            session = null, nodes = emptyList(), messageGroups = emptyList(), historyCursor = null, queued = emptyList(), failure = null,
            runtime = null, lastKnownModel = null, models = emptyList(), modelsLoading = false, composerFocusOwner = null,
            draftSessionId = null, draftSettings = RemoteSettings())
        val current = state.value
        val selectedSource = current.selectedAdapter
        mutableState.value = current.copy(adapterSelections = if (id != null &&
            current.devices.firstOrNull { it.id == id }?.multiAdapter == true && id !in current.adapterSelections)
            current.adapterSelections + (id to selectedSource.id) else current.adapterSelections)
        refresh()
    }

    private fun sourcesChanged(id: String, previous: List<RemoteAdapter>, wasMulti: Boolean) {
        val snapshot = state.value
        if (snapshot.deviceId != id) return
        val current = snapshot.selectedAdapter
        val old = previous.firstOrNull { it.id == current.id }
        val multi = snapshot.devices.firstOrNull { it.id == id }?.multiAdapter == true
        val scopeChanged = multi && old != null && old.nativeScopeId != current.nativeScopeId
        if (scopeChanged || wasMulti != multi) {
            if (multi && id !in snapshot.adapterSelections) mutableState.value = snapshot.copy(
                adapterSelections = snapshot.adapterSelections + (id to current.id))
            selectDevice(id)
        }
    }

    fun selectAdapter(id: String) {
        val snapshot = state.value
        val device = snapshot.deviceId ?: return
        if (snapshot.selectedAdapter.id == id || snapshot.devices.firstOrNull { it.id == device }
                ?.adapters.orEmpty().none { it.id == id }) return
        mutableState.value = snapshot.copy(adapterSelections = snapshot.adapterSelections + (device to id))
        selectDevice(device)
    }

    fun selectSession(session: RemoteSession?) {
        scrollRequests.clear()
        selectionEpoch++
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null, stoppingTurnEnded = false)
        invalidateReads()
        modelEpoch++
        modelLoading?.cancel()
        mutableState.value = state.value.copy(modelsLoading = false)
        mutableState.value = state.value.copy(session = session, nodes = emptyList(), messageGroups = emptyList(), historyCursor = null, queued = emptyList(), failure = null, runtime = null, lastKnownModel = null, composerFocusOwner = null,
            draftSessionId = null, draftSettings = RemoteSettings())
        refresh()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        trace(if (value) "visible" else "hidden")
        if (value) refresh() else {
            invalidateReads()
            modelEpoch++
            modelLoading?.cancel()
            mutableState.value = state.value.copy(modelsLoading = false)
        }
    }

    private fun invalidateReads(preserveRuntime: Boolean = false) {
        hydration.resetStreaming()
        epoch++
        polling?.cancel()
        paging?.cancel()
        statusPolling?.cancel()
        deviceDirectory.observeDesktop(null)
        // Suspend control readiness, not the last native presentation. Reconnecting is not completion.
        mutableState.value = state.value.copy(loading = false, loadingMore = false,
            runtime = state.value.runtime.takeIf { preserveRuntime }, hydrationEnabled = false)
    }

    /**
     * Re-reads the device's model catalog, e.g. when the model menu opens, so a model published
     * while the page stayed open appears without reconnecting. The current list stays shown meanwhile.
     */
    fun refreshModels() {
        val id = state.value.deviceId ?: return
        if (!state.value.capabilities.models) return
        val client = clientFor(state.value) ?: return
        if (!visible || state.value.devices.firstOrNull { it.id == id }?.status != RemoteDeviceStatus.CONNECTED) return
        loadModels(id, client, notifyFailure = true)
    }
    private fun loadModels(id: String, client: FiloClient, notifyFailure: Boolean = false) {
        if (!state.value.capabilities.models) return
        if (modelLoading?.isActive == true) return
        val modelGeneration = ++modelEpoch
        mutableState.value = state.value.copy(modelsLoading = true)
        modelLoading = viewModelScope.launch {
            try {
                val models = client.models()
                if (modelGeneration == modelEpoch && sameConnection(id, client) && state.value.deviceId == id) {
                    mutableState.value = state.value.copy(models = models)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (modelGeneration == modelEpoch && sameConnection(id, client) && state.value.deviceId == id) {
                    trace("models_failed", error, notify = notifyFailure)
                }
            }
            finally {
                if (modelGeneration == modelEpoch) mutableState.value = state.value.copy(modelsLoading = false)
            }
        }
    }
    fun refresh() = refresh(preserveRuntime = false)

    fun retryConnection() {
        val snapshot = state.value
        if (!visible || snapshot.restoring || snapshot.addingDevice || snapshot.loading) return
        if (snapshot.isDraft) {
            val id = snapshot.deviceId ?: return
            mutableState.value = snapshot.copy(loading = true)
            deviceDirectory.checkDevice(id)
        } else refresh()
    }

    private fun refresh(preserveRuntime: Boolean) {
        if (!visible || state.value.restoring || state.value.addingDevice) return
        if (state.value.isDraft) return deviceDirectory.observeDesktop(state.value.deviceId)
        invalidateReads(preserveRuntime)
        val id = state.value.deviceId
        if (id == null) {
            clients.keys.forEach(deviceDirectory::checkDevice)
            return
        }
        val client = clientFor(state.value) ?: return
        if (state.value.devices.firstOrNull { it.id == id }?.status != RemoteDeviceStatus.CONNECTED) {
            mutableState.value = state.value.copy(loading = true)
            deviceDirectory.checkDevice(id)
            return
        }
        deviceDirectory.observeDesktop(id)
        val generation = epoch
        val session = state.value.session
        val capabilities = state.value.capabilities
        if (!capabilities.history) {
            mutableState.value = state.value.copy(loading = false, sessions = emptyList(), failure = null)
            return
        }
        loadModels(id, client)
        mutableState.value = state.value.copy(loading = true)
        polling = viewModelScope.launch {
            var consecutiveFailures = 0
            var notifiedFailure: RemoteFailure? = null
            var liveSubscription = capabilities.live
            do {
                if (state.value.devices.firstOrNull { it.id == id }?.status == RemoteDeviceStatus.ERROR) {
                    updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTING, failure = null) }
                }
                try {
                    if (session == null) {
                        val page = client.refreshSessionCatalog(state.value.sessions)
                        if (generation != epoch) return@launch
                        mutableState.value = state.value.copy(
                            sessions = page.sessions, sessionCursor = page.nextCursor,
                            sessionStatuses = state.value.mergeListedSessionStatuses(id, page),
                            loading = false, failure = null,
                        )
                        startSessionStatusReads()
                    } else {
                        if (!liveSubscription) {
                            val page = client.conversation(session.id)
                            val grant = page.access?.capabilities?.intersect(state.value.selectedAdapter.capabilities)
                            val controlsAvailable = grant?.let {
                                it.live || it.idleSend || it.activeSend == "native-steer" || it.stop || it.settings.isNotEmpty()
                            } == true
                            // A fresh session grant can permit idle controls without a live subscription.
                            applyPage(client, session.id, generation, page, controlReady = controlsAvailable)
                            if (generation != epoch) return@launch
                            liveSubscription = state.value.capabilities.live
                            if (!liveSubscription) break
                        }
                        client.events(session.id).collect { page ->
                            if (generation == epoch) {
                                applyPage(client, session.id, generation, page)
                                consecutiveFailures = 0
                                notifiedFailure = null
                            }
                        }
                    }
                    updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (generation == epoch) {
                        val readFailure = requireNotNull(trace("read_failed", error, notify = false))
                        mutableState.value = state.value.copy(runtime = null)
                        if (remoteErrorCode(error) in setOf("desktop_unavailable", "adapter_unavailable"))
                            deviceDirectory.sourceUnavailable(id, state.value.selectedAdapter.id)
                        // A failed native read is not proof that the device is offline.
                        var reachable = false
                        if (session != null && readFailure in setOf(RemoteFailure.NETWORK, RemoteFailure.SERVICE)) {
                            try {
                                // Read the same original owner when subscription fails; never admit another host.
                                applyPage(client, session.id, generation, client.conversation(session.id), controlReady = false)
                                if (generation != epoch) return@launch
                                reachable = true
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (snapshotError: Exception) {
                                if (generation != epoch) return@launch
                                trace("read_snapshot_failed", snapshotError, notify = false)
                            }
                        }
                        val failure = if (readFailure == RemoteFailure.NETWORK && !reachable) {
                            updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTING, failure = null) }
                            try {
                                val connection = clients[id] ?: return@launch
                                val desktop = checkSlots.withPermit { connection.connect() }
                                if (generation != epoch) return@launch
                                deviceDirectory.admitted(id, connection, desktop)
                                deviceDirectory.observeDesktop(id)
                                reachable = true
                                RemoteFailure.SERVICE
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (healthError: Exception) {
                                if (generation != epoch) return@launch
                                val healthFailure = requireNotNull(trace("read_health_failed", healthError, notify = false))
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.ERROR, failure = healthFailure,
                                    desktopStatus = RemoteDeviceStatus.IDLE) }
                                healthFailure
                            }
                        } else {
                            if (readFailure == RemoteFailure.AUTHENTICATION) {
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.ERROR, failure = readFailure,
                                    desktopStatus = RemoteDeviceStatus.IDLE) }
                            } else if (readFailure in setOf(RemoteFailure.SERVICE, RemoteFailure.PROTOCOL,
                                    RemoteFailure.SESSION_BUSY, RemoteFailure.CONTENT_TOO_LARGE)) {
                                updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) }
                            }
                            if (readFailure == RemoteFailure.NETWORK) RemoteFailure.SERVICE else readFailure
                        }
                        consecutiveFailures++
                        // One interrupted GET may recover on the existing three-second loop.
                        // Health failure and non-network errors remain immediately visible.
                        val recovering = session != null && readFailure == RemoteFailure.NETWORK &&
                            reachable && consecutiveFailures == 1
                        mutableState.value = state.value.copy(loading = false, failure = if (recovering) state.value.failure else failure)
                        if (!recovering && notifiedFailure != failure) {
                            noticeChannel.trySend(RemoteNotice("read_failed", failure, selectionEpoch,
                                remoteErrorDetail(error).takeIf { failure == readFailure },
                                remoteErrorCode(error).takeIf { failure == readFailure }))
                            notifiedFailure = failure
                        }
                    }
                }
                if (session == null) break
                delay(3000)
            } while (isActive && visible && generation == epoch)
        }
    }

    private suspend fun applyPage(
        client: FiloClient, sessionId: String, generation: Long, page: RemoteConversationPage,
        controlReady: Boolean = true, confirmedSettings: Boolean = false,
    ) {
        if (generation != epoch) return
        page.access?.let { access ->
            require(!state.value.selectedAdapter.legacy && access.nativeScopeId.isNotBlank() &&
                access.nativeScopeId == state.value.selectedAdapter.nativeScopeId &&
                access.canonicalSessionRef.isNotBlank() && access.binding.isNotBlank())
            val selected = state.value.session ?: return
            selected.access?.let { old -> require(old.nativeScopeId == access.nativeScopeId &&
                old.canonicalSessionRef == access.canonicalSessionRef) { "Native session identity changed" } }
            mutableState.value = state.value.copy(session = selected.copy(access = access))
        }
        val settingsVersion = settingsMutation.snapshotVersion()
        val old = state.value.nodes
        val oldIds = old.mapTo(HashSet()) { it.id }
        val owner = state.value.owner ?: return
        val incoming = mutableListOf(cachePage(payloadOwner(owner), page, live = true))
        var cursor = page.nextCursor
        val cursors = mutableSetOf<String>()
        // Opening publishes exactly the latest packet; only live updates bridge existing history.
        while (old.isNotEmpty() && cursor != null && incoming.last().nodes.none { it.id in oldIds }) {
            require(cursors.add(cursor)) { "Filo history cursor did not advance" }
            val raw = client.conversation(sessionId, cursor)
            if (generation != epoch) return
            val older = cachePage(payloadOwner(owner), raw, live = false)
            incoming += older
            cursor = older.nextCursor
        }
        if (generation != epoch) return
        historyMutation.withLock {
            if (generation != epoch) return
            var nodes = state.value.nodes
            for (chunk in incoming.asReversed()) nodes = admitRemoteNodes(nodes, chunk.nodes)
            val runtime = page.runtime
            val groups = projectRemoteTopology(nodes, runtime)
            for (chunk in incoming.asReversed()) hydration.accept(payloadOwner(owner), chunk, groups, live = false)
            if (generation != epoch) return
            val settingsKey = state.value.settingsKey ?: owner
            val acceptsSettings = confirmedSettings || settingsMutation.acceptsSnapshot(settingsVersion, settingsKey)
            val previousRuntime = settingsMutation.confirmedRuntime(settingsKey) ?: state.value.runtime
            val nativeRuntime = if (acceptsSettings) page.runtime else page.runtime?.copy(
                model = previousRuntime?.model ?: state.value.lastKnownModel,
                effort = previousRuntime?.effort, serviceTier = previousRuntime?.serviceTier,
                serviceTierKnown = previousRuntime?.serviceTierKnown == true,
            )
            if (acceptsSettings && !confirmedSettings) settingsMutation.observeRuntime(state.value, nativeRuntime)
            mutableState.value = state.value.copy(
                nodes = nodes, messageGroups = groups, hydrationEnabled = visible,
                historyCursor = if (old.isEmpty()) incoming.last().nextCursor else state.value.historyCursor,
                queued = page.queued, loading = false, failure = null,
                session = state.value.session?.let { current ->
                    nativeRuntime?.title?.let { current.copy(title = it) } ?: current
                },
                sessions = state.value.sessions.map { current ->
                    if (current.id == sessionId && nativeRuntime?.title != null) current.copy(title = nativeRuntime.title) else current
                },
                runtime = nativeRuntime.takeIf { controlReady },
                lastKnownModel = nativeRuntime?.model ?: state.value.lastKnownModel,
            )
        }
        state.value.deviceId?.let { id -> updateDevice(id) { it.copy(status = RemoteDeviceStatus.CONNECTED, failure = null) } }
        deviceDirectory.observeDesktop(state.value.deviceId.takeIf { visible })
        state.value.attempts[owner]?.let { attempt -> submission.confirm(owner, attempt, page.inputReceipts) }
        controls.settle(page.runtime)
        page.runtime?.takeIf { it.status in setOf("idle", "active", "running", "ready") || it.completedTurnId != null }?.let { runtime ->
            val key = state.value.sessionKey(sessionId)
            val incomingStatus = RemoteSessionStatus(sessionId, if (runtime.isRunning) "active" else runtime.status.takeIf { it in setOf("idle", "active", "running", "ready") },
                runtime.activeTurnId, runtime.completedTurnId)
            mutableState.value = state.value.copy(sessionStatuses = state.value.sessionStatuses +
                (key to reconcileRemoteSessionStatus(state.value.sessionStatuses[key], incomingStatus)))
        }
        page.runtime?.completedTurnId?.let { turn ->
            val address = state.value.deviceId ?: return@let
            val key = state.value.session?.let { state.value.canonicalOwner(it) } ?: return@let
            val storedSession = if (state.value.selectedAdapter.legacy) sessionId else "@canonical/" +
                remoteIdentity(state.value.session!!.access?.nativeScopeId.orEmpty(),
                    state.value.session!!.access?.canonicalSessionRef.orEmpty())
            if (visible && state.value.viewedTurns[key] != turn) {
                mutableState.value = state.value.copy(viewedTurns = state.value.viewedTurns + (key to turn))
                viewModelScope.launch {
                    try { connections.markViewed(address, storedSession, turn) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        trace("mark_viewed_failed", error)
                        mutableState.value = state.value.copy(storageError = true)
                    }
                }
            }
        }
    }

    fun observeSessions(deviceId: String, ids: List<String>) {
        if (state.value.deviceId != deviceId || state.value.session != null) return
        val allowed = state.value.sessions.map { it.id }.toSet()
        val next = ids.distinct().filter { it in allowed }.take(12)
        if (next == visibleSessions && statusPolling?.isActive == true) return
        visibleSessions = next
        startSessionStatusReads()
    }

    private fun startSessionStatusReads() {
        statusPolling?.cancel()
        val snapshot = state.value
        if (!visible || snapshot.session != null || snapshot.addingDevice) return
        val address = snapshot.deviceId ?: return
        if (snapshot.devices.firstOrNull { it.id == address }?.status != RemoteDeviceStatus.CONNECTED) return
        if (!snapshot.canRead) return
        val client = clientFor(snapshot) ?: return
        deviceDirectory.observeDesktop(address)
        val ids = visibleSessions.filter { id -> snapshot.sessions.any { it.id == id } }
        if (ids.isEmpty()) return
        val selected = selectionEpoch
        statusPolling = viewModelScope.launch {
            while (isActive && visible && selected == selectionEpoch) {
                // The list already supplied exact status. Refresh that same page only after the interval.
                delay(3000)
                try {
                    val cursors = state.value.sessions.filter { it.id in ids }.map { it.listCursor }.distinct()
                    for (cursor in cursors) {
                        val page = client.sessions(cursor)
                        if (selected != selectionEpoch || !sameConnection(address, client) || !visible) return@launch
                        mutableState.value = state.value.copy(
                            sessions = state.value.sessions.replaceCatalogPage(cursor, page),
                            sessionStatuses = state.value.mergeListedSessionStatuses(address, page),
                        )
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (selected != selectionEpoch) return@launch
                    trace("session_status_failed", error)
                    // A failed read does not erase the last confirmed presentation.
                    if (error is FiloHttpException && error.status in setOf(401, 403, 404, 501)) return@launch
                }
            }
        }
    }

    fun newSession() {
        val snapshot = state.value
        if (!snapshot.canCreate || snapshot.deviceId !in clients || snapshot.isDraft || snapshot.controlling) return
        scrollRequests.clear()
        selectionEpoch++
        mutableState.value = state.value.copy(controlling = false, stoppingOwner = null, stoppingTurnId = null, stoppingTurnEnded = false)
        invalidateReads()
        val id = UUID.randomUUID().toString()
        // A local composer identity survives promotion to the first native session.
        mutableState.value = state.value.copy(
            session = RemoteSession(id, "", "", 0), draftSessionId = id, draftSettings = RemoteSettings(),
            nodes = emptyList(), messageGroups = emptyList(), historyCursor = null, queued = emptyList(), failure = null,
            lastKnownModel = null, composerFocusOwner = snapshot.sessionKey(id),
        )
        refresh()
    }

    fun completeComposerFocus(owner: String) {
        if (state.value.composerFocusOwner == owner) mutableState.value = state.value.copy(composerFocusOwner = null)
    }

    fun setModel(model: String) {
        val snapshot = state.value
        val session = snapshot.session ?: return
        val available = snapshot.models.firstOrNull { it.id == model } ?: return
        if (!snapshot.canChooseModel || model == snapshot.selectedModel) return
        val settings = RemoteSettings(available.id,
            effort = if (available.reasoningEfforts != null) snapshot.settingsForModel(available).effort else null,
            serviceTier = snapshot.selectedServiceTier?.takeIf { tier -> available.serviceTiers.orEmpty().any { it.id == tier } },
            updateServiceTier = available.serviceTiers != null)
        if (snapshot.isDraft) {
            mutableState.value = snapshot.copy(draftSettings = settings)
        } else {
            val selected = selectionEpoch
            controls.run { client ->
                if (available.reasoningEfforts == null && available.serviceTiers == null) {
                    client.setModel(session.id, model)
                    if (selected == selectionEpoch) refresh()
                } else settingsMutation.changeModel(client, session.id, settings, snapshot)
            }
        }
    }

    fun setThinkingEnabled(enabled: Boolean) = settingsMutation.setThinkingEnabled(enabled)
    fun setThinkingLevel(effort: String) = settingsMutation.setThinkingLevel(effort)
    fun setServiceTierEnabled(enabled: Boolean) = settingsMutation.setServiceTierEnabled(enabled)
    fun setServiceTier(tier: String?) = settingsMutation.setServiceTier(tier)

    fun stop() {
        val snapshot = state.value
        val session = snapshot.session ?: return
        if (!snapshot.canStop || snapshot.runtime?.isRunning != true || settingsMutation.hasConflict(snapshot)) return
        val turn = snapshot.runtime.activeTurnId ?: return
        controls.run(stoppingTurnId = turn) { client -> client.stop(session.id, turn) }
    }

    fun renameSession(id: String, name: String) {
        val snapshot = state.value
        val selected = snapshot.sessions.firstOrNull { it.id == id } ?: return
        if (name.isBlank() || name.length > 4096 || !snapshot.capabilitiesFor(selected).rename) return
        manageSession(id) { it.rename(id, name) }
    }
    fun archiveSession(id: String) {
        val snapshot = state.value
        val selected = snapshot.sessions.firstOrNull { it.id == id } ?: return
        if (snapshot.capabilitiesFor(selected).archive) manageSession(id) { it.archiveSession(id); null }
    }

    private fun manageSession(id: String, operation: suspend (FiloClient) -> RemoteSession?) {
        val snapshot = state.value
        if (snapshot.session != null || snapshot.controlling || snapshot.sessions.none { it.id == id }) return
        val selected = selectionEpoch
        invalidateReads()
        controls.run(snapshot = snapshot.copy(session = snapshot.sessions.first { it.id == id }),
            clientOverride = clientFor(snapshot.copy(session = snapshot.sessions.first { it.id == id }))) { client ->
            try {
                val updated = operation(client)
                if (selected != selectionEpoch || !sameConnection(snapshot.deviceId, client)) return@run
                val managed = snapshot.sessions.first { it.id == id }
                val owner = snapshot.draftOwner(managed)
                mutableState.value = state.value.copy(
                    sessions = state.value.sessions.mapNotNull { session ->
                        if (session.id != id) session else updated?.let { session.copy(title = it.title, updatedAt = it.updatedAt) }
                    },
                    sessionStatuses = if (updated == null) state.value.sessionStatuses - snapshot.sessionKey(id) else state.value.sessionStatuses,
                    drafts = if (updated == null) state.value.drafts - owner else state.value.drafts,
                    attempts = if (updated == null) state.value.attempts - owner else state.value.attempts,
                    failure = null,
                )
            } finally { if (selected == selectionEpoch) startSessionStatusReads() }
        }
    }

    fun loadMore() {
        if (paging?.isActive == true || state.value.loading) return
        val snapshot = state.value
        if (!snapshot.selectedAdapter.legacy && snapshot.session != null && !snapshot.verifiedSessionAccess) return
        val client = clientFor(snapshot) ?: return
        val cursor = (if (snapshot.session != null) snapshot.historyCursor else snapshot.sessionCursor) ?: return
        val generation = epoch
        mutableState.value = state.value.copy(loadingMore = true)
        paging = viewModelScope.launch {
            try {
                if (snapshot.session != null) {
                    prependPage(client, snapshot.session.id, generation, cursor)
                } else {
                    val page = client.sessions(cursor)
                    require(page.nextCursor != cursor) { "Filo session cursor did not advance" }
                    if (generation == epoch) {
                        val sessions = (state.value.sessions + page.sessions).distinctBy { it.id }
                        mutableState.value = state.value.copy(
                            sessions = sessions,
                            sessionStatuses = state.value.mergeListedSessionStatuses(requireNotNull(snapshot.deviceId), page),
                            sessionCursor = page.nextCursor, failure = null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == epoch) mutableState.value = state.value.copy(failure = trace("page_failed", error))
            } finally {
                if (generation == epoch) mutableState.value = state.value.copy(loadingMore = false)
            }
        }
    }

    private suspend fun cachePage(owner: String, page: RemoteConversationPage, live: Boolean): RemoteConversationPage {
        require(page.nodes.map { it.id } == page.messages.map { it.id }) { "Filo page metadata is missing" }
        hydration.accept(owner, page, emptyList(), live)
        // Retain only topology here; body retention stays in the bounded LRU.
        return page.copy(messages = emptyList(), nodes = page.nodes.map { it.copy(pageCursor = page.pageCursor) })
    }

    private suspend fun prependPage(client: FiloClient, session: String, generation: Long, cursor: String) {
        val owner = state.value.owner ?: return
        val payload = payloadOwner(owner)
        val raw = client.conversation(session, cursor)
        if (generation != epoch) throw CancellationException()
        val page = cachePage(payload, raw, live = false)
        require(page.nextCursor != cursor) { "Filo history cursor did not advance" }
        historyMutation.withLock {
            if (generation != epoch || state.value.historyCursor != cursor) return
            val nodes = admitRemoteNodes(state.value.nodes, page.nodes, older = true)
            val groups = projectRemoteTopology(nodes, state.value.runtime)
            hydration.accept(payload, page, groups, live = false)
            if (generation != epoch) return
            mutableState.value = state.value.copy(nodes = nodes, messageGroups = groups,
                historyCursor = page.nextCursor, failure = null)
        }
        state.value.attempts[owner]?.let { submission.confirm(owner, it, page.inputReceipts) }
    }

    fun searchHistory(query: String): kotlinx.coroutines.flow.Flow<List<com.newoether.agora.ui.chat.ConversationSearchMatch>> {
        val snapshot = state.value
        val owner = snapshot.owner ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val client = clientFor(snapshot) ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val session = snapshot.session ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val generation = epoch
        val payload = snapshot.payloadOwner ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return remoteHistorySearch(state, owner, query,
            loadMessages = { ids -> hydration.loadMessages(payload, ids) },
            loadEarlier = { cursor ->
                if (generation != epoch) throw CancellationException()
                prependPage(client, session.id, generation, cursor)
            },
            failed = { trace("page_failed", it) },
            isCached = { group -> hydration.cachedMessage(payload, group) != null },
        )
    }

    fun editDraft(owner: String, text: String) {
        mutableState.value = state.value.copy(drafts = state.value.drafts + (owner to text))
    }

    fun send() = submission.send()
}
