package com.newoether.agora.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SettingsResourceContractTest {
    @Test
    fun toolCallDisplayOffersOnlyTimelineAndGroupedInEveryLocale() {
        val resources = locateResourceDirectory()
        val localePattern = Regex("""values(?:-[a-z]{2,3}(?:-r[A-Z]{2})?)?""")
        val locales = resources.listFiles().orEmpty().filter {
            it.isDirectory && localePattern.matches(it.name)
        }
        assertEquals(12, locales.size)
        locales.forEach { locale ->
            val values = readStringValues(locale)
            assertFalse(locale.name, values.containsKey("tool_call_display_mode_compact"))
            assertFalse(locale.name, values.containsKey("tool_call_display_mode_compact_desc"))
            for (mode in listOf("timeline", "grouped_timeline")) {
                assertTrue(locale.name, !values["tool_call_display_mode_$mode"].isNullOrBlank())
                assertTrue(locale.name, !values["tool_call_display_mode_${mode}_desc"].isNullOrBlank())
            }
        }
        val appearance = readSettingsSource("SettingsAppearancePage.kt")
        val options = appearance.substringAfter("val selectedLabel = when (normalizedToolCallDisplayMode)")
            .substringAfter("val options = listOf(").substringBefore("\n                            )")
        assertEquals(1, Regex("ToolCallDisplayModes\\.TIMELINE").findAll(options).count())
        assertEquals(1, Regex("ToolCallDisplayModes\\.GROUPED_TIMELINE").findAll(options).count())
        assertEquals(2, Regex("ToolCallDisplayModes\\.").findAll(options).count())
    }

    @Test
    fun retiredPromptGuideLabelsAreAbsentAndUnknownRowsKeepTheExistingFallback() {
        val resources = locateResourceDirectory()
        val localePattern = Regex("""values(?:-[a-z]{2,3}(?:-r[A-Z]{2})?)?""")
        val locales = resources.listFiles().orEmpty().filter {
            it.isDirectory && localePattern.matches(it.name)
        }
        assertEquals(12, locales.size)
        locales.forEach { locale ->
            val values = readStringValues(locale)
            assertFalse(locale.name, values.keys.any { it.startsWith("template_variable_tool_guide_") })
            assertTrue(locale.name, !values["unknown"].isNullOrBlank())
            assertTrue(locale.name, !values["prompts_template_default_desc"].isNullOrBlank())
        }
        val editor = readSettingsSource("SystemPromptEditorPage.kt")
        assertFalse(editor.contains("TOOL_GUIDE_"))
        assertTrue(editor.contains("else -> stringResource(R.string.unknown)"))
        assertTrue(editor.contains("onDelete = if (isPrompt) null"))
        assertTrue(editor.contains("PredefinedVariables.EXAMPLE_VALUES"))
    }

    @Test
    fun anthropicCacheControlsFollowProviderScopeAndAppearanceInteractions() {
        val detail = readSettingsSource("SettingsProviderDetailPage.kt")
        val gate = "if (supportsPromptCacheSetting(currentName, customProviders))"
        val apiKeys = readSettingsSource("ProviderApiKeysSettings.kt")
        val keysPosition = detail.indexOf("ProviderApiKeysSettings(")
        assertTrue(keysPosition >= 0 && detail.indexOf(gate) > keysPosition)
        assertTrue(apiKeys.contains("R.string.provider_api_keys"))
        val controls = detail.substringAfter(gate).substringBefore("if (showDocFab)")
        assertTrue(controls.contains("R.string.advanced_title"))
        assertTrue(controls.contains("customConfig?.providerId ?: currentName"))
        assertTrue(controls.contains("Switch(checked = cacheEnabled, onCheckedChange = null)"))
        assertTrue(controls.contains("role = Role.Switch"))
        assertTrue(controls.contains("setAnthropicCacheEnabled(providerId, it)"))
        // OpenAI endpoints share the switch; only Anthropic exposes the TTL picker.
        assertTrue(controls.contains("val cacheTtlSupported = isAnthropicProtocolProvider(currentName, customProviders)"))
        assertTrue(
            controls.indexOf("if (cacheEnabled && cacheTtlSupported)") in
                0 until controls.indexOf("R.string.provider_cache_duration"),
        )
        assertTrue(controls.contains("listOf(\"5m\", \"1h\")"))
        assertTrue(controls.contains("setAnthropicCacheTtl(providerId, ttl)"))
        assertTrue(controls.contains("Modifier.clickable { expanded = true }"))
        assertTrue(controls.contains("if (cacheTtl == ttl)"))
        assertTrue(controls.contains("Icons.Default.Check"))
        assertTrue(controls.contains("MaterialTheme.colorScheme.surfaceContainer"))
        assertTrue(controls.contains("tonalElevation = 16.dp"))
        assertTrue(controls.contains("AgoraDropdownMenu("))
        assertFalse(controls.contains("R.string.save"))
        val resources = locateResourceDirectory()
        val defaults = readStringValues(File(resources, "values"))
        assertEquals("Cache", defaults["provider_cache"])
        assertEquals("Cache Duration", defaults["provider_cache_duration"])
        val keys = setOf("provider_cache", "provider_cache_desc", "provider_cache_duration", "provider_cache_duration_desc")
        resources.listFiles().orEmpty().filter { File(it, "strings.xml").isFile }.forEach { locale ->
            val values = readStringValues(locale)
            assertTrue(locale.name, keys.all { !values[it].isNullOrBlank() })
            if (locale.name != "values") {
                assertTrue(locale.name, values["provider_cache_desc"] != defaults["provider_cache_desc"])
                assertTrue(locale.name, values["provider_cache_duration_desc"] != defaults["provider_cache_duration_desc"])
            }
        }
    }
    @Test
    fun localizedStringKeysMatchDefaultResources() {
        val resourceDirectory = locateResourceDirectory()
        val defaultKeys = readStringKeys(File(resourceDirectory, "values"))
        val localeDirectoryPattern = Regex("""values-[a-z]{2,3}(?:-r[A-Z]{2})?""")
        val localeDirectories = resourceDirectory.listFiles()
            .orEmpty()
            .filter { it.isDirectory && localeDirectoryPattern.matches(it.name) }

        assertTrue("No localized resource directories found", localeDirectories.isNotEmpty())
        localeDirectories.forEach { localeDirectory ->
            val missingKeys = defaultKeys - readStringKeys(localeDirectory)
            assertTrue(
                "${localeDirectory.name} is missing: ${missingKeys.sorted().joinToString()}",
                missingKeys.isEmpty(),
            )
        }
    }

    @Test
    fun sshKeyAndRemoteEmptyStateCopyIsLocalizedWithExpectedEnglishCasing() {
        val resources = locateResourceDirectory()
        val expected = linkedMapOf(
            "shell_authentication" to "Authentication",
            "shell_ssh_host_key" to "Host Key",
            "shell_ssh_auth_method" to "Authentication method",
            "shell_ssh_auth_private_key" to "Private Key",
            "shell_ssh_import_private_key" to "Import Private Key",
            "shell_ssh_private_key_desc" to "Import an SSH private key up to 256 KiB.",
            "shell_ssh_private_key_passphrase" to "Key passphrase",
            "shell_ssh_private_key_invalid" to "Invalid or unsupported SSH private key",
            "shell_ssh_private_key_too_large" to "Private key exceeds 256 KiB",
            "shell_ssh_private_key_read_failed" to "Could not read private key",
            "shell_ssh_private_key_passphrase_invalid" to "Incorrect key passphrase",
            "remote_new_chat_hint" to "Start a Conversation",
            "remote_empty_connecting_hint" to "Connecting to Codex",
            "remote_empty_offline_hint" to "Codex Is Offline",
            "remote_empty_retry_hint" to "Unable to Load Conversation",
            "remote_empty_conversation_hint" to "No Messages Yet",
        )
        val defaults = readStringValues(File(resources, "values"))
        expected.forEach { (key, value) -> assertEquals(key, value, defaults[key]) }
        val localePattern = Regex("""values-[a-z]{2,3}(?:-r[A-Z]{2})?""")
        resources.listFiles().orEmpty()
            .filter { it.isDirectory && localePattern.matches(it.name) }
            .forEach { locale ->
                val values = readStringValues(locale)
                expected.forEach { (key, english) ->
                    assertTrue("${locale.name}: $key", !values[key].isNullOrBlank())
                    assertFalse("${locale.name}: $key must be translated", values[key] == english)
                }
            }
    }

    @Test
    fun englishSettingsDestinationTitlesFollowTheGlobalTitleCaseContract() {
        val values = readStringValues(File(locateResourceDirectory(), "values"))
        val expected = linkedMapOf(
            "settings_title" to "Settings",
            "settings_provider" to "Providers",
            "models_title" to "Models",
            "generation_title" to "Generation",
            "context_title" to "Context",
            "settings_title_gen" to "Title Generation",
            "prompts_title" to "System Prompts",
            "prompts_edit_title" to "Edit System Prompt",
            "prompts_add_title" to "Add System Prompt",
            "settings_transcription" to "Image Transcription",
            "settings_image_gen" to "Image Generation",
            "search_title" to "Conversation Search",
            "web_search_title" to "Web Search",
            "mcp_title" to "MCP",
            "mcp_add_server" to "Add MCP Server",
            "mcp_edit_server" to "Edit MCP Server",
            "shell_title" to "Shell",
            "sandbox_mgmt_title" to "Sandbox Management",
            "settings_automation" to "Automation",
            "tasks" to "Tasks",
            "task_new" to "New Task",
            "task_edit" to "Edit Task",
            "memory_title" to "Memory",
            "skills_title" to "Skills",
            "appearance_title" to "Appearance",
            "language_title" to "Language",
            "settings_data_control" to "Data Control",
            "settings_proxy" to "Proxy",
            "developer_options_title" to "Developer Options",
            "about_title" to "About",
            "claude_import_title" to "Import from Claude",
            "local_title" to "Local",
            "openai_service_tier_title" to "Service Tier",
        )
        expected.forEach { (key, title) ->
            assertEquals("English title resource $key", title, values[key])
        }
    }

    @Test
    fun englishSettingsSurfaceTitlesFollowTheGlobalTitleCaseContract() {
        val values = readStringValues(File(locateResourceDirectory(), "values"))
        val expected = linkedMapOf(
            "mcp_delete_header" to "Delete Header",
            "secret_show" to "Show Value",
            "secret_hide" to "Hide Value",
            "task_model_default" to "App Default",
            "task_run_now" to "Run Now",
            "task_delete" to "Delete Task",
            "task_enabled" to "Run on Schedule",
            "task_schedule_not_set" to "Not Set",
            "task_new_task" to "New Task",
            "task_schedule_manual" to "Manual Only",
            "task_select_month_day" to "Select Month and Day",
            "task_switch_to_date_input" to "Switch to Date Input",
            "task_switch_to_calendar" to "Switch to Calendar",
            "task_days_of_week" to "Days of Week",
            "task_day_of_month" to "Day of Month",
            "image_gen_show_all" to "Show All Models",
            "prompts_template_title" to "Choose a Template",
            "prompts_empty_title" to "No System Prompts",
            "template_move_up" to "Move Up",
            "template_move_down" to "Move Down",
            "models_clear_search" to "Clear Model Search",
            "local_model_idle_retention" to "Model Idle Retention",
            "shell_ssh_verify_host_key" to "Verify & Pin Host Key",
            "context_compact" to "Context Compact",
            "context_compact_auto" to "Automatic Compact",
            "context_compact_model" to "Compact Model",
            "context_compact_prompt" to "Compact Prompt",
            "context_compact_select_model" to "Select Compact Model",
            "context_compact_retain" to "Recent Messages to Keep",
            "context_compact_threshold" to "Automatic Compact Threshold",
            "settings_developer" to "Developer Options",
            "developer_options_capture" to "Diagnostic Capture",
            "developer_options_disable_title" to "Turn Off Developer Options?",
            "developer_options_clear_diagnostics" to "Clear Diagnostic Session?",
            "developer_options_clear_diagnostics_action" to "Clear Diagnostic Session",
            "developer_options_capture_scroll_to_top" to "Scroll to Top",
            "developer_options_capture_scroll_to_bottom" to "Scroll to Bottom",
            "developer_options_export_share_title" to "Share Agora Diagnostic Bundle",
            "skills_empty" to "No Skills Yet",
        )
        expected.forEach { (key, title) ->
            assertEquals("English settings surface resource $key", title, values[key])
        }
    }

    @Test
    fun englishDiagnosticCaptureCopyUsesFinalPrivacyAndCapacityContract() {
        val values = readStringValues(File(locateResourceDirectory(), "values"))

        assertEquals(
            "Capture incomplete · 64 MiB limit reached",
            values["developer_options_capture_capacity_incomplete"],
        )
        assertEquals(
            "Export Redacted JSON",
            values["developer_options_capture_export_redacted_json"],
        )
        assertEquals(
            "Export Summary Text",
            values["developer_options_capture_export_summary_text"],
        )
        assertFalse(values.containsKey("developer_options_capture_export_raw_json"))
        assertFalse(values.containsKey("developer_options_capture_jump_latest"))
    }

    @Test
    fun englishAppearanceTitlesFollowTheGlobalTitleCaseContract() {
        val values = readStringValues(File(locateResourceDirectory(), "values"))
        val expected = linkedMapOf(
            "appearance_theme_color" to "Theme & Color",
            "appearance_motion_feedback" to "Motion & Feedback",
            "appearance_chat_display" to "Chat Display",
            "theme_mode" to "Theme Mode",
            "theme_mode_light" to "Light",
            "theme_mode_dark" to "Dark",
            "theme_mode_follow_device" to "Follow Device",
            "dynamic_color" to "Dynamic Color",
            "color_scheme" to "Color Scheme",
            "scheme_style" to "Scheme Style",
            "blur_effects" to "Blur Effects",
            "reduce_motion" to "Reduce Motion",
            "haptic_feedback" to "Haptic Feedback",
            "stick_to_bottom" to "Stick to Bottom",
            "parse_inline_dollar_math" to "Parse $…$ as Inline Math",
            "tool_call_display_mode" to "Thinking & Tool Blocks",
            "tool_call_display_mode_timeline" to "Timeline",
            "tool_call_display_mode_grouped_timeline" to "Grouped",
            "thinking_segment_display_mode" to "Thinking Segments",
            "thinking_segment_display_card" to "Card",
            "thinking_segment_display_bottom_sheet" to "Bottom Sheet",
            "auto_expand_active_group" to "Auto-Expand Active Group",
            "font_title" to "Font",
            "font_app_default" to "App Default",
            "font_system_default" to "System Default",
            "font_custom" to "Custom Font",
        )
        expected.forEach { (key, title) ->
            assertEquals("English Appearance title resource $key", title, values[key])
        }
    }

    @Test
    fun localModelIdleRetentionCopyIsLocalizedWithMatchingPlaceholder() {
        val resourceDirectory = locateResourceDirectory()
        val defaultValues = readStringValues(File(resourceDirectory, "values"))
        val keys = setOf(
            "local_model_idle_retention",
            "local_model_idle_retention_desc",
            "local_model_idle_retention_immediate",
            "local_model_idle_retention_minutes",
        )
        val localeDirectoryPattern = Regex("""values-[a-z]{2,3}(?:-r[A-Z]{2})?""")

        resourceDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && localeDirectoryPattern.matches(it.name) }
            .forEach { localeDirectory ->
                val localized = readStringValues(localeDirectory)
                assertTrue(keys.all(localized::containsKey))
                assertTrue(
                    "${localeDirectory.name} must localize retention copy",
                    keys.minus("local_model_idle_retention_minutes").all {
                        localized.getValue(it) != defaultValues.getValue(it)
                    },
                )
                val placeholder = Regex("""%\d+\$[a-z]""")
                assertEquals(
                    placeholder.findAll(defaultValues.getValue("local_model_idle_retention_minutes"))
                        .map { it.value }.toSet(),
                    placeholder.findAll(localized.getValue("local_model_idle_retention_minutes"))
                        .map { it.value }.toSet(),
                )
            }
    }

    @Test
    fun providerDocumentationEntryBelongsToTheDetailEditor() {
        val providerList = readSettingsSource("SettingsProviderPage.kt")
        val providerDetail = readSettingsSource("SettingsProviderDetailPage.kt")

        assertFalse(providerList.contains("DocumentationFab(\"provider.md\")"))
        assertFalse(providerList.contains("showDocumentationFab.collectAsState()"))
        assertTrue(providerDetail.contains(
            "val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()",
        ))
        assertTrue(providerDetail.contains(
            "floatingActionButton = { if (showDocFab) DocumentationFab(\"provider.md\") }",
        ))
        assertTrue(providerDetail.contains(
            "if (showDocFab) Spacer(modifier = Modifier.height(80.dp))",
        ))
    }

    @Test
    fun complexVectorTopologyIsPreserved() {
        val resourceDirectory = locateResourceDirectory()
        val drawableDirectory = File(resourceDirectory, "drawable")
        val deepSeekPaths = readVectorPaths(File(drawableDirectory, "provider_deepseek.xml"))
        val mcpPaths = readVectorPaths(File(drawableDirectory, "ic_mcp.xml"))

        assertEquals("DeepSeek vector path count changed unexpectedly", 1, deepSeekPaths.size)
        assertTrue(
            "DeepSeek paths must preserve the SVG even-odd fill rule",
            deepSeekPaths.all { it.fillType == "evenOdd" },
        )
        val deepSeekSourcePath = readSvgPathData(
            File(checkNotNull(resourceDirectory.parentFile), "assets/deepseek.svg"),
        )
        assertEquals(
            "DeepSeek VectorDrawable must use the canonical source SVG geometry",
            deepSeekSourcePath,
            deepSeekPaths.single().pathData,
        )
        assertArcFlagsAreSeparated(deepSeekPaths.single().pathData)
        val deepSeekInset = readSingleGroupTransform(
            File(drawableDirectory, "provider_deepseek.xml"),
        )
        assertEquals(12f, deepSeekInset.pivotX, 0f)
        assertEquals(12f, deepSeekInset.pivotY, 0f)
        assertEquals(0.96f, deepSeekInset.scaleX, 0f)
        assertEquals(0.96f, deepSeekInset.scaleY, 0f)

        assertEquals("MCP vector path count changed unexpectedly", 2, mcpPaths.size)
        assertTrue(
            "MCP paths must preserve the SVG even-odd fill rule",
            mcpPaths.all { it.fillType == "evenOdd" },
        )
        mcpPaths.forEach { path ->
            assertArcFlagsAreSeparated(path.pathData)
        }
    }

    private fun assertArcFlagsAreSeparated(pathData: String) {
        val number = """[+-]?(?:\d+(?:\.\d+)?|\.\d+)"""
        val arcCommand = Regex(
            """(?i)(?<![A-Za-z])a\s+$number\s+$number\s+$number\s+[01]\s+[01]\s+$number\s+$number""",
        )
        val arcMarker = Regex("""(?i)(?<![A-Za-z])a(?=\s)""")
        val commandCount = arcMarker.findAll(pathData).count()
        val normalizedCount = arcCommand.findAll(pathData).count()

        assertTrue("Expected at least one arc command in: $pathData", commandCount > 0)
        assertEquals(
            "Every arc command must use separate large-arc and sweep flag tokens",
            commandCount,
            normalizedCount,
        )
        assertFalse("Compact 00- arc flags are forbidden", pathData.contains("00-"))
        assertFalse("Compact 01- arc flags are forbidden", pathData.contains("01-"))
    }

    private fun readStringKeys(directory: File): Set<String> =
        directory.listFiles { file -> file.isFile && file.extension == "xml" }
            .orEmpty()
            .flatMap { file ->
                parse(file)
                    .getElementsByTagName("string")
                    .let { nodes ->
                        (0 until nodes.length).mapNotNull { index ->
                            (nodes.item(index) as? Element)
                                ?.getAttribute("name")
                                ?.takeIf(String::isNotBlank)
                        }
                    }
            }
            .toSet()

    private fun readStringValues(directory: File): Map<String, String> =
        directory.listFiles { file -> file.isFile && file.extension == "xml" }
            .orEmpty()
            .flatMap { file ->
                parse(file).getElementsByTagName("string").let { nodes ->
                    (0 until nodes.length).mapNotNull { index ->
                        (nodes.item(index) as? Element)?.let { element ->
                            element.getAttribute("name")
                                .takeIf(String::isNotBlank)
                                ?.let { it to element.textContent.orEmpty() }
                        }
                    }
                }
            }
            .toMap()

    private fun readSvgPathData(file: File): String {
        assertTrue("Missing SVG source: ${file.path}", file.isFile)
        val nodes = parse(file).getElementsByTagName("path")
        assertEquals("Expected one path in ${file.name}", 1, nodes.length)
        return (nodes.item(0) as Element).getAttribute("d")
    }

    private fun readVectorPaths(file: File): List<VectorPath> {
        assertTrue("Missing vector resource: ${file.path}", file.isFile)
        val nodes = parse(file).getElementsByTagName("path")
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as Element
            VectorPath(
                fillType = element.getAttributeNS(ANDROID_NAMESPACE, "fillType"),
                pathData = element.getAttributeNS(ANDROID_NAMESPACE, "pathData"),
            )
        }
    }

    private fun readSingleGroupTransform(file: File): GroupTransform {
        val nodes = parse(file).getElementsByTagName("group")
        assertEquals("Expected one safety-inset group in ${file.name}", 1, nodes.length)
        val element = nodes.item(0) as Element
        return GroupTransform(
            pivotX = element.androidFloat("pivotX"),
            pivotY = element.androidFloat("pivotY"),
            scaleX = element.androidFloat("scaleX"),
            scaleY = element.androidFloat("scaleY"),
        )
    }

    private fun Element.androidFloat(name: String): Float =
        getAttributeNS(ANDROID_NAMESPACE, name).toFloat()

    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

    private fun readSettingsSource(fileName: String): String {
        val file = File(
            locateResourceDirectory().parentFile,
            "java/com/newoether/agora/ui/settings/$fileName",
        )
        assertTrue("Missing Settings source: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun locateResourceDirectory(): File {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = checkNotNull(cursor)
            val candidates = listOf(
                File(directory, "app/src/main/res"),
                File(directory, "src/main/res"),
            )
            candidates.firstOrNull(File::isDirectory)?.let { return it }
            cursor = directory.parentFile
        }
        error("Unable to locate app/src/main/res from ${System.getProperty("user.dir")}")
    }

    private data class VectorPath(
        val fillType: String,
        val pathData: String,
    )

    private data class GroupTransform(
        val pivotX: Float,
        val pivotY: Float,
        val scaleX: Float,
        val scaleY: Float,
    )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
