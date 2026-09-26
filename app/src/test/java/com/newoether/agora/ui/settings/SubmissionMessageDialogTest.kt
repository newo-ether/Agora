package com.newoether.agora.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.api.HttpClient
import com.newoether.agora.ui.components.SubmissionMessageDialog
import com.newoether.agora.util.SubmissionMessage
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en")
class SubmissionMessageDialogTest {
    @get:Rule val compose = createComposeRule()
    @After fun cleanup() = unmockkObject(HttpClient)

    @Test fun aboutSubmissionSendsPackageAndDisplaysPlainTextMessage() {
        var sent = ""
        configureResponse { sent = it }
        showRating()
        submit()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Service Reply").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("<b>Received</b>").assertIsDisplayed()
        assertEquals("org.example.mobile", JSONObject(sent).getString("app"))
        assertTrue(JSONObject(sent).has("versionCode"))
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Service Reply").assertDoesNotExist()
    }

    @Test fun scheduledSubmissionHandsMessageToHostBeforeFormDisappears() {
        configureResponse()
        var completed = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            MaterialTheme {
                var form by remember { mutableStateOf(true) }
                var message by remember { mutableStateOf<SubmissionMessage?>(null) }
                if (form) {
                    Box(Modifier.width(360.dp).height(1200.dp).verticalScroll(rememberScrollState())) {
                        RatingForm { reply ->
                            completed = true
                            message = reply
                            form = false
                        }
                    }
                }
                message?.let { reply -> SubmissionMessageDialog(reply) { message = null } }
            }
        }
        submit()
        compose.waitUntil(10_000) { completed }
        compose.onNodeWithText("Your Name (optional)").assertDoesNotExist()
        compose.onNodeWithText("Service Reply").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Service Reply").assertDoesNotExist()
    }

    private fun configureResponse(onRequest: (String) -> Unit = {}) {
        val id = UUID.randomUUID().toString()
        mockkObject(HttpClient)
        every { HttpClient.client } returns OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body?.writeTo(buffer)
            onRequest(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"message":{"id":"$id","title":"Service Reply","body":"<b>Received</b>","buttonText":"Done"}}""".toResponseBody())
                .build()
        }.build()
    }

    private fun showRating() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        org.robolectric.Shadows.shadowOf(base.packageManager).installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = "org.example.mobile"
                versionName = "test"
            }
        )
        val context = object : ContextWrapper(base) {
            override fun getPackageName() = "org.example.mobile"
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(1200.dp).verticalScroll(rememberScrollState())) {
                        RatingForm()
                    }
                }
            }
        }
    }

    private fun submit() {
        compose.onAllNodes(hasClickAction() and hasContentDescription("5", substring = true))
            .onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Submit").performScrollTo().performClick()
    }
}
