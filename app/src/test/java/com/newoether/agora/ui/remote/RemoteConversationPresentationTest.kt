package com.newoether.agora.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.remote.*
import com.newoether.agora.viewmodel.AnimatedScrollRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h800dp-mdpi")
class RemoteConversationPresentationTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(RemoteState(
        restoring = false, deviceId = "device",
        devices = listOf(RemoteDevice("device", "Computer", "http://computer", RemoteDeviceStatus.CONNECTED,
            desktopStatus = RemoteDeviceStatus.CONNECTED)),
        session = RemoteSession("draft", "", "", 0), draftSessionId = "draft",
        drafts = mapOf("device/draft" to "Unsent draft"),
    ))
    private val vmState = MutableStateFlow(state)
    private val vm = mockk<RemoteViewModel>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show() {
        every { vm.state } answers { vmState.apply { value = state } }
        every { vm.animatedScrollRequest } returns MutableStateFlow<AnimatedScrollRequest?>(null)
        every { settings.blurEffectsEnabled } returns MutableStateFlow(false)
        every { settings.amoledEnabled } returns MutableStateFlow(false)
        every { settings.parseInlineDollarMath } returns MutableStateFlow(false)
        every { settings.stickToBottom } returns MutableStateFlow(true)
        every { settings.autoWrapCodeBlocks } returns MutableStateFlow(true)
        every { settings.toolCallDisplayMode } returns MutableStateFlow(ToolCallDisplayModes.DEFAULT)
        every { settings.thinkingSegmentDisplayMode } returns MutableStateFlow(ThinkingSegmentDisplayModes.DEFAULT)
        every { settings.autoExpandActiveGroup } returns MutableStateFlow(true)
        compose.setContent {
            MaterialTheme {
                RemoteConversation(state, vm, settings, true, {}, {}, { _, _ -> })
            }
        }
    }

    @Test fun inputReceiptStatesNeverRenderAboveTheComposer() {
        val owner = requireNotNull(state.owner)
        state = state.copy(attempts = mapOf(owner to RemoteAttempt("receipt", "Unsent draft",
            RemoteDelivery.SUBMITTING, receiptKind = "submitted_observed")))
        show()
        for (delivery in RemoteDelivery.entries) {
            compose.runOnIdle {
                state = state.copy(attempts = mapOf(owner to state.attempts.getValue(owner).copy(delivery = delivery)))
            }
            compose.onAllNodes(hasText("non-exact", substring = true, ignoreCase = true)).assertCountEquals(0)
            compose.onAllNodes(hasText("Matching native input", substring = true)).assertCountEquals(0)
            compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
        }
        verify(exactly = 0) { vm.send() }
    }

    @Test fun nativeSettingsConfirmationNeverRendersDiagnosticComposerLabels() {
        state = state.copy(runtime = RemoteRuntime("idle", model = "actual-model"))
        show()
        for (phase in listOf("pending", "confirmed", "failed")) {
            compose.runOnIdle {
                state = state.copy(runtime = state.runtime?.copy(settingsChange = RemoteSettingsChange(
                    id = "change-$phase", state = phase, requested = RemoteSettings(model = "requested-model", effort = "high"),
                    actualModel = "actual-model", actualEffort = "low",
                )))
            }
            compose.onAllNodes(hasText("Settings pending", substring = true)).assertCountEquals(0)
            compose.onAllNodes(hasText("Settings confirmed", substring = true)).assertCountEquals(0)
            compose.onAllNodes(hasText("Settings differ", substring = true)).assertCountEquals(0)
            compose.onAllNodes(hasText("actual model:", substring = true)).assertCountEquals(0)
            compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
        }
        verify(exactly = 0) { vm.send() }
        verify(exactly = 0) { vm.setModel(any()) }
        verify(exactly = 0) { vm.setThinkingLevel(any()) }
    }

    @Test fun detailsRemainsVisibleDuringLoadingWithBothUnavailableRowsDisabled() {
        state = state.copy(models = emptyList(), modelsLoading = true, loading = true)
        show()
        compose.onNodeWithContentDescription(text(R.string.tools)).assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.thinking)).assertExists().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.openai_service_tier_title)).assertExists().assertIsNotEnabled()
        verify(exactly = 0) { vm.setThinkingLevel(any()) }
        verify(exactly = 0) { vm.setServiceTier(any()) }
    }
    @Test fun readOnlySourceRetainsTheDetailsButtonAndDisabledSettingsRows() {
        val source = RemoteAdapter("cli", "codex", "cli", "connected",
            capabilities = RemoteCapabilities(history = true))
        state = state.copy(devices = state.devices.map { it.copy(multiAdapter = true, adapters = listOf(source)) },
            adapterSelections = mapOf("device" to "cli"),
            models = listOf(RemoteModel("model", "Model", true, listOf("low", "high"), "low",
                listOf(RemoteServiceTier("fast", "Fast")))))
        show()
        compose.onNodeWithContentDescription(text(R.string.tools)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.thinking)).assertExists().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.openai_service_tier_title)).assertDoesNotExist()
    }
    @Test fun nativeModelRemainsVisibleWithoutCatalogOrMutationSupport() {
        val caps = RemoteCapabilities(history = true, live = true)
        val source = RemoteAdapter("claude-desktop", "claude", "desktop", "connected",
            nativeScopeId = "scope", capabilities = caps)
        state = state.copy(
            devices = state.devices.map { it.copy(multiAdapter = true, adapters = listOf(source)) },
            adapterSelections = mapOf("device" to source.id),
            session = RemoteSession("native", "", "", 0,
                access = RemoteSessionAccess("scope", "canonical", "binding", caps)),
            draftSessionId = null, runtime = RemoteRuntime("idle", model = "actual-native-model"),
            models = listOf(RemoteModel("cached", "Cached model option")),
        )
        show()
        compose.onNodeWithText("Actual Native Model").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Cached model option").assertDoesNotExist()
        verify(exactly = 0) { vm.setModel(any()) }
        verify(exactly = 0) { vm.refreshModels() }
    }

    @Test fun openModelMenuDropsCachedOptionsWhenModelCapabilityIsRevoked() {
        val caps = RemoteCapabilities(history = true, models = true, settings = listOf("model"))
        val source = RemoteAdapter("claude-code", "claude", "cli", "connected",
            nativeScopeId = "scope", capabilities = caps)
        state = state.copy(
            devices = state.devices.map { it.copy(multiAdapter = true, adapters = listOf(source)) },
            adapterSelections = mapOf("device" to source.id),
            session = RemoteSession("native", "", "", 0,
                access = RemoteSessionAccess("scope", "canonical", "binding", caps)),
            draftSessionId = null, runtime = RemoteRuntime("idle", model = "actual-native-model"),
            models = listOf(RemoteModel("cached", "Cached model option")),
        )
        show()
        compose.onNodeWithText("Actual Native Model").performClick()
        compose.onNodeWithText("Cached model option").assertExists().assertIsEnabled()
        compose.runOnIdle {
            state = state.copy(session = state.session!!.copy(access = state.session!!.access!!.copy(
                capabilities = caps.copy(models = false, settings = emptyList()))))
        }
        compose.onNodeWithText("Actual Native Model").assertIsNotEnabled()
        compose.onNodeWithText("Cached model option").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(session = state.session!!.copy(access = state.session!!.access!!.copy(capabilities = caps)))
        }
        compose.onNodeWithText("Actual Native Model").assertIsEnabled()
        compose.onNodeWithText("Cached model option").assertDoesNotExist()
        compose.onNodeWithText("Actual Native Model").performClick()
        compose.onNodeWithText("Cached model option").assertExists()
        verify(exactly = 0) { vm.setModel(any()) }
    }

    @Test fun loadingAndUnavailableModelTriggersCannotOpenAMenuOrRefresh() {
        state = state.copy(modelsLoading = true)
        show()
        compose.onNodeWithText(text(R.string.loading_label)).assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { state = state.copy(modelsLoading = false) }
        compose.onNodeWithText(text(R.string.remote_model_unavailable)).assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle {
            state = state.copy(models = listOf(RemoteModel("model", "Model", true)), modelsLoading = true)
        }
        compose.onNodeWithText("Model").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(modelsLoading = false, loading = true) }
        compose.onNodeWithText("Model").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loading = false) }
        compose.onNodeWithText("Model").assertIsEnabled()
        verify(exactly = 0) { vm.refreshModels() }
        verify(exactly = 0) { vm.setModel(any()) }
    }

    @Test fun bothClaudeSourcesCanChooseUnknownEffortByTouch() {
        val caps = RemoteCapabilities(history = true, live = true, settings = listOf("effort"))
        val source = RemoteAdapter("claude-code", "claude", "cli", "connected",
            nativeScopeId = "scope", capabilities = caps)
        state = state.copy(
            devices = state.devices.map { it.copy(multiAdapter = true, adapters = listOf(source)) },
            adapterSelections = mapOf("device" to source.id),
            session = RemoteSession("native", "", "", 0,
                access = RemoteSessionAccess("scope", "canonical", "binding", caps)),
            draftSessionId = null, runtime = RemoteRuntime("unknown", model = "claude-sonnet-5"),
        )
        show()
        for ((id, origin) in listOf("claude-code" to "cli", "claude-desktop" to "desktop")) {
            compose.runOnIdle {
                state = state.copy(devices = state.devices.map { it.copy(adapters = listOf(source.copy(id = id, origin = origin))) },
                    adapterSelections = mapOf("device" to id))
            }
            compose.onNodeWithContentDescription(text(R.string.tools)).performClick()
            compose.onNodeWithText(text(R.string.thinking)).assertIsEnabled().performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertIsDisplayed().assertIsEnabled()
                .performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .9f, height * .5f)) }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        }
        verify(exactly = 2) { vm.setThinkingLevel("max") }
    }

    @Test fun cliNativeNameAndEffortChoicesSurviveTheSessionCapabilityFence() {
        val model = RemoteModel("gpt-6.1-sol", "GPT-6.1-Sol", true,
            listOf("low", "medium", "high", "xhigh", "max", "ultra"), "low")
        val caps = RemoteCapabilities(history = true, live = true, models = true, settings = listOf("model", "effort"))
        val source = RemoteAdapter("codex-cli", "codex", "cli", "connected",
            nativeScopeId = "scope", capabilities = caps)
        state = state.copy(
            devices = state.devices.map { it.copy(multiAdapter = true, adapters = listOf(source)) },
            adapterSelections = mapOf("device" to source.id),
            session = RemoteSession("native", "", "", 0,
                access = RemoteSessionAccess("scope", "canonical", "binding", caps)),
            draftSessionId = null, models = listOf(model),
            runtime = RemoteRuntime("idle", model = model.id, effort = "xhigh"),
        )
        show()
        compose.onNodeWithText("GPT 6.1 Sol").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("GPT 6.1 Sol").onLast().assertIsEnabled().performClick()
        verify(exactly = 1) { vm.setModel("gpt-6.1-sol") }
        compose.onNodeWithContentDescription(text(R.string.tools)).performClick()
        compose.onNodeWithText(text(R.string.thinking)).assertIsEnabled().performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(3f, 0f..5f, 4)))
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
        verify(exactly = 1) { vm.setThinkingLevel("low") }
    }

    @Test fun modelLabelFormattingKeepsClaudeNativeNamesAndBothRawIds() {
        val codex = RemoteModel("gpt-6.1-sol", "GPT-6.1-Sol")
        val claude = RemoteModel("opus", "deepseek-v4-pro[1m]")
        assertEquals("GPT 6.1 Sol", codex.displayName("codex"))
        assertEquals("gpt-6.1-sol", codex.id)
        assertEquals("deepseek-v4-pro[1m]", claude.displayName("claude"))
        assertEquals("opus", claude.id)
    }

    @Test fun blankNativeNameUsesTheSharedFallbackInSelectorAndMenuWithoutChangingIdentity() {
        state = state.copy(models = listOf(RemoteModel("gpt-6.1-sol", "  ", true)))
        show()
        compose.onNodeWithText("GPT 6.1 Sol").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("GPT 6.1 Sol").onLast().assertIsEnabled().performClick()
        verify(exactly = 1) { vm.setModel("gpt-6.1-sol") }
    }

    @Test fun bothCodexSourcesKeepEffortAndTierControlsAvailableWhileActive() {
        val caps = RemoteCapabilities(history = true, live = true, models = true,
            idleSend = true, activeSend = "native-steer", stop = true,
            settings = listOf("model", "effort", "serviceTier"))
        val source = RemoteAdapter("codex-desktop", "codex", "desktop", "connected",
            nativeScopeId = "scope", capabilities = caps)
        state = state.copy(
            devices = state.devices.map { it.copy(multiAdapter = true, adapters = listOf(source)) },
            adapterSelections = mapOf("device" to source.id),
            session = RemoteSession("native", "", "", 0,
                access = RemoteSessionAccess("scope", "canonical", "binding", caps)),
            draftSessionId = null, models = listOf(RemoteModel("gpt-6.1-sol", "GPT-6.1-Sol", true,
                listOf("low", "high"), "low", listOf(RemoteServiceTier("fast", "Fast")))),
            runtime = RemoteRuntime("active", activeTurnId = "turn", model = "gpt-6.1-sol", effort = "high"),
        )
        show()
        for ((id, origin) in listOf("codex-desktop" to "desktop", "codex-cli" to "cli")) {
            compose.runOnIdle {
                val selected = source.copy(id = id, origin = origin)
                state = state.copy(devices = state.devices.map { it.copy(adapters = listOf(selected)) },
                    adapterSelections = mapOf("device" to id))
            }
            compose.onNodeWithContentDescription(text(R.string.tools)).performClick()
            compose.onNodeWithText(text(R.string.thinking)).assertIsEnabled()
            compose.onNodeWithText(text(R.string.openai_service_tier_title)).assertIsEnabled().performClick()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertIsEnabled()
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1f)) }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        }
        verify(exactly = 2) { vm.setServiceTier("fast") }
    }

    @Test fun bothClaudeSourcesHideServiceTierAndAllowEffortWhileActive() {
        val caps = RemoteCapabilities(history = true, live = true,
            idleSend = true, activeSend = "native-steer", stop = true, settings = listOf("effort"))
        val source = RemoteAdapter("claude-code", "claude", "cli", "connected",
            nativeScopeId = "scope", capabilities = caps)
        state = state.copy(
            devices = state.devices.map { it.copy(multiAdapter = true, adapters = listOf(source)) },
            adapterSelections = mapOf("device" to source.id),
            session = RemoteSession("native", "", "", 0,
                access = RemoteSessionAccess("scope", "canonical", "binding", caps)),
            draftSessionId = null, models = emptyList(),
            runtime = RemoteRuntime("active", activeTurnId = "turn", model = "deepseek-v4-pro[1m]", effort = "high"),
        )
        show()
        for ((id, origin) in listOf("claude-code" to "cli", "claude-desktop" to "desktop")) {
            compose.runOnIdle {
                val selected = source.copy(id = id, origin = origin)
                state = state.copy(devices = state.devices.map { it.copy(adapters = listOf(selected)) },
                    adapterSelections = mapOf("device" to id))
            }
            compose.onNodeWithContentDescription(text(R.string.tools)).performClick()
            compose.onNodeWithText(text(R.string.openai_service_tier_title)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.thinking)).assertIsEnabled().performClick()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertIsEnabled()
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        }
        verify(exactly = 2) { vm.setThinkingLevel("low") }
        verify(exactly = 0) { vm.setServiceTier(any()) }
    }

    @Test fun supportedThinkingAndUnsupportedTierAreDisabledIndependently() {
        state = state.copy(models = listOf(RemoteModel("model", "Model", true, listOf("low", "high"), "low")))
        show()
        compose.onNodeWithContentDescription(text(R.string.tools)).performClick()
        compose.onNodeWithText(text(R.string.thinking)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.openai_service_tier_title)).assertIsNotEnabled()
    }
    @Test fun unknownDeliveryUsesTheNextSendDirectlyWithoutAnAcknowledgmentAction() {
        val owner = requireNotNull(state.owner)
        state = state.copy(attempts = mapOf(owner to RemoteAttempt("previous", "Unsent draft", RemoteDelivery.UNKNOWN)))
        show()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
            .performTextReplacement("Edited retry")
        compose.onNodeWithContentDescription(text(R.string.action)).assertIsEnabled().performClick()
        compose.waitForIdle()
        verify(exactly = 1) { vm.send() }
        verify { vm.editDraft(owner, "Edited retry") }
        compose.onNode(hasSetTextAction()).assertTextContains("Edited retry")
        compose.onNodeWithText(text(R.string.remote_unknown)).assertDoesNotExist()
        compose.onNodeWithText("Allow Sending").assertDoesNotExist()
        verify(exactly = 0) { vm.retryConnection() }
        verify(exactly = 0) { vm.newSession() }
    }

    @Test fun draftStaysEditableThroughFailureAndSafeRetryWithoutASecondSubmissionPath() {
        show()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop").assertIsDisplayed()
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Computer")
        compose.onNodeWithTag("remote-welcome-mark").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft").performTextReplacement("Unsent draft continued")
        compose.runOnIdle {
            state = state.copy(failure = RemoteFailure.NETWORK,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) })
        }
        compose.onNodeWithText(text(R.string.remote_offline), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft continued")
        compose.onNodeWithText(text(R.string.retry)).performClick()
        verify(exactly = 1) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
        verify(exactly = 0) { vm.newSession() }
        compose.runOnIdle {
            state = state.copy(failure = null,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) })
        }
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_connecting), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft continued").performTextReplacement("Unsent draft continued more")
        compose.waitForIdle()
        verify { vm.editDraft("device/draft", "Unsent draft continued more") }
    }

    @Test fun existingOpeningShowsOnlyTheCanonicalLoadingIndicatorWithoutConnectionCopy() {
        state = state.copy(draftSessionId = null, loading = true)
        show()
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertDoesNotExist()
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNodeWithTag("remote-welcome-mark").assertDoesNotExist()
        compose.onNodeWithTag("remote-welcome-device").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )).assertCountEquals(1)
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, failure = RemoteFailure.SERVICE) }
        compose.onNodeWithText(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_online), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.retry)).assertIsEnabled().performClick()
        verify(exactly = 1) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
    }

    @Test fun pendingDraftKeepsActualTopBarStatusAndLoadingHistoryImmediatelySuppressesAnEnteringEmptyBody() {
        state = state.copy(loading = true,
            devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) })
        show()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop")
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Computer")
        compose.onNodeWithText(text(R.string.remote_connecting), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            state = state.copy(session = RemoteSession("history", "", "", 0), draftSessionId = null)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )).assertCountEquals(1)
        compose.runOnIdle { state = state.copy(loading = false, failure = RemoteFailure.SERVICE); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(text(R.string.remote_empty_retry_hint))
        compose.onNodeWithText(text(R.string.retry)).assertIsEnabled()
        val message = ChatMessage(id = "arriving", text = "Arrived during entry", participant = Participant.USER)
        every { vm.observeMessage(any(), "arriving") } returns flowOf(message)
        every { vm.cachedMessage(any(), "arriving") } returns message
        val groups = listOf(RemoteMessageGroup(message, emptyList()))
        compose.runOnIdle {
            state = state.copy(hydrationEnabled = true, runtime = RemoteRuntime("idle"), messageGroups = groups)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(320)
        compose.waitForIdle()
        compose.onNodeWithText("Arrived during entry", substring = true).assertExists()
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNodeWithTag("remote-welcome-mark").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        assertEquals(groups, state.messageGroups)
        verify(exactly = 0) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
    }

    @Test fun retryKeepsFailureAndActionGeometryAcrossAutomaticRecoveryAndUsesOneCover() {
        state = state.copy(draftSessionId = null, failure = RemoteFailure.NETWORK,
            devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) })
        show()
        val retry = compose.onNodeWithText(text(R.string.retry))
        val bounds = retry.getUnclippedBoundsInRoot()
        repeat(3) {
            compose.runOnIdle { state = state.copy(devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) }) }
            compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(text(R.string.remote_empty_retry_hint))
            retry.assertIsDisplayed().assertIsEnabled()
            assertEquals(bounds, retry.getUnclippedBoundsInRoot())
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
            compose.runOnIdle { state = state.copy(devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) }) }
        }
        retry.performTouchInput { click() }
        verify(exactly = 1) { vm.retryConnection() }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(loading = true); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(240)
        compose.waitForIdle()
        val ring = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        ring.assertWidthIsEqualTo(androidx.compose.ui.unit.Dp(48f)).assertHeightIsEqualTo(androidx.compose.ui.unit.Dp(48f))
        retry.assertIsNotEnabled()
        assertEquals(bounds, retry.getUnclippedBoundsInRoot())
        retry.performTouchInput { click() }
        verify(exactly = 1) { vm.retryConnection() }
        compose.runOnIdle { state = state.copy(loading = false); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        ring.assertExists()
        retry.performTouchInput { click() }
        verify(exactly = 1) { vm.retryConnection() }
        compose.mainClock.advanceTimeBy(240)
        compose.waitForIdle()
        ring.assertDoesNotExist()
        retry.assertIsEnabled().performTouchInput { click() }
        verify(exactly = 2) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
        verify(exactly = 0) { vm.newSession() }
    }

    @Test fun foregroundDraftRetryUsesCoverAndLoadedHistoryRecoveryDoesNot() {
        state = state.copy(loading = true, failure = RemoteFailure.SERVICE)
        show()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft").performTextReplacement("Kept during retry")
        val message = ChatMessage(id = "loaded", text = "Retained during retry", participant = Participant.USER)
        every { vm.observeMessage(any(), "loaded") } returns flowOf(message)
        every { vm.cachedMessage(any(), "loaded") } returns message
        compose.runOnIdle {
            state = state.copy(draftSessionId = null, hydrationEnabled = true,
                messageGroups = listOf(RemoteMessageGroup(message, emptyList())))
        }
        compose.onNodeWithText("Retained during retry", substring = true).assertExists()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextContains("Kept during retry")
        verify(exactly = 0) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
    }

    @Test fun admittedHistoryRemainsRenderedAfterConnectionFailureWithoutAnEmptyView() {
        val message = ChatMessage(id = "answer", text = "Retained answer", participant = Participant.USER)
        every { vm.observeMessage(any(), "answer") } returns flowOf(message)
        every { vm.cachedMessage(any(), "answer") } returns message
        state = state.copy(draftSessionId = null, hydrationEnabled = true,
            runtime = RemoteRuntime("idle"), messageGroups = listOf(RemoteMessageGroup(message, emptyList())))
        show()
        compose.onNodeWithText("Retained answer", substring = true).assertExists()
        compose.runOnIdle {
            state = state.copy(hydrationEnabled = false, runtime = null, failure = RemoteFailure.NETWORK,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) })
        }
        compose.onNodeWithText("Retained answer", substring = true).assertExists()
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
        verify(exactly = 0) { vm.retryConnection() }
    }
}
