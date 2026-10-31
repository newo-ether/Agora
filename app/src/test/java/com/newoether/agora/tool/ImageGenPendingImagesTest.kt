package com.newoether.agora.tool

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenPendingImagesTest {
    @Test
    fun `generated image is returned by the owning tool result without a pending queue`() {
        val provider = source("tool/ImageGenToolProvider.kt")
        val executor = source("viewmodel/GenerationToolExecutor.kt")
        val batch = source("viewmodel/GenerationToolBatchEffectExecutor.kt")
        val store = source("tool/ToolImageStore.kt")
        val processor = source("util/ImageArtifactProcessor.kt")
        val httpClient = source("api/HttpClient.kt")

        assertTrue(provider.contains("override fun executeEvents("))
        assertTrue(provider.contains("images = listOfNotNull(textAndImage.second)"))
        assertTrue(provider.contains("imageStore.persistGeneratedBytes("))
        assertTrue(provider.contains("callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS"))
        assertTrue(
            Regex("readTimeoutMillis = Constants\\.IMAGE_GENERATION_TIMEOUT_MS")
                .findAll(provider)
                .count() == 2,
        )
        assertTrue(httpClient.contains("readTimeoutMillis: Long? = null"))
        assertTrue(httpClient.contains("client.newBuilder()"))
        assertTrue(httpClient.contains(".readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)"))
        assertTrue(store.contains("ToolImageAttachment = persistBytes(bytes, \"image/unknown\", filePrefix)"))
        assertTrue(store.contains("return processor.fromSource("))
        assertTrue(processor.contains("bounds.outWidth <= 0 || bounds.outHeight <= 0"))
        assertTrue(processor.contains("?: throw IOException(\"Image decode failed\")"))
        assertFalse(store.contains("?: \"image/png\""))
        assertFalse(provider.contains("PendingImagesByConversation"))
        assertFalse(provider.contains("drainImages("))
        assertFalse(executor.contains("drainGeneratedImages("))
        assertFalse(batch.contains("generatedImages"))
    }

    private fun source(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(
                directory,
                "app/src/main/java/com/newoether/agora/$relativePath",
            )
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            directory = directory.parentFile ?: return@repeat
        }
        error("Unable to locate Agora main sources")
    }
}
