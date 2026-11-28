package com.newoether.agora.ui.remote

import com.newoether.agora.R
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.remote.RemoteDevice
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.remote.RemoteFailure
import com.newoether.agora.remote.RemoteMessageGroup
import com.newoether.agora.remote.RemoteRuntime
import com.newoether.agora.remote.RemoteSession
import com.newoether.agora.remote.RemoteState
import org.junit.Assert.*
import org.junit.Test

class RemoteStatusPresentationTest {
    private val session = RemoteSession("session", "Task", "/workspace", 1)
    private fun state(
        status: RemoteDeviceStatus = RemoteDeviceStatus.CONNECTED,
        desktop: RemoteDeviceStatus = RemoteDeviceStatus.CONNECTED,
    ) = RemoteState(
        restoring = false,
        deviceId = "device",
        devices = listOf(
            RemoteDevice("other", "Other", "http://other", RemoteDeviceStatus.CONNECTED,
                desktopStatus = RemoteDeviceStatus.CONNECTED),
            RemoteDevice("device", "Computer", "http://computer", status, desktopStatus = desktop),
        ),
        session = session,
    )

    @Test fun onlineRequiresTheSelectedDesktopAndCannotBeInferredFromGatewayOrHistory() {
        for (gateway in RemoteDeviceStatus.entries) {
            for (desktop in RemoteDeviceStatus.entries) {
                val expected = (if (gateway == RemoteDeviceStatus.CONNECTED) desktop else gateway)
                    .toMcpConnectionStatus()
                for (runtime in listOf(null, RemoteRuntime("notLoaded"), RemoteRuntime("idle"), RemoteRuntime("active"))) {
                    val snapshot = state(gateway, desktop).copy(runtime = runtime, hydrationEnabled = runtime != null)
                    assertEquals(expected, snapshot.remoteConnectionStatus())
                    assertEquals(expected, snapshot.copy(failure = RemoteFailure.SERVICE).remoteConnectionStatus())
                    assertEquals(expected, snapshot.copy(draftSessionId = session.id).remoteConnectionStatus())
                }
            }
        }
        assertEquals(McpConnectionStatus.IDLE, state().copy(deviceId = "missing").remoteConnectionStatus())
        assertEquals(R.string.remote_online, remoteConnectionStatusLabel(McpConnectionStatus.CONNECTED))
        assertEquals(R.string.remote_connecting, remoteConnectionStatusLabel(McpConnectionStatus.CONNECTING))
        assertEquals(R.string.remote_offline, remoteConnectionStatusLabel(McpConnectionStatus.IDLE))
        assertEquals(R.string.remote_offline, remoteConnectionStatusLabel(McpConnectionStatus.ERROR))
    }

    @Test fun onlineNewDraftIsLocalWhileOfflineDesktopOffersConnectionRetry() {
        val draft = state().copy(draftSessionId = session.id, drafts = mapOf("device/session" to "Unsent"))
        assertEquals(R.string.remote_new_chat_hint, draft.remoteEmptyConversationHint())
        assertFalse(draft.canRetryEmptyConnection())
        for (offlineState in listOf(RemoteDeviceStatus.IDLE, RemoteDeviceStatus.ERROR)) {
            val offline = draft.copy(devices = state(desktop = offlineState).devices)
            assertEquals(R.string.remote_empty_offline_hint, offline.remoteEmptyConversationHint())
            assertTrue(offline.canRetryEmptyConnection())
            assertEquals("Unsent", offline.drafts[offline.owner])
            val failed = offline.copy(failure = RemoteFailure.NETWORK)
            assertEquals(R.string.remote_empty_retry_hint, failed.remoteEmptyConversationHint())
            assertTrue(failed.canRetryEmptyConnection())
        }
    }

    @Test fun openingAndBothConnectingStatesNeverOfferAnotherConcurrentRead() {
        for (snapshot in listOf(state().copy(loading = true), state(RemoteDeviceStatus.CONNECTING),
                state(desktop = RemoteDeviceStatus.CONNECTING), state())) {
            assertEquals(R.string.remote_empty_connecting_hint, snapshot.remoteEmptyConversationHint())
            assertFalse(snapshot.canRetryEmptyConnection())
        }
        val reconnecting = state(RemoteDeviceStatus.CONNECTING).copy(failure = RemoteFailure.NETWORK)
        assertEquals(R.string.remote_empty_retry_hint, reconnecting.remoteEmptyConversationHint())
        assertTrue(reconnecting.canRetryEmptyConnection())
        assertEquals(R.string.remote_empty_retry_hint, reconnecting.copy(loading = true).remoteEmptyConversationHint())
        assertTrue(reconnecting.copy(loading = true).canRetryEmptyConnection())
        val confirmedEmpty = state().copy(hydrationEnabled = true, runtime = RemoteRuntime("notLoaded"))
        assertEquals(R.string.remote_empty_conversation_hint, confirmedEmpty.remoteEmptyConversationHint())
        assertFalse(confirmedEmpty.canRetryEmptyConnection())
    }

