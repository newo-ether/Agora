package com.newoether.agora.ui.chat

import java.net.URI

/** Host resources and generated-content requests use one exact HTTPS allowlist. */
internal object InteractiveAnswerWebPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val HOST_URL = "$ORIGIN/interactive/host.html"
    val assets = setOf("host.html", "host.css", "host.js", "design-system.js", "websandbox.js", "eink-profile.js")
    private val externalHosts = setOf(
        "cdnjs.cloudflare.com", "esm.sh", "cdn.jsdelivr.net", "unpkg.com",
        "basemap.nationalmap.gov", "upload.wikimedia.org", "thumb.wikimedia.org",
    )

    fun assetName(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!safeHttps(uri) || uri.host != "appassets.androidplatform.net" || uri.rawQuery != null) return null
        val name = uri.rawPath.removePrefix("/interactive/")
        return name.takeIf { uri.rawPath == "/interactive/$it" && it in assets }
    }

    /** Websandbox's inert bootstrap documents stay inside a sandboxed subframe. */
    fun allowsNavigation(url: String, mainFrame: Boolean): Boolean =
        !mainFrame && url.substringBefore('#') in setOf("about:blank", "about:srcdoc")

    fun inlineHeight(value: Double): Int? = if (value.isFinite() && value > 0) {
        kotlin.math.ceil(value).toInt().coerceIn(160, 900)
    } else null

    fun allowsNetwork(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return safeHttps(uri) && uri.host in externalHosts
    }

    fun allowsLink(url: String): Boolean = runCatching {
        val uri = URI(url)
        safeHttps(uri) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    fun prompt(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.length <= 4000 }

    private fun safeHttps(uri: URI): Boolean =
        uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
}
