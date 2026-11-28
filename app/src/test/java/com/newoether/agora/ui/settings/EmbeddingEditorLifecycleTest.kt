package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.newoether.agora.R
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.RagManager
import com.newoether.agora.viewmodel.RemoteEmbeddingConnectionTester
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h800dp-mdpi")
class EmbeddingEditorLifecycleTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    @Test
    fun `each failed save emits one snackbar without inline error persistence or draft mutation`() {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        val ragManager = mockk<RagManager>(relaxed = true)
        val requests = mutableListOf<List<String>>()
        val tester = RemoteEmbeddingConnectionTester(
            resolveApiKey = { "" },
            resolveBaseUrl = { error("The editor must pass its URL") },
            computeEmbedding = { _, key, model, url ->
                requests += listOf(key, model, url)
                null
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        every { viewModel.ragManager } returns ragManager
        every { ragManager.resolveEmbeddingKeyForProviderExact(any()) } returns null
        every { viewModel.remoteEmbeddingConnectionTester } returns tester
        var exits = 0
        val initial = EmbeddingModelConfig(name = "", type = EmbeddingModelType.REMOTE)
        compose.setContent {
            MaterialTheme {
                EmbeddingModelEditor(
                    initial = initial,
                    isNew = true,
                    viewModel = viewModel,
                    cacheRow = null,
                    onCache = {},
                    onRecache = {},
                    onBack = { exits++ },
                    showDocFab = false,
                )
            }
        }
        val model = "draft-embedding-model"
        val modelField = compose.onNode(hasSetTextAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("text-embedding-3-small"),
        ))
        modelField.performTextReplacement(model)
        val draftField = compose.onNode(hasSetTextAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(model),
        ))
        val passwordField = compose.onNode(
            hasSetTextAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
        )
        passwordField.performTextReplacement("draft-key")
        val save = compose.onNodeWithContentDescription(context.getString(R.string.save))
        val message = context.getString(
            R.string.embedding_test_failed,
            "Request failed. Check API key, URL, and model name.",
        )
        repeat(2) { attempt ->
            save.assertIsEnabled().performClick()
            compose.waitForIdle()
            verify(exactly = attempt + 1) { viewModel.emitSnackbar(message, null, null) }
            verify(exactly = attempt + 1) { viewModel.emitSnackbar(any(), any(), any()) }
            compose.onNodeWithText(message, useUnmergedTree = true).assertDoesNotExist()
            draftField.assertTextEquals(model)
            save.assertIsEnabled()
            compose.runOnIdle {
                assertEquals(0, exits)
                assertEquals(attempt + 1, requests.size)
                assertEquals("draft-key", requests.last()[0])
                assertEquals(model, requests.last()[1])
                assertEquals(embeddingProviderPresets().first().baseUrl, requests.last()[2])
            }
            verify(exactly = 0) { ragManager.addEmbeddingModel(any()) }
            verify(exactly = 0) { ragManager.updateEmbeddingModel(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `discard suppresses a late failed probe while the editor remains composed`() {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        val ragManager = mockk<RagManager>(relaxed = true)
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<FloatArray?>()
        var probe: Job? = null
        val tester = RemoteEmbeddingConnectionTester(
            resolveApiKey = { "" },
            resolveBaseUrl = { error("The editor must pass its URL") },
            computeEmbedding = { _, _, _, _ ->
                probe = currentCoroutineContext()[Job]
                started.complete(Unit)
                withContext(NonCancellable) { completion.await() }
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        every { viewModel.ragManager } returns ragManager
        every { ragManager.resolveEmbeddingKeyForProviderExact(any()) } returns null
        every { viewModel.remoteEmbeddingConnectionTester } returns tester
        var exits = 0
        val initial = EmbeddingModelConfig(name = "", type = EmbeddingModelType.REMOTE)
        compose.setContent {
            MaterialTheme {
                EmbeddingModelEditor(
                    initial = initial,
                    isNew = true,
                    viewModel = viewModel,
                    cacheRow = null,
                    onCache = {},
                    onRecache = {},
                    onBack = { exits++ },
                    showDocFab = false,
                )
            }
        }
        compose.onNode(hasSetTextAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("text-embedding-3-small"),
        ))
            .performTextReplacement("discarded-model")
        compose.onNodeWithContentDescription(context.getString(R.string.save)).performClick()
        compose.runOnIdle { assertTrue(started.isCompleted) }
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.onNodeWithText(context.getString(R.string.unsaved_changes_discard)).performClick()
        compose.runOnIdle {
            assertEquals(1, exits)
            assertNotNull(probe)
            assertTrue(probe!!.isCancelled)
            completion.complete(null)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(probe!!.isCompleted) }
        compose.onNode(hasSetTextAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("discarded-model"),
        )).assertExists()
        verify(exactly = 0) { viewModel.emitSnackbar(any(), any(), any()) }
        verify(exactly = 0) { ragManager.addEmbeddingModel(any()) }
        verify(exactly = 0) { ragManager.updateEmbeddingModel(any(), any(), any(), any()) }
    }

    @Test
    fun `discard cancels probe and rejects late success while parent scope stays alive`() = runBlocking {
        val lifecycle = EmbeddingEditorLifecycle()
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<String?>()
        var probe: Job? = null
        var persisted = 0
        lifecycle.testConnection(
            scope = this,
            test = {
                probe = currentCoroutineContext()[Job]
                started.complete(Unit)
                withContext(NonCancellable) { completion.await() }
            },
            onResult = { persisted++ },
        )
        started.await()
        lifecycle.close()
        assertTrue(lifecycle.closed)
        assertNotNull(probe)
        assertTrue(probe!!.isCancelled)
        completion.complete("OK")
        probe!!.join()
        assertEquals(0, persisted)
        assertTrue(currentCoroutineContext()[Job]!!.isActive)
    }

    @Test
    fun `open editor delivers results and permits another completed probe`() = runBlocking {
        val lifecycle = EmbeddingEditorLifecycle()
        val results = mutableListOf<String?>()
        coroutineScope {
            lifecycle.testConnection(this, test = { "Failed" }, onResult = { results += it })
        }
        coroutineScope {
            lifecycle.testConnection(this, test = { "OK" }, onResult = { results += it })
        }
        assertEquals(listOf("Failed", "OK"), results)
        lifecycle.close()
    }

    @Test
    fun `concurrent and post exit probes are not started`() = runBlocking {
        val lifecycle = EmbeddingEditorLifecycle()
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<String?>()
        var probes = 0
        var results = 0
        coroutineScope {
            lifecycle.testConnection(
                scope = this,
                test = {
                    probes++
                    started.complete(Unit)
                    completion.await()
                },
                onResult = { results++ },
            )
            started.await()
            lifecycle.testConnection(this, test = { probes++; "OK" }, onResult = { results++ })
            completion.complete("OK")
        }
        lifecycle.close()
        lifecycle.close()
        coroutineScope {
            lifecycle.testConnection(this, test = { probes++; "OK" }, onResult = { results++ })
        }
        assertEquals(1, probes)
        assertEquals(1, results)
    }

    @Test
    fun `close deletes accepted but unsaved embedding copy`() = runBlocking {
        val lifecycle = EmbeddingEditorLifecycle()
        val dest = temporaryFolder.newFile("embedding-unsaved.gguf")
        assertTrue(copyLocalChatGguf(ByteArrayInputStream("GGUFpayload".toByteArray()), dest, lifecycle.imports))
        assertTrue(dest.exists())
        lifecycle.close()
        lifecycle.close()
        assertFalse(dest.exists())
    }

    @Test
    fun `transfer before close preserves saved file and never owns existing model`() = runBlocking {
        val lifecycle = EmbeddingEditorLifecycle()
        val original = temporaryFolder.newFile("existing-model.gguf")
        val dest = temporaryFolder.newFile("embedding-saved.gguf")
        val bytes = "GGUFpayload".toByteArray()
        assertTrue(copyLocalChatGguf(ByteArrayInputStream(bytes), dest, lifecycle.imports))
        lifecycle.imports.transfer(dest.absolutePath)
        lifecycle.imports.discard(original.absolutePath)
        lifecycle.imports.discard(dest.absolutePath)
        lifecycle.close()
        assertTrue(original.exists())
        assertTrue(dest.readBytes().contentEquals(bytes))
    }

    @Test
    fun `replacement deletes only the previous draft owned copy`() = runBlocking {
        val lifecycle = EmbeddingEditorLifecycle()
        val original = temporaryFolder.newFile("existing.gguf")
        val first = temporaryFolder.newFile("embedding-first.gguf")
        val replacement = temporaryFolder.newFile("embedding-replacement.gguf")
        val bytes = "GGUFpayload".toByteArray()
        assertTrue(copyLocalChatGguf(ByteArrayInputStream(bytes), first, lifecycle.imports))
        assertTrue(copyLocalChatGguf(ByteArrayInputStream(bytes), replacement, lifecycle.imports))
        lifecycle.imports.discard(original.absolutePath)
        lifecycle.imports.discard(first.absolutePath)
        assertFalse(first.exists())
        assertTrue(original.exists())
        assertTrue(replacement.exists())
        lifecycle.imports.transfer(replacement.absolutePath)
        lifecycle.close()
        assertTrue(original.exists())
        assertTrue(replacement.exists())
    }

    @Test
    fun `late import completion after exit cannot retain file`() = runBlocking {
        val lifecycle = EmbeddingEditorLifecycle()
        val dest = temporaryFolder.newFile("embedding-late.gguf")
        val input = object : ByteArrayInputStream("GGUFpayload".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count < 0) lifecycle.close()
                return count
            }
        }
        assertFalse(copyLocalChatGguf(input, dest, lifecycle.imports))
        assertTrue(lifecycle.closed)
        assertFalse(dest.exists())
    }

    @Test
    fun `failed or cancelled replacement removes partial file but preserves current draft`() = runBlocking {
        val lifecycle = EmbeddingEditorLifecycle()
        val current = temporaryFolder.newFile("embedding-current.gguf")
        assertTrue(copyLocalChatGguf(ByteArrayInputStream("GGUFcurrent".toByteArray()), current, lifecycle.imports))
        listOf(IOException("failed"), CancellationException("cancelled")).forEachIndexed { index, failure ->
            val partial = temporaryFolder.newFile("embedding-partial-$index.gguf")
            var reads = 0
            var closed = false
            val input = object : InputStream() {
                override fun read(): Int = throw failure
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ > 0) throw failure
                    "GGUF".toByteArray().copyInto(buffer, offset)
                    return 4
                }
                override fun close() { closed = true }
            }
            var thrown: Exception? = null
            try {
                copyLocalChatGguf(input, partial, lifecycle.imports)
            } catch (actual: Exception) {
                thrown = actual
            }
            assertEquals(failure, thrown)
            assertTrue(closed)
            assertFalse(partial.exists())
            assertTrue(current.exists())
        }
        lifecycle.close()
        assertFalse(current.exists())
    }

    @Test
    fun `coroutine cancellation during copy closes input and removes unsaved partial file`() = runBlocking {
        val lifecycle = EmbeddingEditorLifecycle()
        val partial = temporaryFolder.newFile("embedding-cancelled.gguf")
        var closed = false
        val copy = launch {
            val job = currentCoroutineContext()[Job]!!
            var reads = 0
            val input = object : InputStream() {
                override fun read(): Int = error("Bulk reads expected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ > 0) {
                        job.cancel()
                        return -1
                    }
                    "GGUF".toByteArray().copyInto(buffer, offset)
                    return 4
                }
                override fun close() { closed = true }
            }
            copyLocalChatGguf(input, partial, lifecycle.imports)
        }
        copy.join()
        assertTrue(copy.isCancelled)
        assertTrue(closed)
        assertFalse(partial.exists())
        lifecycle.close()
    }
}
