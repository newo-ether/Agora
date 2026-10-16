package com.newoether.agora.api

import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.diagnostics.DiagnosticRequestContext
import okhttp3.MediaType.Companion.toMediaType
import com.newoether.agora.util.DebugLog
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object HttpClient {
    class RequestTrace(
        private val requestId: String,
        private val origin: String,
        private val diagnosticContext: DiagnosticRequestContext? = null,
    ) {
        private val startedAtNanos = System.nanoTime()
        private val markedStages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val exchangeOrdinal = java.util.concurrent.atomic.AtomicLong(0L)

        @Volatile
        private var currentDiagnosticContext = diagnosticContext

        internal fun child(requestKind: String, requestIdSuffix: String): RequestTrace {
            require(requestKind.isNotBlank())
            require(requestIdSuffix.isNotBlank())
            val childRequestId = "$requestId:$requestIdSuffix"
            return RequestTrace(
                requestId = childRequestId,
                origin = requestKind,
                diagnosticContext = diagnosticContext?.copy(
                    requestId = childRequestId,
                    requestKind = requestKind,
                ),
            )
        }

        internal fun beginHttpExchange(): DiagnosticRequestContext? {
            val base = diagnosticContext ?: return null
            val exchange = base.copy(
                requestId = base.requestId?.let {
                    it + ":http-" + exchangeOrdinal.incrementAndGet()
                },
            )
            currentDiagnosticContext = exchange
            return exchange
        }

        fun recordParsedEvent(event: StreamEvent) {
            DeveloperDiagnostics.recordParsedStreamEvent(currentDiagnosticContext, event)
        }

        fun mark(stage: String, detail: String = "") {
            val context = currentDiagnosticContext
            val stageIdentity = context?.requestId?.let { it + "|" + stage } ?: stage
            if (!markedStages.add(stageIdentity)) return
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            val suffix = detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            DebugLog.d(
                "AgoraTTFT",
                "[req=$requestId origin=$origin] stage=$stage elapsedMs=$elapsedMs$suffix",
            )
            DeveloperDiagnostics.recordHttpStage(
                context = context,
                stage = stage,
                elapsedMillis = elapsedMs,
                detail = detail,
            )
        }
    }

    data class TextResponse(
        val code: Int,
        val body: String,
        val isSuccessful: Boolean,
    )
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Header names that carry secret credentials across the providers.
    private val CREDENTIAL_HEADERS = setOf("authorization", "x-api-key", "x-goog-api-key", "api-key")

    /** True for loopback / RFC-1918 / link-local hosts, bare LAN hostnames
     *  (e.g. "ollama", "nas.local"), and Tailscale tailnet addresses. Public FQDNs like
     *  api.openai.com return false.
     *
     *  Tailscale counts as local because the tailnet is itself an encrypted WireGuard overlay:
     *  cleartext HTTP inside it is not cleartext on any wire. Recognizing it specifically is
     *  what lets a self-hosted provider work over http:// WITHOUT having to disable the
     *  credential guard for every public host as well. */
    internal fun isLocalHost(host: String): Boolean {
        if (host.isBlank()) return false
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".home") || h.endsWith(".internal")) return true
        // Tailscale MagicDNS: <machine>.<tailnet>.ts.net
        if (h == "ts.net" || h.endsWith(".ts.net")) return true
        // Tailscale IPv6 ULA range fd7a:115c:a1e0::/48
        if (h.startsWith("fd7a:115c:a1e0")) return true
        // Bare hostname with no dot → LAN name, not a public domain.
        if (!h.contains('.')) return true
        val o = h.split('.')
        if (o.size == 4 && o.all { it.toIntOrNull() in 0..255 }) {
            val a = o[0].toInt(); val b = o[1].toInt()
            return a == 127 || a == 10 || (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) || (a == 169 && b == 254) ||
                // Tailscale hands out 100.64.0.0/10 (RFC 6598 CGNAT space) to tailnet nodes.
                (a == 100 && b in 64..127)
        }
        return false
    }

    /** Fail-closed guard: never transmit API credentials over cleartext HTTP to a
     *  non-local host. LAN / loopback / Tailscale endpoints (Ollama, self-hosted) stay allowed. */
    internal fun guardCleartextCredentials(url: String, headers: Map<String, String>) {
        if (!url.startsWith("http://", ignoreCase = true)) return
        val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
        if (isLocalHost(host)) return
        if (headers.keys.any { it.lowercase() in CREDENTIAL_HEADERS }) {
            throw IOException(
                "Refusing to send API credentials over cleartext HTTP to a non-local host. " +
                    "Use an https:// endpoint, or reach it over LAN/Tailscale."
            )
        }
    }

    // ── Network proxy ─────────────────────────────────────────────────────
    /** HTTP = plaintext CONNECT proxy; HTTPS = the same protocol carried over TLS to the
     *  proxy itself (e.g. Caddy forward_proxy); SOCKS = SOCKS5. */
    enum class ProxyType { HTTP, HTTPS, SOCKS }

    /** Active proxy config, or null = direct connection. Read live by the proxy
     *  selector, so changing it takes effect immediately without rebuilding the client. */
    data class ProxyConfig(
        val type: ProxyType,
        val host: String,
        val port: Int,
        val username: String = "",
        val password: String = "",
        /** Hosts/CIDRs that bypass the proxy (e.g. localhost, 192.168.0.0/16). */
        val bypass: List<String> = emptyList()
    )

    @Volatile private var proxyConfig: ProxyConfig? = null

    /** Apply (or clear) the proxy. Also installs a default [java.net.Authenticator] for
     *  SOCKS proxy auth, which OkHttp's proxyAuthenticator does not cover. */
    fun setProxy(config: ProxyConfig?) {
        proxyConfig = config?.takeIf { it.host.isNotBlank() && it.port in 1..65535 }
        val cfg = proxyConfig
        if (cfg != null && cfg.username.isNotBlank()) {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication? =
                    if (requestorType == RequestorType.PROXY)
                        java.net.PasswordAuthentication(cfg.username, cfg.password.toCharArray())
                    else null
            })
        } else {
            java.net.Authenticator.setDefault(null)
        }
    }

    private fun resolveProxy(host: String): java.net.Proxy {
        val cfg = proxyConfig ?: return java.net.Proxy.NO_PROXY
        if (isProxyBypassed(host, cfg.bypass)) return java.net.Proxy.NO_PROXY
        // java.net.Proxy has no HTTPS type: an HTTPS proxy is an HTTP proxy whose socket is
        // upgraded to TLS by [proxySocketFactory] before OkHttp speaks to it.
        val type = if (cfg.type == ProxyType.SOCKS) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
        return java.net.Proxy(type, java.net.InetSocketAddress.createUnresolved(cfg.host, cfg.port))
    }

    /** Wraps the TCP connection to the proxy in TLS while [ProxyType.HTTPS] is active. Reads
     *  [proxyConfig] live, like [proxySelector], so it needs no client rebuild. */
    private val proxySocketFactory = ProxyTlsSocketFactory {
        proxyConfig
            ?.takeIf { it.type == ProxyType.HTTPS }
            ?.let { ProxyTlsSocketFactory.Target(it.host, it.port) }
    }

    /** True if [host] matches a bypass entry: exact host, `*.suffix` wildcard, or IPv4 CIDR. */
    private fun isProxyBypassed(host: String, bypass: List<String>): Boolean {
        if (host.isBlank()) return true
        val h = host.lowercase().trim('[', ']')
        for (raw in bypass) {
            val entry = raw.trim().lowercase()
            when {
                entry.isEmpty() -> continue
                entry.contains('/') -> if (ipv4InCidr(h, entry)) return true
                entry.startsWith("*.") -> if (h == entry.drop(2) || h.endsWith(entry.drop(1))) return true
                else -> if (h == entry) return true
            }
        }
        return false
    }

    private fun ipv4ToLong(ip: String): Long? {
        val o = ip.split('.')
        if (o.size != 4) return null
        var v = 0L
        for (p in o) { val n = p.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n.toLong() }
        return v
    }

    private fun ipv4InCidr(host: String, cidr: String): Boolean {
        val parts = cidr.split('/')
        if (parts.size != 2) return false
        val bits = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return false
        val ipL = ipv4ToLong(host) ?: return false
        val netL = ipv4ToLong(parts[0]) ?: return false
        val mask = if (bits == 0) 0L else (-1L shl (32 - bits)) and 0xFFFFFFFFL
        return (ipL and mask) == (netL and mask)
    }

    private val proxySelector = object : java.net.ProxySelector() {
        override fun select(uri: java.net.URI?): MutableList<java.net.Proxy> =
            mutableListOf(resolveProxy(uri?.host ?: ""))
        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, e: IOException?) {}
    }

    private val proxyAuthenticator = object : okhttp3.Authenticator {
        override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): Request? {
            val cfg = proxyConfig
            if (cfg == null || cfg.username.isBlank() || cfg.type == ProxyType.SOCKS) return null
            if (response.request.header("Proxy-Authorization") != null) return null // already tried
            return response.request.newBuilder()
                .header("Proxy-Authorization", okhttp3.Credentials.basic(cfg.username, cfg.password))
                .build()
        }
    }

    private val traceEventListenerFactory = EventListener.Factory { call ->
        object : EventListener() {
            private fun mark(stage: String, detail: String = "") {
                call.request().tag(RequestTrace::class.java)?.mark(stage, detail)
            }
            override fun dnsStart(call: Call, domainName: String) = mark("dns_start")
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) =
                mark("dns_end", "addresses=${inetAddressList.size}")
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
                mark("connect_start", "proxy=${proxy.type()}")
            override fun secureConnectStart(call: Call) = mark("tls_start")
            override fun secureConnectEnd(call: Call, handshake: Handshake?) =
                mark("tls_end", "version=${handshake?.tlsVersion}")
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
                mark("connect_end", "protocol=$protocol")
            override fun requestBodyStart(call: Call) = mark("request_body_start")
            override fun requestBodyEnd(call: Call, byteCount: Long) = mark("request_body_end", "bytes=$byteCount")
            override fun responseHeadersStart(call: Call) = mark("response_headers_start")
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .eventListenerFactory(traceEventListenerFactory)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .socketFactory(proxySocketFactory)
        .proxySelector(proxySelector)
        .proxyAuthenticator(proxyAuthenticator)
        .build()

    /** Every currently-live streaming handle in the process. Per-conversation scopes additionally
     * index their own handles for targeted Stop; this global set remains the process-shutdown
     * backstop. Registered on open by [streamPost], removed on [StreamHandle.close]. */
    private val liveHandles: MutableSet<StreamHandle> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    class StreamHandle(
        private val call: okhttp3.Call,
        private val scope: com.newoether.agora.viewmodel.StreamScope?,
        private val trace: RequestTrace?,
        private val diagnosticContext: DiagnosticRequestContext?,
        private val maxLineBytes: Long? = null,
        private val maxErrorBytes: Long? = null,
    ) : com.newoether.agora.viewmodel.GenerationCancelHandle {
        private val response = AtomicReference<okhttp3.Response?>(null)
        private val cancelled = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val firstLineObserved = AtomicBoolean(false)
        private val wireLineNumber = java.util.concurrent.atomic.AtomicLong(0L)

        val code: Int get() = checkNotNull(response.get()) { "Response headers are not available" }.code
        val source: BufferedSource? get() = response.get()?.body?.source()
        val errorBody: String?
            get() = try {
                val openedResponse = response.get()
                val body = openedResponse?.body?.let { responseBody ->
                    maxErrorBytes?.let { responseBody.source().readBoundedWireText(it) }
                        ?: responseBody.string()
                }
                if (openedResponse != null && body != null) {
                    DeveloperDiagnostics.recordHttpResponseBody(
                        context = diagnosticContext,
                        code = openedResponse.code,
                        body = body,
                    )
                }
                body
            } catch (_: Exception) {
                null
            }

        internal fun attach(openedResponse: okhttp3.Response) {
            if (cancelled.get() || closed.get()) {
                openedResponse.close()
                throw IOException("Streaming request was cancelled before response headers")
            }
            check(response.compareAndSet(null, openedResponse)) { "Response is already attached" }
            if (cancelled.get() || closed.get()) {
                response.getAndSet(null)?.close()
                throw IOException("Streaming request was cancelled while attaching response")
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            HttpClient.liveHandles.remove(this)
            scope?.unregister(this)
            // Cancel the Call even on normal completion: a no-op when already done,
            // but guarantees any blocked readLine() on this handle is woken the moment
            // the response is torn down rather than waiting on the read timeout.
            runCatching { call.cancel() }
            response.getAndSet(null)?.close()
        }

        fun readLine(): String? {
            val line = source?.let { input ->
                if (maxLineBytes != null) input.readBoundedWireLine(maxLineBytes) else input.readUtf8Line()
            }
            if (line != null) {
                DeveloperDiagnostics.recordWireLine(
                    context = diagnosticContext,
                    lineNumber = wireLineNumber.incrementAndGet(),
                    line = line,
                )
                if (firstLineObserved.compareAndSet(false, true)) {
                    trace?.mark("first_wire_line", "chars=${line.length}")
                }
            }
            return line
        }
        /**
         * Shortens subsequent blocking reads on this one response. OpenAI-compatible SSE streams
         * use this after a semantic terminal `finish_reason`: a bounded grace window accepts the
         * optional trailing usage chunk / `[DONE]`, but peer socket closure is no longer required
         * to complete the generation.
         */
        fun setReadTimeoutMillis(timeoutMillis: Long) {
            require(timeoutMillis > 0L)
            source?.timeout()?.timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        }
        /** Cancel the underlying HTTP call immediately — unblocks [readLine]. */
        override fun cancel() {
            cancelled.set(true)
            call.cancel()
            response.get()?.close()
        }
    }

    fun streamPost(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): StreamHandle =
        streamPost(url, jsonBody, headers, scope = boundStreamScope())

    /**
     * Open a streaming POST. If [scope] is non-null, the handle is registered there so a
     * per-conversation Stop can cancel exactly this generation's streams without severing
     * another conversation's in-flight stream. If null, the handle is only in the global
     * [liveHandles] set cancelled by [cancelAllStreams].
     *
     * GenerationManager binds the scope to its coroutine context through [withStreamScope].
     * Child `withContext` calls inherit that binding, while parallel conversation coroutines keep
     * independent values even when they execute on the same pooled thread.
     */
    fun streamPost(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        scope: com.newoether.agora.viewmodel.StreamScope?,
        callClient: okhttp3.Call.Factory = client,
        maxLineBytes: Long? = null,
        maxErrorBytes: Long? = null,
    ): StreamHandle = streamPostBody(
        url = url,
        body = jsonBody.toRequestBody(JSON),
        headers = headers,
        diagnosticBody = jsonBody,
        scope = scope,
        callClient = callClient,
        maxLineBytes = maxLineBytes,
        maxErrorBytes = maxErrorBytes,
    )

    fun streamPostBody(
        url: String,
        body: RequestBody,
        headers: Map<String, String> = emptyMap(),
        diagnosticBody: String? = null,
    ): StreamHandle = streamPostBody(
        url = url,
        body = body,
        headers = headers,
        diagnosticBody = diagnosticBody,
        scope = boundStreamScope(),
    )

    fun streamPostBody(
        url: String,
        body: RequestBody,
        headers: Map<String, String> = emptyMap(),
        diagnosticBody: String? = null,
        scope: com.newoether.agora.viewmodel.StreamScope?,
        callClient: okhttp3.Call.Factory = client,
        maxLineBytes: Long? = null,
        maxErrorBytes: Long? = null,
    ): StreamHandle {
        guardCleartextCredentials(url, headers)
        val trace = boundRequestTrace()
        val diagnosticContext = trace?.beginHttpExchange()
        DeveloperDiagnostics.recordHttpRequest(
            context = diagnosticContext,
            method = "POST",
            url = url,
            headers = headers,
            body = diagnosticBody ?: "{\"streaming_body\":true}",
        )
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val request = requestBuilder.build().newBuilder()
            .tag(RequestTrace::class.java, trace)
            .build()
        val call = callClient.newCall(request)
        val handle = StreamHandle(call, scope, trace, diagnosticContext, maxLineBytes, maxErrorBytes)
        // Register before execute(): Stop must be able to cancel DNS, connect, TLS, request upload,
        // and response-header wait rather than only an already-open response body.
        if (scope != null) scope.register(handle)
        liveHandles.add(handle)
        return try {
            val bodyBytes = runCatching { body.contentLength() }.getOrDefault(-1L)
            trace?.mark(
                "http_execute",
                "bodyBytes=$bodyBytes",
            )
            val response = call.execute()
            handle.attach(response)
            trace?.mark("response_headers", "code=${response.code}")
            handle
        } catch (t: Throwable) {
            handle.close()
            throw t
        }
    }

    private val coroutineStreamScope =
        ThreadLocal<com.newoether.agora.viewmodel.StreamScope?>()
    private val coroutineRequestTrace = ThreadLocal<RequestTrace?>()

    /** Bind [scope] to the current generation coroutine. Thread-context propagation keeps the
     * value stable across suspensions/dispatcher hops without exposing process-global mutable
     * ownership to parallel conversations. */
    internal suspend fun <T> withStreamScope(
        scope: com.newoether.agora.viewmodel.StreamScope?,
        requestTrace: RequestTrace? = null,
        block: suspend () -> T,
    ): T = withContext(
        coroutineStreamScope.asContextElement(scope) +
            coroutineRequestTrace.asContextElement(requestTrace)
    ) {
        block()
    }

    internal fun boundStreamScope(): com.newoether.agora.viewmodel.StreamScope? =
        coroutineStreamScope.get()

    internal fun boundRequestTrace(): RequestTrace? = coroutineRequestTrace.get()

    /** Cancel EVERY in-flight streaming Call in the process — the "stop everything" backstop
     *  (e.g. ViewModel cleared). Per-conversation Stop should use the conversation's
     *  [com.newoether.agora.viewmodel.StreamScope] instead, so it doesn't kill another
     *  conversation's generation. */
    fun cancelAllStreams() {
        liveHandles.toList().forEach { runCatching { it.cancel() } }
        liveHandles.clear()
    }

    private fun newCall(
        request: Request,
        callTimeoutMillis: Long?,
        readTimeoutMillis: Long? = null,
    ): okhttp3.Call {
        val callClient = readTimeoutMillis?.let { timeoutMillis ->
            require(timeoutMillis > 0L)
            client.newBuilder()
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .build()
        } ?: client
        return callClient.newCall(request).also { call ->
            callTimeoutMillis?.let {
                require(it > 0L)
                call.timeout().timeout(it, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun post(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        callTimeoutMillis: Long? = null,
        readTimeoutMillis: Long? = null,
    ): String? {
        guardCleartextCredentials(url, headers)
        val body = jsonBody.toRequestBody(JSON)
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = newCall(
            request = requestBuilder.build(),
            callTimeoutMillis = callTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
        ).execute()
        return response.use {
            if (it.isSuccessful) it.body?.string()
            else {
                DebugLog.e("HttpClient", "POST failed status=${it.code}")
                null
            }
        }
    }

    fun postTextResponse(
        url: String,
        bodyText: String,
        headers: Map<String, String> = emptyMap(),
        callTimeoutMillis: Long? = null,
    ): TextResponse {
        guardCleartextCredentials(url, headers)
        val body = bodyText.toRequestBody(JSON)
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        return newCall(requestBuilder.build(), callTimeoutMillis).execute().use { response ->
            TextResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
                isSuccessful = response.isSuccessful,
            )
        }
    }

    fun fetchModels(
        url: String,
        headers: Map<String, String> = emptyMap(),
        callTimeoutMillis: Long? = null,
    ): String? = fetchModelsResponse(url, headers, callTimeoutMillis)
        .takeIf(TextResponse::isSuccessful)
        ?.body

    fun fetchModelsResponse(
        url: String,
        headers: Map<String, String> = emptyMap(),
        callTimeoutMillis: Long? = null,
    ): TextResponse {
        guardCleartextCredentials(url, headers)
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = newCall(requestBuilder.build(), callTimeoutMillis).execute()
        return response.use {
            TextResponse(
                code = it.code,
                body = it.body.string(),
                isSuccessful = it.isSuccessful,
            )
        }
    }

    fun getTextResponse(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): TextResponse {
        guardCleartextCredentials(url, headers)
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        return client.newCall(requestBuilder.build()).execute().use { response ->
            TextResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
                isSuccessful = response.isSuccessful,
            )
        }
    }

    /** GET raw bytes (e.g. an image referenced by URL). Returns null on failure. */
    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        callTimeoutMillis: Long? = null,
        readTimeoutMillis: Long? = null,
    ): ByteArray? {
        guardCleartextCredentials(url, headers)
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = newCall(
            request = requestBuilder.build(),
            callTimeoutMillis = callTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
        ).execute()
        return response.use {
            if (it.isSuccessful) it.body?.bytes() else null
        }
    }
}
