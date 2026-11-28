package com.newoether.agora.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sandbox.SandboxMirror
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsShellDevicePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mockk<SettingsRepository>()
    private val viewModel = mockk<ChatViewModel>()
    private val host = ShellDeviceConfig(id = "host", name = "Build Host", serverUrl = "https://build.example.test")
    private lateinit var strings: Map<Int, String>
    private lateinit var drawingView: View
    private var primaryContainer = Color.Unspecified
    private var onSurfaceVariant = Color.Unspecified

    private fun showList(
        devices: List<ShellDeviceConfig> = listOf(host),
        isPageActive: () -> Boolean = { true },
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        every { viewModel.settings } returns settings
        every { viewModel.isSandboxFlavor } returns false
        every { settings.shellEnabled } returns MutableStateFlow(true)
        every { settings.shellConfirmEnabled } returns MutableStateFlow(false)
        every { settings.shellDevices } returns MutableStateFlow(devices)
        every { settings.sandboxEnabled } returns MutableStateFlow(false)
        every { settings.sandboxSharedStorageEnabled } returns MutableStateFlow(false)
        every { settings.sandboxMirror } returns MutableStateFlow(SandboxMirror.DEFAULT)
        every { settings.showDocumentationFab } returns MutableStateFlow(false)
        every { settings.removeShellDevice(any()) } returns Job().apply { complete() }
        compose.setContent {
            drawingView = LocalView.current
            val context = LocalContext.current
            strings = listOf(R.string.task_section_details, R.string.delete, R.string.cancel,
                R.string.shell_delete_confirm_title, R.string.shell_remove_device,
                R.string.edit, R.string.options).associateWith(context::getString)
            CompositionLocalProvider(
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true),
                LocalSettingsScrollRetentionActive provides isPageActive(),
                LocalLayoutDirection provides layoutDirection,
            ) {
                MaterialTheme {
                    primaryContainer = MaterialTheme.colorScheme.primaryContainer
                    Box(Modifier.size(400.dp, 900.dp)) { SettingsShellPage(viewModel) {} }
                }
            }
        }
    }
    private fun row() = compose.onNodeWithTag("shell-device-row:${host.id}")
    private fun more() = compose.onNodeWithTag("shell-device-menu:${host.id}")
    private fun editorName() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("shell-field-name")))
    private fun menu(label: Int) = compose.onNode(hasText(strings.getValue(label)) and hasClickAction())

    @Test
    fun wholeCardOpensEditDeleteMenuAndEditActionDoesNotOpenTheEditorUntilSelected() {
        showList()
        row().performScrollTo().performClick()
        menu(R.string.edit).assertIsDisplayed()
        menu(R.string.delete).assertIsDisplayed()
        menu(R.string.task_section_details).assertDoesNotExist()
        val editBounds = menu(R.string.edit).fetchSemanticsNode().boundsInRoot
        val deleteBounds = menu(R.string.delete).fetchSemanticsNode().boundsInRoot
        assertTrue("Edit is the first menu action", editBounds.top < deleteBounds.top)
        editorName().assertDoesNotExist()
        menu(R.string.edit).performClick()
        compose.waitForIdle()
        editorName().assertExists()
        compose.onNodeWithContentDescription(strings.getValue(R.string.shell_remove_device)).assertDoesNotExist()
        menu(R.string.delete).assertDoesNotExist()
    }

    @Test
    fun trailingMoreOpensTheSameMenuAndOnlyEditEntersTheEditor() {
        showList()
        more().assertContentDescriptionEquals(strings.getValue(R.string.options))
        compose.onNodeWithContentDescription(strings.getValue(R.string.edit)).assertDoesNotExist()
        more().performScrollTo().performTouchInput { click() }
        compose.waitForIdle()
        editorName().assertDoesNotExist()
        menu(R.string.edit).assertIsDisplayed()
        menu(R.string.delete).assertIsDisplayed()
        menu(R.string.edit).performClick()
        compose.waitForIdle()
        editorName().assertExists()
        compose.onNodeWithContentDescription(strings.getValue(R.string.shell_remove_device)).assertDoesNotExist()
        menu(R.string.delete).assertDoesNotExist()
    }

    @Test
    fun ltrMoreKeepsItsFortyEightDpTargetAndSixDpEndPaddingWithAnAdjacentPopup() {
        verifyMoreGeometryAndPopup(LayoutDirection.Ltr, openFromRow = false)
    }

    @Test
    fun rtlWholeRowOpensThePopupBesideItsMirroredMoreAnchor() {
        verifyMoreGeometryAndPopup(LayoutDirection.Rtl, openFromRow = true)
    }

    private fun verifyMoreGeometryAndPopup(direction: LayoutDirection, openFromRow: Boolean) {
        showList(layoutDirection = direction)
        more().performScrollTo()
        val rowBounds = row().getUnclippedBoundsInRoot()
        val buttonBounds = more().getUnclippedBoundsInRoot()
        val anchorBounds = compose.onNodeWithTag(
            "shell-device-menu-anchor:${host.id}", useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        // Material's semantics expose the 40 dp inner container. Its touch target and
        // outer minimum-size layout are separate from that visible container.
        more().assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        assertEquals(48.dp, anchorBounds.right - anchorBounds.left)
        assertEquals(48.dp, anchorBounds.bottom - anchorBounds.top)
        assertEquals(buttonBounds.left + buttonBounds.right, anchorBounds.left + anchorBounds.right)
        assertEquals(buttonBounds.top + buttonBounds.bottom, anchorBounds.top + anchorBounds.bottom)
        assertTrue("The anchor contains the inner button layout",
            anchorBounds.left <= buttonBounds.left && anchorBounds.right >= buttonBounds.right &&
                anchorBounds.top <= buttonBounds.top && anchorBounds.bottom >= buttonBounds.bottom)
        val endPadding = if (direction == LayoutDirection.Ltr) {
            rowBounds.right - anchorBounds.right
        } else anchorBounds.left - rowBounds.left
        assertEquals(6.dp, endPadding)
        val screenRow = row().screenBounds()
        val screenAnchor = compose.onNodeWithTag(
            "shell-device-menu-anchor:${host.id}", useUnmergedTree = true,
        ).screenBounds()
        val window = compose.onRoot().screenBounds()
        if (openFromRow) row().performTouchInput { click() }
        else more().performTouchInput { click() }
        val popup = compose.onNodeWithTag("shell-device-dropdown:${host.id}")
        popup.assertIsDisplayed().assert(hasAnyAncestor(isPopup()))
        val screenPopup = popup.screenBounds()
        if (direction == LayoutDirection.Ltr) {
            assertEquals("Popup ends at the More anchor", screenAnchor.right, screenPopup.right, 1f)
            assertTrue("Popup opens in the trailing half", screenPopup.center.x > screenRow.center.x)
        } else {
            assertEquals("RTL popup starts at the More anchor", screenAnchor.left, screenPopup.left, 1f)
            assertTrue("RTL popup opens in the trailing half", screenPopup.center.x < screenRow.center.x)
        }
        assertTrue("Popup remains inside the window: popup=$screenPopup window=$window",
            screenPopup.left >= window.left - 1f && screenPopup.right <= window.right + 1f)
        menu(R.string.edit).assertIsDisplayed()
        menu(R.string.delete).assertIsDisplayed()
        editorName().assertDoesNotExist()
        verify(exactly = 0) { settings.removeShellDevice(any()) }
    }

    @Test
    fun expandedMenuClosesOnPageInactivationAndDoesNotReviveOrActOnReturn() {
        var pageActive by mutableStateOf(true)
        showList(isPageActive = { pageActive })
        more().performScrollTo().performTouchInput { click() }
        menu(R.string.edit).assertIsDisplayed()
        menu(R.string.delete).assertIsDisplayed()
        compose.runOnIdle { pageActive = false }
        compose.waitForIdle()
        menu(R.string.edit).assertDoesNotExist()
        menu(R.string.delete).assertDoesNotExist()
        more().assertIsNotEnabled().performTouchInput { click() }
        row().performTouchInput { click() }
        compose.waitForIdle()
        editorName().assertDoesNotExist()
        compose.onNodeWithText(strings.getValue(R.string.shell_delete_confirm_title)).assertDoesNotExist()
        verify(exactly = 0) { settings.removeShellDevice(any()) }
        compose.runOnIdle { pageActive = true }
        compose.waitForIdle()
        more().assertIsEnabled()
        menu(R.string.edit).assertDoesNotExist()
        menu(R.string.delete).assertDoesNotExist()
        editorName().assertDoesNotExist()
        compose.onNodeWithText(strings.getValue(R.string.shell_delete_confirm_title)).assertDoesNotExist()
        verify(exactly = 0) { settings.removeShellDevice(any()) }
    }

    @Test
    fun menuDeleteOnlyRemovesTheCapturedDeviceAfterExplicitConfirmation() {
        showList()
        row().performScrollTo().performClick()
        menu(R.string.delete).performClick()
        compose.onNodeWithText(strings.getValue(R.string.shell_delete_confirm_title)).assertExists()
        verify(exactly = 0) { settings.removeShellDevice(any()) }
        menu(R.string.cancel).performClick()
        verify(exactly = 0) { settings.removeShellDevice(any()) }
        row().performClick()
        menu(R.string.delete).performClick()
        menu(R.string.delete).performClick()
        verify(exactly = 1) { settings.removeShellDevice(host.id) }
        editorName().assertDoesNotExist()
    }

    @Test
    fun listRetainsOnlyTheDescriptionAndEllipsizesItAtTwoLines() {
        val description = "A dedicated build machine with isolated jobs and cached dependencies. ".repeat(12)
        val described = host.copy(description = description)
        val ssh = host.copy(
            id = "ssh", name = "SSH Host", type = "ssh", sshHost = "ssh.example.test",
            description = "Interactive shell", sshUser = "builder", sshPort = 2222,
        )
        showList(listOf(described, ssh))
        row().performScrollTo()
        val rendered = compose.onNode(
            hasText(description) and hasAnyAncestor(hasTestTag("shell-device-row:${host.id}")),
            useUnmergedTree = true,
        )
        rendered.assertIsDisplayed()
        val descriptionLayout = layout(rendered)
        assertEquals(2, descriptionLayout.lineCount)
        assertTrue("Long descriptions visibly end in an ellipsis", descriptionLayout.isLineEllipsized(1))
        compose.onNodeWithText("Interactive shell", useUnmergedTree = true).assertExists()
        for (address in listOf(host.serverUrl, ssh.sshHost, "builder@ssh.example.test:2222")) {
            compose.onNodeWithText(address, substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun blankDescriptionsAddNoAddressOrReplacementSupportingText() {
        showList()
        row().performScrollTo()
        row().assertTextEquals(host.name, "Conch")
        compose.onNodeWithText(host.serverUrl, substring = true, useUnmergedTree = true).assertDoesNotExist()
        more().performClick()
        menu(R.string.edit).performClick()
        val url = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("shell-field-url")))
        url.performScrollTo().assertTextEquals(host.serverUrl)
    }

    @Test
    fun conchAndSshBadgesUseTheRestoredPrimaryContainerFillAndLabelSmall() {
        val ssh = host.copy(id = "ssh", name = "SSH Host", type = "ssh", sshHost = "ssh.example.test")
        showList(listOf(host, ssh))
        for ((device, text) in listOf(host to "Conch", ssh to "SSH")) {
            val badge = compose.onNodeWithTag("shell-device-type:${device.id}", useUnmergedTree = true)
            badge.performScrollTo()
            val image = draw(badge)
            try {
                assertColor(primaryContainer, Color(image.getPixel(image.width / 2, 1)))
            } finally {
                image.recycle()
            }
            val label = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("shell-device-type:${device.id}")), useUnmergedTree = true)
            assertEquals(11.sp, layout(label).layoutInput.style.fontSize)
        }
    }

    // API 35 patterned ripple pixels require hardware. A real pointer press must reach
    // the platform drawable, and cancellation/release must clear its pressed state.
    @Test
    fun cardPointerPressReleaseAndCancellationReachTheApi35PlatformRipple() {
        showList()
        row().performScrollTo()
        row().performTouchInput {
            down(center)
            // The scroll container delays clickable's press indication while detecting a drag.
            advanceEventTime(160)
        }
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        lateinit var ripple: RippleDrawable
        try {
            compose.runOnIdle { ripple = pressedRipple() }
            editorName().assertDoesNotExist()
            menu(R.string.edit).assertDoesNotExist()
        } finally {
            row().performTouchInput { cancel() }
        }
        compose.runOnIdle { assertFalse(android.R.attr.state_pressed in ripple.state) }
        menu(R.string.edit).assertDoesNotExist()
        row().performTouchInput {
            down(center)
            // The scroll container delays clickable's press indication while detecting a drag.
            advanceEventTime(160)
        }
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        try {
            compose.runOnIdle { assertSame(ripple, pressedRipple()) }
        } finally {
            row().performTouchInput { up() }
        }
        compose.runOnIdle { assertFalse(android.R.attr.state_pressed in ripple.state) }
        menu(R.string.edit).assertExists()
    }

    @Test
    fun newSshUsernameIsAnEmptyEditableFieldWithAGrayRootPlaceholder() {
        compose.setContent {
            MaterialTheme {
                onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                Box(Modifier.size(400.dp, 900.dp)) {
                    SettingsShellDeviceEditor(
                        initial = host.copy(type = "ssh", sshHost = "example.test"), isNew = true,
                        viewModel = viewModel, existingDevices = emptyList(), showDocFab = false,
                        isCurrent = { true }, onBack = {},
                    )
                }
            }
        }
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("shell-field-user")))
        field.performScrollTo()
        field.assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            androidx.compose.ui.text.AnnotatedString(""),
        ))
        val hint = compose.onNode(hasText("root") and hasAnyAncestor(hasTestTag("shell-field-user")),
            useUnmergedTree = true).assertIsDisplayed()
        val style = layout(hint).layoutInput.style
        assertEquals(14.sp, style.fontSize)
        assertEquals(onSurfaceVariant, style.color)
        field.performTextReplacement("builder")
        hint.assertDoesNotExist()
        field.assertTextEquals("builder")
    }
    @Test
    fun shellUrlValueAndPlaceholderRenderAtTheSameSmallerFontAsTheMcpField() {
        var reference by mutableStateOf("")
        var hint = ""
        var label = ""
        compose.setContent {
            drawingView = LocalView.current
            val context = LocalContext.current
            hint = context.getString(R.string.shell_device_url_hint)
            label = context.getString(R.string.shell_device_url)
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.size(400.dp, 950.dp)) {
                        Box(Modifier.weight(1f)) {
                            SettingsShellDeviceEditor(
                                initial = host.copy(serverUrl = ""), isNew = false,
                                viewModel = viewModel, existingDevices = listOf(host),
                                showDocFab = false, isCurrent = { true }, onBack = {},
                            )
                        }
                        McpLabeledField(
                            label = "Reference URL", value = reference, onValueChange = { reference = it },
                            placeholder = hint, modifier = Modifier.testTag("mcp-reference"),
                        )
                    }
                }
            }
        }
        val shellField = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("shell-field-url")))
        shellField.performScrollTo()
        val shellHint = compose.onNode(hasText(hint) and hasAnyAncestor(hasTestTag("shell-field-url")), useUnmergedTree = true)
        val referenceHint = compose.onNode(hasText(hint) and hasAnyAncestor(hasTestTag("mcp-reference")), useUnmergedTree = true)
        val hintStyle = layout(shellHint).layoutInput.style
        val referenceStyle = layout(referenceHint).layoutInput.style
        assertEquals(14.sp, hintStyle.fontSize)
        assertEquals(referenceStyle.fontSize, hintStyle.fontSize)
        assertEquals(referenceStyle.lineHeight, hintStyle.lineHeight)
        assertEquals(onSurfaceVariant, hintStyle.color)
        val labelStyle = layout(compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("shell-field-url")), useUnmergedTree = true)).layoutInput.style
        assertEquals(16.sp, labelStyle.fontSize)
        assertEquals(FontWeight.Medium, labelStyle.fontWeight)
        val url = "https://typed.example.test"
        shellField.performTextReplacement(url)
        compose.runOnIdle { reference = url }
        val referenceField = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("mcp-reference")))
        val valueStyle = layout(shellField).layoutInput.style
        assertEquals(14.sp, valueStyle.fontSize)
        assertEquals(layout(referenceField).layoutInput.style.fontSize, valueStyle.fontSize)
        assertEquals(hintStyle.fontSize, valueStyle.fontSize)
        assertEquals(onSurfaceVariant, valueStyle.color)
    }

    private fun SemanticsNodeInteraction.screenBounds(): Rect {
        val node = fetchSemanticsNode()
        val root = node.layoutInfo.coordinates.findRootCoordinates()
        return node.boundsInRoot.translate(root.localToScreen(Offset.Zero))
    }

    private fun draw(node: SemanticsNodeInteraction): Bitmap {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val image = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            val canvas = Canvas(image)
            canvas.translate(-bounds.left, -bounds.top)
            drawingView.draw(canvas)
        }
        return image
    }

    private fun pressedRipple(): RippleDrawable {
        fun ripples(view: View): List<RippleDrawable> = buildList {
            (view.background as? RippleDrawable)?.let { add(it) }
            if (view is ViewGroup) repeat(view.childCount) { addAll(ripples(view.getChildAt(it))) }
        }
        val pressed = ripples(drawingView.rootView).filter { android.R.attr.state_pressed in it.state }
        assertEquals("A real card press reaches one platform ripple", 1, pressed.size)
        return pressed.single()
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
    private fun assertColor(expected: Color, actual: Color) {
        assertTrue(abs(expected.red - actual.red) <= 1f / 255f)
        assertTrue(abs(expected.green - actual.green) <= 1f / 255f)
        assertTrue(abs(expected.blue - actual.blue) <= 1f / 255f)
        assertEquals(expected.alpha, actual.alpha, 1f / 255f)
    }
}
