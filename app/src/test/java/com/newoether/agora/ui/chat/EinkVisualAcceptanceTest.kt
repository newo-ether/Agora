package com.newoether.agora.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.motion.ProvideAgoraMotionPolicy
import com.newoether.agora.ui.settings.CollapsingSettingsScaffold
import com.newoether.agora.ui.settings.EinkModeSettingsItem
import com.newoether.agora.ui.settings.SettingsGroup
import com.newoether.agora.ui.settings.SettingsGroupColumn
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.ui.theme.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native Android component renders, not physical-panel acceptance or a substitute app mockup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EinkVisualAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun appearanceToggleAppliesImmediatelyAndRestoresTheNormalPalette() {
        var enabled by mutableStateOf(false)
        var rememberedOwner: Any? = null
        compose.setContent {
            AgoraTheme(themeMode = ThemeMode.LIGHT, eInkMode = enabled) {
                rememberedOwner = remember { Any() }
                ProvideAgoraMotionPolicy(false, eInkMode = enabled) {
                    CollapsingSettingsScaffold("Appearance", {}, Modifier.width(360.dp).height(640.dp)) {
                        SettingsGroupColumn {
                            SettingsGroup("Theme & Color", items = listOf {
                                EinkModeSettingsItem(enabled) { enabled = it }
                            })
                            SettingsGroup("Reading", items = listOf {
                                Text("Replies update once per second. Completed replies and approvals appear immediately.",
                                    modifier = Modifier.padding(16.dp))
                            })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val initialOwner = rememberedOwner
        save("appearance-normal")
        compose.onNodeWithText("E-ink Mode").performClick()
        compose.runOnIdle { assertEquals(true, enabled); assertSame(initialOwner, rememberedOwner) }
        save("appearance-eink")
        compose.onNodeWithText("E-ink Mode").performClick()
        compose.runOnIdle { assertEquals(false, enabled); assertSame(initialOwner, rememberedOwner) }
    }

    @Test
    fun realMessageRendererKeepsCodeTablesAndMathReadableInEinkMode() {
        val rows = listOf(
            ChatMessage(id = "question", text = "Explain the cost and show an example.", participant = Participant.USER),
            ChatMessage(id = "answer", text = """
                ## A practical example
                The answer remains still while you read it. **Important terms** remain clear.

                | Operation | Cost |
                | --- | --- |
                | Read | O(1) |
                | Search | O(n) |

                ```kotlin
                val total = values.sum()
                println(total)
                ```

                The ratio is \(\frac{a+b}{c^2}\).
                """.trimIndent(), participant = Participant.MODEL, status = MessageStatus.SUCCESS),
        )
        ru.noties.jlatexmath.JLatexMathAndroid.init(
            androidx.test.core.app.ApplicationProvider.getApplicationContext())
        var enabled by mutableStateOf(true)
        compose.setContent {
            AgoraTheme(themeMode = ThemeMode.LIGHT, eInkMode = enabled) {
                ProvideAgoraMotionPolicy(false, eInkMode = enabled) {
                    Column(Modifier.width(360.dp).height(720.dp).background(MaterialTheme.colorScheme.background)) {
                        val state = rememberLazyListState()
                        MessageList(messages = StableMessageList(rows), conversationId = "preview",
                            state = state, viewportHeight = 620,
                            modifier = Modifier.weight(1f), initialMessage = { id -> rows.firstOrNull { it.id == id } })
                        com.newoether.agora.ui.components.EinkListNavigation(state)
                    }
                }
            }
        }
        repeat(5) { compose.waitForIdle(); Thread.sleep(100) }
        save("message-eink")
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        save("message-normal")
    }

    @Test
    fun viewportButtonsPageImmediatelyAndKeepTheAnchorWhenRowsAreAppendedWithLargeFonts() {
        lateinit var state: LazyListState
        var count by mutableStateOf(40)
        compose.setContent {
            AgoraTheme(eInkMode = true) {
                ProvideAgoraMotionPolicy(false, eInkMode = true) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                        Column(Modifier.width(360.dp).height(640.dp).background(MaterialTheme.colorScheme.background)) {
                            state = rememberLazyListState()
                            LazyColumn(Modifier.weight(1f), state = state) {
                                items(count, key = { it }) { Text("Conversation ${it + 1}", Modifier.padding(16.dp)) }
                            }
                            com.newoether.agora.ui.components.EinkListNavigation(state)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Next Page").performClick()
        var anchor = 0 to 0
        compose.runOnIdle {
            assertTrue(state.firstVisibleItemIndex > 0)
            anchor = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            count += 10
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(anchor, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset) }
        save("list-eink-large-text")
        compose.onNodeWithContentDescription("Previous Page").performClick()
        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
    }

    @Test
    fun approvalDialogKeepsActionsReadableInBothModes() {
        var enabled by mutableStateOf(true)
        var dialogView: android.view.View? = null
        compose.setContent {
            AgoraTheme(themeMode = ThemeMode.LIGHT, eInkMode = enabled) {
                ProvideAgoraMotionPolicy(false, eInkMode = enabled) {
                    com.newoether.agora.ui.components.AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Tool approval") },
                        text = { Text("Allow this tool to read the selected document?") },
                        confirmButton = {
                            dialogView = androidx.compose.ui.platform.LocalView.current
                            com.newoether.agora.ui.components.TextButton({}) { Text("Allow") }
                        },
                        dismissButton = { com.newoether.agora.ui.components.TextButton({}) { Text("Deny") } },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Allow").assertExists()
        save("dialog-eink", dialogView?.rootView)
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        save("dialog-normal", dialogView?.rootView)
    }

    @Test
    fun readingToggleRetainsDraftOwnerAndListPositionAndResetsOnConversationChange() {
        var conversation by mutableStateOf("one")
        var draftOwner: Any? = null
        lateinit var state: LazyListState
        lateinit var reading: EinkReadingMode
        compose.setContent {
            AgoraTheme(eInkMode = true) {
                ProvideAgoraMotionPolicy(false, eInkMode = true) {
                    state = rememberLazyListState()
                    reading = rememberEinkReadingMode(true, conversation, state)
                    Column(Modifier.width(360.dp).height(640.dp)) {
                        Column(Modifier.readingChrome(reading.active)) {
                            draftOwner = remember { Any() }
                            Text("Draft editor")
                        }
                        LazyColumn(Modifier.weight(1f), state = state) {
                            items(40, key = { it }) { Text("Reading paragraph ${it + 1}", Modifier.padding(16.dp)) }
                        }
                        com.newoether.agora.ui.components.EinkPageControls(state.canScrollBackward,
                            state.canScrollForward, {}, {}, reading = reading.active, onReadingToggle = reading::toggle)
                    }
                }
            }
        }
        compose.waitForIdle()
        val original = draftOwner
        compose.runOnIdle { state.requestScrollToItem(8, 0) }
        compose.waitForIdle()
        val anchor = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        compose.onNodeWithContentDescription("Reading Mode").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Draft editor").assertDoesNotExist()
        compose.runOnIdle { assertTrue(reading.active); assertSame(original, draftOwner)
            assertEquals(anchor, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset) }
        save("reading-eink")
        compose.onNodeWithContentDescription("Return to Chat").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Draft editor").assertExists()
        compose.runOnIdle { assertEquals(false, reading.active); assertSame(original, draftOwner)
            assertEquals(anchor, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset) }
        compose.onNodeWithContentDescription("Reading Mode").performClick()
        compose.runOnIdle { conversation = "two" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(false, reading.active) }
    }

    @Test
    fun realHeaderHasAnUncutCapsuleInEinkMode() {
        compose.setContent {
            AgoraTheme(eInkMode = true) {
                ProvideAgoraMotionPolicy(false, eInkMode = true) {
                    Column(Modifier.width(360.dp)) {
                        ChatTopBar(isNewChatMode = false, conversations = emptyList(), currentConversationId = "one",
                            currentConversationTitle = "Interface and device access", totalTokens = 12100,
                            contextTokenBudget = 256000, onOpenDrawer = {}, onSystemPromptClick = {}, onNewChat = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        save("header-eink")
    }

    @Test
    fun footerUsesReaderLatestUpDownOrderAndKeepsTheReaderToggleAtTheLeft() {
        var reading by mutableStateOf(false)
        val actions = mutableListOf<String>()
        compose.setContent {
            AgoraTheme(eInkMode = true) {
                ProvideAgoraMotionPolicy(false, eInkMode = true) {
                    com.newoether.agora.ui.components.EinkPageControls(true, true,
                        { actions += "up" }, { actions += "down" }, Modifier.width(360.dp),
                        onLatest = { actions += "latest" }, reading = reading,
                        onReadingToggle = { actions += "reader"; reading = !reading })
                }
            }
        }
        compose.waitForIdle()
        val labels = listOf("Reading Mode", "Jump to Latest", "Previous Page", "Next Page")
        val positions = labels.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot.left }
        assertTrue(positions.zipWithNext().all { (left, right) -> left < right })
        save("footer-eink")
        labels.forEach { compose.onNodeWithContentDescription(it).performClick() }
        compose.runOnIdle { assertEquals(listOf("reader", "latest", "up", "down"), actions) }
        assertEquals(positions.first(), compose.onNodeWithContentDescription("Return to Chat").fetchSemanticsNode().boundsInRoot.left)
    }

    @Test
    fun dialogModeOverridesAreRemovedWithoutRecreatingItsContent() {
        var enabled by mutableStateOf(false)
        lateinit var window: android.view.Window
        var owner: Any? = null
        compose.setContent {
            androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                window = (androidx.compose.ui.platform.LocalView.current.parent as androidx.compose.ui.window.DialogWindowProvider).window
                owner = remember { Any() }
                com.newoether.agora.ui.components.DialogWindowEinkPresentation(enabled)
                Text("Interactive dialog")
            }
        }
        compose.waitForIdle()
        val originalOwner = owner
        var beforeDim = 0f
        var beforeFlags = 0
        var beforeAnimation = 0
        compose.runOnIdle {
            beforeDim = window.attributes.dimAmount
            beforeFlags = window.attributes.flags
            beforeAnimation = window.attributes.windowAnimations
            enabled = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0f, window.attributes.dimAmount)
            assertEquals(0, window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            enabled = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(beforeDim, window.attributes.dimAmount)
            assertEquals(beforeFlags, window.attributes.flags)
            assertEquals(beforeAnimation, window.attributes.windowAnimations)
            assertSame(originalOwner, owner)
        }
    }

    private fun save(name: String, target: android.view.View? = null) {
        val directory = File("build/outputs/eink-screenshots").apply { mkdirs() }
        lateinit var bitmap: Bitmap
        // PixelCopy has no display server under Robolectric. Draw the actual composed Activity
        // into a native Canvas instead; fail if no visible content was painted.
        compose.runOnIdle {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
            val view = target ?: activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            view.draw(canvas)
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("The native preview must contain visible UI", pixels.count { it != android.graphics.Color.WHITE } > 500)
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
