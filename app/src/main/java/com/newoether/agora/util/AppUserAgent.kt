package com.newoether.agora.util

import okhttp3.Interceptor

/**
 * Process-wide client identity for every outbound HTTP request.
 *
 * OkHttp's own default (`okhttp/x.y.z`) says nothing about the app. A single application
 * interceptor installed on each transport is the only place that can cover provider streaming,
 * model discovery, web search/fetch, MCP, remote Filo and update checks without every call site
 * remembering to set the header.
 */
object AppUserAgent {
    // Version comes from the package manager at startup rather than BuildConfig, because the
    // module does not enable buildConfig. "?" keeps requests valid before Application.onCreate
    // runs and in JVM tests.
    @Volatile
    private var headerValue: String = "Agora/?"

    fun initialize(versionName: String?) {
        headerValue = "Agora/" + (versionName?.takeIf { it.isNotBlank() } ?: "?")
    }

    /** Identity token (`Agora/<version>`) for User-Agents that must embed it in a larger string. */
    val token: String get() = headerValue

    /**
     * Adds the Agora identity only when the call did not set its own User-Agent: `web_fetch`,
     * the DuckDuckGo scraper and provider-specific code must be able to present a different
     * value (browser-like UAs are required by some sites and services).
     */
    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.header("User-Agent") != null) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header("User-Agent", headerValue).build())
        }
    }
}
