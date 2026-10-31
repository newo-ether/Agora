package com.newoether.agora.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddCustomProviderPageSourceContractTest {
    @Test
    fun addProviderNavigatesThroughTheSharedSettingsTransition() {
        val page = readSettingsSource("SettingsProviderPage.kt")

        assertTrue(page.contains("targetState = selectedProvider to showAddCustomPage"))
        assertTrue(page.contains("forward = selectedProvider != null || showAddCustomPage"))
        assertTrue(page.contains("if (addCustomProvider) {\n                SettingsSecondaryPane {\n                    AddCustomProviderPage("))
        assertTrue(page.contains("onClick = { showAddCustomPage = true }"))
        assertTrue(page.contains("onBack = { showAddCustomPage = false }"))
        assertTrue(page.contains("SettingsProviderDetailPage("))
        assertTrue(page.contains("providerName = provider"))
        assertFalse(page.contains("AlertDialog("))
        assertFalse(page.contains("CustomProviderDialogContent"))
        assertFalse(page.contains("addProvider("))
    }

    @Test
    fun protocolSelectorRetainsVisibleLabelAndAccessibilityDescription() {
        val editor = readSettingsSource("AddCustomProviderPage.kt")

        assertTrue(editor.contains("mutableStateOf(CustomEndpointProtocol.OPENAI)"))
        assertTrue(editor.contains("val protocolLabel = stringResource(R.string.custom_provider_protocol_label)"))
        assertTrue(editor.contains("Text(\n                                protocolLabel,"))
        assertTrue(editor.contains("CustomEndpointProtocolSelector("))
        assertTrue(editor.contains("selected = protocol"))
        assertTrue(editor.contains("onSelected = { protocol = it }"))
        assertTrue(editor.contains("Modifier.semantics { contentDescription = protocolLabel }"))
    }

    @Test
    fun onlySaveCreatesTheProviderAfterTrimmedLiveValidation() {
        val editor = readSettingsSource("AddCustomProviderPage.kt")
        val save = editor.substringAfter("fun save() {").substringBefore("\n    CollapsingSettingsScaffold(")
        val beforeSave = editor.substringBefore("fun save() {")
        val content = editor.substringAfter("\n    CollapsingSettingsScaffold(")

        assertTrue(editor.contains("IconButton(onClick = ::save)"))
        assertTrue(editor.contains("Icons.Default.Save"))
        assertTrue(editor.contains("contentDescription = stringResource(R.string.save)"))
        assertTrue(save.contains("val trimmedName = name.trim()"))
        assertTrue(save.contains("val trimmedUrl = baseUrl.trim()"))
        assertTrue(save.contains("nameError = CustomProviderNamePolicy.hasConflict("))
        assertTrue(save.contains("existingNames = viewModel.settings.customProviders.value.map { it.name }"))
        assertTrue(save.contains("urlError = trimmedUrl.isBlank()"))
        assertTrue(save.contains("if (nameError || urlError) return"))
        assertTrue(save.contains("viewModel.customModelConfiguration.addProvider(trimmedName, trimmedUrl, protocol)\n        onBack()"))
        assertEquals(1, Regex("\\baddProvider\\(").findAll(editor).count())
        assertFalse(beforeSave.contains("addProvider("))
        assertFalse(content.contains("addProvider("))
        assertFalse(editor.contains("DisposableEffect"))
        assertFalse(editor.contains("LaunchedEffect"))
        assertFalse(editor.contains("AlertDialog("))
    }

    @Test
    fun bothBackActionsUseTheSharedUnsavedDraftGuard() {
        val editor = readSettingsSource("AddCustomProviderPage.kt")
        val page = readSettingsSource("SettingsProviderPage.kt")

        assertTrue(page.contains("BackHandler(enabled = !showAddCustomPage)"))
        assertTrue(editor.contains("rememberUnsavedChangesBack("))
        assertTrue(editor.contains("hasUnsavedChanges = name.isNotEmpty() || baseUrl.isNotEmpty() ||\n            protocol != CustomEndpointProtocol.OPENAI"))
        assertTrue(editor.contains("BackHandler { guardedBack() }"))
        assertTrue(editor.contains("onBack = guardedBack"))
        assertFalse(editor.contains("onDispose"))
        assertFalse(editor.contains("onDismissRequest"))
    }

    private fun readSettingsSource(fileName: String): String {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = checkNotNull(cursor)
            val candidates = listOf(
                File(directory, "app/src/main/java/com/newoether/agora/ui/settings/$fileName"),
                File(directory, "src/main/java/com/newoether/agora/ui/settings/$fileName"),
            )
            candidates.firstOrNull(File::isFile)?.let { return it.readText().replace("\r\n", "\n") }
            cursor = directory.parentFile
        }
        error("Unable to locate Settings source: $fileName")
    }
}
