package com.newoether.agora.data

import com.newoether.agora.readLocaleStringResourceSources
import java.io.File
import com.newoether.agora.util.SecretCrypto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableSettingsArchiveTest {
    @Test
    fun todoAndSubagentPermissionsPersistRoundTripAndRespectLegacyStrategies() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("agora-tool-permissions").toFile()
        val context = mockk<android.content.Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory
        val movesClass = "androidx.datastore.core.FileMoves_androidKt"
        val atomicMove = Class.forName(movesClass)
            .getDeclaredMethod("atomicMoveTo", File::class.java, File::class.java)
        mockkStatic(movesClass)
        every { atomicMove.invoke(null, any<File>(), any<File>()) } answers {
            java.nio.file.Files.move(firstArg<File>().toPath(), secondArg<File>().toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        }
        mockkObject(SecretCrypto)
        every { SecretCrypto.encrypt(any()) } answers { firstArg<String>() }
        every { SecretCrypto.decrypt(any()) } answers { firstArg<String>() }
        try {
            val manager = SettingsManager(context)
            assertTrue(manager.todoEnabled.first())
            assertTrue(manager.subagentToolsEnabled.first())
            manager.saveTodoEnabled(false)
            manager.saveSubagentToolsEnabled(false)
            manager.saveLocalLowContextModeEnabled(true)
            val conversationSettings = mapOf("conversation" to ConversationSettings(shellEnabled = false))
            manager.saveConversationSettingsMap(conversationSettings)
            val reopened = SettingsManager(context)
            assertFalse(reopened.todoEnabled.first())
            assertFalse(reopened.subagentToolsEnabled.first())
            val exported = PortableSettingsArchive.toJsonObject(manager, false)
            assertEquals(JsonPrimitive(false), exported["todoEnabled"])
            assertEquals(JsonPrimitive(false), exported["subagentToolsEnabled"])
            val permissions = JsonObject(exported.filterKeys {
                it == "todoEnabled" || it == "subagentToolsEnabled"
            })
            val empty = JsonObject(emptyMap())
            PortableSettingsArchive.restoreFromJsonObject(empty, manager, false, false, null) { it }
            assertFalse(manager.todoEnabled.first())
            assertFalse(manager.subagentToolsEnabled.first())
            PortableSettingsArchive.restoreFromJsonObject(
                JsonObject(mapOf("todoEnabled" to JsonPrimitive(true))), manager, false, false, null,
            ) { it }
            assertTrue(manager.todoEnabled.first())
            assertFalse(manager.subagentToolsEnabled.first())
            PortableSettingsArchive.restoreFromJsonObject(
                JsonObject(mapOf("subagentToolsEnabled" to JsonPrimitive(true))), manager, false, false, null,
            ) { it }
            assertTrue(manager.todoEnabled.first())
            assertTrue(manager.subagentToolsEnabled.first())
            PortableSettingsArchive.restoreFromJsonObject(permissions, manager, false, false, null) { it }
            assertFalse(manager.todoEnabled.first())
            assertFalse(manager.subagentToolsEnabled.first())
            PortableSettingsArchive.restoreFromJsonObject(empty, manager, true, false, null) { it }
            assertTrue(manager.todoEnabled.first())
            assertTrue(manager.subagentToolsEnabled.first())
            assertTrue(manager.localLowContextModeEnabled.first())
            assertEquals(conversationSettings, manager.conversationSettings.first())
            PortableSettingsArchive.restoreFromJsonObject(permissions, manager, true, false, null) { it }
            val restored = SettingsManager(context)
            assertFalse(restored.todoEnabled.first())
            assertFalse(restored.subagentToolsEnabled.first())
            assertTrue(restored.localLowContextModeEnabled.first())
            assertEquals(conversationSettings, restored.conversationSettings.first())
        } finally {
            unmockkObject(SecretCrypto)
            unmockkStatic(movesClass)
            directory.deleteRecursively()
        }
    }

    @Test
    fun legacyServiceTiersNormalizeThroughPortableImportAndExport() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("agora-tier-settings").toFile()
        val context = mockk<android.content.Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory
        val movesClass = "androidx.datastore.core.FileMoves_androidKt"
        val atomicMove = Class.forName(movesClass)
            .getDeclaredMethod("atomicMoveTo", File::class.java, File::class.java)
        mockkStatic(movesClass)
        every { atomicMove.invoke(null, any<File>(), any<File>()) } answers {
            java.nio.file.Files.move(firstArg<File>().toPath(), secondArg<File>().toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        }
        try {
            val manager = SettingsManager(context)
            manager.saveOpenAiServiceTier("priority")
            assertEquals("fast", manager.openAiServiceTier.first())
            val legacy = Json.parseToJsonElement("""{"openAiServiceTier":"scale"}""") as JsonObject
            PortableSettingsArchive.restoreFromJsonObject(legacy, manager, false, false, null) { it }
            assertEquals("default", manager.openAiServiceTier.first())
            assertEquals("default", PortableSettingsArchive.toJsonObject(manager, false)
                .getValue("openAiServiceTier").jsonPrimitive.content)
            manager.saveConversationSettings("conversation", ConversationSettings(openAiServiceTier = "scale"))
            assertEquals("default", manager.conversationSettings.first()
                .getValue("conversation").openAiServiceTier)
            manager.saveConversationSettingsMap(
                mapOf("conversation" to ConversationSettings(openAiServiceTier = "priority")),
            )
            assertEquals("fast", SettingsManager(context).conversationSettings.first()
                .getValue("conversation").openAiServiceTier)
        } finally {
            unmockkStatic(movesClass)
            directory.deleteRecursively()
        }
    }

    @Test
    fun cacheArchiveRoundTripAndLegacyStrategiesUseRealSettingsStorage() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("agora-cache-settings").toFile()
        val context = mockk<android.content.Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory
        // The Android JVM stub selects pre-26 renameTo, which cannot replace files on Windows.
        val movesClass = "androidx.datastore.core.FileMoves_androidKt"
        val atomicMove = Class.forName(movesClass).getDeclaredMethod("atomicMoveTo", File::class.java, File::class.java)
        mockkStatic(movesClass)
        every { atomicMove.invoke(null, any<File>(), any<File>()) } answers {
            java.nio.file.Files.move(firstArg<File>().toPath(), secondArg<File>().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        }
        try {
            mockkObject(SecretCrypto)
            every { SecretCrypto.encrypt(any()) } answers { firstArg<String>() }
            every { SecretCrypto.decrypt(any()) } answers { firstArg<String>() }
            val manager = SettingsManager(context)
            assertTrue(manager.anthropicCacheEnabled.first())
            assertEquals("1h", manager.anthropicCacheTtl.first())
            manager.saveAnthropicCacheEnabled(false)
            manager.saveAnthropicCacheTtl("5m")
            manager.saveLocalLowContextModeEnabled(true)
            manager.saveCustomProviders(listOf(CustomProviderConfig(
                name = "Relay", protocol = CustomEndpointProtocol.ANTHROPIC,
                anthropicCacheEnabled = false, anthropicCacheTtl = "5m",
            )))
            val exported = PortableSettingsArchive.toJsonObject(manager, false)
            assertEquals("false", exported.getValue("anthropicCacheEnabled").jsonPrimitive.content)
            assertEquals("5m", exported.getValue("anthropicCacheTtl").jsonPrimitive.content)
            val cacheArchive = JsonObject(exported.filterKeys {
                it in setOf("anthropicCacheEnabled", "anthropicCacheTtl", "customProviders")
            })
            val empty = JsonObject(emptyMap())
            PortableSettingsArchive.restoreFromJsonObject(empty, manager, false, false, null) { it }
            assertFalse(manager.anthropicCacheEnabled.first())
            assertEquals("5m", manager.anthropicCacheTtl.first())
            PortableSettingsArchive.restoreFromJsonObject(empty, manager, true, false, null) { it }
            assertTrue(manager.anthropicCacheEnabled.first())
            assertEquals("1h", manager.anthropicCacheTtl.first())
            assertTrue(manager.localLowContextModeEnabled.first())
            PortableSettingsArchive.restoreFromJsonObject(cacheArchive, manager, true, false, null) { it }
            val reopened = SettingsManager(context)
            assertFalse(reopened.anthropicCacheEnabled.first())
            assertEquals("5m", reopened.anthropicCacheTtl.first())
            val custom = reopened.customProviders.first().single()
            assertFalse(custom.anthropicCacheEnabled)
            assertEquals("5m", custom.anthropicCacheTtl)
            val legacy = Json.parseToJsonElement("""{"customProviders":[{"name":"Relay","protocol":"anthropic"}]}""") as JsonObject
            PortableSettingsArchive.restoreFromJsonObject(legacy, manager, false, false, null) { it }
            val imported = manager.customProviders.first().single()
            assertEquals(custom.providerId, imported.providerId)
            assertTrue(imported.anthropicCacheEnabled)
            assertEquals("1h", imported.anthropicCacheTtl)
            assertFalse(manager.anthropicCacheEnabled.first())
            assertEquals("5m", manager.anthropicCacheTtl.first())
        } finally {
            unmockkObject(SecretCrypto)
            unmockkStatic(movesClass)
            directory.deleteRecursively()
        }
    }
    @Test
    fun invalidCacheArchiveFailsBeforeAnySettingsWrite() = runTest {
        val manager = mockk<SettingsManager>(relaxed = true)
        every { manager.shellDevices } returns flowOf(emptyList())
        every { manager.mcpServers } returns flowOf(emptyList())
        every { manager.embeddingModels } returns flowOf(emptyList())
        every { manager.activeEmbeddingModelId } returns flowOf("")
        every { manager.customFontPath } returns flowOf("")
        every { manager.customFontName } returns flowOf("")
        every { manager.customProviders } returns flowOf(emptyList())
        for (field in listOf("anthropicCacheTtl", "anthropicCacheEnabled")) {
            for (invalid in listOf(JsonNull, JsonObject(emptyMap()), JsonArray(emptyList()), JsonPrimitive(7), JsonPrimitive("invalid"))) {
                val record = JsonObject(mapOf("name" to JsonPrimitive("Relay"), field to invalid))
                val nested = JsonObject(mapOf("customProviders" to JsonArray(listOf(record))))
                for (body in listOf(record, nested)) {
                    val result = runCatching {
                        PortableSettingsArchive.restoreFromJsonObject(
                            body, manager, true, false, null,
                        ) { it }
                    }
                    assertTrue(body.toString(), result.exceptionOrNull() is IllegalArgumentException)
                }
            }
        }
        coVerify(exactly = 0) { manager.resetPortableSettingsForImport() }
        coVerify(exactly = 0) { manager.saveAnthropicCacheEnabled(any()) }
        coVerify(exactly = 0) { manager.saveAnthropicCacheTtl(any()) }
        coVerify(exactly = 0) { manager.saveCustomProviders(any()) }
    }
    @Test
    fun providerNamePreferencesRestoreWithIdentityRemappingAndAbsencePreservation() = runTest {
        val providerId = "custom-provider-00000000-0000-4000-8000-000000000001"
        val manager = mockk<SettingsManager>(relaxed = true)
        every { manager.shellDevices } returns flowOf(emptyList())
        every { manager.mcpServers } returns flowOf(emptyList())
        every { manager.embeddingModels } returns flowOf(emptyList())
        every { manager.activeEmbeddingModelId } returns flowOf("")
        every { manager.customFontPath } returns flowOf("")
        every { manager.customFontName } returns flowOf("")
        every { manager.customProviders } returns flowOf(listOf(CustomProviderConfig("Relay", id = providerId)))
        val writes = mutableListOf<Pair<Map<String, Boolean>, Boolean>>()
        coEvery { manager.saveModelProviderNames(any(), any()) } answers {
            writes += firstArg<Map<String, Boolean>>() to secondArg<Boolean>()
        }
        val archive = Json.parseToJsonElement(
            """{"customProviders":[{"name":"Relay"}],"modelProviderNames":{"Relay:a":false,"OpenAI:b":true}}""",
        ) as kotlinx.serialization.json.JsonObject
        PortableSettingsArchive.restoreFromJsonObject(archive, manager, false, false, null) { it }
        assertEquals(listOf(mapOf("$providerId:a" to false, "OpenAI:b" to true) to false), writes)
        PortableSettingsArchive.restoreFromJsonObject(archive, manager, true, false, null) { it }
        assertEquals(true, writes.last().second)
        assertEquals(false, writes.last().first.entries.single { it.key.endsWith(":a") }.value)
        assertEquals(true, writes.last().first["OpenAI:b"])
        val empty = kotlinx.serialization.json.JsonObject(emptyMap())
        PortableSettingsArchive.restoreFromJsonObject(empty, manager, false, false, null) { it }
        assertEquals(2, writes.size)
        PortableSettingsArchive.restoreFromJsonObject(empty, manager, true, false, null) { it }
        assertEquals(2, writes.size)
        coVerify(exactly = 2) { manager.resetPortableSettingsForImport() }
        val root = locateDirectory("app/src/main/java", "src/main/java")
        val export = File(root, "com/newoether/agora/data/PortableSettingsArchive.kt").readText()
        val storage = File(root, "com/newoether/agora/data/SettingsManager.kt").readText() +
            File(root, "com/newoether/agora/data/PortableSettingsReset.kt").readText()
        assertTrue(export.contains("putEncoded(\"modelProviderNames\", sm.modelProviderNames.first())"))
        assertTrue(storage.contains("clearPortableSettings(prefs)"))
        assertTrue(storage.contains("prefs[MODEL_PROVIDER_NAMES_JSON] = \"{}\""))
        assertTrue(storage.contains("produceMigrations = { listOf(modelProviderNamesMigration) }"))
    }

    @Test
    fun amoledIsDefaultOffPortableAndAvailableInEveryTheme() {
        val root = locateDirectory("app/src/main/java", "src/main/java")
        fun source(path: String) = File(root, "com/newoether/agora/$path").readText()
        val manager = source("data/SettingsManager.kt") +
            // Appearance save* accessors were extracted here to keep SettingsManager <=800 lines.
            source("data/SettingsManagerAppearance.kt") +
            source("data/PortableSettingsReset.kt")
        val archive = source("data/PortableSettingsArchive.kt")
        val repository = source("data/repository/SettingsRepository.kt")
        val page = source("ui/settings/SettingsAppearancePage.kt")
        assertTrue(manager.contains("it[AMOLED_ENABLED] ?: false"))
        assertTrue(manager.contains("it[AMOLED_ENABLED] = enabled"))
        assertTrue(manager.contains("prefs.remove(AMOLED_ENABLED)"))
        assertTrue(repository.contains("hot(settingsManager.amoledEnabled, false)"))
        assertTrue(archive.contains("put(\"amoledEnabled\", JsonPrimitive(sm.amoledEnabled.first()))"))
        assertTrue(archive.contains("obj.boolean(\"amoledEnabled\")?.let { sm.saveAmoledEnabled(it) }"))
        assertTrue(archive.contains("if (replace) sm.resetPortableSettingsForImport()"))
        assertTrue(page.indexOf("R.string.amoled_mode)") < page.indexOf("if (isDynamicAvailable)"))
        assertTrue(page.contains("Switch(checked = amoledEnabled, onCheckedChange = null)"))
        assertTrue(page.contains("role = Role.Switch"))
        val resources = locateDirectory("app/src/main/res", "src/main/res")
        resources.listFiles().orEmpty().map { File(it, "strings.xml") }.filter(File::isFile).forEach {
            val strings = it.readLocaleStringResourceSources()
            assertTrue("AMOLED title missing in $it", strings.contains("name=\"amoled_mode\""))
            assertTrue("AMOLED description missing in $it", strings.contains("name=\"amoled_mode_desc\""))
        }
    }

    @Test
    fun compactThresholdImportAcceptsOnlyThePortableRange() {
        assertEquals(50, importedContextCompactThresholdPercent(50))
        assertEquals(90, importedContextCompactThresholdPercent(90))
        assertEquals(100, importedContextCompactThresholdPercent(100))
        assertEquals(null, importedContextCompactThresholdPercent(null))
        assertEquals(null, importedContextCompactThresholdPercent(49))
        assertEquals(null, importedContextCompactThresholdPercent(101))
    }

    @Test
    fun legacyArchiveProviderReusesExistingIdentityAndMarksRoomReferences() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val result = PortableSettingsArchive.prepareImportedCustomProviders(
            raw = listOf(CustomProviderConfig(name = "Relay X")),
            existing = listOf(CustomProviderConfig(name = "Relay X", id = id)),
            replace = false,
        )

        assertEquals(id, result.providers.single().id)
        assertEquals(setOf("Relay X"), result.providers.single().legacyNames)
        assertEquals(false, result.providers.single().responsesApiEnabled)
        assertEquals(mapOf("Relay X" to id), result.modelReferenceRemap)
        assertEquals(mapOf("Relay X" to "Relay X"), result.providerNameRemap)
    }

    @Test
    fun importedProviderResponsesSettingReplacesExistingStableRecord() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val result = PortableSettingsArchive.prepareImportedCustomProviders(
            raw = listOf(CustomProviderConfig(name = "Relay X", responsesApiEnabled = true)),
            existing = listOf(CustomProviderConfig(name = "Relay X", id = id)),
            replace = false,
        )
        assertEquals(id, result.providers.single().id)
        assertEquals(true, result.providers.single().responsesApiEnabled)
    }

    @Test
    fun replacingFromLegacyArchiveAllocatesStableIdentity() {
        val result = PortableSettingsArchive.prepareImportedCustomProviders(
            raw = listOf(CustomProviderConfig(name = "Relay X")),
            existing = emptyList(),
            replace = true,
        )

        val provider = result.providers.single()
        assertTrue(CustomProviderIdentityPolicy.isStableId(provider.id))
        assertEquals(provider.id, result.modelReferenceRemap["Relay X"])
        assertEquals(setOf("Relay X"), provider.legacyNames)
    }

    @Test
    fun uncachedNotificationIsPortableConditionalAndSharesOneCachePolicy() {
        val mainRoot = locateDirectory("app/src/main/java", "src/main/java")
        fun source(path: String) = File(mainRoot, path).readText().replace("\r\n", "\n")

        val schema = source("com/newoether/agora/data/SettingsPreferenceSchema.kt")
        val manager = source("com/newoether/agora/data/SettingsManager.kt") +
            source("com/newoether/agora/data/PortableSettingsReset.kt")
        val repository = source("com/newoether/agora/data/repository/SettingsRepository.kt")
        val archive = source("com/newoether/agora/data/PortableSettingsArchive.kt")
        val settingsPage = source("com/newoether/agora/ui/settings/SettingsSearchPage.kt")
        val startup = source("com/newoether/agora/viewmodel/StartupMaintenanceCoordinator.kt")
        val rag = source("com/newoether/agora/viewmodel/RagManager.kt")

        assertTrue(schema.contains("booleanPreferencesKey(\"show_uncached_notification\")"))
        assertTrue(manager.contains("it[SHOW_UNCACHED_NOTIFICATION] ?: true"))
        assertTrue(manager.contains("prefs.remove(SHOW_UNCACHED_NOTIFICATION)"))
        assertTrue(repository.contains("hot(settingsManager.showUncachedNotification, true)"))
        assertTrue(archive.contains("put(\"showUncachedNotification\""))
        assertTrue(archive.contains("obj.boolean(\"showUncachedNotification\")"))

        val autoCacheGroup = settingsPage
            .substringAfter("title = stringResource(R.string.auto_cache_title)")
            .substringBefore("title = stringResource(R.string.search_methods_title)")
        val autoCacheIndex = autoCacheGroup.indexOf("R.string.auto_cache)")
        val reminderIndex = autoCacheGroup.indexOf("R.string.show_uncached_notification)")
        assertTrue(autoCacheGroup.contains("if (!autoCacheEnabled)"))
        assertTrue(autoCacheIndex >= 0 && reminderIndex > autoCacheIndex)

        assertFalse(startup.contains("handleUncachedMessages"))
        assertFalse(startup.contains("getIndexableMessageCount"))
        assertFalse(startup.contains("getEmbeddingCountByModel"))

        val modelSwitchPolicy = rag
            .substringAfter("fun setActiveEmbeddingModel(id: String)")
            .substringBefore("fun cacheMessagesForModel")
        assertTrue(modelSwitchPolicy.contains("admitActiveModel(id)"))
        assertFalse(modelSwitchPolicy.contains("cacheMessagesForModel("))
        assertFalse(modelSwitchPolicy.contains("embedding_model_caching"))

        val admissionPolicy = rag
            .substringAfter("private suspend fun admitActiveModel(")
            .substringBefore("/**")
        assertTrue(admissionPolicy.contains("settings.getAutoCacheEnabled()"))
        assertTrue(admissionPolicy.contains("scheduleCacheWork(modelId)"))
        assertTrue(admissionPolicy.contains("settings.getShowUncachedNotification()"))

        val resourceRoot = locateDirectory("app/src/main/res", "src/main/res")
        listOf(
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
        ).forEach { directory ->
            val strings = File(resourceRoot, "$directory/strings.xml").readLocaleStringResourceSources()
            assertTrue("Missing title in $directory", strings.contains("name=\"show_uncached_notification\""))
            assertTrue("Missing description in $directory", strings.contains("name=\"show_uncached_notification_desc\""))
        }
    }

    private fun locateDirectory(vararg candidates: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            candidates.map { path -> File(directory, path) }
                .firstOrNull(File::isDirectory)
                ?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate ${candidates.joinToString()}")
    }
}
