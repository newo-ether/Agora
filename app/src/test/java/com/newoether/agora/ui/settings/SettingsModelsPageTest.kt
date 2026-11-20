package com.newoether.agora.ui.settings

import com.newoether.agora.readLocaleStringResourceSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelsPageTest {
    @Test
    fun customModelsAreGroupedByProviderOrder() {
        val groups = customModelGroups(
            customModels = setOf(
                "Relay:zeta",
                "OpenAI:gpt-4.1",
                "Relay:alpha",
            ),
            providerOrder = listOf("OpenAI", "Relay"),
        )

        assertEquals(listOf("OpenAI", "Relay"), groups.map { it.providerName })
        assertEquals(listOf("Relay:alpha", "Relay:zeta"), groups[1].models)
    }

    @Test
    fun fetchedModelsExcludeManualEntries() {
        val groups = fetchedModelGroups(
            availableModels = linkedMapOf(
                "OpenAI" to listOf("OpenAI:gpt-4.1", "OpenAI:gpt-5"),
                "Relay" to listOf("Relay:sonnet"),
            ),
            customModels = setOf("OpenAI:gpt-5"),
            modelAliases = emptyMap(),
            query = "",
            providerOrder = listOf("OpenAI", "Relay"),
        )

        assertEquals(
            listOf("OpenAI:gpt-4.1", "Relay:sonnet"),
            groups.flatMap { it.models },
        )
    }

    @Test
    fun searchFiltersModelsAndDropsEmptyProviderGroups() {
        val groups = fetchedModelGroups(
            availableModels = linkedMapOf(
                "OpenAI" to listOf("OpenAI:gpt-4.1"),
                "Relay" to listOf("Relay:model-a", "Relay:model-b"),
            ),
            customModels = emptySet(),
            modelAliases = mapOf("Relay:model-b" to "Daily Sonnet"),
            query = "sonnet",
            providerOrder = listOf("OpenAI", "Relay"),
        )

        assertEquals(listOf("Relay"), groups.map { it.providerName })
        assertEquals(listOf("Relay:model-b"), groups.single().models)
    }

    @Test
    fun searchMatchesInferredAliasAndKeepsCollidingRawModelIdsDistinct() {
        val batch = "OpenRouter:anthropic/claude-opus-5:batch"
        val fast = "OpenRouter:anthropic/claude-opus-5-fast"
        val groups = fetchedModelGroups(
            availableModels = linkedMapOf("OpenRouter" to listOf(batch, fast)),
            customModels = emptySet(),
            modelAliases = emptyMap(),
            query = "Claude Opus 5",
            providerOrder = listOf("OpenRouter"),
        )

        assertEquals(listOf(batch, fast), groups.single().models)
    }

    @Test
    fun unchangedFallbackDoesNotBecomeExplicitAlias() {
        assertEquals(
            "",
            modelAliasToPersist(
                rawAlias = "",
                initialDisplayAlias = "Claude Opus 5",
                editedAlias = " Claude Opus 5 ",
            ),
        )
        assertEquals(
            "Production",
            modelAliasToPersist(
                rawAlias = "",
                initialDisplayAlias = "Claude Opus 5",
                editedAlias = " Production ",
            ),
        )
        assertEquals(
            "stored-identity",
            modelAliasToPersist(
                rawAlias = "stored-identity",
                initialDisplayAlias = "Stored Display Identity",
                editedAlias = "Stored Display Identity",
            ),
        )
        assertEquals(
            "",
            modelAliasToPersist(
                rawAlias = "Production",
                initialDisplayAlias = "Production",
                editedAlias = "",
            ),
        )
    }

    @Test
    fun providerNameSearchKeepsThatProvidersModels() {
        val groups = fetchedModelGroups(
            availableModels = linkedMapOf(
                "OpenAI" to listOf("OpenAI:gpt-4.1"),
                "Relay" to listOf("Relay:model-a", "Relay:model-b"),
            ),
            customModels = emptySet(),
            modelAliases = emptyMap(),
            query = "relay",
            providerOrder = listOf("OpenAI", "Relay"),
        )

        assertEquals(listOf("Relay:model-a", "Relay:model-b"), groups.single().models)
    }

    @Test
    fun `sync card owns progress feedback and settings host emits no progress snackbar`() {
        val page = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsModelsPage.kt",
        )
        val settingsHost = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt",
        )
        val syncCard = page.substringAfter("item(key = \"sync\")")
            .substringBefore("item(key = \"auto_search\")")

        assertTrue(page.contains("val isSyncingModels by viewModel.isSyncingModels.collectAsState()"))
        assertEquals(2, Regex("Crossfade\\(").findAll(syncCard).count())
        assertEquals(2, Regex("tween\\(durationMillis = 250\\)").findAll(syncCard).count())
        assertTrue(syncCard.contains("R.string.models_syncing"))
        assertTrue(syncCard.contains("CircularProgressIndicator("))
        assertTrue(syncCard.contains("Icons.Default.Refresh"))
        assertEquals(2, Regex("Modifier\\.size\\(24\\.dp\\)").findAll(syncCard).count())
        assertFalse(settingsHost.contains("snackbar_fetching_models"))
        assertFalse(settingsHost.contains("LaunchedEffect(isSyncingModels)"))
    }

    @Test
    fun `syncing copy replaces fetching snackbar copy in every locale`() {
        val directories = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh",
            "values-zh-rTW",
        )

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertTrue("$directory models_syncing", strings.contains("name=\"models_syncing\""))
            assertFalse(
                "$directory snackbar_fetching_models",
                strings.contains("name=\"snackbar_fetching_models\""),
            )
        }
        assertTrue(
            sourceFile("app/src/main/res/values/strings.xml")
                .contains("<string name=\"models_syncing\">Syncing...</string>"),
        )
    }

    @Test
    fun `both model editors keep provider choice local until the explicit save action`() {
        val page = sourceFile("app/src/main/java/com/newoether/agora/ui/settings/SettingsModelsPage.kt")
        assertEquals(2, Regex("ModelProviderNameSwitch\\(showProviderName\\)").findAll(page).count())
        assertEquals(3, Regex("showProviderName = showProviderName,").findAll(page).count())
        val rename = page.substringAfter("showModelAliasDialog?.let { model ->")
            .substringBefore("@Composable")
        val beforeSave = rename.substringBefore("confirmButton =")
        assertTrue(beforeSave.contains("onDismissRequest = { showModelAliasDialog = null }"))
        assertFalse(beforeSave.contains("updateModelAlias("))
        assertEquals(1, Regex("updateModelAlias\\(").findAll(rename).count())
        assertTrue(rename.contains("R.string.provider_save"))
        val switch = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/ModelProviderNameSwitch.kt",
        )
        assertFalse(switch.contains("viewModel"))
        assertTrue(switch.contains("role = Role.Switch"))
    }

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
