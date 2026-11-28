package com.newoether.agora.ui.chat

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.ProcessTextInbox
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.viewmodel.AttachmentImportProcessor
import com.newoether.agora.viewmodel.ComposerDraftController
import com.newoether.agora.viewmodel.ComposerDraftPersistence
import com.newoether.agora.viewmodel.ConversationComposerController
import com.newoether.agora.viewmodel.ConversationWorkspaceStore
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Fresh controllers and TextFields restore from a closed/reopened production Room draft. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class ProcessTextComposerRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: ChatDatabase
    private val scopes = mutableListOf<CoroutineScope>()

    @Before fun setUp() = runBlocking {
        app.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(app)
        database.chatDao().upsertConversation(ChatEntity("owner", "owner", lastUpdated = 1))
        database.chatDao().updateDraft("owner", "old disk draft", null, 1)
    }

    @After fun close() {
        stopControllers()
        database.close()
    }

    @Test fun failedWriteRestoresPendingSelectionOntoOldDiskDraftWithFreshController() {
        var failWrites = true
        val first = controller { failWrites }
        var controller by mutableStateOf(first)
        var field by mutableStateOf(TextFieldState(load(first)))
        var inbox by mutableStateOf(inbox())
        var loaded by mutableStateOf(true)
        var attempts = 0
        compose.setContent {
            val request by inbox.requests.collectAsState()
            ProcessTextComposerEffect("owner", loaded, false, request.firstOrNull(), field,
                onConsumed = inbox::consume,
                persistText = { owner, text ->
                    controller.persistIngressText(owner, text).also { attempts += 1 }
                })
        }
        compose.waitUntil { attempts > 0 }
        lateinit var pending: android.os.Bundle
        compose.runOnIdle {
            assertEquals("old disk draft\n\nselected text", field.text.toString())
            assertEquals(1, inbox.requests.value.size)
            pending = inbox.save()
            loaded = false
        }
        compose.waitForIdle()
        assertEquals("old disk draft", runBlocking { database.chatDao().getConversation("owner") }?.draftText)
        reopen()
        failWrites = false
        val fresh = controller { failWrites }
        val restoredField = TextFieldState(load(fresh))
        val restoredInbox = ProcessTextInbox().apply { restore(pending) }
        compose.runOnIdle {
            controller = fresh
            field = restoredField
            inbox = restoredInbox
            loaded = true
        }
        // Apply the replacement field/inbox before awaiting asynchronous Room persistence.
        compose.waitForIdle()
        compose.waitUntil { restoredInbox.requests.value.isEmpty() }
        compose.runOnIdle {
            assertEquals("old disk draft\n\nselected text", restoredField.text.toString())
        }
        reopen()
        assertEquals("old disk draft\n\nselected text", load(controller { false }))
    }

    @Test fun consumedIngressRestoresCommittedTextWithFreshControllerAndEmptyInbox() {
        val first = controller { false }
        val field = TextFieldState(load(first))
        val inbox = inbox()
        compose.setContent {
            val requests by inbox.requests.collectAsState()
            ProcessTextComposerEffect("owner", true, false, requests.firstOrNull(), field,
                onConsumed = inbox::consume, persistText = first::persistIngressText)
        }
        compose.waitUntil { inbox.requests.value.isEmpty() }
        val saved = inbox.save()
        reopen()
        val fresh = controller { false }
        val restoredField = TextFieldState(load(fresh))
        val restoredInbox = ProcessTextInbox().apply { restore(saved) }
        assertTrue(restoredInbox.requests.value.isEmpty())
        assertEquals("old disk draft\n\nselected text", restoredField.text.toString())
    }

    @Test fun savedIngressFromBeforeCommitCanReplayWithoutDurableRequestReceipt() {
        val pending = inbox()
        val staleSavedState = pending.save()
        val first = controller { false }
        val firstText = appendSelectedText(load(first), pending.requests.value.single().text)
        assertTrue(runBlocking { first.persistIngressText("owner", firstText) })
        pending.consume(pending.requests.value.single().id)
        reopen()
        val fresh = controller { false }
        val restoredInbox = ProcessTextInbox().apply { restore(staleSavedState) }
        val restoredField = TextFieldState(load(fresh))
        compose.setContent {
            val requests by restoredInbox.requests.collectAsState()
            ProcessTextComposerEffect("owner", true, false, requests.firstOrNull(), restoredField,
                onConsumed = restoredInbox::consume, persistText = fresh::persistIngressText)
        }
        compose.waitUntil { restoredInbox.requests.value.isEmpty() }
        compose.runOnIdle {
            assertEquals("old disk draft\n\nselected text\n\nselected text", restoredField.text.toString())
        }
    }

    @Test fun frozenSubmissionReleaseWithoutEditingDrainsBothAskRequests() {
        val controller = controller { false }
        val field = TextFieldState(load(controller))
        val state = runBlocking { controller.state("owner") }
        assertNotNull(runBlocking {
            controller.freezeSubmission("owner", 7L, field.text.toString(), emptyList())
        })
        val retryVersion = state.value.ingressRetryVersion
        var attempts = 0
        val inbox = inbox()
        inbox.receive(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, "second selection"))
        val ids = inbox.requests.value.map { it.id }
        val consumed = mutableListOf<String>()
        compose.setContent {
            val requests by inbox.requests.collectAsState()
            val snapshot by state.collectAsState()
            ProcessTextComposerEffect("owner", true, false, requests.firstOrNull(), field,
                onConsumed = { id -> consumed += id; inbox.consume(id) },
                persistText = { owner, text ->
                    controller.persistIngressText(owner, text).also { attempts += 1 }
                }, ingressRetryVersion = snapshot.ingressRetryVersion)
        }
        compose.waitUntil { attempts > 0 }
        compose.runOnIdle {
            assertEquals("old disk draft\n\nselected text", field.text.toString())
            assertEquals(ids, inbox.requests.value.map { it.id })
            assertTrue(consumed.isEmpty())
        }
        assertTrue(runBlocking { controller.releaseSubmission("owner", 7L) })
        assertEquals(retryVersion + 1L, state.value.ingressRetryVersion)
        compose.waitUntil { inbox.requests.value.isEmpty() }
        compose.runOnIdle {
            assertEquals(ids, consumed)
            assertEquals("old disk draft\n\nselected text\n\nsecond selection", field.text.toString())
        }
        reopen()
        assertEquals("old disk draft\n\nselected text\n\nsecond selection", load(controller { false }))
    }

    private fun controller(failWrites: () -> Boolean): ConversationComposerController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        val repository = ConversationRepository(database.chatDao(), database, scheduleMaintenance = {})
        val workspace = ConversationWorkspaceStore(repository,
            mockk<SettingsRepository>(relaxed = true),
            mockk<ConversationSettingsTransferCoordinator>(relaxed = true), scope)
        val persistence = object : ComposerDraftPersistence by workspace {
            override suspend fun updateDraft(ownerId: String, text: String, attachmentsJson: String?) {
                check(!failWrites()) { "Injected draft write failure" }
                workspace.updateDraft(ownerId, text, attachmentsJson)
            }
        }
        return ConversationComposerController(scope,
            ComposerDraftController(persistence, repository), AttachmentImportProcessor(app))
    }

    private fun load(controller: ConversationComposerController): String =
        runBlocking { controller.loadSelected("owner").text }

    private fun stopControllers() = runBlocking {
        val jobs = scopes.mapNotNull { it.coroutineContext[Job] }
        scopes.forEach { it.cancel() }
        // Room collectors must finish before their connection pool is closed or reopened.
        jobs.joinAll()
        scopes.clear()
    }

    private fun reopen() {
        stopControllers()
        database.close()
        database = ChatDatabase.build(app)
    }

    private fun inbox() = ProcessTextInbox().apply {
        receive(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, "selected text"))
    }
}
