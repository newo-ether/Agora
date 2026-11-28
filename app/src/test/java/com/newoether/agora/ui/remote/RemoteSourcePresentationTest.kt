package com.newoether.agora.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.newoether.agora.remote.RemoteAdapter
import com.newoether.agora.remote.RemoteCapabilities
import com.newoether.agora.remote.RemoteDevice
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.remote.RemoteRuntime
import com.newoether.agora.remote.RemoteSession
import com.newoether.agora.remote.RemoteSessionAccess
import com.newoether.agora.remote.RemoteState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h700dp-mdpi")
class RemoteSourcePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val desktop = RemoteAdapter("codex-desktop", "codex", "desktop", "connected")
    private val claude = RemoteAdapter("claude-code", "claude", "cli", "disconnected")

    @Test fun fourInstalledChipsExposeIndependentStatusesAndHideOnlyRemovedSources() {
        val cli = desktop.copy(id = "codex-cli", origin = "cli", state = "connecting")
        val claudeDesktop = claude.copy(id = "claude-desktop", origin = "desktop", state = "unavailable")
        val installed = listOf(desktop, cli, claude, claudeDesktop)
        var sources by mutableStateOf(installed)
        var selected by mutableStateOf(desktop.id)
        compose.setContent { MaterialTheme { RemoteAgentChipRow(sources, selected, { selected = it }) } }
        for ((name, status) in listOf("Codex Desktop" to "Online", "Codex CLI" to "Connecting",
            "Claude Code" to "Offline", "Claude Desktop" to "Offline")) {
            compose.onNodeWithContentDescription("$name, $status").assertExists()
        }
        compose.onAllNodesWithTag("remote-agent-status-dot", useUnmergedTree = true).assertCountEquals(4)
        compose.runOnIdle { sources = installed.map { it.copy(state = "disconnected") } }
        for (name in listOf("Codex Desktop", "Codex CLI", "Claude Code", "Claude Desktop")) {
            compose.onNodeWithContentDescription("$name, Offline").assertExists()
        }
        compose.runOnIdle { sources = sources.filterNot { it.id == claudeDesktop.id } }
        compose.onNodeWithTag("remote-agent-chip-claude-desktop").assertDoesNotExist()
        for (source in installed.filterNot { it.id == claudeDesktop.id }) {
            compose.onNodeWithTag("remote-agent-chip-${source.id}").assertExists()
        }
        assertEquals(desktop.id, selected)
        for (label in listOf("Online", "Offline", "Connecting")) {
            compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun onlineSourcesLeadWithFixedOrderWithinEachGroupAndPreserveSelection() {
        val cli = desktop.copy(id = "codex-cli", origin = "cli", state = "connecting")
        val claudeDesktop = claude.copy(id = "claude-desktop", origin = "desktop", state = "connected")
        var sources by mutableStateOf(listOf(claudeDesktop, claude, cli, desktop.copy(state = "disconnected")))
        var selected by mutableStateOf(claude.id)
        var selections = 0
        compose.setContent { MaterialTheme {
            RemoteAgentChipRow(sources, selected, { selected = it; selections++ })
        } }
        fun assertOrder(vararg ids: String) {
            val positions = ids.map { id ->
                compose.onNodeWithTag("remote-agent-chip-$id").fetchSemanticsNode()
                    .positionInRoot.x
            }
            assertTrue("Online chips must lead; source priority applies within each group",
                positions.zipWithNext().all { (left, right) -> left < right })
            compose.onNodeWithTag("remote-agent-chip-$selected").assertIsSelected()
        }
        assertOrder(claudeDesktop.id, desktop.id, cli.id, claude.id)
        compose.runOnIdle {
            sources = sources.reversed().map {
                it.copy(state = if (it.id == cli.id || it.id == claude.id) "connected" else "disconnected")
            }
        }
        assertOrder(cli.id, claude.id, desktop.id, claudeDesktop.id)
        compose.runOnIdle {
            sources = sources.reversed().map { it.copy(state = "disconnected") }
            selected = cli.id
        }
        assertOrder(desktop.id, cli.id, claude.id, claudeDesktop.id)
        compose.runOnIdle { sources = sources.map { it.copy(state = "connected") } }
        assertOrder(desktop.id, cli.id, claude.id, claudeDesktop.id)
        assertEquals(0, selections)
    }

    @Test fun offlineSourceRemainsSelectableAndFreshRemovalRemovesOnlyItsChip() {
        var sources by mutableStateOf(listOf(desktop, claude))
        var selected by mutableStateOf(desktop.id)
        var selections = 0
        compose.setContent { MaterialTheme {
            RemoteAgentChipRow(sources, selected, { selected = it; selections++ })
        } }
        val target = compose.onNodeWithTag("remote-agent-chip-claude-code")
        val bounds = target.getUnclippedBoundsInRoot()
        assertEquals(40f, (bounds.bottom - bounds.top).value, 0.01f)
        target.performClick().assertIsSelected()
        assertEquals(claude.id, selected)
        assertEquals(1, selections)
        target.performClick()
        assertEquals(1, selections)
        compose.runOnIdle { sources = listOf(desktop) }
        compose.onNodeWithTag("remote-agent-chip-claude-code").assertDoesNotExist()
        compose.onNodeWithText("Codex Desktop").assertExists()
        assertEquals(claude.id, selected)
    }

    @Test fun claudeWelcomeUsesItsIdentityWithoutCodexModelOrCompleteHistoryClaims() {
        val source = claude.copy(state = "connected", nativeScopeId = "claude-scope",
            capabilities = RemoteCapabilities(history = true, historyMode = "bounded"))
        var state by mutableStateOf(RemoteState(deviceId = "device", restoring = false, hydrationEnabled = true,
            devices = listOf(RemoteDevice("device", "Computer", "address", RemoteDeviceStatus.CONNECTED,
                adapters = listOf(source), multiAdapter = true)),
            session = RemoteSession("native", "", "", 1, access = RemoteSessionAccess(
                "claude-scope", "native", "binding", source.capabilities)), runtime = RemoteRuntime("idle")))
        compose.setContent { MaterialTheme {
            RemoteEmptyConversation(state, true, 64.dp, 180.dp, {})
        } }
        compose.onNodeWithTag("remote-welcome-headline").assertExists()
        compose.onNodeWithText("Claude Code").assertExists()
        compose.onNodeWithText("Computer").assertExists()
        compose.onNodeWithText("Codex Desktop").assertDoesNotExist()
        compose.onNodeWithText("Shows only text captured after the Claude Mod registered. Earlier history, tool activity, media, and thinking are not included.").assertDoesNotExist()
        assertFalse(state.canSend)
        assertTrue(state.historyBounded)
        compose.runOnIdle {
            state = state.copy(devices = state.devices.map { device -> device.copy(
                adapters = listOf(source.copy(id = "claude-desktop", origin = "desktop"))) })
        }
        compose.onNodeWithText("Claude Desktop").assertExists()
        compose.onNodeWithText("Claude Code").assertDoesNotExist()
        compose.onNodeWithText("Shows only text captured after the Claude Mod registered. Earlier history, tool activity, media, and thinking are not included.").assertDoesNotExist()
        assertFalse(state.canCreate)
        assertFalse(state.canSend)
        assertFalse(state.canStop)
        assertTrue(state.historyBounded)
    }
}
