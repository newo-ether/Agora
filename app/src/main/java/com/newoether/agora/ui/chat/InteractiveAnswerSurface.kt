package com.newoether.agora.ui.chat

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import android.content.Intent
import android.net.Uri
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.newoether.agora.ui.components.EinkPageControls
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.newoether.agora.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.newoether.agora.templates.TemplateFileRequest
import kotlinx.serialization.json.JsonElement
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.DebugLog.InteractiveStage
import com.newoether.agora.R
import com.newoether.agora.model.InteractiveAnswer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream

/** Vertical gestures begun in an interface stay in its browser; horizontal drawer gestures can yield. */
internal class InteractiveAnswerTouchHost(context: Context) : FrameLayout(context) {
    private var touching = false
    private var controlOwnsGesture = false
    private var downX = 0f
    private var downY = 0f
    private var verticalGesture: Boolean? = null
    private var canPrevious = false
    private var canNext = false
    private var awaitingTouchBounds = true
    private var lastY = 0f
    private var scrollDirection = 0
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    fun updateScrollBounds(previous: Boolean, next: Boolean, activeTouch: Boolean) {
        canPrevious = previous
        canNext = next
        if (touching && activeTouch) awaitingTouchBounds = false
        if (touching && verticalGesture == true && !ownsGesture()) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    private fun ownsGesture(): Boolean = touching && (controlOwnsGesture || verticalGesture == null ||
        verticalGesture == true && (awaitingTouchBounds || if (scrollDirection < 0) canPrevious else canNext))

    fun claimControlGesture() {
        if (!touching) return
        controlOwnsGesture = true
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touching = true
            controlOwnsGesture = false
            downX = event.x
            downY = event.y
            verticalGesture = null
            awaitingTouchBounds = true
            lastY = event.y
            scrollDirection = 0
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && verticalGesture == null) {
            val dx = kotlin.math.abs(event.x - downX)
            val dy = kotlin.math.abs(event.y - downY)
            if (maxOf(dx, dy) > touchSlop) verticalGesture = dy >= dx
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (event.y != lastY) scrollDirection = if (event.y < lastY) 1 else -1
            lastY = event.y
        }
        val ownsGesture = ownsGesture()
        val touchParent = parent
        val finished = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (!finished) touchParent?.requestDisallowInterceptTouchEvent(ownsGesture)
        return try {
            super.dispatchTouchEvent(event)
            true
        } finally {
            if (finished) {
                touching = false
                controlOwnsGesture = false
                touchParent?.requestDisallowInterceptTouchEvent(false)
            } else {
                touchParent?.requestDisallowInterceptTouchEvent(ownsGesture)
            }
            // Inner frame geometry decides boundary handoff, not the outer WebView scroll range.
        }
    }
}

