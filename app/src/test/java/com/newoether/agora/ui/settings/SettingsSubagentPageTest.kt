package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.viewmodel.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
class SettingsSubagentPageTest {
    @get:Rule val compose = createComposeRule()

    private val alpha = "OpenAI:alpha"
    private val beta = "OpenAI:beta"
    private val gamma = "Local:gamma"
    private var enabled by mutableStateOf(true)
    private var configured by mutableStateOf<Set<String>?>(null)
    private var globalModels by mutableStateOf(setOf(alpha, beta, gamma))
    private var recommended by mutableStateOf<String?>(null)
    private val additions = mutableListOf<Set<String>>()
    private val removals = mutableListOf<String>()
    private val recommendations = mutableListOf<String?>()
    private val enableChanges = mutableListOf<Boolean>()
    private var capsuleMode by mutableStateOf("individual")
    private val capsuleModeChanges = mutableListOf<String>()
    private val retention = SettingsScrollRetention()
    private lateinit var scope: CoroutineScope
    private lateinit var strings: Map<Int, String>
    private lateinit var expectedValueStyle: TextStyle
    private var expectedPrimary = Color.Unspecified
    private var backs = 0
    private var appearanceReference by mutableStateOf(false)
    private var appearanceViewModel: ChatViewModel? = null

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        scope = rememberCoroutineScope()
        val context = LocalContext.current
        strings = listOf(
            R.string.subagents_enable,
            R.string.subagents_capsule_display_title,
            R.string.subagents_limits_title,
            R.string.subagents_capsule_display_individual,
            R.string.subagents_capsule_display_grouped,
            R.string.subagents_model_list_title,
            R.string.subagents_recommended_model_title,
            R.string.subagents_no_recommended_model,
            R.string.subagents_no_models,
            R.string.subagents_add_models,
            R.string.provider_add,
            R.string.provider_cancel,
            R.string.provider_save,
            R.string.delete,
            R.string.theme_mode,
            R.string.theme_mode_dark,
        ).associateWith { context.getString(it) }
        CompositionLocalProvider(
            LocalSettingsScrollRetention provides retention,
            LocalSettingsScrollPath provides listOf("subagents"),
            LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true),
        ) {
            MaterialTheme {
                expectedValueStyle = MaterialTheme.typography.labelLarge
                expectedPrimary = MaterialTheme.colorScheme.primary
                Box(Modifier.size(400.dp, 800.dp)) { content() }
            }
        }
    }

    @Composable
    private fun Content() {
        SettingsSubagentContent(
            subagentToolsEnabled = enabled,
            subagentEnabledModels = configured,
            subagentRecommendedModel = recommended,
            enabledModels = globalModels,
            modelAliases = mapOf(alpha to "Alpha", beta to "Beta", gamma to "Gamma"),
            customProviders = emptyList<CustomProviderConfig>(),
            onSubagentToolsEnabledChange = {
                enableChanges += it
                enabled = it
            },
            onAddModels = {
                additions += it
                configured = configured.orEmpty() + it
            },
            onRemoveModel = {
                removals += it
                configured = configured.orEmpty() - it
                if (recommended == it) recommended = null
            },
            onRecommendedModelChange = {
                recommendations += it
                recommended = it
            },
            onBack = { backs++ },
            capsuleDisplayMode = capsuleMode,
            onCapsuleDisplayModeChange = { capsuleModeChanges += it; capsuleMode = it },
        )
    }

    @Test
    fun enabledPageOrdersEnableRecommendationModelsDisplayAndLimits() {
        configured = setOf(alpha)
        show()
        val rows = listOf(
            compose.onNodeWithText(text(R.string.subagents_enable)),
            compose.onNodeWithText(text(R.string.subagents_recommended_model_title)),
            compose.onNodeWithText(text(R.string.subagents_model_list_title)),
            compose.onNodeWithTag("subagent-capsule-display"),
            compose.onNodeWithText(text(R.string.subagents_limits_title)),
        )
        assertDocumentOrder(rows)
        compose.onNodeWithTag("subagent-max-total").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("subagent-max-concurrent").performScrollTo().assertIsEnabled()
    }

    @Test
    fun disablingKeepsDisplayBeforeLimitsAndHidesOnlyModelGroups() {
        configured = setOf(alpha)
        show()
        compose.onNodeWithText(text(R.string.subagents_enable)).performClick()
        compose.onNodeWithText(text(R.string.subagents_recommended_model_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.subagents_model_list_title)).assertDoesNotExist()
        assertDocumentOrder(listOf(
            compose.onNodeWithText(text(R.string.subagents_enable)),
            compose.onNodeWithTag("subagent-capsule-display"),
            compose.onNodeWithText(text(R.string.subagents_limits_title)),
        ))
        compose.onNodeWithTag("subagent-capsule-display").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("subagent-max-total").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("subagent-max-concurrent").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(listOf(false), enableChanges)
            assertEquals(setOf(alpha), configured)
        }
    }

    @Test
    fun capsuleDisplayUsesPrimaryLabelTypographyAndAnEndAlignedValueSlot() {
        enabled = false
        show()
        val row = compose.onNodeWithTag("subagent-capsule-display").performScrollTo()
        val value = compose.onNodeWithText(
            text(R.string.subagents_capsule_display_individual), useUnmergedTree = true,
        )
        val layouts = mutableListOf<TextLayoutResult>()
        value.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val style = layout.layoutInput.style
        assertEquals(expectedPrimary, style.color)
        assertEquals(expectedValueStyle.fontFamily, style.fontFamily)
        assertEquals(expectedValueStyle.fontSize, style.fontSize)
        assertEquals(expectedValueStyle.fontWeight, style.fontWeight)
        assertEquals(expectedValueStyle.lineHeight, style.lineHeight)
        assertEquals(expectedValueStyle.letterSpacing, style.letterSpacing)
        assertEquals(TextAlign.End, style.textAlign)
        assertEquals(1, layout.layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
        val density = layout.layoutInput.density.density
        assertEquals(92f * density, layout.size.width.toFloat(), 1f)
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val valueBounds = value.fetchSemanticsNode().boundsInRoot
        assertTrue("Current value must stay in the trailing half", valueBounds.left > rowBounds.center.x)
        assertTrue("Current value must stay within its row", valueBounds.right <= rowBounds.right)
    }

    @Test
    fun capsuleDisplayMenuOpensBesideTheTrailingValueAndSelectionClosesIt() {
        enabled = false
        show()
        val row = compose.onNodeWithTag("subagent-capsule-display").performScrollTo()
        val rowBounds = row.screenBounds()
        val windowBounds = compose.onRoot().screenBounds()
        val valueBounds = compose.onNodeWithText(
            text(R.string.subagents_capsule_display_individual), useUnmergedTree = true,
        ).screenBounds()
        row.performClick()
        val choice = compose.onNode(
            hasText(text(R.string.subagents_capsule_display_grouped)) and hasAnyAncestor(isPopup()),
        )
        val menuItemBounds = choice.screenBounds()
        assertTrue("Menu must open on the trailing side", menuItemBounds.center.x > rowBounds.center.x)
        assertTrue("Menu must overlap the trailing anchor", menuItemBounds.right >= valueBounds.left)
        assertTrue("Menu must stay inside the app window: menu=$menuItemBounds window=$windowBounds",
            menuItemBounds.left >= windowBounds.left - 1f && menuItemBounds.right <= windowBounds.right + 1f)
        choice.performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("grouped"), capsuleModeChanges) }
    }

    @Test
    fun capsuleDisplayPopupMatchesTheProductionAppearanceTrailingAnchor() {
        enabled = false
        val settings = mockk<SettingsRepository>(relaxed = true)
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        every { viewModel.settings } returns settings
        every { settings.themeMode } returns MutableStateFlow("LIGHT")
        every { settings.amoledEnabled } returns MutableStateFlow(false)
        every { settings.colorScheme } returns MutableStateFlow("FOREST")
        every { settings.schemeStyle } returns MutableStateFlow("TONAL_SPOT")
        every { settings.dynamicColor } returns MutableStateFlow(false)
        every { settings.blurEffectsEnabled } returns MutableStateFlow(false)
        every { settings.reduceMotion } returns MutableStateFlow(true)
        every { settings.stickToBottom } returns MutableStateFlow(true)
        every { settings.showGenerationSpeed } returns MutableStateFlow(false)
        every { settings.parseInlineDollarMath } returns MutableStateFlow(false)
        every { settings.autoWrapCodeBlocks } returns MutableStateFlow(false)
        every { settings.hapticsEnabled } returns MutableStateFlow(false)
        every { settings.toolCallDisplayMode } returns MutableStateFlow("expanded")
        every { settings.thinkingSegmentDisplayMode } returns MutableStateFlow("card")
        every { settings.autoExpandActiveGroup } returns MutableStateFlow(false)
        every { settings.fontPreference } returns MutableStateFlow("default")
        every { settings.customFontPath } returns MutableStateFlow("")
        every { settings.customFontName } returns MutableStateFlow("")
        every { settings.showDocumentationFab } returns MutableStateFlow(false)
        appearanceViewModel = viewModel
        show()
        compose.onNodeWithTag("subagent-capsule-display").performScrollTo().performClick()
        val choice = compose.onNode(
            hasText(text(R.string.subagents_capsule_display_grouped)) and hasAnyAncestor(isPopup()),
        )
        val capsuleBounds = choice.screenBounds()
        choice.performClick()
        compose.runOnIdle { appearanceReference = true }
        compose.onNodeWithText(text(R.string.theme_mode)).performScrollTo().performClick()
        val appearanceBounds = compose.onNode(
            hasText(text(R.string.theme_mode_dark)) and hasAnyAncestor(isPopup()),
        ).screenBounds()
        assertEquals("Both production menus must share the same trailing edge", appearanceBounds.right, capsuleBounds.right, 1f)
    }

    private fun assertDocumentOrder(rows: List<SemanticsNodeInteraction>) {
        val tops = rows.map { row ->
            row.performScrollTo()
            val top = row.fetchSemanticsNode().boundsInRoot.top
            val scroll = retention.scrollState(listOf("subagents"))!!.value
            top + scroll
        }
        tops.zipWithNext().forEach { (before, after) ->
            assertTrue("Settings sections must follow their document order: $tops", before < after)
        }
    }

    private fun SemanticsNodeInteraction.screenBounds(): Rect {
        val node = fetchSemanticsNode()
        val root = node.layoutInfo.coordinates.findRootCoordinates()
        return node.boundsInRoot.translate(root.localToScreen(Offset.Zero))
    }

    @Test
    fun capsuleDisplayIsAvailableWhileDisabledAndAppliesWithoutSaving() {
        enabled = false
        show()
        compose.onNodeWithTag("subagent-capsule-display").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.subagents_capsule_display_grouped)).performClick()
        assertEquals(listOf("grouped"), capsuleModeChanges)
        assertEquals("grouped", capsuleMode)
        assertTrue(enableChanges.isEmpty())
        compose.onNodeWithTag("subagent-capsule-display").performClick()
        compose.onNodeWithText(text(R.string.subagents_capsule_display_individual)).performClick()
        assertEquals(listOf("grouped", "individual"), capsuleModeChanges)
        compose.onNodeWithText(text(R.string.provider_save)).assertDoesNotExist()
    }

    @Test
    fun capsuleDisplayCurrentChoiceDoesNotResaveOrChangeModelPreferences() {
        show()
        compose.onNodeWithTag("subagent-capsule-display").performScrollTo().performClick()
        compose.onAllNodesWithText(text(R.string.subagents_capsule_display_individual))[1].performClick()
        assertTrue(capsuleModeChanges.isEmpty())
        assertTrue(recommendations.isEmpty())
        assertTrue(additions.isEmpty())
    }

    private fun show() = compose.setContent {
        Host {
            if (appearanceReference) SettingsAppearancePage(checkNotNull(appearanceViewModel), {})
            else Content()
        }
    }
    private fun text(id: Int) = strings.getValue(id)
    private fun dialogModel(alias: String) = compose.onNode(
        hasText(alias) and hasAnyAncestor(isDialog()),
    )

    @Test
    fun unconfiguredListDefaultsToEmptyWithoutWritingIt() {
        recommended = alpha
        show()

        listOf(alpha, beta, gamma).forEach {
            compose.onNodeWithTag("subagent-model:$it").assertDoesNotExist()
        }
        compose.onNodeWithText(text(R.string.subagents_no_models)).assertExists()
        compose.onNodeWithText(text(R.string.subagents_no_recommended_model)).assertExists()
        compose.onNodeWithTag("subagent-recommended-model").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.subagents_add_models)).assertExists()
        compose.onNodeWithText(text(R.string.provider_save)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(null, configured)
            assertEquals(alpha, recommended)
            assertTrue(additions.isEmpty())
            assertTrue(removals.isEmpty())
            assertTrue(recommendations.isEmpty())
        }
    }

    @Test
    fun firstAddFromDefaultEmptyListAddsOnlySelectedModels() {
        show()

        compose.onNodeWithText(text(R.string.subagents_no_models)).assertExists()
        compose.onNodeWithText(text(R.string.subagents_add_models)).performScrollTo().performClick()
        dialogModel("Alpha").assertExists()
        dialogModel("Beta").performClick()
        dialogModel("Gamma").assertExists()
        compose.runOnIdle {
            assertEquals(null, configured)
            assertTrue(additions.isEmpty())
        }
        compose.onNodeWithText(text(R.string.provider_add)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("subagent-model:$beta").assertExists()
        compose.onNodeWithTag("subagent-model:$alpha").assertDoesNotExist()
        compose.onNodeWithTag("subagent-model:$gamma").assertDoesNotExist()
        compose.onNodeWithTag("subagent-recommended-model").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(setOf(beta), configured)
            assertEquals(listOf(setOf(beta)), additions)
            assertTrue(recommendations.isEmpty())
        }
    }

    @Test
    fun explicitlyEmptyListDoesNotInheritAndDisablesRecommendation() {
        configured = emptySet()
        show()

        compose.onNodeWithText(text(R.string.subagents_no_models)).assertExists()
        compose.onNodeWithTag("subagent-model:$alpha").assertDoesNotExist()
        compose.onNodeWithTag("subagent-recommended-model").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.subagents_add_models)).assertExists()
    }

    @Test
    fun noGlobalModelsOffersNoAddActionOrRecommendation() {
        globalModels = emptySet()
        show()

        compose.onNodeWithText(text(R.string.subagents_no_models)).assertExists()
        compose.onNodeWithTag("subagent-recommended-model").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.subagents_add_models)).assertDoesNotExist()
    }

    @Test
    fun disabledFeatureHidesBothSectionsAndWholeRowEnablesItImmediately() {
        enabled = false
        configured = setOf(alpha)
        show()

        compose.onNodeWithText(text(R.string.subagents_model_list_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.subagents_recommended_model_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.subagents_enable)).performClick()
        compose.onNodeWithTag("subagent-model:$alpha").assertExists()
        compose.runOnIdle { assertEquals(listOf(true), enableChanges) }
    }

    @Test
    fun staleConfiguredAndRecommendedIdsNeverBecomeCandidates() {
        configured = setOf(alpha, "Removed:obsolete")
        recommended = "Removed:obsolete"
        show()

        compose.onNodeWithTag("subagent-model:Removed:obsolete").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.subagents_no_recommended_model)).assertExists()
        compose.onNodeWithTag("subagent-recommended-model").performScrollTo().performClick()
        dialogModel("Alpha").assertExists()
        dialogModel("Beta").assertDoesNotExist()
        dialogModel("obsolete").assertDoesNotExist()
        compose.runOnIdle { assertTrue(recommendations.isEmpty()) }
    }

    @Test
    fun recommendationPickerHasOnlyAllowedModelsAndPersistsExactIdOrNull() {
        configured = setOf(alpha)
        recommended = alpha
        show()

        compose.onNodeWithTag("subagent-recommended-model").performScrollTo().performClick()
        dialogModel("Alpha").assertExists()
        dialogModel("Beta").assertDoesNotExist()
        dialogModel("Gamma").assertDoesNotExist()
        dialogModel(text(R.string.subagents_no_recommended_model)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf<String?>(null), recommendations) }

        compose.onNodeWithTag("subagent-recommended-model").performClick()
        dialogModel("Alpha").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(null, alpha), recommendations) }
    }

    @Test
    fun addDialogMultiSelectsOnlyMissingModelsAndSendsExactIds() {
        configured = setOf(alpha)
        show()

        compose.onNodeWithText(text(R.string.subagents_add_models)).performScrollTo().performClick()
        dialogModel("Alpha").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.provider_add)).assertIsEnabled()
        dialogModel("Beta").performClick()
        dialogModel("Gamma").performClick()
        compose.runOnIdle { assertTrue(additions.isEmpty()) }
        compose.onNodeWithText(text(R.string.provider_add)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(setOf(beta, gamma)), additions) }
        compose.onNodeWithTag("subagent-model:$beta").assertExists()
        compose.onNodeWithTag("subagent-model:$gamma").assertExists()
    }

    @Test
    fun cancellingAddDiscardsSelectionAndDoesNotPersist() {
        configured = emptySet()
        show()

        compose.onNodeWithText(text(R.string.subagents_add_models)).performScrollTo().performClick()
        dialogModel("Alpha").performClick()
        compose.onNodeWithText(text(R.string.provider_cancel)).performClick()
        compose.runOnIdle { assertTrue(additions.isEmpty()) }
        compose.onNodeWithText(text(R.string.subagents_add_models)).performClick()
        compose.onNodeWithText(text(R.string.provider_add)).assertIsEnabled()
    }

    @Test
    fun globallyDisabledSelectionIsExcludedBeforeAddCallback() {
        configured = emptySet()
        show()

        compose.onNodeWithText(text(R.string.subagents_add_models)).performScrollTo().performClick()
        dialogModel("Alpha").performClick()
        dialogModel("Beta").performClick()
        compose.runOnIdle { globalModels = setOf(beta, gamma) }
        dialogModel("Alpha").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.provider_add)).performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(beta)), additions) }
    }

    @Test
    fun removeMenuSendsExactModelIdAndLastRemovalStaysExplicitlyEmpty() {
        configured = setOf(beta)
        recommended = beta
        show()

        compose.onNodeWithTag("subagent-model:$beta").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(removals.isEmpty()) }
        compose.onNodeWithText(text(R.string.delete)).performClick()
        compose.onNodeWithText(text(R.string.subagents_no_models)).assertExists()
        compose.onNodeWithText(text(R.string.subagents_no_recommended_model)).assertExists()
        compose.onNodeWithTag("subagent-model:$alpha").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(beta), removals)
            assertEquals(emptySet<String>(), configured)
        }
    }

    @Test
    fun disablingWhilePickerIsOpenClosesItAndRetainsConfiguration() {
        configured = setOf(alpha)
        recommended = alpha
        show()

        compose.onNodeWithTag("subagent-recommended-model").performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle { enabled = false }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { enabled = true }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(setOf(alpha), configured)
            assertEquals(alpha, recommended)
            assertTrue(recommendations.isEmpty())
        }
    }

    @Test
    fun returningToProductionPageRestoresItsExistingScrollContext() {
        globalModels = (0 until 24).mapTo(linkedSetOf()) { "OpenAI:model-$it" }
        configured = globalModels
        var showing by mutableStateOf(true)
        compose.setContent {
            Host { if (showing) Content() else Text("Other page") }
        }
        compose.runOnIdle {
            scope.launch { retention.scrollState(listOf("subagents"))!!.scrollTo(420) }
        }
        compose.runOnIdle { showing = false }
        compose.runOnIdle { showing = true }
        compose.runOnIdle {
            assertEquals(420, retention.scrollState(listOf("subagents"))!!.value)
            assertTrue(additions.isEmpty())
            assertTrue(removals.isEmpty())
            assertEquals(0, backs)
        }
    }
}
