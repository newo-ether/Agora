package com.newoether.agora.ui.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import android.content.res.Configuration
import java.util.Locale
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.chat.ChatSwitchingOverlay
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.remote.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteEmptyConversationTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(RemoteState(
        restoring = false, deviceId = "device",
        devices = listOf(RemoteDevice("device", "Quantum", "http://computer", RemoteDeviceStatus.CONNECTED,
            desktopStatus = RemoteDeviceStatus.CONNECTED)),
        session = RemoteSession("session", "", "", 0), draftSessionId = "session",
    ))
    private var active by mutableStateOf(true)
    private var bottom by mutableStateOf(180.dp)
    private var layoutDirection by mutableStateOf(LayoutDirection.Ltr)
    private var fontScale by mutableStateOf(1f)
    private var expectedSubtitleColor = Color.Unspecified
    private lateinit var drawingView: View
    private var retries = 0
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show(reduceMotion: Boolean = false, opacityProbe: Boolean = false) {
        compose.setContent {
            val colors = if (opacityProbe) lightColorScheme(background = Color.White, onBackground = Color.Red)
                else MaterialTheme.colorScheme
            MaterialTheme(colorScheme = colors) {
                drawingView = LocalView.current
                val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
                SideEffect { expectedSubtitleColor = subtitleColor }
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                    LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion),
                ) {
                    Box(Modifier.size(400.dp, 700.dp).background(MaterialTheme.colorScheme.background)
                        .testTag("remote-empty-test-host")) {
                        RemoteEmptyConversation(state, active, 80.dp, bottom, { retries++ })
                    }
                }
            }
        }
    }

    @Test fun emptyBodyIsStableOnInitialCompositionAndAfterAbsence() {
        assertInitialAndLaterAppearance(reduceMotion = false)
    }

    @Test fun reducedMotionKeepsTheSameStableEmptyBody() {
        assertInitialAndLaterAppearance(reduceMotion = true)
    }

    private fun assertInitialAndLaterAppearance(reduceMotion: Boolean) {
        state = state.copy(loading = true,
            devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) })
        compose.mainClock.autoAdvance = false
        show(reduceMotion = reduceMotion, opacityProbe = true)
        compose.waitForIdle()
        assertTrue("The empty body itself has no entrance fade", strongestWelcomeRed() >= 254)
        val body = compose.onNodeWithTag("remote-empty-conversation")
        val initialBounds = body.getUnclippedBoundsInRoot()
        frame()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        val middle = strongestWelcomeRed()
        assertTrue("Only the loading overlay owns opacity motion", middle >= 254)
        assertEquals(initialBounds, body.getUnclippedBoundsInRoot())
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val later = strongestWelcomeRed()
        assertTrue("The body remains opaque", later >= 254)
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        assertTrue("The entrance reaches full opacity", strongestWelcomeRed() >= 254)
        assertEquals(initialBounds, body.getUnclippedBoundsInRoot())
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop")
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(session = null); Snapshot.sendApplyNotifications() }
        frame()
        body.assertDoesNotExist()
        assertEquals(0, strongestWelcomeRed())
        compose.runOnIdle {
            state = state.copy(session = RemoteSession("returning", "", "", 0), loading = false,
                failure = RemoteFailure.SERVICE,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTED, name = "Renamed Workstation") })
            Snapshot.sendApplyNotifications()
        }
        frame()
        assertTrue("A later body appearance is also stable", strongestWelcomeRed() >= 254)
        val returningBounds = body.getUnclippedBoundsInRoot()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        val returningMiddle = strongestWelcomeRed()
        assertTrue("The returning body remains opaque", returningMiddle >= 254)
        assertEquals(returningBounds, body.getUnclippedBoundsInRoot())
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        assertTrue("Later appearance reaches full opacity", strongestWelcomeRed() >= 254)
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Renamed Workstation")
        compose.onAllNodesWithText(text(R.string.retry)).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.retry)).performTouchInput { click() }
        assertEquals(1, retries)
    }

    @Test fun liveFailureUpdatesAndBackgroundRecoveryKeepOneStableRetryUntilHistoryArrives() {
        compose.mainClock.autoAdvance = false
        show(opacityProbe = true)
        frame()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val enteringOpacity = strongestWelcomeRed()
        assertTrue(enteringOpacity >= 254)
        compose.runOnIdle {
            state = state.copy(failure = RemoteFailure.SERVICE,
                devices = state.devices.map { it.copy(name = "Latest Device") })
            Snapshot.sendApplyNotifications()
        }
        frame()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(text(R.string.remote_empty_retry_hint))
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Latest Device")
        compose.onNodeWithText("Quantum").assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.retry)).assertCountEquals(1)
        assertTrue("A state update does not animate the body", strongestWelcomeRed() >= 254)
        val retryBounds = compose.onNodeWithText(text(R.string.retry)).getUnclippedBoundsInRoot()
        repeat(3) {
            compose.runOnIdle {
                state = state.copy(devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) })
                Snapshot.sendApplyNotifications()
            }
            frame()
            compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(text(R.string.remote_empty_retry_hint))
            compose.onNodeWithText(text(R.string.retry)).assertIsEnabled()
            assertEquals(retryBounds, compose.onNodeWithText(text(R.string.retry)).getUnclippedBoundsInRoot())
            compose.runOnIdle { state = state.copy(devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) }) }
            frame()
            assertEquals(retryBounds, compose.onNodeWithText(text(R.string.retry)).getUnclippedBoundsInRoot())
        }
        val groups = listOf(RemoteMessageGroup(ChatMessage("loaded", text = "Retained", participant = Participant.USER), emptyList()))
        compose.runOnIdle { state = state.copy(messageGroups = groups); Snapshot.sendApplyNotifications() }
        frame()
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        assertEquals("Loaded history removes partial empty-state paint immediately", 0, strongestWelcomeRed())
        assertEquals(groups, state.messageGroups)
        compose.runOnIdle {
            state = state.copy(messageGroups = emptyList(), loading = true, failure = null,
                devices = state.devices.map { it.copy(name = "Newest Device") })
            Snapshot.sendApplyNotifications()
        }
        frame()
        assertTrue("The latest empty body is immediately stable", strongestWelcomeRed() >= 254)
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop")
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Newest Device")
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(240)
        compose.waitForIdle()
        assertTrue(strongestWelcomeRed() >= 254)
        assertEquals(0, retries)
    }

    private fun frame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun strongestWelcomeRed(): Int {
        val bounds = compose.onNodeWithTag("remote-empty-test-host").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        return try {
            compose.runOnUiThread {
                val canvas = Canvas(bitmap)
                canvas.translate(-bounds.left, -bounds.top)
                drawingView.draw(canvas)
            }
            var strongest = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                strongest = maxOf(strongest, red - green)
            }
            strongest
        } finally { bitmap.recycle() }
    }

    @Test fun onlineNewDraftAndPendingConnectionKeepProductIdentityWhileOfflineAndFailureStayLocalized() {
        show()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop").assertIsDisplayed()
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Quantum")
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        assertEquals(0, retries)
        compose.runOnIdle { state = state.copy(devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) }) }
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(devices = state.devices.map { it.copy(status = RemoteDeviceStatus.IDLE) }) }
        compose.onNodeWithText(text(R.string.remote_empty_offline_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertIsEnabled().performClick()
        assertEquals(1, retries)
        compose.runOnIdle {
            state = state.copy(draftSessionId = null, hydrationEnabled = true,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTED) })
        }
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_empty_conversation_hint)).assertDoesNotExist()
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Quantum")
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
    }

    @Test fun cliExistingRetryRetainsUnavailableHeadlineUntilNativeSourceConnects() {
        val cli = RemoteAdapter("codex-cli", "codex", "cli", "disconnected",
            capabilities = RemoteCapabilities(create = true, history = true))
        state = state.copy(draftSessionId = null, hydrationEnabled = true,
            devices = state.devices.map { it.copy(multiAdapter = true, adapters = listOf(cli),
                status = RemoteDeviceStatus.ERROR) })
        show()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(
            RuntimeEnvironment.getApplication().getString(R.string.remote_source_no_session, "Codex CLI"))
        val bounds = compose.onNodeWithText(text(R.string.retry)).getUnclippedBoundsInRoot()
        compose.runOnIdle {
            state = state.copy(loading = true,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) })
        }
        repeat(4) {
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(
                RuntimeEnvironment.getApplication().getString(R.string.remote_source_no_session, "Codex CLI"))
            compose.onNodeWithText("Codex CLI").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.retry)).assertIsNotEnabled()
            assertEquals(bounds, compose.onNodeWithText(text(R.string.retry)).getUnclippedBoundsInRoot())
        }
        compose.runOnIdle {
            state = state.copy(loading = false,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTED,
                    adapters = listOf(cli.copy(state = "connected"))) })
        }
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex CLI")
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
    }

    @Test fun failureIsNeutralAndRetryIsAnExplicitActionDisabledOnAnInactivePage() {
        state = state.copy(failure = RemoteFailure.SERVICE)
        show()
        compose.onNodeWithText(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertIsEnabled().performClick()
        assertEquals(1, retries)
        compose.runOnIdle { active = false }
        compose.onNodeWithText(text(R.string.retry)).assertIsNotEnabled()
        assertEquals(1, retries)
        compose.runOnIdle { state = state.copy(loading = true) }
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertIsNotEnabled()
    }

    @Test fun loadedContentSuppressesEveryEmptyPresentationWithoutReplacingTheHistory() {
        show()
        val message = ChatMessage(id = "answer", text = "Retained", participant = Participant.MODEL)
        val groups = listOf(RemoteMessageGroup(message, emptyList()))
        compose.runOnIdle { state = state.copy(messageGroups = groups, failure = RemoteFailure.NETWORK) }
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        assertEquals(groups, state.messageGroups)
        assertEquals(0, retries)
    }

    @Test fun guidanceFollowsTheOriginalTopAndComposerAvailableRangeIncludingImeChanges() {
        show()
        val content = compose.onNodeWithTag("remote-empty-conversation")
        val firstBounds = content.getUnclippedBoundsInRoot()
        assertEquals((80f + 700f - 180f) / 2f, (firstBounds.top.value + firstBounds.bottom.value) / 2f, 0.5f)
        compose.runOnIdle { bottom = 360.dp }
        val imeBounds = content.getUnclippedBoundsInRoot()
        assertEquals((80f + 700f - 360f) / 2f, (imeBounds.top.value + imeBounds.bottom.value) / 2f, 0.5f)
        compose.runOnIdle { state = state.copy(loading = false, failure = RemoteFailure.SERVICE) }
        val failureBounds = content.getUnclippedBoundsInRoot()
        assertEquals((80f + 700f - 360f) / 2f, (failureBounds.top.value + failureBounds.bottom.value) / 2f, 0.5f)
        assertTrue("The full failure block stays above the IME/composer", failureBounds.bottom.value <= 340.5f)
    }

    @Test fun welcomeUsesASmallerEightyDpMarkAboveTheProductAndDeviceIdentity() {
        show()
        val mark = compose.onNodeWithTag("remote-welcome-mark")
            .assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(80.dp).getUnclippedBoundsInRoot()
        val headline = compose.onNodeWithTag("remote-welcome-headline")
            .assertTextEquals("Codex Desktop").getUnclippedBoundsInRoot()
        assertEquals(16f, headline.top.value - mark.bottom.value, 0.5f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("remote-welcome-headline")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val style = layouts.single().layoutInput.style
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertEquals(28.sp, style.fontSize)
        assertEquals(35.sp, style.lineHeight)
        val device = compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Quantum").getUnclippedBoundsInRoot()
        assertEquals(8f, device.top.value - headline.bottom.value, 0.5f)
        val deviceLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("remote-welcome-device")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(deviceLayouts) }
        val deviceStyle = deviceLayouts.single().layoutInput.style
        assertEquals(14.sp, deviceStyle.fontSize)
        assertEquals(FontWeight.Normal, deviceStyle.fontWeight)
        assertEquals(expectedSubtitleColor, deviceStyle.color)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(2)
        compose.runOnIdle { state = state.copy(failure = RemoteFailure.SERVICE) }
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(text(R.string.remote_empty_retry_hint))
        compose.onNodeWithTag("remote-welcome-mark").assertWidthIsEqualTo(80.dp)
        compose.onNodeWithText(text(R.string.retry)).assertIsDisplayed()
    }

    @Test fun largeTextKeepsFailureHeadlineAndRetryAboveTheComposerByShrinkingOnlyTheMark() {
        state = state.copy(failure = RemoteFailure.SERVICE,
            devices = state.devices.map { it.copy(name = "Quantum Workstation with a very long configured device name for two lines") })
        fontScale = 1.5f
        layoutDirection = LayoutDirection.Rtl
        bottom = 360.dp
        show()
        compose.onNodeWithText(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.retry)).assertIsDisplayed().performClick()
        val bounds = compose.onNodeWithTag("remote-empty-conversation").getUnclippedBoundsInRoot()
        assertTrue(bounds.top.value >= 79.5f)
        assertTrue(bounds.bottom.value <= 340.5f)
        assertEquals(1, retries)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("remote-welcome-headline")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(28.sp, layouts.single().layoutInput.style.fontSize)
        val subtitleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("remote-welcome-device")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(subtitleLayouts) }
        assertTrue(subtitleLayouts.single().lineCount <= 2)
        val retryBounds = compose.onNodeWithText(text(R.string.retry)).getUnclippedBoundsInRoot()
        val subtitleBounds = compose.onNodeWithTag("remote-welcome-device").getUnclippedBoundsInRoot()
        assertTrue("Retry stays below the entire measured subtitle", retryBounds.top.value > subtitleBounds.bottom.value)
        val headlineBounds = compose.onNodeWithTag("remote-welcome-headline").getUnclippedBoundsInRoot()
        val markBounds = compose.onNodeWithTag("remote-welcome-mark").getUnclippedBoundsInRoot()
        val measuredDetailsHeight = bounds.bottom.value - headlineBounds.top.value
        val availableForMark = (260f - measuredDetailsHeight - 16f).coerceIn(0f, 80f)
        assertEquals("The mark uses only space left after the complete measured details",
            availableForMark, (markBounds.bottom - markBounds.top).value, 0.5f)
    }

    @Test fun veryShortFailureViewportKeepsNaturalDetailsAndMakesRetryReachableByBoundedScrolling() {
        state = state.copy(failure = RemoteFailure.SERVICE,
            devices = state.devices.map { it.copy(name = "Quantum Workstation with a very long configured device name for two lines") })
        fontScale = 1.5f
        layoutDirection = LayoutDirection.Rtl
        bottom = 480.dp
        show()
        val viewport = compose.onNodeWithTag("remote-empty-conversation")
        fun assertViewportBounds() {
            val bounds = viewport.getUnclippedBoundsInRoot()
            assertEquals(80f, bounds.top.value, 0.5f)
            assertEquals(220f, bounds.bottom.value, 0.5f)
            assertEquals(140f, (bounds.bottom - bounds.top).value, 0.5f)
        }
        assertViewportBounds()
        // The mark keeps its fixed size; the scrollable block absorbs the overflow instead.
        compose.onNodeWithTag("remote-welcome-mark").assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(80.dp)
        val headline = compose.onNodeWithTag("remote-welcome-headline")
            .assertTextEquals(text(R.string.remote_empty_retry_hint))
        val headlineLayouts = mutableListOf<TextLayoutResult>()
        headline.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(headlineLayouts) }
        assertEquals(28.sp, headlineLayouts.single().layoutInput.style.fontSize)
        val subtitleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("remote-welcome-device")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(subtitleLayouts) }
        assertEquals(2, subtitleLayouts.single().lineCount)
        val retry = compose.onNodeWithText(text(R.string.retry)).assertIsEnabled()
        val initialRetryBounds = retry.getUnclippedBoundsInRoot()
        assertTrue("Retry keeps a nonzero natural text height", (initialRetryBounds.bottom - initialRetryBounds.top).value > 0f)
        assertTrue("Retry initially lies below the short viewport", initialRetryBounds.top.value >= 220f)
        retry.assertIsNotDisplayed()
        val initialScrollRange = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, initialScrollRange.value(), 0.5f)
        assertTrue("Natural details exceed the available 140dp", initialScrollRange.maxValue() > 0f)
        retry.performScrollTo().assertIsDisplayed()
        val scrolledRange = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Reaching Retry performs a real scroll", scrolledRange.value() > 0f)
        val retryBounds = retry.getUnclippedBoundsInRoot()
        assertEquals((initialRetryBounds.bottom - initialRetryBounds.top).value,
            (retryBounds.bottom - retryBounds.top).value, 0.5f)
        assertTrue(retryBounds.top.value >= 79.5f)
        assertTrue(retryBounds.bottom.value <= 220.5f)
        assertTrue("Headline above the viewport is clipped after scrolling",
            headline.fetchSemanticsNode().boundsInRoot.top >= 79.5f)
        assertViewportBounds()
        retry.performTouchInput { click() }
        assertEquals(1, retries)
        headline.performScrollTo().assertIsDisplayed()
        assertViewportBounds()
    }

    @Test fun selectedDeviceNameUpdatesInPlaceWithoutInferringIdentityFromAddressOrRowOrder() {
        val other = RemoteDevice("other", "Render Station", "http://unrelated-host", RemoteDeviceStatus.CONNECTED,
            desktopStatus = RemoteDeviceStatus.CONNECTED)
        state = state.copy(devices = listOf(other) + state.devices)
        show()
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Quantum")
        compose.onNodeWithText("http://computer").assertDoesNotExist()
        compose.onNodeWithText("http://unrelated-host").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(deviceId = "other") }
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop")
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Render Station")
        compose.onNodeWithText("Quantum").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(devices = state.devices.map { if (it.id == "other") it.copy(name = "  Renamed Workstation  ") else it })
        }
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Renamed Workstation")
        assertEquals(0, retries)
    }

    @Test fun unnamedLegacyUrlAndMissingDeviceUseTheLocalizedDeviceFallback() {
        show()
        for (name in listOf("", "   ", "http://legacy-name", "HTTPS://legacy-name")) {
            compose.runOnIdle { state = state.copy(devices = state.devices.map { it.copy(name = name) }) }
            compose.onNodeWithTag("remote-welcome-device").assertTextEquals(text(R.string.remote_device))
            compose.onNodeWithText("http://computer").assertDoesNotExist()
            compose.onNodeWithText("http://legacy-name").assertDoesNotExist()
            compose.onNodeWithText("HTTPS://legacy-name").assertDoesNotExist()
        }
        compose.runOnIdle { state = state.copy(deviceId = "missing") }
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals(text(R.string.remote_device))
        compose.onNodeWithText(text(R.string.remote_empty_offline_hint)).assertIsDisplayed()
    }

    @Test fun longDeviceNameWrapsAtTwoLinesWithoutChangingTheProductHeadline() {
        val name = "Quantum Production Workstation with a deliberately long configured name repeated for a two line ellipsis test"
        state = state.copy(devices = state.devices.map { it.copy(name = name) })
        show()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals(name)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(name, layout.layoutInput.text.text)
        assertEquals(312, layout.layoutInput.constraints.maxWidth)
        assertEquals(2, layout.layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
        assertEquals(2, layout.lineCount)
        assertTrue(layout.isLineEllipsized(1))
        compose.onNodeWithTag("remote-welcome-mark").assertHeightIsEqualTo(80.dp)
    }

    @Test fun shortEnglishAndChineseHeadlinesUseTheSameExistingResourceKeys() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = mapOf(
            R.string.remote_new_chat_hint to ("Start a Conversation" to "开始对话"),
            R.string.remote_empty_connecting_hint to ("Connecting to Codex" to "正在连接 Codex"),
            R.string.remote_empty_offline_hint to ("Codex Is Offline" to "Codex 已离线"),
            R.string.remote_empty_retry_hint to ("Unable to Load Conversation" to "无法加载对话"),
            R.string.remote_empty_conversation_hint to ("No Messages Yet" to "暂无消息"),
        )
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
        expected.forEach { (key, pair) ->
            assertEquals(pair.first, english.getString(key))
            assertEquals(pair.second, chinese.getString(key))
        }
    }

    @Test fun loadingCoverWithoutConnectionHintRetainsItsOriginalGeometryAndGestureBarrier() {
        state = state.copy(draftSessionId = null, loading = true)
        var clicks = 0
        var drags = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp, 700.dp)) {
                    Box(Modifier.fillMaxSize().testTag("underlying-content")
                        .clickable { clicks++ }.pointerInput(Unit) {
                            detectVerticalDragGestures { _, _ -> drags++ }
                        })
                    ChatSwitchingOverlay(true, false, 80.dp, 180.dp)
                }
            }
        }
        val indicator = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        val indicatorBounds = indicator.getUnclippedBoundsInRoot()
        assertEquals((80f + 700f - 180f) / 2f,
            (indicatorBounds.top.value + indicatorBounds.bottom.value) / 2f, 0.5f)
        val formerHintCenter = indicator.fetchSemanticsNode().boundsInRoot.center + androidx.compose.ui.geometry.Offset(0f, 40f)
        compose.onNodeWithTag("underlying-content").performTouchInput {
            click(formerHintCenter)
            swipe(formerHintCenter, androidx.compose.ui.geometry.Offset(formerHintCenter.x, formerHintCenter.y - 120f))
        }
        assertEquals(0, clicks)
        assertEquals(0, drags)
        assertEquals(0, retries)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertDoesNotExist()
        compose.onNodeWithTag("remote-welcome-mark").assertDoesNotExist()
        compose.onNodeWithTag("remote-welcome-device").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
    }
}
