package com.newoether.agora.ui

import com.newoether.agora.readLocaleStringResourceSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentApprovedFeatureSourceContractTest {
    @Test
    fun `Skill add imports Markdown and starts destinations while its sheet hides`() {
        val skills = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsSkillsPage.kt",
        )
        val prompts = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsPromptsPage.kt",
        )
        val skillAction = skills
            .substringAfter("fun runAddSkillAction(action: () -> Unit)")
            .substringBefore("LaunchedEffect(catalogRevision)")
        val promptAction = prompts
            .substringAfter("val pickTemplate: (SystemPromptEntry) -> Unit")
            .substringBefore("BackHandler(enabled = editingEntry != null)")
        val skillSheet = skills
            .substringAfter("if (showAddSkillSheet)")
            .substringBefore("showDeleteFileConfirm?.let")

        assertTrue(skills.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(
            skills.contains(
                "import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet",
            ),
        )
        assertTrue(skills.contains("ModalBottomSheet("))
        assertTrue(skillSheet.contains("DialogWindowEdgeToEdge()"))
        assertTrue(
            skillSheet.indexOf("DialogWindowEdgeToEdge()") <
                skillSheet.indexOf("text = stringResource(R.string.skills_add)"),
        )
        assertEquals(2, Regex("supportingContent =").findAll(skillSheet).count())
        assertTrue(skillSheet.contains("R.string.skills_add_from_markdown_desc"))
        assertTrue(skillSheet.contains("R.string.skills_add_manually_desc"))
        assertTrue(skillSheet.contains("Icons.Default.Description"))
        val markdownImport = sourceFile(
            "app/src/main/java/com/newoether/agora/data/SkillMarkdownImport.kt",
        )
        assertTrue(skills.contains("readSkillMarkdown(context, uri)"))
        assertTrue(markdownImport.contains("MAX_SKILL_IMPORT_BYTES"))
        assertTrue(markdownImport.contains("CodingErrorAction.REPORT"))
        assertTrue(skills.contains("description = \"\""))
        assertTrue(skills.contains("markdownPicker.launch("))
        assertTrue(skills.contains("showNewFileDialog = true"))
        assertTrue(skillAction.indexOf("action()") < skillAction.indexOf("addSkillSheetState.hide()"))
        assertTrue(
            promptAction.indexOf("editingEntry = entry") <
                promptAction.indexOf("templateSheetState.hide()"),
        )
        assertTrue(
            prompts.contains(
                "DefaultSystemPrompt.create(java.util.Locale.getDefault()).copy(title = \"\")",
            ),
        )

        localeDirectories().forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertTrue(
                "$directory skills_add_from_markdown",
                strings.contains("name=\"skills_add_from_markdown\""),
            )
            assertTrue(
                "$directory skills_add_from_markdown_desc",
                strings.contains("name=\"skills_add_from_markdown_desc\""),
            )
            assertTrue(
                "$directory skills_add_manually",
                strings.contains("name=\"skills_add_manually\""),
            )
            assertTrue(
                "$directory skills_add_manually_desc",
                strings.contains("name=\"skills_add_manually_desc\""),
            )
        }
    }

    @Test
    fun `MCP connection work uses the shared permit limit`() {
        val registry = sourceFile(
            "app/src/main/java/com/newoether/agora/mcp/McpRegistry.kt",
        )
        assertTrue(registry.contains("Semaphore(permits = MAX_CONCURRENT_CONNECTIONS)"))
        assertTrue(registry.contains("connectionPermits.withPermit"))
    }

    @Test
    fun `Automation wake lock is persisted portable resettable and scoped to execution`() {
        val schema = sourceFile(
            "app/src/main/java/com/newoether/agora/data/SettingsPreferenceSchema.kt",
        )
        val manager = sourceFile(
            "app/src/main/java/com/newoether/agora/data/SettingsManager.kt",
        ) + sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsReset.kt",
        )
        val repository = sourceFile(
            "app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt",
        )
        val portable = sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsArchive.kt",
        )
        val legacy = sourceFile(
            "app/src/main/java/com/newoether/agora/data/ExportExtraSettings.kt",
        )
        val engine = sourceFile(
            "app/src/main/java/com/newoether/agora/automation/TaskExecutionEngine.kt",
        )
        val owner = sourceFile(
            "app/src/main/java/com/newoether/agora/automation/AutomationWakeLockOwner.kt",
        )

        assertTrue(schema.contains("AUTOMATION_WAKE_LOCK_ENABLED"))
        assertTrue(manager.contains("val automationWakeLockEnabled: Flow<Boolean>"))
        assertTrue(manager.contains("saveAutomationWakeLockEnabled"))
        assertTrue(manager.contains("prefs.remove(AUTOMATION_WAKE_LOCK_ENABLED)"))
        assertTrue(repository.contains("val automationWakeLockEnabled: StateFlow<Boolean>"))
        assertTrue(repository.contains("fun setAutomationWakeLockEnabled"))
        assertEquals(2, Regex("\"automationWakeLockEnabled\"").findAll(portable).count())
        assertTrue(legacy.contains("automationWakeLockEnabled"))
        val directEntry = engine.substringAfter("suspend fun runOnce(")
            .substringBefore("internal suspend fun runOnceWithAutomationGuardsHeld(")
        val guardedEntry = engine.substringAfter("internal suspend fun runOnceWithAutomationGuardsHeld(")
            .substringBefore("private suspend fun runOnceLocked(")
        assertTrue("Direct execution delegates to the shared protected entry",
            directEntry.contains("runOnceWithAutomationGuardsHeld("))
        val wakeLock = guardedEntry.indexOf("automationWakeLockOwner.whileHeld")
        val generation = guardedEntry.indexOf("runOnceLocked(")
        assertTrue("Shared automation entry holds the wake lock before generation",
            wakeLock >= 0 && generation > wakeLock)
        assertTrue(owner.contains("return try {"))
        assertTrue(owner.contains("finally {"))
        assertTrue(owner.contains("PowerManager.PARTIAL_WAKE_LOCK"))
    }

    @Test
    fun `Automation orders wake lock and flavor-safe battery actions in every locale`() {
        val page = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAutomationPage.kt",
        )
        val manifest = sourceFile("app/src/main/AndroidManifest.xml")
        val fdroidManifest = sourceFile("app/src/fdroid/AndroidManifest.xml")
        val backgroundGroup = page
            .substringAfter("title = stringResource(R.string.automation_background_execution)")
            .substringBefore("if (showDocFab)")

        assertTrue(backgroundGroup.contains("R.string.automation_exact_execution"))
        assertTrue(backgroundGroup.contains("R.string.automation_wake_lock"))
        assertTrue(backgroundGroup.contains("R.string.automation_battery_optimization"))
        assertTrue(
            backgroundGroup.indexOf("R.string.automation_exact_execution") <
                backgroundGroup.indexOf("R.string.automation_wake_lock"),
        )
        assertTrue(
            backgroundGroup.indexOf("R.string.automation_wake_lock") <
                backgroundGroup.indexOf("R.string.automation_battery_optimization"),
        )
        assertTrue(page.contains("isIgnoringBatteryOptimizations(context.packageName)"))
        assertTrue(page.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(page.contains("Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertTrue(page.contains("Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS"))
        assertTrue(page.contains("Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertFalse(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertTrue(fdroidManifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertTrue(manifest.contains("android.permission.WAKE_LOCK"))

        val keys = listOf(
            "automation_background_execution",
            "automation_wake_lock",
            "automation_wake_lock_desc",
            "automation_battery_optimization",
            "automation_battery_optimization_ignored_desc",
            "automation_battery_optimization_active_desc",
        )
        localeDirectories().forEach { directory ->
            val fileName = "automation_strings.xml"
            val strings = sourceFile("app/src/main/res/$directory/$fileName")
            keys.forEach { key ->
                assertTrue("$directory $key", strings.contains("name=\"$key\""))
            }
        }
    }

    private fun localeDirectories(): List<String> = listOf(
        "values",
        "values-ar",
        "values-de",
        "values-es",
        "values-fr",
        "values-ja",
        "values-ko",
        "values-pt-rBR",
        "values-ru",
        "values-vi",
        "values-zh",
        "values-zh-rTW",
    )

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) {
                return if (candidate.name == "strings.xml") {
                    candidate.readLocaleStringResourceSources()
                } else candidate.readText()
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
