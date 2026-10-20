package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DefaultSystemPromptTest {
    @Test
    fun defaultDoesNotAdvertiseTheSkillLibraryAndMigratesCatalogDefaultsInPlace() {
        val old = DefaultSystemPrompt.previousCatalogVersionForMigration(Locale.ENGLISH).copy(id = "keep-id")
        val migrated = migrateSystemPromptsOnStartup(listOf(old), Locale.ENGLISH).single()
        assertEquals("keep-id", migrated.id)
        val values = mapOf(PredefinedVariables.ACTIVE_MEMORY to "", PredefinedVariables.SKILL_CATALOG to "never advertise.md")
        val text = PredefinedVariables.compile(migrated.resolvedSystemItems, values)
        assertFalse(text.contains("never advertise.md"))
        assertFalse(text.contains("<skill_catalog>"))
        assertTrue(text.contains("only when the user explicitly requests it"))
        val custom = old.copy(systemItems = old.systemItems + PromptTemplateItem(type = PromptItemType.CUSTOM, value = "personal"))
        assertEquals(custom, migrateSystemPromptsOnStartup(listOf(custom), Locale.ENGLISH).single())
    }

    @Test
    fun sentDateExample_includesEnglishWeekday() {
        assertEquals("2026-05-09 Sat", PredefinedVariables.EXAMPLE_VALUES[PredefinedVariables.SENT_DATE])
    }

    @Test
    fun titleForLocale_usesChineseDefaultForChineseLocale() {
        assertEquals("Default", DefaultSystemPrompt.titleForLocale(Locale.ENGLISH))
        assertEquals("\u9ed8\u8ba4", DefaultSystemPrompt.titleForLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals("\u9810\u8a2d", DefaultSystemPrompt.titleForLocale(Locale.forLanguageTag("zh-Hant")))
        assertEquals("Predeterminado", DefaultSystemPrompt.titleForLocale(Locale.forLanguageTag("es")))
        assertEquals("Par d\u00e9faut", DefaultSystemPrompt.titleForLocale(Locale.FRENCH))
    }

    @Test
    fun create_includesActiveMemoryAndToolPolicy_omitsRuntimeContext() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val systemPrompt = PredefinedVariables.compile(
            entry.systemItems,
            mapOf(
                PredefinedVariables.ACTIVE_MEMORY to "User prefers concise answers."
            )
        )

        assertFalse(systemPrompt.contains("<agora_runtime_context>"))
        assertFalse(systemPrompt.contains("<current_date>"))
        assertFalse(systemPrompt.contains("<current_time>"))
        assertTrue(systemPrompt.contains("<active_memory_context>\nUser prefers concise answers.\n</active_memory_context>"))
        assertTrue(systemPrompt.contains("Shell and device files:"))
        assertTrue(systemPrompt.contains("configured shell server or the Local Sandbox"))
        assertFalse(systemPrompt.contains("generate_image"))
    }

    @Test
    fun unmodifiedPreviousDefaultIsMigratedInPlace() {
        val previous = DefaultSystemPrompt.previousVersionForMigration(Locale.ENGLISH)
            .copy(id = "default-id")
        val migrated = migrateUnmodifiedBuiltInDefault(
            prompts = listOf(previous),
            locale = Locale.ENGLISH,
        ).single()
        val current = DefaultSystemPrompt.create(Locale.ENGLISH)

        assertEquals("default-id", migrated.id)
        assertEquals(
            current.resolvedSystemItems.map { it.type to it.value },
            migrated.resolvedSystemItems.map { it.type to it.value },
        )
        assertEquals(
            current.resolvedUserItems.map { it.type to it.value },
            migrated.resolvedUserItems.map { it.type to it.value },
        )
        assertEquals(
            current.resolvedAssistantItems.map { it.type to it.value },
            migrated.resolvedAssistantItems.map { it.type to it.value },
        )
        assertTrue(migrated.userPrependItems.isEmpty())
        assertTrue(migrated.userPostpendItems.isEmpty())
    }

    @Test
    fun localizedPreviousDefaultTitlesAreMigrated() {
        val locales = listOf(
            Locale.ENGLISH,
            Locale.forLanguageTag("ar"),
            Locale.GERMAN,
            Locale.forLanguageTag("es"),
            Locale.FRENCH,
            Locale.JAPANESE,
            Locale.KOREAN,
            Locale.forLanguageTag("pt-BR"),
            Locale.forLanguageTag("ru"),
            Locale.SIMPLIFIED_CHINESE,
            Locale.forLanguageTag("zh-Hant"),
        )
        val previousEntries = locales.mapIndexed { index, locale ->
            DefaultSystemPrompt.previousVersionForMigration(locale)
                .copy(id = "localized-default-$index")
        } + DefaultSystemPrompt.previousVersionForMigration(Locale.ENGLISH)
            .copy(id = "mixed-case-default", title = "dEfAuLt")
        val migrated = migrateUnmodifiedBuiltInDefault(
            prompts = previousEntries,
            locale = Locale.ENGLISH,
        )
        val currentSystem = DefaultSystemPrompt.create(Locale.ENGLISH)
            .resolvedSystemItems.map { it.type to it.value }

        assertEquals(previousEntries.map { it.id }, migrated.map { it.id })
        assertEquals(previousEntries.map { it.title }, migrated.map { it.title })
        migrated.forEach { entry ->
            assertEquals(
                currentSystem,
                entry.resolvedSystemItems.map { it.type to it.value },
            )
        }
    }

    @Test
    fun modifiedPreviousDefaultsAreNotMigrated() {
        val previous = DefaultSystemPrompt.previousVersionForMigration(Locale.ENGLISH)
        val contentModified = previous.copy(
            systemItems = previous.systemItems + PromptTemplateItem(
                type = PromptItemType.CUSTOM,
                value = "custom",
            ),
        )
        val titleModified = previous.copy(title = "My Default")

        assertEquals(
            listOf(contentModified, titleModified),
            migrateUnmodifiedBuiltInDefault(
                prompts = listOf(contentModified, titleModified),
                locale = Locale.ENGLISH,
            ),
        )
    }

    @Test
    fun create_requiresBackslashMathDelimiters() {
        val systemPrompt = PredefinedVariables.compile(
            DefaultSystemPrompt.create(Locale.ENGLISH).systemItems,
            emptyMap(),
        )

        assertTrue(
            systemPrompt.contains(
                "Use Markdown when it improves readability.\n" +
                    "For math, write inline formulas as \\( ... \\) and display formulas as \\[ ... \\]. " +
                    "Do not use \$ or \$\$ as math delimiters.\n\n<active_memory_context>"
            )
        )
        val beforeRule = PredefinedVariables.compile(
            DefaultSystemPrompt.versionBeforeMathRuleForMigration(Locale.ENGLISH).systemItems,
            emptyMap(),
        )
        assertTrue(beforeRule.contains("Use Markdown when it improves readability.\n\n<active_memory_context>"))
        assertFalse(beforeRule.contains("For math"))
    }

    @Test
    fun unmodifiedDefaultBeforeMathRuleIsMigratedInPlace() {
        val beforeRule = DefaultSystemPrompt.versionBeforeMathRuleForMigration(Locale.SIMPLIFIED_CHINESE)
            .copy(id = "default-id")
        val migrated = migrateUnmodifiedBuiltInDefault(
            prompts = listOf(beforeRule),
            locale = Locale.SIMPLIFIED_CHINESE,
        ).single()
        val current = DefaultSystemPrompt.create(Locale.SIMPLIFIED_CHINESE)

        assertEquals("default-id", migrated.id)
        assertEquals(beforeRule.title, migrated.title)
        assertEquals(
            current.resolvedSystemItems.map { it.type to it.value },
            migrated.resolvedSystemItems.map { it.type to it.value },
        )
        assertEquals(
            current.resolvedUserItems.map { it.type to it.value },
            migrated.resolvedUserItems.map { it.type to it.value },
        )
        assertEquals(
            current.resolvedAssistantItems.map { it.type to it.value },
            migrated.resolvedAssistantItems.map { it.type to it.value },
        )
    }

    @Test
    fun modifiedDefaultsBeforeMathRuleAreNotMigrated() {
        val beforeRule = DefaultSystemPrompt.versionBeforeMathRuleForMigration(Locale.ENGLISH)
        val contentModified = beforeRule.copy(
            systemItems = beforeRule.systemItems + PromptTemplateItem(
                type = PromptItemType.CUSTOM,
                value = "custom",
            ),
        )
        val titleModified = beforeRule.copy(title = "My Default")

        assertEquals(
            listOf(contentModified, titleModified),
            migrateUnmodifiedBuiltInDefault(
                prompts = listOf(contentModified, titleModified),
                locale = Locale.ENGLISH,
            ),
        )
    }

    @Test
    fun create_wrapsUserMessagesWithSentDateAndTimeMetadata() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val userTemplate = PredefinedVariables.splitMessageTemplate(entry.resolvedUserItems)
        val prefix = PredefinedVariables.compile(
            userTemplate.beforePrompt,
            mapOf(
                PredefinedVariables.SENT_DATE to "2026-05-09 Sat",
                PredefinedVariables.SENT_TIME to "21:35:10"
            ),
            emptyMap()
        )
        val suffix = PredefinedVariables.compile(userTemplate.afterPrompt, emptyMap(), emptyMap())
        val assistantTemplate = PredefinedVariables.splitMessageTemplate(entry.resolvedAssistantItems)

        assertEquals("<agora_user_message sent_date=\"2026-05-09 Sat\" sent_time=\"21:35:10\">\n", prefix)
        assertEquals("\n</agora_user_message>", suffix)
        assertTrue(assistantTemplate.beforePrompt.isEmpty())
        assertTrue(assistantTemplate.afterPrompt.isEmpty())
    }
}