    @Test fun unrelatedNativeReadFailureDoesNotOverrideAConfirmedDesktopButUnavailableDoes() {
        val failed = state().copy(failure = RemoteFailure.SERVICE)
        assertEquals(McpConnectionStatus.CONNECTED, failed.remoteConnectionStatus())
        assertEquals(R.string.remote_empty_retry_hint, failed.remoteEmptyConversationHint())
        assertTrue(failed.canRetryEmptyConnection())
        val unavailable = failed.copy(devices = state(desktop = RemoteDeviceStatus.ERROR).devices)
        assertEquals(McpConnectionStatus.ERROR, unavailable.remoteConnectionStatus())
        assertEquals(R.string.remote_offline, remoteConnectionStatusLabel(unavailable.remoteConnectionStatus()))
        assertEquals(RemoteDeviceStatus.CONNECTED, unavailable.devices.last().status)
        assertEquals(R.string.remote_empty_retry_hint, unavailable.remoteEmptyConversationHint())
    }

    @Test fun creatableDisconnectedSourceKeepsDraftWelcomeWithoutClaimingOnline() {
        val desktop = com.newoether.agora.remote.RemoteAdapter("codex-desktop", "codex", "desktop", "disconnected",
            capabilities = com.newoether.agora.remote.RemoteCapabilities(create = true, history = true))
        val draft = state().copy(draftSessionId = session.id,
            devices = listOf(state().devices.last().copy(multiAdapter = true, adapters = listOf(desktop))))
        assertEquals(McpConnectionStatus.IDLE, draft.remoteConnectionStatus())
        assertEquals(R.string.remote_new_chat_hint, draft.remoteEmptyConversationHint())
        assertFalse(draft.canRetryEmptyConnection())
        assertEquals(R.string.remote_source_unavailable, draft.copy(draftSessionId = null).remoteEmptyConversationHint())
    }

    @Test fun disconnectedCliCannotAcquireDraftWelcomeFromAnOlderCreateAdvertisement() {
        for ((id, family) in listOf("codex-cli" to "codex", "claude-code" to "claude")) {
            val cli = com.newoether.agora.remote.RemoteAdapter(id, family, "cli", "disconnected",
                capabilities = com.newoether.agora.remote.RemoteCapabilities(create = true, history = true))
            val draft = state().copy(draftSessionId = session.id,
                devices = listOf(state().devices.last().copy(multiAdapter = true, adapters = listOf(cli))))
            assertFalse(draft.canCreate)
            assertEquals(McpConnectionStatus.IDLE, draft.remoteConnectionStatus())
            assertEquals(R.string.remote_source_unavailable, draft.remoteEmptyConversationHint())
            assertTrue(draft.canRetryEmptyConnection())
        }
    }

    @Test fun unavailableSourceRetainsHeadlineAndRetryAcrossGatewayCheck() {
        val cli = com.newoether.agora.remote.RemoteAdapter("codex-cli", "codex", "cli", "unavailable")
        val draft = state().copy(draftSessionId = session.id,
            devices = listOf(state().devices.last().copy(multiAdapter = true, adapters = listOf(cli))))
        for (gateway in listOf(RemoteDeviceStatus.CONNECTED, RemoteDeviceStatus.CONNECTING, RemoteDeviceStatus.ERROR)) {
            val snapshot = draft.copy(loading = gateway == RemoteDeviceStatus.CONNECTING,
                devices = draft.devices.map { it.copy(status = gateway) })
            assertEquals(R.string.remote_source_unavailable, snapshot.remoteEmptyConversationHint())
            assertTrue(snapshot.canRetryEmptyConnection())
        }
        assertEquals(R.string.remote_empty_retry_hint,
            draft.copy(failure = RemoteFailure.PROTOCOL).remoteEmptyConversationHint())
    }

    @Test fun loadedHistoryAlwaysWinsOverEmptyGuidanceDuringFailureAndRecovery() {
        val message = ChatMessage(id = "answer", text = "Retained answer", participant = Participant.MODEL)
        val groups = listOf(RemoteMessageGroup(message, emptyList()))
        for (status in RemoteDeviceStatus.entries) {
            val snapshot = state(desktop = status).copy(messageGroups = groups,
                drafts = mapOf("device/session" to "Unsent"))
            for (candidate in listOf(snapshot, snapshot.copy(failure = RemoteFailure.NETWORK), snapshot.copy(loading = true))) {
                assertNull(candidate.remoteEmptyConversationHint())
                assertFalse(candidate.canRetryEmptyConnection())
                assertSame(groups, candidate.messageGroups)
                assertEquals("Unsent", candidate.drafts[candidate.owner])
            }
        }
        assertNull(state().copy(session = null).remoteEmptyConversationHint())
    }
}
