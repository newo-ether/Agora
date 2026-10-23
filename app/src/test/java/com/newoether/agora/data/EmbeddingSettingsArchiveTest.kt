package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.util.SecretCrypto
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingSettingsArchiveTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val local = EmbeddingModelConfig(
        id = "local", name = "Local", type = EmbeddingModelType.LOCAL_ONNX,
        localFilePath = "/device/models/local.onnx",
    )
    private val remote = EmbeddingModelConfig(
        id = "remote", name = "Remote", type = EmbeddingModelType.REMOTE,
        remoteApiKey = "device-secret", remoteModelName = "embedding-v1",
    )
    private val incoming = EmbeddingModelConfig(
        id = "incoming", name = "Imported", type = EmbeddingModelType.REMOTE,
        remoteModelName = "embedding-v2",
    )

    @Test
    fun mergeWithoutActiveFieldKeepsSurvivingRemoteSelection() = runTest {
        withSettings { manager ->
            manager.saveEmbeddingModels(listOf(local, remote))
            manager.setActiveEmbeddingModelId(remote.id)
            restore(manager, archive(), replace = false)
            assertEquals(remote.id, manager.activeEmbeddingModelId.first())
            assertEquals(listOf(local, remote, incoming), manager.embeddingModels.first())
            restore(manager, archive("activeRemoteEmbeddingModelId" to JsonPrimitive(incoming.id)), false)
            assertEquals(incoming.id, manager.activeEmbeddingModelId.first())
            restore(manager, archive("activeRemoteEmbeddingModelId" to JsonNull), false)
            assertEquals("", manager.activeEmbeddingModelId.first())
        }
    }

    @Test
    fun mergeAppliesActiveOnlyFieldsWithoutReplacingTheModelList() = runTest {
        withSettings { manager ->
            manager.saveEmbeddingModels(listOf(local, remote))
            manager.setActiveEmbeddingModelId(local.id)
            restore(manager, JsonObject(mapOf("activeRemoteEmbeddingModelId" to JsonPrimitive(remote.id))), false)
            assertEquals(remote.id, manager.activeEmbeddingModelId.first())
            restore(manager, JsonObject(mapOf("activeRemoteEmbeddingModelId" to JsonNull)), false)
            assertEquals("", manager.activeEmbeddingModelId.first())
            restore(manager, JsonObject(mapOf("activeEmbeddingModelId" to JsonPrimitive(remote.id))), false)
            assertEquals(remote.id, manager.activeEmbeddingModelId.first())
            assertEquals(listOf(local, remote), manager.embeddingModels.first())
        }
    }

    @Test
    fun explicitAndLegacyActiveReferencesSelectOnlySurvivingRemoteModels() = runTest {
        withSettings { manager ->
            manager.saveEmbeddingModels(listOf(local, remote))
            manager.setActiveEmbeddingModelId(remote.id)
            restore(manager, archive("activeEmbeddingModelId" to JsonPrimitive(incoming.id)), false)
            assertEquals(incoming.id, manager.activeEmbeddingModelId.first())
            restore(manager, archive("activeRemoteEmbeddingModelId" to JsonPrimitive("missing")), false)
            assertEquals("", manager.activeEmbeddingModelId.first())
            restore(manager, archive("activeEmbeddingModelId" to JsonPrimitive(local.id)), false)
            assertEquals("", manager.activeEmbeddingModelId.first())
        }
    }

    @Test
    fun replaceWithoutActiveReferenceClearsRemoteSelectionButPreservesLocalRecords() = runTest {
        withSettings { manager ->
            manager.saveEmbeddingModels(listOf(local, remote))
            manager.setActiveEmbeddingModelId(remote.id)
            restore(manager, archive(), replace = true)
            assertEquals("", manager.activeEmbeddingModelId.first())
            assertEquals(listOf(local, incoming), manager.embeddingModels.first())
        }
    }

    @Test
    fun replaceSelectsImportedRemoteButDoesNotTrustStructuralCredentialsOrPaths() = runTest {
        withSettings { manager ->
            manager.saveEmbeddingModels(listOf(local, remote))
            manager.setActiveEmbeddingModelId(local.id)
            val imported = remote.copy(name = "Updated", remoteModelName = "new-model",
                remoteApiKey = "archive-secret", localFilePath = "/archive/hostile.gguf")
            val archive = JsonObject(mapOf(
                "remoteEmbeddingModels" to json.encodeToJsonElement(listOf(imported)),
                "activeRemoteEmbeddingModelId" to JsonPrimitive(remote.id),
            ))
            restore(manager, archive, replace = true)
            assertEquals(remote.id, manager.activeEmbeddingModelId.first())
            assertEquals(listOf(local, imported.copy(remoteApiKey = remote.remoteApiKey, localFilePath = "")),
                manager.embeddingModels.first())
        }
    }

    @Test
    fun localActiveSelectionSurvivesMergeReplaceAndExplicitRemoteClear() = runTest {
        withSettings { manager ->
            manager.saveEmbeddingModels(listOf(local, remote))
            manager.setActiveEmbeddingModelId(local.id)
            restore(manager, archive(), replace = false)
            assertEquals(local.id, manager.activeEmbeddingModelId.first())
            restore(manager, archive(), replace = true)
            assertEquals(local.id, manager.activeEmbeddingModelId.first())
            restore(manager, archive("activeRemoteEmbeddingModelId" to JsonNull), true)
            assertEquals(local.id, manager.activeEmbeddingModelId.first())
            assertTrue(manager.embeddingModels.first().contains(local))
        }
    }

    @Test
    fun structuralExportExcludesLocalPathsSecretsAndConversationMetadata() = runTest {
        withSettings { manager ->
            manager.saveEmbeddingModels(listOf(local, remote.copy(localFilePath = "/device/stale.gguf")))
            manager.setActiveEmbeddingModelId(local.id)
            val exported = PortableSettingsArchive.toJsonObject(manager, false)
            assertEquals(JsonNull, exported["activeRemoteEmbeddingModelId"])
            val models = json.decodeFromJsonElement<List<EmbeddingModelConfig>>(
                exported.getValue("remoteEmbeddingModels"),
            )
            assertEquals(listOf(remote.copy(remoteApiKey = "", localFilePath = "")), models)
            assertFalse(exported.toString().contains("/device/"))
            assertFalse(exported.toString().contains("device-secret"))
            assertFalse(exported.containsKey("todoJson"))
            assertFalse(exported.containsKey("parentConversationId"))
            assertFalse(exported.containsKey("subagentResultJson"))
            assertFalse(exported.containsKey("sourceJson"))
        }
    }

    private fun archive(vararg active: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(
        mapOf("remoteEmbeddingModels" to json.encodeToJsonElement(listOf(incoming))) + active,
    )

    private suspend fun restore(manager: SettingsManager, archive: JsonObject, replace: Boolean) {
        PortableSettingsArchive.restoreFromJsonObject(archive, manager, replace, false, null) { it }
    }

    private suspend fun withSettings(block: suspend (SettingsManager) -> Unit) {
        val directory = Files.createTempDirectory("agora-embedding-archive").toFile()
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory
        val movesClass = "androidx.datastore.core.FileMoves_androidKt"
        val atomicMove = Class.forName(movesClass)
            .getDeclaredMethod("atomicMoveTo", File::class.java, File::class.java)
        mockkStatic(movesClass)
        every { atomicMove.invoke(null, any<File>(), any<File>()) } answers {
            Files.move(firstArg<File>().toPath(), secondArg<File>().toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }
        mockkObject(SecretCrypto)
        every { SecretCrypto.encrypt(any()) } answers { firstArg<String>() }
        every { SecretCrypto.decrypt(any()) } answers { firstArg<String>() }
        try {
            block(SettingsManager(context))
        } finally {
            unmockkObject(SecretCrypto)
            unmockkStatic(movesClass)
            directory.deleteRecursively()
        }
    }
}
