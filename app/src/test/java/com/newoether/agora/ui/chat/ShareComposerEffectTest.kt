package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.ShareRequest
import com.newoether.agora.SharedAttachmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareComposerEffectTest {
    @get:Rule val compose = createComposeRule()

    private val image = SharedAttachmentSource("/cache/a.png", "a.png", "image/png", 10L)

    @Test
    fun importsAttachmentsAppendsTextThenConsumesTheShare() {
        val field = TextFieldState()
        val imported = mutableListOf<String>()
        val consumed = mutableListOf<String>()
        val request = ShareRequest("share", "caption", listOf(image))
        compose.setContent {
            ShareComposerEffect(
                ownerId = com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID,
                draftLoaded = true,
                switching = false,
                request = request,
                textFieldState = field,
                onConsumed = consumed::add,
                persistText = { _, _ -> true },
                importAttachment = { _, attachment -> imported += attachment.uri; true },
                isFrozen = { false },
                acceptsLocalSandbox = { false },
                reportRejected = {},
            )
        }
        compose.runOnIdle {
            assertEquals(listOf("/cache/a.png"), imported)
            assertEquals("caption", field.text.toString())
            assertEquals(listOf("share"), consumed)
        }
    }

    @Test
    fun waitsForTheNewChatOwnerInsteadOfJoiningTheOpenConversation() {
        val field = TextFieldState()
        var owner by mutableStateOf("conversation")
        val imported = mutableListOf<String>()
        val consumed = mutableListOf<String>()
        compose.setContent {
            ShareComposerEffect(
                ownerId = owner,
                draftLoaded = true,
                switching = false,
                request = ShareRequest("share", "caption", listOf(image)),
                textFieldState = field,
                onConsumed = consumed::add,
                persistText = { _, _ -> true },
                importAttachment = { _, attachment -> imported += attachment.uri; true },
                isFrozen = { false },
                acceptsLocalSandbox = { false },
                reportRejected = {},
            )
        }
        compose.runOnIdle {
            assertTrue(imported.isEmpty())
            assertEquals("", field.text.toString())
            assertTrue(consumed.isEmpty())
            owner = com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
        }
        compose.runOnIdle {
            assertEquals(listOf("/cache/a.png"), imported)
            assertEquals("caption", field.text.toString())
            assertEquals(listOf("share"), consumed)
        }
    }

    @Test
    fun rejectedOnlyShareIsReportedAndConsumedWithoutTouchingTheOpenConversation() {
        val field = TextFieldState("existing draft")
        val rejected = mutableListOf<List<String?>>()
        val consumed = mutableListOf<String>()
        compose.setContent {
            ShareComposerEffect(
                ownerId = "conversation",
                draftLoaded = false,
                switching = true,
                request = ShareRequest("share", null, emptyList(), listOf("application/zip", null)),
                textFieldState = field,
                onConsumed = consumed::add,
                persistText = { _, _ -> true },
                importAttachment = { _, _ -> true },
                isFrozen = { false },
                acceptsLocalSandbox = { false },
                reportRejected = { rejected += it },
            )
        }
        compose.runOnIdle {
            assertEquals(listOf(listOf("application/zip", null)), rejected)
            assertEquals("existing draft", field.text.toString())
            assertEquals(listOf("share"), consumed)
        }
    }

    @Test
    fun refusedAttachmentIsRetriedOnceTheSubmissionReleases() {
        val field = TextFieldState()
        var frozen by mutableStateOf(true)
        var retryVersion by mutableStateOf(0L)
        val imported = mutableListOf<String>()
        val consumed = mutableListOf<String>()
        compose.setContent {
            ShareComposerEffect(
                ownerId = com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID,
                draftLoaded = true,
                switching = false,
                request = ShareRequest("share", null, listOf(image)),
                textFieldState = field,
                onConsumed = consumed::add,
                persistText = { _, _ -> true },
                importAttachment = { _, attachment -> imported += attachment.uri; !frozen },
                isFrozen = { frozen },
                acceptsLocalSandbox = { false },
                reportRejected = {},
                ingressRetryVersion = retryVersion,
            )
        }
        compose.runOnIdle {
            assertTrue(imported.isEmpty())
            assertTrue(consumed.isEmpty())
            frozen = false
            retryVersion = 1L
        }
        compose.runOnIdle {
            assertEquals(listOf("/cache/a.png"), imported)
            assertEquals(listOf("share"), consumed)
        }
    }
}
