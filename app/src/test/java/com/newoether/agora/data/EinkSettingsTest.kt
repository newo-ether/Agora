package com.newoether.agora.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import com.newoether.agora.util.SecretCrypto
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EinkSettingsTest {
    @Test
    fun modePersistsRoundTripsAndNeverOverwritesOrdinaryAppearance() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("agora-eink-settings").toFile()
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory
        val movesClass = "androidx.datastore.core.FileMoves_androidKt"
        val atomicMove = Class.forName(movesClass).getDeclaredMethod("atomicMoveTo", File::class.java, File::class.java)
        mockkStatic(movesClass)
        every { atomicMove.invoke(null, any<File>(), any<File>()) } answers {
            java.nio.file.Files.move(firstArg<File>().toPath(), secondArg<File>().toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        }
        try {
            mockkObject(SecretCrypto)
            every { SecretCrypto.encrypt(any()) } answers { firstArg<String>() }
            every { SecretCrypto.decrypt(any()) } answers { firstArg<String>() }
            val manager = SettingsManager(context)
            assertFalse(manager.eInkMode.first())
            manager.saveThemeMode("DARK")
            manager.saveReduceMotion(false)
            manager.saveBlurEffectsEnabled(true)
            manager.saveEInkMode(true)
            assertTrue(SettingsManager(context).eInkMode.first())
            val archive = PortableSettingsArchive.toJsonObject(manager, false)
            assertEquals("true", archive.getValue("eInkMode").jsonPrimitive.content)
            manager.saveEInkMode(false)
            assertEquals("DARK", manager.themeMode.first())
            assertTrue(manager.blurEffectsEnabled.first())
            assertFalse(manager.reduceMotion.first())
            PortableSettingsArchive.restoreFromJsonObject(archive, manager, false, false, null) { it }
            assertTrue(manager.eInkMode.first())
            PortableSettingsArchive.restoreFromJsonObject(JsonObject(emptyMap()), manager, false, false, null) { it }
            assertTrue(manager.eInkMode.first()) // Old MERGE archives have no opinion.
            PortableSettingsArchive.restoreFromJsonObject(JsonObject(emptyMap()), manager, true, false, null) { it }
            assertFalse(manager.eInkMode.first()) // Old REPLACE archives restore the default.
        } finally {
            unmockkObject(SecretCrypto)
            unmockkStatic(movesClass)
            directory.deleteRecursively()
        }
    }
}
