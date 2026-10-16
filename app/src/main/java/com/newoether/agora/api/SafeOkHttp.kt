package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import okhttp3.OkHttpClient
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Creates OkHttpClient.Builder with explicit TLS config that works on Android 16+.
 */
object SafeOkHttp {
    private val trustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    private val sslSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(emptyArray(), arrayOf(trustManager), null)
        ctx.socketFactory
    }

    fun builder(): OkHttpClient.Builder = try {
        OkHttpClient.Builder().sslSocketFactory(sslSocketFactory, trustManager)
    } catch (e: Exception) {
        DebugLog.e("SafeOkHttp", "TLS setup failed, falling back to default builder")
        OkHttpClient.Builder()
    }
}
