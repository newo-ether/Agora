package com.newoether.agora.ui.settings

import com.newoether.agora.data.LocalChatModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class LocalChatModelEditorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val valid = LocalChatModelDraft(modelId = "model-1.0_q4", modelPath = "/model.gguf")

    @Test
    fun `new defaults fit context without changing serialized legacy defaults`() {
        val draft = LocalChatModelDraft.from(null)
        assertEquals("8192", draft.nCtx)
        assertEquals("4096", draft.maxTokens)
        assertEquals("1.0", draft.temperature)
        assertEquals("0.95", draft.topP)
        assertNull(valid.validate { false })
        val legacy = LocalChatModelConfig(modelId = "legacy", alias = "Legacy")
        assertEquals(2048, legacy.nCtx)
        assertEquals(4096, legacy.maxTokens)
        assertEquals("2048", LocalChatModelDraft.from(legacy).nCtx)
        assertEquals("4096", LocalChatModelDraft.from(legacy).maxTokens)
    }

    @Test
    fun `edit preserves record identity file and stored settings`() {
        val initial = LocalChatModelConfig(
            id = "record", modelId = "existing", alias = "Existing",
            localFilePath = "/existing.gguf", mmprojPath = "/original.gguf",
            nCtx = 8192, temperature = 0.4f, topP = 0.75f, maxTokens = 513,
        )
        val draft = LocalChatModelDraft.from(initial)
        assertEquals(initial, draft.toConfig(initial))
        val edited = draft.copy(modelId = "renamed", alias = "Renamed").toConfig(initial)
        assertEquals(initial.id, edited.id)
        assertEquals(initial.localFilePath, edited.localFilePath)
        assertEquals(initial.mmprojPath, edited.mmprojPath)
        assertEquals(initial.nCtx, edited.nCtx)
        assertEquals(initial.temperature, edited.temperature)
        assertEquals(initial.topP, edited.topP)
        assertEquals(initial.maxTokens, edited.maxTokens)
        assertEquals("renamed", edited.modelId)
    }

    @Test
    fun `IDs are required lowercase constrained trimmed and unique`() {
        assertEquals(LocalChatModelDraftError.ID_REQUIRED, valid.copy(modelId = " ").validate { false })
        listOf("Model", "a/b", "a:b", "a b", "模型").forEach { id ->
            assertEquals(id, LocalChatModelDraftError.ID_INVALID, valid.copy(modelId = id).validate { false })
        }
        assertEquals(LocalChatModelDraftError.ID_TAKEN, valid.validate { true })
        var checked = ""
        val trimmed = valid.copy(modelId = "  model-1.0_q4  ", alias = " ")
        assertNull(trimmed.validate { checked = it; false })
        assertEquals("model-1.0_q4", checked)
        assertEquals(checked, trimmed.toConfig(null).modelId)
        assertEquals(checked, trimmed.toConfig(null).alias)
    }

    @Test
    fun `file and positive integer limits are required`() {
        assertEquals(LocalChatModelDraftError.MODEL_REQUIRED, valid.copy(modelPath = "").validate { false })
        listOf("", "0", "-1", "1.5", "2147483648").forEach { value ->
            assertEquals(value, LocalChatModelDraftError.CONTEXT, valid.copy(nCtx = value).validate { false })
            assertEquals(value, LocalChatModelDraftError.MAX_TOKENS, valid.copy(maxTokens = value).validate { false })
        }
        assertNull(valid.copy(nCtx = "1", maxTokens = "1").validate { false })
    }

    @Test
    fun `sampling ranges reject non finite values and accept endpoints`() {
        listOf("", "-0.1", "2.1", "NaN", "Infinity", "-Infinity").forEach { value ->
            assertEquals(value, LocalChatModelDraftError.TEMPERATURE, valid.copy(temperature = value).validate { false })
        }
        listOf("", "-0.1", "1.1", "NaN", "Infinity", "-Infinity").forEach { value ->
            assertEquals(value, LocalChatModelDraftError.TOP_P, valid.copy(topP = value).validate { false })
        }
        assertNull(valid.copy(temperature = "0", topP = "0").validate { false })
        assertNull(valid.copy(temperature = "2", topP = "1").validate { false })
    }

    @Test
    fun `both new and existing drafts forbid output above context`() {
        assertEquals(
            LocalChatModelDraftError.OUTPUT_EXCEEDS_CONTEXT,
            valid.copy(nCtx = "1024", maxTokens = "1025").validate { false },
        )
        val initial = LocalChatModelConfig(modelId = "legacy", alias = "Legacy", localFilePath = "/legacy.gguf")
        val draft = LocalChatModelDraft.from(initial)
        assertEquals(LocalChatModelDraftError.OUTPUT_EXCEEDS_CONTEXT, draft.validate { false })
        assertNull(draft.copy(maxTokens = draft.nCtx).validate { false })
        assertEquals(4096, initial.maxTokens)
    }

    @Test
    fun `replacement discard and disposal never delete original or saved files`() {
        val deleted = mutableListOf<File>()
        val imports = LocalChatModelImports { deleted += it }
        val original = File("original.gguf")
        val first = File("first.gguf")
        val second = File("second.gguf")
        val saved = File("saved.gguf")
        imports.discard(original.path)
        assertTrue(imports.accept(first))
        imports.discard(first.path)
        assertTrue(imports.accept(second))
        assertTrue(imports.accept(saved))
        imports.transfer(saved.path, original.path)
        imports.close()
        imports.close()
        assertEquals(listOf(first, second), deleted)
        assertFalse(imports.accept(File("late.gguf")))
    }

    @Test
    fun `valid model and projector copies transfer or clean on disposal`() = runBlocking {
        val saved = temporaryFolder.newFile("model.gguf")
        val discarded = temporaryFolder.newFile("projector.gguf")
        val imports = LocalChatModelImports()
        val bytes = "GGUFpayload".toByteArray()
        assertTrue(copyLocalChatGguf(ByteArrayInputStream(bytes), saved, imports))
        assertTrue(copyLocalChatGguf(ByteArrayInputStream(bytes), discarded, imports))
        assertTrue(saved.readBytes().contentEquals(bytes))
        imports.transfer(saved.path)
        imports.close()
        assertTrue(saved.exists())
        assertFalse(discarded.exists())
    }

    @Test
    fun `invalid and truncated GGUF copies are removed`() = runBlocking {
        listOf("", "G", "GGU", "BAD!payload").forEachIndexed { index, content ->
            val dest = temporaryFolder.newFile("invalid-$index.gguf")
            assertFalse(copyLocalChatGguf(ByteArrayInputStream(content.toByteArray()), dest, LocalChatModelImports()))
            assertFalse(dest.exists())
        }
    }

    @Test
    fun `failed and cancelled copies clean partial file and close input`() = runBlocking {
        listOf(IOException("failed"), CancellationException("cancelled")).forEachIndexed { index, failure ->
            val dest = temporaryFolder.newFile("partial-$index.gguf")
            var closed = false
            var reads = 0
            val input = object : InputStream() {
                override fun read(): Int = throw failure
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ > 0) throw failure
                    "GGUF".toByteArray().copyInto(buffer, offset)
                    return 4
                }
                override fun close() { closed = true }
            }
            var thrown = false
            try {
                copyLocalChatGguf(input, dest, LocalChatModelImports())
            } catch (actual: Exception) {
                thrown = true
                assertEquals(failure, actual)
            }
            assertTrue(thrown)
            assertTrue(closed)
            assertFalse(dest.exists())
        }
    }

    @Test
    fun `completion after exit cannot retain a new file`() = runBlocking {
        val imports = LocalChatModelImports()
        val dest = temporaryFolder.newFile("late.gguf")
        val bytes = "GGUFpayload".toByteArray()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count < 0) imports.close()
                return count
            }
        }
        assertFalse(copyLocalChatGguf(input, dest, imports))
        assertFalse(dest.exists())
    }
}
