package com.newoether.agora.util

import android.util.Base64
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.newoether.agora.remote.RemoteFailure
import com.newoether.agora.remote.classifyRemoteFailure
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConchEncryptedHttpTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun frame(kind: String, sequence: Long, body: ByteArray = byteArrayOf()): String {
        val value = buildJsonObject {
            put("_conch_event", kind); put("_conch_sequence", sequence)
            put("body", Base64.encodeToString(body, Base64.NO_WRAP))
        }
        return "event: $kind\ndata: ${ShellCrypto.encrypt(key, value.toString().toByteArray())}\n\n"
    }

    @Test fun authenticatedChunksReconstructExactBytesAndRequireTerminal() {
        val expected = byteArrayOf(0, -1, 127, -128)
        val input = Buffer().writeUtf8(frame("http_body", 0, expected) + frame("http_end", 1))
        val source = ConchHttpBodySource(ConchHttpFrames(input, key.copyOf()))
        val output = Buffer()
        assertEquals(4L, source.read(output, 100))
        assertEquals(-1L, source.read(output, 100))
        assertArrayEquals(expected, output.readByteArray())
        val truncated = ConchHttpBodySource(ConchHttpFrames(Buffer().writeUtf8(frame("http_body", 0, expected)), key.copyOf()))
        assertEquals(4L, truncated.read(Buffer(), 100))
        assertTrue(runCatching { truncated.read(Buffer(), 100) }.exceptionOrNull() is IOException)
    }

    @Test fun reorderedReplayedAndRenamedFramesAreRejected() {
        for (input in listOf(frame("http_body", 1), frame("http_body", 0).replace("event: http_body", "event: http_end"))) {
            assertTrue(runCatching { ConchHttpFrames(Buffer().writeUtf8(input), key.copyOf()).read() }.exceptionOrNull() is IOException)
        }
        val frames = ConchHttpFrames(Buffer().writeUtf8(frame("http_body", 0) + frame("http_body", 0)), key.copyOf())
        frames.read()
        assertTrue(runCatching { frames.read() }.exceptionOrNull() is IOException)
    }

    /** Fake peer: every request ends here, so the token check runs without a network. */
    private fun clientAnswering(calls: MutableList<String>, answer: (Request) -> Pair<Int, String>) = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false)
        .addInterceptor(ConchEncryptedHttp("a".repeat(64)))
        .addInterceptor { chain ->
            val request = chain.request()
            calls += request.url.encodedPath
            val (code, body) = answer(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    private fun signedHandshake(request: Request, signingToken: String = "a".repeat(64)): String {
        val publicKey = ShellCrypto.encodePublicKey(ShellCrypto.generateEphemeralKeyPair().public)
        val challenge = request.url.queryParameter("challenge").orEmpty()
        val nonce = "fixture-nonce"
        val features = "hkdf-extract-v2,response-auth-v1,sse-sequence-v1,rejection-auth-v1"
        fun signature(payload: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(signingToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal("$nonce|$payload".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        return buildJsonObject {
            put("public_key", publicKey); put("nonce", nonce); put("challenge", challenge); put("features", features)
            put("signature", signature(publicKey))
            put("features_signature", signature("conch-handshake-v2|$publicKey|$challenge|$features"))
        }.toString()
    }

    @Test fun wrongTokenIsAnAuthenticationFailureAndIsNotRetried() {
        val calls = mutableListOf<String>()
        val client = clientAnswering(calls) { request -> 200 to signedHandshake(request, "b".repeat(64)) }
        val error = runCatching { client.newCall(Request.Builder().url("http://filo.test/v1/info").build()).execute() }.exceptionOrNull()
        assertTrue(error is ConchAuthenticationException)
        assertEquals(RemoteFailure.AUTHENTICATION, classifyRemoteFailure(error as Exception))
        assertEquals(listOf("/public-key"), calls)
    }

    @Test fun rejectedHandshakeIsAnAuthenticationFailureAndIsNotRetried() {
        for (status in listOf(401, 403)) {
            val calls = mutableListOf<String>()
            val client = clientAnswering(calls) { status to "unauthorized" }
            val error = runCatching { client.newCall(Request.Builder().url("http://filo.test/v1/info").build()).execute() }.exceptionOrNull()
            assertTrue(error is ConchAuthenticationException)
            assertEquals(RemoteFailure.AUTHENTICATION, classifyRemoteFailure(error as Exception))
            assertEquals(listOf("/public-key"), calls)
        }
    }

    @Test fun rejectedEncryptedRequestIsAnAuthenticationFailureWithoutReplayingReadsOrWrites() {
        for (status in listOf(401, 403)) for (method in listOf("GET", "POST")) {
            val calls = mutableListOf<String>()
            val client = clientAnswering(calls) { request ->
                if (request.url.encodedPath == "/public-key") 200 to signedHandshake(request) else status to "unauthorized"
            }
            val request = Request.Builder().url("http://filo.test/v1/info")
                .method(method, if (method == "POST") "input".toRequestBody() else null).build()
            val error = runCatching { client.newCall(request).execute() }.exceptionOrNull()
            assertTrue(error is ConchAuthenticationException)
            assertEquals(RemoteFailure.AUTHENTICATION, classifyRemoteFailure(error as Exception))
            assertEquals(listOf("/public-key", "/e2e/request"), calls)
        }
    }

    @Test fun unsuccessfulHttpResponsesAreServiceFailuresAndNeverReplayWrites() {
        for (path in listOf("/public-key", "/e2e/request")) for (method in listOf("GET", "POST")) {
            val calls = mutableListOf<String>()
            val client = clientAnswering(calls) { request ->
                if (request.url.encodedPath == path) 502 to "bad gateway" else 200 to signedHandshake(request)
            }
            val request = Request.Builder().url("http://filo.test/v1/info")
                .method(method, if (method == "POST") "input".toRequestBody() else null).build()
            val error = runCatching { client.newCall(request).execute() }.exceptionOrNull()
            assertTrue(error is ConchServiceException)
            assertEquals(RemoteFailure.SERVICE, classifyRemoteFailure(error as Exception))
            val exchange = if (path == "/public-key") listOf(path) else listOf("/public-key", path)
            assertEquals(if (method == "GET") exchange + exchange else exchange, calls)
        }
    }

    @Test fun connectionFailuresRemainNetworkFailuresAndNeverReplayWrites() {
        for (failure in listOf(java.net.ConnectException("refused"), java.net.SocketTimeoutException("timeout"),
            java.net.UnknownHostException("unreachable"))) for (method in listOf("GET", "POST")) {
            val calls = mutableListOf<String>()
            val client = clientAnswering(calls) { throw failure }
            val request = Request.Builder().url("http://filo.test/v1/info")
                .method(method, if (method == "POST") "input".toRequestBody() else null).build()
            val error = runCatching { client.newCall(request).execute() }.exceptionOrNull()
            assertSame(failure, error)
            assertEquals(RemoteFailure.NETWORK, classifyRemoteFailure(error as Exception))
            assertEquals(if (method == "GET") listOf("/public-key", "/public-key") else listOf("/public-key"), calls)
        }
    }

    @Test fun originalGoGatewayInteroperatesForRawBytesAndErrors() {
        val url = System.getenv("CONCH_HTTP_FIXTURE_URL")
        assumeTrue("Isolated original Go gateway fixture is opt-in", !url.isNullOrBlank())
        val bytes = ByteArray(150_003) { it.toByte() }
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
            .addInterceptor(ConchEncryptedHttp("a".repeat(64))).build()
        for ((path, code) in listOf("/v1/echo" to 200, "/v1/error" to 409)) {
            client.newCall(Request.Builder().url(url!! + path)
                .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { response ->
                assertEquals(code, response.code)
                assertEquals("application/octet-stream", response.header("Content-Type"))
                assertArrayEquals(bytes, response.body.bytes())
            }
        }
    }
}
