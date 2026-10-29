package com.newoether.agora.data

import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SystemPromptMigrationTest {
    @Test
    fun startupPreservesCustomizedTemplatesContainingLegacyRuntimeTags() {
        val original = DefaultSystemPrompt.create(Locale.ENGLISH).copy(
            id = "user-prompt",
            systemItems = listOf(text("<agora_runtime_context>old</agora_runtime_context>"), text("My rules")),
            userItems = listOf(text("My prefix"), PredefinedVariables.promptItem()),
            assistantItems = listOf(PredefinedVariables.promptItem(), text("My suffix")),
        )
        assertEquals(original, coldStart(original))
        assertEquals(original, coldStart(coldStart(original)))
    }

    @Test
    fun startupPreservesLegacyContentAndWrappersWhileNormalizingTheirStorage() {
        val original = SystemPromptEntry(
            id = "legacy-custom",
            title = "My prompt",
            content = "<agora_runtime_context>Custom runtime instructions</agora_runtime_context>",
            userPrependItems = listOf(text("User prefix")),
            userPostpendItems = listOf(text("User suffix")),
        )
        val migrated = coldStart(original)
        assertEquals(original.id, migrated.id)
        assertEquals(original.title, migrated.title)
        assertEquals(original.content, migrated.content)
        assertEquals(original.resolvedSystemItems.map { it.type to it.value },
            migrated.resolvedSystemItems.map { it.type to it.value })
        assertEquals(original.resolvedUserItems.map { it.type to it.value },
            migrated.resolvedUserItems.map { it.type to it.value })
        assertEquals(original.resolvedAssistantItems.map { it.type to it.value },
            migrated.resolvedAssistantItems.map { it.type to it.value })
        assertEquals(migrated, coldStart(migrated))
    }

    @Test
    fun startupStillUpgradesOnlyTheKnownUnmodifiedBuiltInDefault() {
        val previous = DefaultSystemPrompt.previousVersionForMigration(Locale.ENGLISH)
        val customized = previous.copy(systemItems = previous.systemItems + text("My rules"))
        val migrated = coldStart(previous)
        val current = DefaultSystemPrompt.create(Locale.ENGLISH)
        assertEquals(previous.id, migrated.id)
        assertEquals(current.resolvedSystemItems.map { it.type to it.value },
            migrated.resolvedSystemItems.map { it.type to it.value })
        assertEquals(customized.resolvedSystemItems, coldStart(customized).resolvedSystemItems)
        assertEquals(migrated, coldStart(migrated))
    }

    @Test
    fun everyHistoricalDefaultUpgradesButAnyCustomizedWrapperOrTextRemains() {
        val versions = listOf(
            DefaultSystemPrompt.previousVersionForMigration(Locale.ENGLISH),
            DefaultSystemPrompt.versionBeforeMathRuleForMigration(Locale.ENGLISH),
            DefaultSystemPrompt.versionBeforeToolRenameForMigration(Locale.ENGLISH),
            DefaultSystemPrompt.versionBeforeToolGuidesForMigration(Locale.ENGLISH),
            DefaultSystemPrompt.versionWithToolGuidesForMigration(Locale.ENGLISH),
            DefaultSystemPrompt.versionBeforeHtmlPreviewForMigration(Locale.ENGLISH),
        )
        val current = DefaultSystemPrompt.create(Locale.ENGLISH)
        versions.forEachIndexed { index, original ->
            val version = original.copy(id = "historic-$index")
            val migrated = coldStart(version)
            assertEquals(version.id, migrated.id)
            assertEquals(version.title, migrated.title)
            assertEquals(current.resolvedSystemItems.map { it.type to it.value },
                migrated.resolvedSystemItems.map { it.type to it.value })
            assertEquals(version.resolvedUserItems.map { it.type to it.value },
                migrated.resolvedUserItems.map { it.type to it.value })
            assertEquals(version.resolvedAssistantItems.map { it.type to it.value },
                migrated.resolvedAssistantItems.map { it.type to it.value })
            assertEquals(migrated, coldStart(migrated))
            listOf(
                version.copy(systemItems = version.resolvedSystemItems + text(" ")),
                version.copy(userItems = listOf(text("Custom prefix")) + version.resolvedUserItems),
                version.copy(assistantItems = version.resolvedAssistantItems + text("Custom suffix")),
                version.copy(title = "My Default"),
            ).forEach { customized ->
                val restored = coldStart(customized)
                assertEquals(customized.resolvedSystemItems.map { it.type to it.value },
                    restored.resolvedSystemItems.map { it.type to it.value })
                assertEquals(customized.resolvedUserItems.map { it.type to it.value },
                    restored.resolvedUserItems.map { it.type to it.value })
                assertEquals(customized.resolvedAssistantItems.map { it.type to it.value },
                    restored.resolvedAssistantItems.map { it.type to it.value })
                assertEquals(restored, coldStart(restored))
            }
        }
    }

    @Test
    fun deletingOrEditingHtmlGuidanceSurvivesRepeatedSerializedStartup() {
        val current = coldStart(DefaultSystemPrompt.versionBeforeHtmlPreviewForMigration(Locale.ENGLISH))
        val guidance = current.systemItems.single { it.value == HtmlPreviewGuidance.text }
        val removed = current.copy(systemItems = current.systemItems.filterNot { it.id == guidance.id })
        val previous = DefaultSystemPrompt.versionBeforeHtmlPreviewForMigration(Locale.ENGLISH)
        assertEquals(previous.resolvedSystemItems.map { it.type to it.value },
            removed.resolvedSystemItems.map { it.type to it.value })
        assertEquals(DefaultSystemPrompt.BUILT_IN_MIGRATION_VERSION, removed.builtInMigrationVersion)
        assertFalse(DefaultSystemPrompt.isUnmodifiedPreviousVersion(removed))
        val edited = current.copy(systemItems = current.systemItems.map {
            if (it.id == guidance.id) it.copy(value = it.value + "\nMy own visualization rules.") else it
        })
        listOf(removed, edited).forEach { customized ->
            assertEquals(customized, coldStart(customized))
            assertEquals(customized, coldStart(coldStart(customized)))
        }
        assertFalse(HtmlPreviewGuidance.isPresent(coldStart(removed).systemItems))
    }

    @Test
    fun legacyJsonDefaultsToUnmigratedAndSerializedProvenanceIsNotPromptText() {
        val legacy = DefaultSystemPrompt.versionBeforeHtmlPreviewForMigration(Locale.ENGLISH)
        val json = Json.encodeToString(listOf(legacy))
        assertFalse(json.contains("builtInMigrationVersion"))
        val decoded = Json.decodeFromString<List<SystemPromptEntry>>(json).single()
        assertEquals(0, decoded.builtInMigrationVersion)
        val migrated = coldStart(decoded)
        assertEquals(DefaultSystemPrompt.BUILT_IN_MIGRATION_VERSION, migrated.builtInMigrationVersion)
        assertEquals(migrated, coldStart(migrated))
        assertFalse(PredefinedVariables.compile(migrated.systemItems, emptyMap()).contains("builtInMigrationVersion"))
    }

    @Test
    fun currentOrFutureProvenancePreservesEvenExactHistoricalText() {
        val legacy = DefaultSystemPrompt.versionBeforeHtmlPreviewForMigration(Locale.ENGLISH)
        listOf(DefaultSystemPrompt.BUILT_IN_MIGRATION_VERSION,
            DefaultSystemPrompt.BUILT_IN_MIGRATION_VERSION + 1).forEach { version ->
            val accepted = legacy.copy(builtInMigrationVersion = version)
            assertFalse(DefaultSystemPrompt.isUnmodifiedPreviousVersion(accepted))
            assertEquals(accepted, coldStart(accepted))
            assertEquals(accepted, coldStart(coldStart(accepted)))
        }
    }

    @Test
    fun startupPreservesEmptySystemAndEmptyPromptCatalog() {
        val empty = DefaultSystemPrompt.create(Locale.ENGLISH).copy(systemItems = emptyList())
        assertEquals(empty, coldStart(empty))
        assertEquals(empty, coldStart(coldStart(empty)))
        assertEquals(emptyList<SystemPromptEntry>(), migrateSystemPromptsOnStartup(
            Json.decodeFromString<List<SystemPromptEntry>>("[]"), Locale.ENGLISH,
        ))
    }

    private fun coldStart(entry: SystemPromptEntry): SystemPromptEntry {
        val persisted = Json.encodeToString(listOf(entry))
        return migrateSystemPromptsOnStartup(
            Json.decodeFromString<List<SystemPromptEntry>>(persisted), Locale.ENGLISH,
        ).single()
    }

    private fun text(value: String) = PromptTemplateItem(type = PromptItemType.CUSTOM, value = value)
}
