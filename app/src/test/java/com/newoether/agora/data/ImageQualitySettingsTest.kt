package com.newoether.agora.data

import androidx.datastore.preferences.core.edit
import com.newoether.agora.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ImageQualitySettingsTest {
    @Test
    fun defaultsPresetsAndRetainedCustomValuesSurviveNewSettingsOwners() = runTest {
        withImageQualityTestSettings { manager, context ->
            assertEquals(ImageQualitySettings(), manager.imageQuality.settings.first())
            manager.imageQuality.update(ImageQualitySettings.CUSTOM, 3072, 91)
            assertEquals(ImageQualitySettings("custom", 3072, 91), SettingsManager(context).imageQuality.settings.first())
            for ((mode, edge, jpeg) in listOf(Triple("light", 1024, 75), Triple("balanced", 2048, 80), Triple("high", 4096, 95))) {
                manager.imageQuality.update(mode = mode)
                val value = SettingsManager(context).imageQuality.settings.first()
                assertEquals(edge, value.maxLongEdge)
                assertEquals(jpeg, value.jpegQuality)
                assertEquals(3072, value.customMaxLongEdge)
                assertEquals(91, value.customJpegQuality)
            }
            manager.imageQuality.update(mode = "custom")
            assertEquals(3072, manager.imageQuality.settings.first().maxLongEdge)
            assertEquals(91, manager.imageQuality.settings.first().jpegQuality)
        }
    }

    @Test
    fun settingsSurviveClosingAndReopeningTheFileDataStore() = runTest {
        val directory = Files.createTempDirectory("agora-image-quality-reopen").toFile()
        try {
            withImageQualityTestSettings(storageDirectory = directory) { manager, _ ->
                manager.imageQuality.update("custom", 3328, 93)
            }
            withImageQualityTestSettings(storageDirectory = directory) { manager, _ ->
                assertEquals(ImageQualitySettings("custom", 3328, 93), manager.imageQuality.settings.first())
                manager.imageQuality.update("light")
            }
            withImageQualityTestSettings(storageDirectory = directory) { manager, _ ->
                assertEquals(ImageQualitySettings("light", 3328, 93), manager.imageQuality.settings.first())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun readsAndWritesNormalizeModeAndClampNumericBoundsWithoutWritingOnRead() = runTest {
        withImageQualityTestSettings { manager, context ->
            context.dataStore.edit {
                it[IMAGE_QUALITY_MODE] = "unknown"
                it[IMAGE_QUALITY_MAX_LONG_EDGE] = Int.MAX_VALUE
                it[IMAGE_QUALITY_JPEG_QUALITY] = Int.MIN_VALUE
            }
            assertEquals(ImageQualitySettings("balanced", 8192, 1), manager.imageQuality.settings.first())
            assertEquals("unknown", context.dataStore.data.first()[IMAGE_QUALITY_MODE])
            manager.imageQuality.update("custom", -1, 101)
            assertEquals(ImageQualitySettings("custom", 128, 100), manager.imageQuality.settings.first())
            val before = context.dataStore.data.first()
            manager.imageQuality.update()
            assertEquals(before, context.dataStore.data.first())
        }
    }

    @Test
    fun exportMergePartialRestoreAndReplaceOlderArchivesRespectFieldOwnership() = runTest {
        withImageQualityTestSettings { manager, context ->
            manager.imageQuality.update("high", 3000, 89)
            val exported = PortableSettingsArchive.toJsonObject(manager, false)
            assertEquals(JsonPrimitive("high"), exported["imageQualityMode"])
            assertEquals(JsonPrimitive(3000), exported["imageQualityMaxLongEdge"])
            assertEquals(JsonPrimitive(89), exported["imageQualityJpegQuality"])
            val qualityArchive = JsonObject(exported.filterKeys { it.startsWith("imageQuality") })
            manager.imageQuality.update("light", 500, 20)
            restore(manager, qualityArchive)
            assertEquals(ImageQualitySettings("high", 3000, 89), manager.imageQuality.settings.first())
            restore(manager, JsonObject(emptyMap()))
            assertEquals(ImageQualitySettings("high", 3000, 89), manager.imageQuality.settings.first())
            restore(manager, buildJsonObject { put("imageQualityJpegQuality", 96) })
            assertEquals(ImageQualitySettings("high", 3000, 96), manager.imageQuality.settings.first())
            context.dataStore.edit { it[AUTO_BACKUP_ENABLED] = true }
            restore(manager, JsonObject(emptyMap()), replace = true)
            assertEquals(ImageQualitySettings(), SettingsManager(context).imageQuality.settings.first())
            assertNull(context.dataStore.data.first()[IMAGE_QUALITY_MODE])
            assertNull(context.dataStore.data.first()[IMAGE_QUALITY_MAX_LONG_EDGE])
            assertNull(context.dataStore.data.first()[IMAGE_QUALITY_JPEG_QUALITY])
            assertEquals(true, context.dataStore.data.first()[AUTO_BACKUP_ENABLED])
            restore(manager, buildJsonObject { put("imageQualityMode", "custom"); put("imageQualityMaxLongEdge", 4096) }, true)
            assertEquals(ImageQualitySettings("custom", 4096, 80), manager.imageQuality.settings.first())
        }
    }

    @Test
    fun invalidPortableTypesFailBeforeReplaceWritesAndIntegersNormalize() = runTest {
        withImageQualityTestSettings { manager, context ->
            manager.imageQuality.update("custom", 3000, 90)
            manager.saveAppLanguage("fr")
            val before = context.dataStore.data.first()
            val invalid = listOf(
                "imageQualityMode" to JsonPrimitive(1),
                "imageQualityMode" to JsonNull,
                "imageQualityMaxLongEdge" to JsonPrimitive("2048"),
                "imageQualityMaxLongEdge" to JsonPrimitive(2048.5),
                "imageQualityJpegQuality" to JsonPrimitive(true),
                "imageQualityJpegQuality" to JsonArray(emptyList()),
            )
            for ((key, value) in invalid) {
                try {
                    restore(manager, buildJsonObject { put(key, value); put("appLanguage", "en") }, true)
                    fail("Accepted invalid $key: $value")
                } catch (_: IllegalArgumentException) {
                    assertEquals(before, context.dataStore.data.first())
                }
            }
            restore(manager, buildJsonObject {
                put("imageQualityMode", "future-mode")
                put("imageQualityMaxLongEdge", 100000)
                put("imageQualityJpegQuality", -100)
            })
            assertEquals(ImageQualitySettings("balanced", 8192, 1), manager.imageQuality.settings.first())
        }
    }

    @Test
    fun repositoryInitialLoadPublishesDurableQualityAndPartialSettersKeepCustomValues() = runTest {
        withImageQualityTestSettings { manager, _ ->
            manager.imageQuality.update("custom", 3584, 92)
            val job = SupervisorJob()
            try {
                val repository = SettingsRepository(manager, CoroutineScope(coroutineContext + job))
                repository.awaitInitialLoad()
                assertEquals(ImageQualitySettings("custom", 3584, 92), repository.imageQuality.value)
                repository.updateImageQuality(mode = "light").join()
                assertEquals(ImageQualitySettings("light", 3584, 92), manager.imageQuality.settings.first())
                repository.updateImageQuality(customJpegQuality = 87).join()
                assertEquals(ImageQualitySettings("light", 3584, 87), manager.imageQuality.settings.first())
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    private suspend fun restore(manager: SettingsManager, archive: JsonObject, replace: Boolean = false) {
        PortableSettingsArchive.restoreFromJsonObject(archive, manager, replace, false, null) { it }
    }
}