@Composable
internal fun InteractiveAnswerSurface(
    answer: InteractiveAnswer,
    inline: Boolean,
    modifier: Modifier = Modifier,
    onSent: () -> Unit = {},
    promptPrefix: String = "",
    confirmPrompt: Boolean = true,
    allowSubmission: Boolean = true,
    inlineHeight: Int = 320,
    onHeightChanged: (Int) -> Unit = {},
    onFileAccess: suspend (TemplateFileRequest) -> JsonElement = { error("Connect a template folder first") },
    onSend: suspend (InteractivePrompt) -> Boolean,
) {
    val eInkMode = LocalAgoraMotionPolicy.current.eInkMode
    val fontScale = LocalDensity.current.fontScale
    val latestMode by rememberUpdatedState(eInkMode)
    val latestFontScale by rememberUpdatedState(fontScale)
    var canPrevious by remember(answer) { mutableStateOf(false) }
    var canNext by remember(answer) { mutableStateOf(false) }
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingInteractivePrompt?>(null) }
    var error by remember { mutableStateOf(false) }
    var loaded by remember(answer) { mutableStateOf(false) }
    val showPages = eInkMode && answer.eInkAware && loaded && (canPrevious || canNext)
    val latestHeightChanged by rememberUpdatedState(onHeightChanged)
    val fileScope = rememberCoroutineScope()
    val latestFileAccess by rememberUpdatedState(onFileAccess)
    var fileRequests by remember(answer) { mutableStateOf(0) }
    val latestSend by rememberUpdatedState(onSend)
    val latestAllowSubmission by rememberUpdatedState(allowSubmission)
    val latestSent by rememberUpdatedState(onSent)
    val latestPrefix by rememberUpdatedState(promptPrefix)
    val webView = remember(answer) { WebView(context) }
    val touchHost = remember(webView) { InteractiveAnswerTouchHost(context) }
    // Revokes queued bridge callbacks before teardown, including callbacks from disposed viewers.
    val alive = remember(webView) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val destroyed = remember(webView) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val ports = remember(webView) { mutableListOf<WebMessagePort>() }
    var connected by remember(webView) { mutableStateOf(false) }
    LaunchedEffect(webView, connected, eInkMode, fontScale) {
        if (alive.get() && connected) ports.firstOrNull()?.postMessage(WebMessage(buildJsonObject {
            put("type", "display"); put("eInkMode", eInkMode); put("fontScale", fontScale)
        }.toString()))
    }
    fun page(direction: String) {
        if (alive.get() && loaded && eInkMode && answer.eInkAware) {
            ports.firstOrNull()?.postMessage(WebMessage(buildJsonObject {
                put("type", "page"); put("direction", direction)
            }.toString()))
        }
    }
    // This deadline is failure detection for a hung renderer, never a substitute for ready.
    LaunchedEffect(webView) {
        kotlinx.coroutines.delay(15000)
        if (!loaded) {
            DebugLog.interactiveStage(InteractiveStage.READY_TIMEOUT, inline)
            error = true
        }
    }
    LaunchedEffect(error) {
        if (error) {
            alive.set(false)
            pending = null
            ports.forEach { runCatching { it.close() } }
            if (destroyed.compareAndSet(false, true)) {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }
    DisposableEffect(webView) {
        onDispose {
            alive.set(false)
            ports.forEach { runCatching { it.close() } }
            if (destroyed.compareAndSet(false, true)) {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }
    Box(modifier.then(if (error) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        if (error) {
            Column {
                Text(stringResource(R.string.interactive_answer_error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                Text(answer.fallback, Modifier.padding(16.dp))
            }
        } else {
            Column {
            AndroidView(factory = {
                webView.apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setDownloadListener { _, _, _, _, _ -> }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            DebugLog.interactiveStage(InteractiveStage.CONSOLE, inline,
                                consoleLevel = message.messageLevel().ordinal, line = message.lineNumber())
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !InteractiveAnswerWebPolicy.allowsNavigation(request.url.toString(), request.isForMainFrame)

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            val asset = InteractiveAnswerWebPolicy.assetName(url)
                            if (asset != null) {
                                val mime = when {
                                    asset.endsWith(".html") -> "text/html"
                                    asset.endsWith(".css") -> "text/css"
                                    else -> "application/javascript"
                                }
                                return WebResourceResponse(mime, "utf-8", context.assets.open("interactive/$asset"))
                            }
                            if (InteractiveAnswerWebPolicy.allowsNavigation(url, request.isForMainFrame) ||
                                (!request.isForMainFrame && InteractiveAnswerWebPolicy.allowsNetwork(url))) return null
                            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (url != InteractiveAnswerWebPolicy.HOST_URL || ports.isNotEmpty() || !alive.get()) return
                            val channel = view.createWebMessageChannel()
                            ports.add(channel[0])
                            connected = true
                            channel[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                                override fun onMessage(port: WebMessagePort, message: WebMessage?) {
                                    if (!alive.get()) return
                                    val raw = message?.data ?: return
                                    if (raw.length > 16000) return
                                    val data = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
                                    val id = (data["id"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
                                    val method = (data["method"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                                    val value = (data["value"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
                                    fun reply(ok: Boolean, cancelled: Boolean = false) {
                                        if (!alive.get() || id == null) return
                                        port.postMessage(WebMessage(buildJsonObject {
                                            put("id", id)
                                            put("ok", ok)
                                            put("cancelled", cancelled)
                                            if (!ok) put("error", context.getString(R.string.interactive_answer_followup_failed))
                                        }.toString()))
                                    }
                                    when (method) {
                                        "pageState" -> {
                                            val state = data["value"] as? kotlinx.serialization.json.JsonObject
                                            if (answer.eInkAware && state != null) {
                                                canPrevious = state["canPrevious"]?.jsonPrimitive?.booleanOrNull == true
                                                canNext = state["canNext"]?.jsonPrimitive?.booleanOrNull == true
                                            }
                                        }
                                        "ready" -> {
                                            loaded = true
                                            DebugLog.interactiveStage(InteractiveStage.READY, inline, width = view.width, height = view.height)
                                        }
                                        "scrollBounds" -> {
                                            val bounds = data["value"] as? kotlinx.serialization.json.JsonObject
                                            val previous = bounds?.get("canPrevious")?.jsonPrimitive?.booleanOrNull
                                            val next = bounds?.get("canNext")?.jsonPrimitive?.booleanOrNull
                                            val activeTouch = bounds?.get("activeTouch")?.jsonPrimitive?.booleanOrNull
                                            if (previous != null && next != null && activeTouch != null) {
                                                touchHost.updateScrollBounds(previous, next, activeTouch)
                                            }
                                        }
                                        "controlTouch" -> touchHost.claimControlGesture()
                                        "height" -> {
                                            if (inline) (data["value"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
                                                ?.let(InteractiveAnswerWebPolicy::inlineHeight)?.let { latestHeightChanged(it) }
                                        }
                                        "error" -> {
                                            DebugLog.interactiveStage(InteractiveStage.SCRIPT_ERROR, inline)
                                            error = true
                                            reply(false)
                                        }
                                        "listDirectory", "readTextFile", "readFile" -> {
                                            val request = runCatching { TemplateFileRequest.parse(method, data["value"]) }.getOrNull()
                                            if (id == null || request == null || fileRequests >= 4) reply(false)
                                            else {
                                                fileRequests++
                                                fileScope.launch {
                                                    try {
                                                        val result = latestFileAccess(request)
                                                        if (alive.get()) port.postMessage(WebMessage(buildJsonObject {
                                                            put("id", id); put("ok", true); put("result", result)
                                                        }.toString()))
                                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                                    catch (failure: Exception) {
                                                        if (alive.get()) port.postMessage(WebMessage(buildJsonObject {
                                                            put("id", id); put("ok", false); put("error", failure.message ?: "File operation failed")
                                                        }.toString()))
                                                    } finally { fileRequests-- }
                                                }
                                            }
                                        }
                                        "listReferenceFiles" -> {
                                            if (id != null) {
                                                val references = runCatching { com.newoether.agora.templates.templateReferenceSnapshot(answer) }
                                                if (references.isFailure) reply(false)
                                                else port.postMessage(WebMessage(buildJsonObject {
                                                    put("id", id); put("ok", true)
                                                    put("result", Json.parseToJsonElement(kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.newoether.agora.templates.TemplateReference.serializer()), references.getOrThrow())))
                                                }.toString()))
                                            }
                                        }
                                        "sendPrompt" -> {
                                            val request = InteractivePrompt.fromBridge(data["value"])
                                            val text = request?.let { InteractiveAnswerWebPolicy.prompt(latestPrefix + it.text) }
                                            if (!latestAllowSubmission || id == null || request == null || text == null || pending != null) reply(false)
                                            else pending = PendingInteractivePrompt(id, text, ::reply, request.sourceFiles)
                                        }
                                        "openLink" -> {
                                            val ok = InteractiveAnswerWebPolicy.allowsLink(value) && runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                                            }.isSuccess
                                            reply(ok)
                                        }
                                        else -> reply(false)
                                    }
                                }
                            })
                            val payload = buildJsonObject {
                                put("answer", Json.parseToJsonElement(answer.encode()))
                                put("inline", inline)
                                put("eInkMode", latestMode)
                                put("fontScale", latestFontScale)
                            }
                            DebugLog.interactiveStage(InteractiveStage.BOOTSTRAP, inline,
                                htmlBytes = answer.html.toByteArray().size, expressions = answer.jsExpressions.size)
                            view.postWebMessage(WebMessage(payload.toString(), arrayOf(channel[1])), Uri.parse(InteractiveAnswerWebPolicy.ORIGIN))
                        }

                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                            alive.set(false)
                            error = true
                            if (destroyed.compareAndSet(false, true)) view.destroy()
                            return true
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: android.webkit.WebResourceError) {
                            if (request.isForMainFrame) error = true
                        }
                    }
                    loadUrl(InteractiveAnswerWebPolicy.HOST_URL)
                }.let { browser ->
                    touchHost.apply {
                        addView(browser, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    }
                }
            }, modifier = if (inline) Modifier.fillMaxWidth().height((inlineHeight - if (showPages) 48 else 0).coerceAtLeast(1).dp)
                else Modifier.fillMaxWidth().weight(1f))
            if (showPages) EinkPageControls(canPrevious, canNext, { page("previous") }, { page("next") })
            }
        }
        if (!loaded && !error) {
            Column(Modifier.align(Alignment.Center)) {
                if (!eInkMode) CircularProgressIndicator(Modifier.padding(16.dp))
                Text(stringResource(R.string.interactive_answer_loading), Modifier.padding(horizontal = 16.dp))
            }
        }
    }
    pending?.let { request ->
        InteractiveFollowupPrompt(request, confirmPrompt, onSend = { latestSend(it) }, onFinished = { accepted ->
            pending = null
            if (accepted) latestSent()
        })
    }
}
