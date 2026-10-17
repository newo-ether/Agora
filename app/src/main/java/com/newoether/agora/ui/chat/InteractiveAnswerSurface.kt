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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream

/** HTML drag controls may claim the current touch; ordinary page areas keep normal scrolling. */
internal class InteractiveAnswerTouchHost(context: Context) : FrameLayout(context) {
    private var touching = false
    private var controlOwnsGesture = false

    fun claimControlGesture() {
        if (!touching) return
        controlOwnsGesture = true
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touching = true
            controlOwnsGesture = false
        }
        val touchParent = parent
        val finished = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (controlOwnsGesture && !finished) touchParent?.requestDisallowInterceptTouchEvent(true)
        return try {
            super.dispatchTouchEvent(event)
            true
        } finally {
            if (finished) {
                touching = false
                controlOwnsGesture = false
                touchParent?.requestDisallowInterceptTouchEvent(false)
            } else if (controlOwnsGesture) {
                touchParent?.requestDisallowInterceptTouchEvent(true)
            }
            // Unclaimed gestures retain Chromium's own scroll-interception decisions.
        }
    }
}

/** Native confirmation owns sending; generated code cannot bypass a user decision. */
private data class PendingInteractivePrompt(val id: Int, val text: String, val reply: (Boolean, Boolean) -> Unit)

@Composable
internal fun InteractiveAnswerSurface(
    answer: InteractiveAnswer,
    inline: Boolean,
    modifier: Modifier = Modifier,
    onSent: () -> Unit = {},
    inlineHeight: Int = 320,
    onHeightChanged: (Int) -> Unit = {},
    onSend: suspend (String) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingInteractivePrompt?>(null) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val latestHeightChanged by rememberUpdatedState(onHeightChanged)
    val latestSend by rememberUpdatedState(onSend)
    val latestSent by rememberUpdatedState(onSent)
    val webView = remember(answer) { WebView(context) }
    val touchHost = remember(webView) { InteractiveAnswerTouchHost(context) }
    // Revokes queued bridge callbacks before teardown, including callbacks from disposed viewers.
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val destroyed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val ports = remember { mutableListOf<WebMessagePort>() }
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
                                        "ready" -> {
                                            loaded = true
                                            DebugLog.interactiveStage(InteractiveStage.READY, inline, width = view.width, height = view.height)
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
                                        "sendPrompt" -> {
                                            val text = InteractiveAnswerWebPolicy.prompt(value)
                                            if (id == null || text == null || pending != null) reply(false)
                                            else pending = PendingInteractivePrompt(id, text, ::reply)
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
            }, modifier = if (inline) Modifier.fillMaxWidth().height(inlineHeight.dp) else Modifier.fillMaxSize())
        }
        if (!loaded && !error) {
            Column(Modifier.align(Alignment.Center)) {
                CircularProgressIndicator(Modifier.padding(16.dp))
                Text(stringResource(R.string.interactive_answer_loading), Modifier.padding(horizontal = 16.dp))
            }
        }
    }
    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { if (!sending) { request.reply(false, true); pending = null } },
            title = { Text(stringResource(R.string.interactive_answer_followup)) },
            text = { Text(request.text) },
            confirmButton = {
                Button(enabled = !sending, onClick = {
                    if (!sending) {
                        sending = true
                        scope.launch {
                            val accepted = try {
                                latestSend(request.text)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            request.reply(accepted, false)
                            sending = false
                            pending = null
                            if (accepted) latestSent()
                        }
                    }
                }) { Text(stringResource(R.string.interactive_answer_send)) }
            },
            dismissButton = {
                TextButton(enabled = !sending, onClick = { request.reply(false, true); pending = null }) {
                    Text(stringResource(R.string.interactive_answer_cancel))
                }
            },
        )
    }

}
