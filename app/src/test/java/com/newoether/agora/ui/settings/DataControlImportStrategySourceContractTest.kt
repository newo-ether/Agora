package com.newoether.agora.ui.settings

import com.newoether.agora.readLocaleStringResourceSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataControlImportStrategySourceContractTest {
    @Test
    fun unifiedImportPageUsesSharedStrategyControls() {
        val page = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/datacontrol/SettingsDataControlPage.kt",
        ).readText().normalizeLines() + sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/datacontrol/NativeDataSelectionDialogs.kt",
        ).readText().normalizeLines()

        assertEquals(3, Regex("""\bPillTabSwitcher\(""").findAll(page).count())
        assertFalse(page.contains("StrategyChip("))
        assertFalse(page.contains("FilterChip("))
        assertTrue(page.contains("var claudeImportStrategy by remember"))
        assertTrue(page.contains("var gptImportStrategy by remember"))
        assertTrue(
            Regex(
                """importClaudeChat\(\s*uri,\s*claudeImportStrategy,\s*finalIds,""",
            ).containsMatchIn(page),
        )
        assertTrue(
            Regex(
                """importGptChat\(\s*uri,\s*gptImportStrategy,\s*finalIds,""",
            ).containsMatchIn(page),
        )
    }

    @Test
    fun externalReplaceRequiresTheSharedDestructiveConfirmation() {
        val page = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/datacontrol/SettingsDataControlPage.kt",
        ).readText().normalizeLines()

        assertTrue(page.contains("pendingExternalReplace = true to finalIds"))
        assertTrue(page.contains("pendingExternalReplace = false to finalIds"))
        assertTrue(page.contains("pendingExternalReplace?.let { (isClaude, selectedIds) ->"))
        assertTrue(page.contains("R.string.external_import_replace_confirm_title"))
        assertTrue(page.contains("fontWeight = FontWeight.Bold"))
        assertTrue(page.contains("contentColor = MaterialTheme.colorScheme.error"))
        assertTrue(
            Regex(
                """importClaudeChat\(\s*uri,\s*DataImporter\.ImportStrategy\.REPLACE,\s*selectedIds,""",
            ).containsMatchIn(page),
        )
        assertTrue(
            Regex(
                """importGptChat\(\s*uri,\s*DataImporter\.ImportStrategy\.REPLACE,\s*selectedIds,""",
            ).containsMatchIn(page),
        )
    }

    @Test
    fun replaceConfirmationResourcesHaveSupportedLocaleParity() {
        val keys = listOf(
            "external_import_replace_confirm_title",
            "external_import_replace_confirm_message",
            "external_import_replace_confirm_button",
        )
        val directories = listOf(
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

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml").readLocaleStringResourceSources()
            keys.forEach { key ->
                assertTrue("Missing $key in $directory", strings.contains("name=\"$key\""))
            }
        }

        val defaults = sourceFile("app/src/main/res/values/strings.xml").readLocaleStringResourceSources()
        assertTrue(
            defaults.contains(
                "<string name=\"external_import_replace_confirm_title\">" +
                    "Replace Existing Conversations?</string>",
            ),
        )
    }

    @Test
    fun nativePreviewAndOperationsUseNonDismissibleCircularProgress() {
        val page = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/datacontrol/SettingsDataControlPage.kt",
        ).readText().normalizeLines()
        val dialog = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/datacontrol/NativeDataProgressDialog.kt",
        ).readText().normalizeLines()
        val manager = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/ImportExportManager.kt",
        ).readText().normalizeLines()

        assertTrue(
            manager.contains(
                "fun exportData(uri: Uri, categories: Set<DataExporter.ExportCategory>, " +
                    "includeApiKeys: Boolean) {\n        _exportProgress.value = 0f\n" +
                    "        scope.launch(Dispatchers.IO)",
            ),
        )
        assertTrue(
            manager.contains(
                "fun importData(uri: Uri, decisions: Map<DataExporter.ExportCategory, " +
                    "DataImporter.ImportStrategy>) {\n        _importProgress.value = 0f\n" +
                    "        scope.launch(Dispatchers.IO)",
            ),
        )
        assertTrue(manager.contains("val importPreviewLoading: StateFlow<Boolean>"))
        val exportData = manager.substringAfter("fun exportData(uri: Uri")
            .substringBefore("fun previewImport(uri: Uri)")
        val cancellationCatch = exportData.indexOf("catch (cancelled: kotlinx.coroutines.CancellationException)")
        val failureCatch = exportData.indexOf("catch (e: Exception)")
        assertTrue(cancellationCatch >= 0)
        assertTrue(failureCatch > cancellationCatch)
        val cancellationBody = exportData.substring(cancellationCatch, failureCatch)
        assertTrue(cancellationBody.contains("_exportProgress.value = null"))
        assertTrue(cancellationBody.contains("throw cancelled"))
        assertFalse(cancellationBody.contains("export_failed"))
        val previewImport = manager.substringAfter("fun previewImport(uri: Uri)")
            .substringBefore("fun clearImportState()")
        assertTrue(
            previewImport.startsWith(
                " {\n        _importPreviewLoading.value = true\n" +
                    "        clearImportState()\n" +
                    "        scope.launch(Dispatchers.IO)",
            ),
        )
        assertTrue(
            previewImport.contains(
                "finally {\n                _importPreviewLoading.value = false\n            }",
            ),
        )
        assertFalse(previewImport.contains("_importProgress.value"))

        assertTrue(
            page.contains(
                "val importPreviewLoading by viewModel.importExport.importPreviewLoading." +
                    "collectAsState()",
            ),
        )
        assertTrue(
            page.contains(
                "LaunchedEffect(importPreview, importPreviewLoading) {\n" +
                    "        if (importPreview != null && !importPreviewLoading)",
            ),
        )
        assertTrue(
            page.contains(
                "isNativeProgressVisible = importPreviewLoading || isExporting || isImporting",
            ),
        )
        assertTrue(page.contains("importPreviewLoading -> R.string.loading_label"))
        assertTrue(page.contains("isExporting -> R.string.exporting_label"))
        assertTrue(page.contains("else -> R.string.importing_label"))
        assertTrue(
            page.contains(
                "NativeDataProgressDialog(title = stringResource(nativeProgressTitle))",
            ),
        )

        assertTrue(dialog.contains("dismissOnBackPress = false"))
        assertTrue(dialog.contains("dismissOnClickOutside = false"))
        assertTrue(dialog.contains("MotionAwareCircularProgressIndicator()"))
        assertFalse(dialog.contains("LinearProgressIndicator"))

        val thirdPartyProgress = page.substringAfter("if (isThirdPartyImporting)")
            .substringBefore("// Export dialog")
        assertTrue(thirdPartyProgress.contains("LinearProgressIndicator"))
        assertTrue(thirdPartyProgress.contains("${'$'}{(progress * 100).toInt()}%"))
        assertFalse(thirdPartyProgress.contains("exportProgress ?: importProgress"))
    }

    @Test
    fun nativeLoadingTitleHasSupportedLocaleParity() {
        val directories = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh",
            "values-zh-rTW",
        )

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml").readLocaleStringResourceSources()
            assertEquals(
                "$directory must contain exactly one loading_label",
                1,
                Regex("""name="loading_label"""").findAll(strings).count(),
            )
        }

        val defaults = sourceFile("app/src/main/res/values/strings.xml").readLocaleStringResourceSources()
        assertTrue(defaults.contains("<string name=\"loading_label\">Loading…</string>"))
    }

    @Test
    fun nativePreviewSeparatesCategoryBlocksWithoutChangingInternalGap() {
        val page = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/datacontrol/NativeDataSelectionDialogs.kt",
        ).readText().normalizeLines()

        assertTrue(page.contains("Column(verticalArrangement = Arrangement.spacedBy(16.dp))"))
        val strategyRow = page.substringAfter("private fun StrategyRow(")
        assertTrue(strategyRow.contains("Spacer(Modifier.height(4.dp))"))
    }

    @Test
    fun unavailableResourcesUseDisabledLocalizedRenderingAndSuccessReporting() {
        val manager = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/ImportExportManager.kt",
        ).readText().normalizeLines()
        val autoBackup = sourceFile(
            "app/src/main/java/com/newoether/agora/data/AutoBackupManager.kt",
        ).readText().normalizeLines()
        val thumbnail = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/AttachmentThumbnail.kt",
        ).readText().normalizeLines()
        val preview = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/AttachmentPreviewRow.kt",
        ).readText().normalizeLines()
        val bubble = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/UserMessageBubble.kt",
        ).readText().normalizeLines()

        assertTrue(manager.contains("val result = exporter.export"))
        assertTrue(manager.contains("result.missingResourceCount > 0"))
        assertTrue(manager.contains("R.string.export_success_missing_resources"))
        assertTrue(autoBackup.contains("backup.second.missingResourceCount"))
        assertTrue(autoBackup.contains("sendMissingResourceNotification(missingResourceCount)"))
        assertTrue(autoBackup.contains("file to exportResult"))
        val warningBranch = autoBackup
            .substringAfter("if (missingResourceCount > 0)")
            .substringBefore("return BackupResult.SUCCESS")
        assertFalse(warningBranch.contains("sendFailureNotification"))
        assertTrue(thumbnail.contains("if (unavailable)"))
        assertTrue(thumbnail.contains("R.string.attachment_unavailable"))
        assertTrue(preview.contains("unavailable || !isReady -> Modifier"))
        assertTrue(preview.contains("if (isPdf && attachment.imageArtifacts.isNotEmpty()) firstPdfPage < 0"))
        assertTrue(bubble.contains("metadataItems + legacyItems"))
        assertTrue(bubble.contains("unavailable = storedMediaUnavailable("))
        assertTrue(bubble.contains("return occurrences.all { it.unavailable || it.path.isBlank() }"))
    }

    @Test
    fun unavailableResourceStringsHaveSupportedLocaleParity() {
        val keys = listOf(
            "attachment_unavailable",
            "export_success_missing_resources",
            "auto_backup_missing_resources",
        )
        val directories = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh",
            "values-zh-rTW",
        )

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml").readLocaleStringResourceSources()
            keys.forEach { key ->
                assertEquals(
                    "$directory must contain exactly one $key",
                    1,
                    Regex("""name="$key"""").findAll(strings).count(),
                )
            }
        }
    }

    @Test
    fun legacyClaudePageAndStrategyTypeAreRemoved() {
        val mainRoot = sourceFile("app/src/main/java")
        val legacyPage = File(
            mainRoot,
            "com/newoether/agora/ui/settings/SettingsClaudeImportPage.kt",
        )
        assertFalse(legacyPage.exists())

        val kotlinSources = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
        kotlinSources.forEach { source ->
            val text = source.readText()
            assertFalse(source.path, text.contains("SettingsClaudeImportPage"))
            assertFalse(
                source.path,
                text.contains("com.newoether.agora.ui.settings.ImportStrategy"),
            )
        }
    }

    private fun sourceFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.exists()) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("Unable to locate Agora source: $relativePath")
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")
}
