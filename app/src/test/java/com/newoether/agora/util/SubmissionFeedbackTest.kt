package com.newoether.agora.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.api.HttpClient
import com.newoether.agora.data.claimSubmissionMessage
import com.newoether.agora.data.SettingsManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SubmissionFeedbackTest {
    private val payload = """{"message":{"id":"sample-v1","title":"Update","body":"Received","buttonText":"Done"}}"""

    @After fun cleanup() = unmockkObject(HttpClient)

    @Test fun acceptedResponsesTolerateAbsentAndInvalidOptionalMessages() {
        for (body in listOf("", "{}", """{"message":null}""", "not json",
            """{"message":{"id":4,"title":"Update","body":"Text"}}""",
            """{"message":{"id":"x","title":" ","body":"Text"}}""", "x".repeat(70_000))) {
            respond(200, body)
            assertEquals(SubmissionResponse(true), submitFeedback("https://example.test/rating", "{}"))
        }
        respond(204, "")
        assertEquals(SubmissionResponse(true), submitFeedback("https://example.test/crash", "{}"))
    }

    @Test fun acceptedMessageIsParsedButHttpRejectionAndNetworkFailureNeverShowIt() {
        respond(200, payload)
        assertEquals(SubmissionMessage("sample-v1", "Update", "Received", "Done"),
            submitFeedback("https://example.test/rating", "{}").message)
        respond(403, payload)
        assertEquals(SubmissionResponse(false), submitFeedback("https://example.test/rating", "{}"))
        mockkObject(HttpClient)
        every { HttpClient.client } returns OkHttpClient.Builder().addInterceptor {
            throw IOException("offline")
        }.build()
        assertEquals(SubmissionResponse(false), submitFeedback("https://example.test/rating", "{}"))
    }

    @Test fun crashSubmissionAddsRuntimePackageToLegacyReportAndKeepsOtherFields() {
        var sent = ""
        respond(200, payload) { sent = it }
        val result = CrashReporter.submit("""{"trace":"example","appVersion":"1"}""", "org.example.mobile")
        assertTrue(result.accepted)
        val json = JSONObject(sent)
        assertEquals("org.example.mobile", json.getString("packageName"))
        assertEquals("example", json.getString("trace"))
        assertEquals("1", json.getString("appVersion"))
        assertEquals("sample-v1", result.message?.id)
        assertFalse(CrashReporter.submit("invalid", "org.example.mobile").accepted)
    }

    @Test fun messageClaimsAreAtomicAcrossOwnersAndSurvivePortableSettingsReset() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val manager = SettingsManager(context)
        val claims = (1..12).map {
            async(Dispatchers.IO) { claimSubmissionMessage(context, id) }
        }.awaitAll()
        assertEquals(1, claims.count { it })
        manager.resetPortableSettingsForImport()
        assertFalse(claimSubmissionMessage(context, id))
        assertTrue(claimSubmissionMessage(context, id + "-next"))
    }

    private fun respond(code: Int, body: String, request: (String) -> Unit = {}) {
        mockkObject(HttpClient)
        every { HttpClient.client } returns OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body?.writeTo(buffer)
            request(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body.toResponseBody()).build()
        }.build()
    }
}
