package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBottomBarControlOrderTest {
    @Test
    fun `normal chat bottom fade reveals the live background instead of painting a static color`() {
        val chatApp = mainSource("com/newoether/agora/ui/chat/ChatApp.kt")
        val source = chatApp + mainSource("com/newoether/agora/ui/chat/bottombar/ChatComposerSurface.kt")
        val masks = mainSource("com/newoether/agora/util/GradientBlur.kt")

        assertTrue(chatApp.contains("ChatComposerSurface("))
        assertTrue(source.contains("val expandedGradientTopPaddingPx = with(density) { 20.dp.toPx() }"))
        assertTrue(source.contains("val gradientWidthPx = with(density) { 40.dp.toPx() }"))
        assertTrue(source.contains("if (!isExpanded) Spacer(modifier = Modifier.height(12.dp))"))
        assertTrue(source.contains("expandedHeightPx = with(density) { 44.dp.toPx() }"))
        assertTrue(source.contains(".fillMaxHeight().widthIn(max = 840.dp).fillMaxWidth()"))
        assertTrue(source.contains(".gradientBlur("))
        assertTrue(source.contains("blurAtTopDp = if (blurEffectsEnabled) 8f else 0f"))
        assertTrue(masks.contains("Modifier.verticalBottomOverlayFade(fadeHeightDp, bottomOverlayHeight, renderEffect)"))
        assertTrue(source.contains("fadeHeightDp = 40f"))
        assertTrue(source.contains("bottomOverlayHeight = bottomBarHeight + with(density) { outerSpacerHeightPx.toDp() } + 12.dp"))
        assertTrue(source.contains("if (isExpanded && totalH > 0f)"))
        assertFalse(source.contains("normalGradientTopPaddingPx"))
        assertTrue(masks.contains("fun Modifier.verticalBottomOverlayFade("))
        assertTrue(masks.contains("fun bottomOverlayFadeStops("))
        assertTrue(masks.contains("CompositingStrategy.Offscreen"))
        assertTrue(masks.contains("blendMode = BlendMode.DstIn"))
    }

    @Test
    fun `OpenAI Search appears directly below Service Tier`() {
        val source = File(
            locateMainSourceRoot(),
            "com/newoether/agora/ui/chat/bottombar/ComposerToolsMenuContent.kt",
        ).readText()
        val serviceTierCondition = "if (openAiServiceTierAvailable && isModelValid)"
        val nativeSearchCondition = "if (openAiWebSearchAvailable && isModelValid)"
        val genericSearchCondition = "if (showWebSearch)"

        assertEquals(1, source.countOccurrences(serviceTierCondition))
        assertEquals(1, source.countOccurrences(nativeSearchCondition))
        assertEquals(1, source.countOccurrences(genericSearchCondition))

        val serviceTierStart = source.indexOf(serviceTierCondition)
        val serviceTierBodyStart = source.indexOf('{', startIndex = serviceTierStart)
        val serviceTierEnd = source.matchingBraceIndex(serviceTierBodyStart)
        val nextControlStart = source.indexOfFirstNonWhitespace(serviceTierEnd + 1)
        val nativeSearchStart = source.indexOf(nativeSearchCondition)
        val genericSearchStart = source.indexOf(genericSearchCondition)

        assertTrue("Service Tier must be present", serviceTierStart >= 0)
        assertTrue("OpenAI Search must immediately follow Service Tier", source.startsWith(nativeSearchCondition, nextControlStart))
        assertTrue("OpenAI Search must remain before generic Web Search", nativeSearchStart < genericSearchStart)
    }

    @Test
    fun `Low Context Mode disables only capability controls`() {
        val source = mainSource("com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt") +
            mainSource("com/newoether/agora/ui/chat/bottombar/ComposerToolsMenuContent.kt")
        val lowContextCondition = "if (showLowContextMode)"
        val lowContextStart = source.indexOf(lowContextCondition)
        val lowContextBodyStart = source.indexOf('{', startIndex = lowContextStart)
        val lowContextEnd = source.matchingBraceIndex(lowContextBodyStart)
        val lowContextItem = source.substring(lowContextStart, lowContextEnd + 1)
        val capabilitySection = source
            .substringAfter("val isGemini")
            .substringBefore("Text(stringResource(R.string.context_compact))")
        val thinkingSection = source
            .substringAfter("Text(stringResource(R.string.thinking))")
            .substringBefore("val isGemini")
        val compactAndAdvancedSection = source
            .substringAfter("Text(stringResource(R.string.context_compact))")

        assertEquals(1, source.countOccurrences(lowContextCondition))
        assertTrue(lowContextItem.contains("R.string.low_context_mode"))
        assertTrue(lowContextItem.contains("checked = lowContextModeEnabled"))
        assertTrue(lowContextItem.contains("onCheckedChange = onLowContextModeToggle"))
        assertTrue(source.contains("val capabilityControlsEnabled = !lowContextModeEnabled"))
        assertEquals(11, capabilitySection.countOccurrences("enabled = capabilityControlsEnabled"))
        assertTrue(capabilitySection.contains("NativeSearchMenuItem("))
        assertTrue(capabilitySection.contains("if (showWebSearch)"))
        assertTrue(capabilitySection.contains("if (showShell)"))
        assertFalse(thinkingSection.contains("capabilityControlsEnabled"))
        assertFalse(compactAndAdvancedSection.contains("capabilityControlsEnabled"))
        assertTrue(compactAndAdvancedSection.contains("enabled = canCompact && !isCompacting"))
        assertTrue(compactAndAdvancedSection.contains("R.string.advanced_settings"))
    }

    @Test
    fun `System Prompt and Local Provider default use their canonical enabled state`() {
        val topBar = mainSource("com/newoether/agora/ui/chat/ChatTopBar.kt")
        val chatApp = mainSource("com/newoether/agora/ui/chat/ChatApp.kt")
        val settings = mainSource(
            "com/newoether/agora/ui/settings/SettingsProviderDetailPage.kt",
        )
        val systemPromptItem = topBar
            .substringAfter("Text(stringResource(R.string.system_prompt))")
            .substringBefore("Text(stringResource(R.string.conversation_fork_menu))")
        val lowContextSetting = settings
            .substringAfter("value = localLowContextModeEnabled")
            .substringBefore("LocalModelIdleRetentionSlider(")

        assertTrue(topBar.contains("systemPromptEnabled: Boolean = true"))
        assertTrue(systemPromptItem.contains("enabled = systemPromptEnabled"))
        assertTrue(chatApp.contains(
            "systemPromptEnabled = !conversationControls.lowContextModeEnabled"
        ))
        assertTrue(settings.contains(
            "viewModel.settings.localLowContextModeEnabled.collectAsState()"
        ))
        assertTrue(lowContextSetting.contains(
            "viewModel.settings::setLocalLowContextModeEnabled"
        ))
        assertTrue(lowContextSetting.contains("R.string.low_context_mode"))
        assertTrue(lowContextSetting.contains("R.string.low_context_mode_default_desc"))
        assertTrue(lowContextSetting.contains("checked = localLowContextModeEnabled"))
    }
    @Test
    fun `System Prompt create action stays left and uses the default template`() {
        val host = mainSource("com/newoether/agora/ui/components/SystemPromptPickerDialog.kt")
        val chat = mainSource("com/newoether/agora/ui/chat/ChatDialogs.kt")
        val tasks = mainSource("com/newoether/agora/ui/tasks/TaskEditorPage.kt")
        val actions = host
            .substringAfter("internal fun SystemPromptPickerDialog(")
            .substringBefore("promptDraft?.let")
            .substringAfter("confirmButton = {")
        val create = actions.indexOf(
            "TextButton(onClick = { promptDraft = DefaultSystemPrompt.create().copy(title = \"\") })",
        )
        val flexible = actions.indexOf("Spacer(modifier = Modifier.weight(1f))")
        val cancel = actions.indexOf("Text(stringResource(R.string.cancel))")
        val fixed = actions.indexOf("Spacer(modifier = Modifier.width(8.dp))")
        val save = actions.indexOf("Text(stringResource(R.string.save))")
        assertTrue(create >= 0 && create < flexible && flexible < cancel)
        assertTrue(cancel < fixed && fixed < save)
        // Chat and Tasks show the same picker, so their layouts cannot drift apart.
        assertTrue(chat.contains("SystemPromptPickerDialog("))
        assertTrue(tasks.contains("SystemPromptPickerDialog("))
        assertTrue(host.contains("isNew = true"))
        assertTrue(host.contains("addSystemPromptAndAwait("))
    }

    @Test
    fun `Top Bar and Bottom Bar share canonical context projection usage`() {
        val chatApp = mainSource("com/newoether/agora/ui/chat/ChatApp.kt")
        val chatViewModel = mainSource("com/newoether/agora/viewmodel/ChatViewModel.kt")
        val conversationUi = mainSource(
            "com/newoether/agora/viewmodel/ConversationUiStateAssembler.kt",
        )

        assertEquals(2, chatApp.countOccurrences("contextUsage.estimatedTokenCount"))
        assertTrue(chatApp.contains("totalTokens = contextUsage.estimatedTokenCount"))
        assertTrue(chatApp.contains(
            "contextEstimatedTokens = contextUsage.estimatedTokenCount"
        ))
        assertFalse(chatApp.contains("viewModel.totalTokens.collectAsState()"))
        assertFalse(chatViewModel.contains("conversationUi.totalTokens"))
        assertFalse(conversationUi.contains("val totalTokens: StateFlow<Int>"))
        assertFalse(conversationUi.contains("sumOf { it.tokenCount }"))
    }

    private fun mainSource(relativePath: String): String =
        File(locateMainSourceRoot(), relativePath).readText()

    private fun String.matchingBraceIndex(openBraceIndex: Int): Int {
        require(openBraceIndex >= 0) { "Opening brace was not found" }
        var depth = 0
        for (index in openBraceIndex until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        error("Closing brace was not found")
    }

    private fun String.indexOfFirstNonWhitespace(startIndex: Int): Int {
        for (index in startIndex until length) {
            if (!this[index].isWhitespace()) return index
        }
        return length
    }

    private fun String.countOccurrences(token: String): Int {
        var count = 0
        var startIndex = 0
        while (true) {
            val matchIndex = indexOf(token, startIndex = startIndex)
            if (matchIndex < 0) return count
            count++
            startIndex = matchIndex + token.length
        }
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
