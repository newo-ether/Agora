package com.newoether.agora.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingModelEditorSourceContractTest {
    @Test
    fun remoteProviderUsesWholeRowDropdownWithSelectedCheckmark() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")
        val provider = editor.section(
            "var providerExpanded by remember",
            "if (!isNew || preset.editableUrl)",
        )

        assertTrue(provider.contains("SettingsItem("))
        assertTrue(provider.contains("headlineContent = { Text(stringResource(R.string.embedding_provider_label)) }"))
        assertTrue(provider.contains("supportingContent = { Text(stringResource(R.string.embedding_type_remote_desc)) }"))
        assertTrue(provider.contains("Icons.Default.Cloud"))
        assertTrue(provider.contains("trailingContent ="))
        assertTrue(provider.contains("MaterialTheme.typography.labelLarge"))
        assertTrue(provider.contains("color = MaterialTheme.colorScheme.primary"))
        assertTrue(provider.contains("AgoraDropdownMenu("))
        assertTrue(provider.contains("containerColor = MaterialTheme.colorScheme.surfaceContainer"))
        assertTrue(provider.contains("tonalElevation = 16.dp"))
        assertTrue(provider.contains("AgoraDropdownMenuItem("))
        assertTrue(provider.contains("if (index == draft.providerIndex)"))
        assertTrue(provider.contains("Icons.Default.Check"))
        assertTrue(provider.contains("Modifier.clickable { providerExpanded = true }"))
        assertTrue(provider.contains("onDismissRequest = { providerExpanded = false }"))
        assertTrue(provider.contains("R.string.embedding_provider_custom"))
        assertFalse(editor.contains("FilterChip("))
        assertFalse(editor.contains("FlowRow("))
    }

    @Test
    fun selectingProviderRetainsDraftResetAndInitialModelDefaults() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")
        val provider = editor.section(
            "var providerExpanded by remember",
            "if (!isNew || preset.editableUrl)",
        )

        assertTrue(provider.contains("if (index != draft.providerIndex)"))
        assertFalse(provider.contains("testError"))
        assertTrue(provider.contains("providerIndex = index"))
        assertTrue(provider.contains("baseUrl = p.baseUrl"))
        assertTrue(provider.contains("modelName = p.models.firstOrNull().orEmpty()"))
        assertTrue(provider.contains("useSavedKey = true"))
        assertTrue(provider.contains("apiKey = \"\""))
        assertTrue(provider.contains("providerExpanded = false"))
        assertTrue(editor.contains("modelName = if (isNew) preset.models.firstOrNull().orEmpty() else initial.remoteModelName"))
    }

    @Test
    fun remoteModelUsesBadgeAndOneHintedIdFieldWithoutPresetChips() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")
        val model = editor.section(
            "title = stringResource(R.string.embedding_model_label)",
            "} else if (isNew) {\n                SettingsGroup(",
        )

        assertTrue(model.contains("SettingsIconContent(icon = Icons.Default.Tag)"))
        assertEquals(2, Regex("R.string.model_id_label").findAll(model).count())
        assertEquals(1, Regex("""\bMcpLabeledField\(""").findAll(model).count())
        assertTrue(model.contains("value = draft.modelName"))
        assertTrue(model.contains("placeholder = stringResource(R.string.embedding_model_id_hint)"))
        assertTrue(model.contains("ReadOnlyField("))
        assertTrue(model.contains("initial.remoteModelName"))
        assertFalse(model.contains("FilterChip("))
        assertFalse(model.contains("preset.models.forEach"))
    }

    @Test
    fun onlyLocalAddShowsTheTwoLineWholeRowFilePicker() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")
        val filePickerGroup = editor.section(
            "} else if (isNew) {\n                SettingsGroup(",
            "title = stringResource(R.string.embedding_general)",
        )

        assertEquals(1, Regex("""R\.string\.embedding_model_file\b""").findAll(filePickerGroup).count())
        assertTrue(filePickerGroup.contains("title = stringResource(R.string.embedding_model_file)"))
        assertFalse(filePickerGroup.contains("headlineContent = { Text(stringResource(R.string.embedding_model_file)) }"))
        assertTrue(filePickerGroup.contains("ModelImportItem("))
        assertTrue(filePickerGroup.contains("title = stringResource(R.string.embedding_import_title)"))
        assertTrue(filePickerGroup.contains("filePath = draft.localFilePath"))
        assertTrue(filePickerGroup.contains("description = stringResource(R.string.embedding_import_desc)"))
        assertTrue(filePickerGroup.contains("Icons.Default.AutoAwesome"))
        assertTrue(filePickerGroup.contains("importing = isImporting"))
        assertFalse(filePickerGroup.contains("trailingContent"))
        val item = readSettingsSource("ModelImportItem.kt")
        assertTrue(item.contains("enabled = enabled && !importing"))
        assertTrue(item.contains("targetState = Triple(importing, imported, missing)"))
        assertTrue(item.contains("MotionAwareCircularProgressIndicator("))
        assertTrue(filePickerGroup.contains("filePicker.launch(arrayOf(\"*/*\"))"))
        assertFalse(filePickerGroup.contains("isNew && draft.localFilePath.isBlank()"))
        assertFalse(filePickerGroup.contains("TextButton("))
        assertFalse(editor.contains("R.string.embedding_replace_file"))
    }

    @Test
    fun editorKeepsExplicitSaveAndUnsavedDraftBackGuard() {
        val editor = readSettingsSource("EmbeddingModelEditor.kt")

        assertTrue(editor.contains("IconButton(onClick = ::save, enabled = canSave)"))
        assertTrue(editor.contains("rememberUnsavedChangesBack(hasUnsavedChanges = draft != start || isImporting, onBack = leave)"))
        assertTrue(editor.contains("BackHandler { guardedBack() }"))
        assertTrue(editor.contains("onBack = guardedBack"))
        assertTrue(editor.contains("val lifecycle = remember(initial.id) { EmbeddingEditorLifecycle() }"))
        assertTrue(editor.contains("DisposableEffect(lifecycle)"))
        assertTrue(editor.contains("onDispose { lifecycle.close() }"))
        val leave = editor.section("val leave = {", "val guardedBack =")
        assertTrue(leave.indexOf("lifecycle.close()") >= 0)
        assertTrue(leave.indexOf("lifecycle.close()") < leave.indexOf("onBack()"))
        val save = editor.section("fun save()", "val filePicker =")
        assertTrue(save.contains("if (lifecycle.closed || !canSave) return"))
        val transfer = save.indexOf("lifecycle.imports.transfer(draft.localFilePath)")
        assertTrue("A successful local save transfers import ownership", transfer >= 0)
        assertTrue("Ownership transfers before navigation closes the editor", save.indexOf("leave()", transfer) > transfer)
        assertTrue(save.contains("if (lifecycle.closed) return@testConnection"))
        val picker = editor.section("val filePicker =", "CollapsingSettingsScaffold(")
        assertTrue(picker.contains("uri == null || lifecycle.closed || isImporting"))
        assertTrue(picker.contains("importEmbeddingGguf(context, uri, lifecycle.imports)"))
        assertTrue(picker.contains("if (lifecycle.closed) return@launch"))
        assertTrue(picker.contains("lifecycle.imports.discard(draft.localFilePath)"))
        assertFalse(editor.contains("File(path).delete()"))
    }

    private fun String.section(start: String, end: String): String {
        val startIndex = indexOf(start)
        assertTrue("Missing section start: $start", startIndex >= 0)
        val endIndex = indexOf(end, startIndex + start.length)
        assertTrue("Missing section end: $end", endIndex > startIndex)
        return substring(startIndex, endIndex)
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
