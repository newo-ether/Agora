package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.phone.PhoneApp
import com.newoether.agora.phone.PhonePermissions
import com.newoether.agora.phone.PhoneUseIcon
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// Size qualifiers trigger a Robolectric dialog/text-field loop: robolectric/robolectric#8460.
@Config(sdk = [35], application = Application::class, qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPhoneUsePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val apps = listOf(PhoneApp("com.example.alpha", "Alpha"), PhoneApp("org.example.beta", "Beta"))
    private var selected by mutableStateOf(emptySet<String>())
    private val toggles = mutableListOf<String>()
    private var dismissals = 0
    private lateinit var title: String
    private lateinit var done: String

    private fun showPicker(entries: List<PhoneApp>? = apps, fontScale: Float = 1f) {
        compose.setContent {
            val context = LocalContext.current
            title = context.getString(R.string.phone_choose_apps)
            done = context.getString(R.string.phone_done)
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true),
            ) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 900.dp)) {
                        PhoneAppPickerDialog(entries, selected, onToggle = { packageName ->
                            toggles += packageName
                            selected = if (packageName in selected) selected - packageName else selected + packageName
                        }, onDismiss = { dismissals++ })
                    }
                }
            }
        }
    }

    @Test fun appPickerUsesTheModelPickerTextHierarchyAndLeadingCheckboxLayout() {
        showPicker()
        assertEquals(FontWeight.Bold, layout(compose.onNodeWithText(title)).layoutInput.style.fontWeight)
        val label = compose.onNodeWithText("Alpha", useUnmergedTree = true)
        val packageName = compose.onNodeWithText(apps[0].packageName, useUnmergedTree = true)
        assertEquals(16.sp, layout(label).layoutInput.style.fontSize)
        assertEquals(FontWeight.Medium, layout(label).layoutInput.style.fontWeight)
        assertEquals(12.sp, layout(packageName).layoutInput.style.fontSize)
        val checkbox = compose.onAllNodes(isToggleable(), useUnmergedTree = true)[0]
            .getUnclippedBoundsInRoot()
        val labelBounds = label.getUnclippedBoundsInRoot()
        val packageBounds = packageName.getUnclippedBoundsInRoot()
        assertTrue("Checkbox precedes the text column", checkbox.right < labelBounds.left)
        assertEquals(labelBounds.left, packageBounds.left)
        assertTrue("Package is below the name", packageBounds.top >= labelBounds.bottom)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText(done).assertIsDisplayed()
    }

    @Test fun filteringAndRowSelectionApplyImmediatelyAndDoneOnlyDismisses() {
        showPicker()
        val search = compose.onNode(hasSetTextAction())
        search.performTextInput("ALPHA")
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithText("Alpha").performClick()
        assertEquals(setOf(apps[0].packageName), selected)
        assertEquals(listOf(apps[0].packageName), toggles)
        search.performTextReplacement("org.example")
        compose.onNodeWithText("Alpha").assertDoesNotExist()
        compose.onNodeWithText("Beta").performClick()
        search.performTextClearance()
        compose.onAllNodes(isToggleable(), useUnmergedTree = true)[0].assertIsOn()
        compose.onAllNodes(isToggleable(), useUnmergedTree = true)[0].performClick()
        assertEquals(setOf(apps[1].packageName), selected)
        assertEquals(listOf(apps[0].packageName, apps[1].packageName, apps[0].packageName), toggles)
        compose.onNodeWithText(done).performClick()
        assertEquals(1, dismissals)
        assertEquals(3, toggles.size)
    }

    @Test fun enlargedFontPickerScrollsToTheLastAppAndKeepsTheDismissActionReachable() {
        val entries = (1..40).map { PhoneApp("com.example.application$it", "Application $it") }
        showPicker(entries, fontScale = 1.6f)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(entries.last().label))
        compose.onNodeWithText(entries.last().label).assertIsDisplayed().performClick()
        assertEquals(setOf(entries.last().packageName), selected)
        compose.onNodeWithText(done).assertIsDisplayed().performClick()
        assertEquals(1, dismissals)
    }

    @Test fun pickerLoadingIsCenteredAndDoesNotComposeAnEmptyList() {
        showPicker(null)
        val indicator = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .getUnclippedBoundsInRoot()
        val search = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        assertEquals((search.left + search.right).value / 2f, (indicator.left + indicator.right).value / 2f, 0.5f)
        assertEquals(24f, (indicator.top - search.bottom - 12.dp).value, 0.5f)
        compose.onNode(hasScrollToIndexAction()).assertDoesNotExist()
        compose.onNodeWithText(done).assertIsDisplayed()
    }

    @Test fun permissionRowsExistBeforeRuntimeReadinessWithoutMissingFallback() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                    SettingsPhonePermissions("shizuku", 0, null, {})
                }
            }
        }
        for (label in listOf(R.string.phone_write_settings_permission, R.string.phone_shizuku_installed, R.string.phone_shizuku_running,
            R.string.phone_shizuku_permission)) {
            compose.onNodeWithText(text(label)).performScrollTo().assertIsDisplayed()
        }
        compose.onAllNodesWithText(text(R.string.about_checking), useUnmergedTree = true).assertCountEquals(6)
        compose.onNodeWithText(text(R.string.phone_missing)).assertDoesNotExist()
    }

    @Test fun pendingRootShowsCheckingWhileOtherRowsResolveAndDoesNotPoll() {
        val completion = CompletableDeferred<Boolean>()
        val permissions = mockk<PhonePermissions> {
            every { granted("root", any()) } returns mapOf("overlay" to true, "write_settings" to true, "notifications" to false, "root" to false)
            coEvery { probeRoot() } coAnswers { completion.await() }
        }
        compose.setContent { MaterialTheme { SettingsPhonePermissions("root", 0, permissions, {}) } }
        val checking = compose.onNodeWithText(text(R.string.about_checking), useUnmergedTree = true)
        val before = checking.getUnclippedBoundsInRoot()
        assertEquals(TextAlign.End, layout(checking).layoutInput.style.textAlign)
        compose.onAllNodesWithText(text(R.string.phone_granted), useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText(text(R.string.phone_missing), useUnmergedTree = true).assertCountEquals(1)
        compose.runOnIdle { completion.complete(true) }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.about_checking)).assertDoesNotExist()
        val granted = compose.onAllNodesWithText(text(R.string.phone_granted), useUnmergedTree = true)
        granted.assertCountEquals(3)
        val after = granted[2].getUnclippedBoundsInRoot()
        assertEquals(before.left, after.left)
        assertEquals(before.right, after.right)
        compose.mainClock.advanceTimeBy(5000)
        verify(exactly = 1) { permissions.granted("root", any()) }
        coVerify(exactly = 1) { permissions.probeRoot() }
    }

    @Test fun lateRootProbeCannotReplaceAnotherModesPermissionRows() {
        val completion = CompletableDeferred<Boolean>()
        var mode by mutableStateOf("root")
        val permissions = mockk<PhonePermissions> {
            every { granted("root", any()) } returns mapOf("overlay" to true, "write_settings" to true, "notifications" to true, "root" to false)
            every { granted("shizuku", any()) } returns mapOf("overlay" to true, "write_settings" to true, "notifications" to true,
                "shizuku_installed" to true, "shizuku_running" to true, "shizuku_permission" to true)
            coEvery { probeRoot() } coAnswers { withContext(NonCancellable) { completion.await() } }
        }
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                    SettingsPhonePermissions(mode, 0, permissions, {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.about_checking)).assertExists()
        compose.runOnIdle { mode = "shizuku" }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.phone_root)).assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.phone_granted), useUnmergedTree = true).assertCountEquals(6)
        compose.runOnIdle { completion.complete(false) }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.phone_missing)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.phone_shizuku_permission)).performScrollTo().assertIsDisplayed()
        verify(exactly = 1) { permissions.granted("shizuku", any()) }
    }

    private fun text(id: Int) = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>().getString(id)

    @Test fun sharedChoicesKeepAStableEndAlignedSlotWhenSelectionChanges() {
        assertChoiceAlignment(LayoutDirection.Ltr)
    }

    @Test fun sharedChoiceAlignmentFollowsTheLogicalEndInRtl() {
        assertChoiceAlignment(LayoutDirection.Rtl)
    }

    private fun assertChoiceAlignment(direction: LayoutDirection) {
        var mode by mutableStateOf("root")
        var rootLabel = ""
        var accessibilityLabel = ""
        compose.setContent {
            val context = LocalContext.current
            rootLabel = context.getString(R.string.phone_root)
            accessibilityLabel = context.getString(R.string.phone_accessibility)
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MaterialTheme {
                    Column(Modifier.width(360.dp)) {
                        SettingsChoiceItem(R.string.phone_mode, R.string.phone_mode_description,
                            PhoneUseIcon, mode,
                            listOf("root" to R.string.phone_root, "accessibility" to R.string.phone_accessibility),
                        ) { mode = it }
                    }
                }
            }
        }
        val initial = compose.onNodeWithText(rootLabel, useUnmergedTree = true)
        val before = initial.getUnclippedBoundsInRoot()
        val result = layout(initial)
        assertEquals(TextAlign.End, result.layoutInput.style.textAlign)
        assertEquals(1, result.layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, result.layoutInput.overflow)
        assertEquals(92f * result.layoutInput.density.density, result.size.width.toFloat(), 1f)
        compose.onNodeWithText(rootLabel).performClick()
        compose.onNode(hasText(accessibilityLabel) and hasAnyAncestor(isPopup())).performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        val after = compose.onNodeWithText(accessibilityLabel, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(before.left, after.left)
        assertEquals(before.right, after.right)
        assertEquals("accessibility", mode)
    }

    @Test fun phoneUseDestinationFollowsMcpAndUsesThePhoneControlIcon() {
        val tools = baseSettingsGroups.single { it.titleRes == R.string.settings_group_tools }.items
        val index = tools.indexOfFirst { it.key == "phoneuse" }
        assertEquals("mcp", tools[index - 1].key)
        assertEquals("automation", tools[index + 1].key)
        assertEquals(PhoneUseIcon, tools[index].icon)
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
