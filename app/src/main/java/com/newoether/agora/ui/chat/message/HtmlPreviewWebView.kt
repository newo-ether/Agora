package com.newoether.agora.ui.chat.message

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** Owns only the untrusted browser lifecycle. No native object is exposed to page JavaScript. */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal class HtmlPreviewWebView(
    context: Context,
    document: HtmlPreviewDocument,
    private val resources: HtmlPreviewResources,
    private val onFailure: () -> Unit,
    private val onUnsupported: () -> Unit,
) : WebView(context) {
    private var released = false
    private var admitted = false
    private val probeUrl = "${document.origin}/probe.html"
    private val probeTimeout = Runnable {
        if (!released && !admitted) {
            release()
            onUnsupported()
        }
    }

    init {
        // Compose's holder otherwise supplies WRAP_CONTENT. WebView then forces Blink's
        // layout height to zero, collapsing the full-height sandbox iframe despite measurement.
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkLoads = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            saveFormData = false
        }
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                resources.response(request.url.toString(), request.method, request.isForMainFrame)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowedFrame = request.url.toString() == document.frameUrl ||
                    (!admitted && request.url.toString() in setOf("${document.origin}/probe-control.html", "${document.origin}/probe-blocked.html"))
                if (!request.isForMainFrame && allowedFrame && request.method == "GET") return false
                val uri = request.url
                if (request.hasGesture() && request.method == "GET" &&
                    uri.scheme in setOf("https", "http") && uri.host != null &&
                    uri.userInfo == null && !uri.host.orEmpty().endsWith(".preview.agora.invalid")) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                release()
                onFailure()
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (released || admitted || view.url != probeUrl || title != HtmlPreviewProbe.COMPLETE_TITLE) return
                view.evaluateJavascript(HtmlPreviewProbe.RESULT_SCRIPT) { result ->
                    if (released || admitted || view.url != probeUrl) return@evaluateJavascript
                    if (resources.admit(result)) {
                        admitted = true
                        removeCallbacks(probeTimeout)
                        loadUrl(document.rootUrl)
                    } else {
                        release()
                        onUnsupported()
                    }
                }
            }
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null)
                return true
            }
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, message: Message): Boolean = false
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.cancel()
                return true
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.cancel()
                return true
            }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
                result.cancel()
                return true
            }
        }
        setDownloadListener { _, _, _, _, _ -> }
        setOnLongClickListener { true }
        loadUrl(probeUrl)
        postDelayed(probeTimeout, 5_000)
    }

    fun release() {
        if (released) return
        released = true
        removeCallbacks(probeTimeout)
        resources.close()
        (parent as? ViewGroup)?.removeView(this)
        stopLoading()
        onPause()
        removeAllViews()
        destroy()
    }
}
