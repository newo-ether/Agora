package com.newoether.agora.util

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConchAndroidCryptoTest {
    @Test fun modernAndroidUsesTheDefaultXdhImplementation() {
        val alice = ShellCrypto.generateEphemeralKeyPair()
        assertEquals(KeyPairGenerator.getInstance("XDH").generateKeyPair().public.javaClass, alice.public.javaClass)
        assertEncryptedRoundTrip()
    }

    @Test @Config(sdk = [28]) fun legacyAndroidUsesBundledX25519WithoutRegisteringAProvider() {
        val alice = ShellCrypto.generateEphemeralKeyPair()
        assertTrue(alice.public.javaClass.name.startsWith("org.bouncycastle."))
        assertEncryptedRoundTrip()
    }

    private fun assertEncryptedRoundTrip() {
        val providers = Security.getProviders().toList()
        val alice = ShellCrypto.generateEphemeralKeyPair()
        val bob = ShellCrypto.generateEphemeralKeyPair()
        val aliceKey = ShellCrypto.deriveAesKey(alice.private, ShellCrypto.decodePublicKey(ShellCrypto.encodePublicKey(bob.public)))
        val bobKey = ShellCrypto.deriveAesKey(bob.private, ShellCrypto.decodePublicKey(ShellCrypto.encodePublicKey(alice.public)))
        assertArrayEquals(aliceKey, bobKey)
        val plaintext = "Pi encrypted Android connection".toByteArray()
        assertArrayEquals(plaintext, ShellCrypto.decrypt(bobKey, ShellCrypto.encrypt(aliceKey, plaintext)))
        assertEquals(providers, Security.getProviders().toList())
    }

    @Test fun cryptoSetupFailureReachesAsyncCallbackWithoutRetryOrMutation() {
        val publicKey = ShellCrypto.encodePublicKey(ShellCrypto.generateEphemeralKeyPair().public)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val challenge = exchange.requestURI.rawQuery.orEmpty().substringAfter("challenge=", "")
            val body = Json.encodeToString(signedConchFixture(publicKey, challenge)).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .addInterceptor(ConchEncryptedHttp("isolated-fixture-key")).build()
        val completed = CountDownLatch(1)
        val failure = AtomicReference<IOException>()
        server.start()
        mockkObject(ShellCrypto)
        try {
            every { ShellCrypto.generateEphemeralKeyPair() } throws NoSuchAlgorithmException("Missing crypto")
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/v1/info").build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) { failure.set(error); completed.countDown() }
                    override fun onResponse(call: Call, response: Response) { response.close(); completed.countDown() }
                })
            assertTrue("Callback must settle instead of crashing the dispatcher", completed.await(10, TimeUnit.SECONDS))
            assertTrue(failure.get() is ConchChannelException)
            assertTrue(failure.get().cause is NoSuchAlgorithmException)
            assertEquals(1, requests.get())
        } finally {
            unmockkObject(ShellCrypto)
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
