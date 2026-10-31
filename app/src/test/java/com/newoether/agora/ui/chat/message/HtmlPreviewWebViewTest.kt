package com.newoether.agora.ui.chat.message

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWebView
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HtmlPreviewVisualStateShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION")
class HtmlPreviewWebViewTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var parent: FrameLayout
    private val fixtures = mutableListOf<Fixture>()

    @Before
    fun createHost() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        parent = FrameLayout(controller.get())
        controller.get().setContentView(parent)
    }

    @After
    fun releaseBrowsers() {
        fixtures.forEach { it.browser.release() }
        controller.pause().stop().destroy()
    }

    @Test
    fun browserSettingsDisableNativeStorageFilesNetworkAndPopupCapabilities() {
        val fixture = fixture()
        val browser = fixture.browser
        val settings = browser.settings
        assertTrue(settings.javaScriptEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.allowFileAccessFromFileURLs)
        assertFalse(settings.allowUniversalAccessFromFileURLs)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.databaseEnabled)
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        assertTrue(settings.mediaPlaybackRequiresUserGesture)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
        assertEquals(WebSettings.LOAD_NO_CACHE, settings.cacheMode)
        assertTrue(settings.blockNetworkLoads)
        assertFalse(settings.saveFormData)
        assertFalse(settings.builtInZoomControls)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, browser.importantForAutofill)
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(browser))
        assertEquals("${fixture.document.origin}/probe.html", shadowOf(browser).lastLoadedUrl)
        assertNull(shadowOf(browser).getJavascriptInterface("Android"))
        assertNull(shadowOf(browser).getJavascriptInterface("Agora"))
    }

    @Test
    fun exactSuccessfulProbeAdmitsTheGeneratedDocumentOnce() {
        val fixture = fixture()
        val browser = fixture.browser
        val shadow = shadowOf(browser)
        shadow.webChromeClient.onReceivedTitle(browser, HtmlPreviewProbe.COMPLETE_TITLE)
        assertEquals(HtmlPreviewProbe.RESULT_SCRIPT, shadow.lastEvaluatedJavascript)
        val callback = requireNotNull(shadow.lastEvaluatedJavascriptCallback)

        callback.onReceiveValue(HtmlPreviewProbe.ACCEPTED_RESULT)
        assertEquals(fixture.document.rootUrl, shadow.lastLoadedUrl)
        assertEquals(200, fixture.resources.response(fixture.document.rootUrl, "GET", true).statusCode)
        callback.onReceiveValue("\"1,1:1,1\"")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(fixture.document.rootUrl, shadow.lastLoadedUrl)
        assertEquals(0, fixture.failures.get())
        assertEquals(0, fixture.unsupported.get())
        assertFalse(shadow.wasDestroyCalled())
    }

    @Test
    fun invalidProbeClosesResourcesAndReportsUnsupportedExactlyOnce() {
        val fixture = fixture()
        val browser = fixture.browser
        val shadow = shadowOf(browser)
        shadow.webChromeClient.onReceivedTitle(browser, HtmlPreviewProbe.COMPLETE_TITLE)
        val callback = requireNotNull(shadow.lastEvaluatedJavascriptCallback)

        callback.onReceiveValue("\"1,1:1,1\"")
        callback.onReceiveValue(HtmlPreviewProbe.ACCEPTED_RESULT)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(1, fixture.unsupported.get())
        assertEquals(0, fixture.failures.get())
        assertTrue(shadow.wasDestroyCalled())
        assertNull(browser.parent)
        assertEquals(403, fixture.resources.response(fixture.document.rootUrl, "GET", true).statusCode)
    }

    @Test
    fun callbackAfterExplicitReleaseCannotAdmitOrReportAnError() {
        val fixture = fixture()
        val browser = fixture.browser
        val shadow = shadowOf(browser)
        shadow.webChromeClient.onReceivedTitle(browser, HtmlPreviewProbe.COMPLETE_TITLE)
        val callback = requireNotNull(shadow.lastEvaluatedJavascriptCallback)
        val probeUrl = browser.url

        browser.release()
        callback.onReceiveValue(HtmlPreviewProbe.ACCEPTED_RESULT)
        browser.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))

        assertEquals(probeUrl, shadow.lastLoadedUrl)
        assertTrue(shadow.wasDestroyCalled())
        assertTrue(shadow.wasOnPauseCalled())
        assertNull(browser.parent)
        assertEquals(0, fixture.unsupported.get())
        assertEquals(0, fixture.failures.get())
        assertFalse(fixture.resources.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
    }

    @Test
    fun probeResultAfterNavigationIsStaleAndCannotLoadGeneratedContent() {
        val fixture = fixture()
        val browser = fixture.browser
        val shadow = shadowOf(browser)
        shadow.webChromeClient.onReceivedTitle(browser, HtmlPreviewProbe.COMPLETE_TITLE)
        val callback = requireNotNull(shadow.lastEvaluatedJavascriptCallback)
        val otherUrl = "${fixture.document.origin}/other.html"
        browser.loadUrl(otherUrl)

        callback.onReceiveValue(HtmlPreviewProbe.ACCEPTED_RESULT)

        assertEquals(otherUrl, shadow.lastLoadedUrl)
        assertEquals(403, fixture.resources.response(fixture.document.rootUrl, "GET", true).statusCode)
        assertEquals(0, fixture.unsupported.get())
        assertEquals(0, fixture.failures.get())
    }

    @Test
    fun wrongTitleAndWrongPageDoNotStartProbeEvaluation() {
        val fixture = fixture()
        val browser = fixture.browser
        val shadow = shadowOf(browser)
        shadow.webChromeClient.onReceivedTitle(browser, "ordinary title")
        assertNull(shadow.lastEvaluatedJavascript)

        browser.loadUrl(fixture.document.rootUrl)
        shadow.webChromeClient.onReceivedTitle(browser, HtmlPreviewProbe.COMPLETE_TITLE)
        assertNull(shadow.lastEvaluatedJavascript)
        assertEquals(403, fixture.resources.response(fixture.document.rootUrl, "GET", true).statusCode)
    }

    @Test
    fun missingProbeResultTimesOutAndDisposesTheBrowser() {
        val fixture = fixture()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(1, fixture.unsupported.get())
        assertEquals(0, fixture.failures.get())
        assertTrue(shadowOf(fixture.browser).wasDestroyCalled())
        assertNull(fixture.browser.parent)
        assertFalse(fixture.resources.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
    }

    @Test
    fun navigationAllowsOnlyKnownChildFramesWithoutOpeningExternalAppsAutomatically() {
        val fixture = fixture()
        val browser = fixture.browser
        val client = shadowOf(browser).webViewClient
        assertFalse(client.shouldOverrideUrlLoading(browser, request(fixture.document.frameUrl)))
        assertFalse(client.shouldOverrideUrlLoading(browser, request("${fixture.document.origin}/probe-control.html")))
        assertFalse(client.shouldOverrideUrlLoading(browser, request("${fixture.document.origin}/probe-blocked.html")))
        listOf(
            request("https://example.com/"),
            request(fixture.document.frameUrl, mainFrame = true),
            request(fixture.document.frameUrl, method = "POST"),
            request("file:///data/private.txt"),
            request("content://app/private"),
            request("intent://open"),
        ).forEach { assertTrue(client.shouldOverrideUrlLoading(browser, it)) }
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        val intercepted = client.shouldInterceptRequest(browser, request(fixture.document.frameUrl))
        assertNotNull(intercepted)
        assertEquals(403, intercepted!!.statusCode)
    }

    @Test
    fun permissionPromptsFilePickersAndWindowsAreDenied() {
        val fixture = fixture()
        val browser = fixture.browser
        val chrome = shadowOf(browser).webChromeClient
        var denied = false
        val permission = object : PermissionRequest() {
            override fun getOrigin(): Uri = Uri.parse(fixture.document.frameUrl)
            override fun getResources(): Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE)
            override fun grant(resources: Array<out String>) { throw AssertionError("Permission granted") }
            override fun deny() { denied = true }
        }
        chrome.onPermissionRequest(permission)
        assertTrue(denied)
        var geolocation: Pair<Boolean, Boolean>? = null
        chrome.onGeolocationPermissionsShowPrompt(fixture.document.origin, GeolocationPermissions.Callback { _, allow, retain ->
            geolocation = allow to retain
        })
        assertEquals(false to false, geolocation)
        var fileCallback = false
        var files: Array<Uri>? = arrayOf(Uri.parse("file:///unexpected"))
        val chooser = object : WebChromeClient.FileChooserParams() {
            override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT)
            override fun getAcceptTypes(): Array<String> = arrayOf("*/*")
            override fun getFilenameHint(): String? = null
            override fun getMode() = MODE_OPEN
            override fun getTitle(): CharSequence? = null
            override fun isCaptureEnabled() = false
        }
        assertTrue(chrome.onShowFileChooser(browser, ValueCallback {
            fileCallback = true
            files = it
        }, chooser))
        assertTrue(fileCallback)
        assertNull(files)
        assertFalse(chrome.onCreateWindow(browser, false, true, Message.obtain()))
    }

    @Test
    fun rendererCrashReleasesResourcesAndReportsTheFailure() {
        val fixture = fixture()
        val browser = fixture.browser
        val detail = object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = 0
        }
        assertTrue(shadowOf(browser).webViewClient.onRenderProcessGone(browser, detail))
        assertEquals(1, fixture.failures.get())
        assertEquals(0, fixture.unsupported.get())
        assertTrue(shadowOf(browser).wasDestroyCalled())
        assertNull(browser.parent)
        assertEquals(403, fixture.resources.response(fixture.document.rootUrl, "GET", true).statusCode)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(0, fixture.unsupported.get())
    }

    @Test
    fun loadingKeepsTheBrowserHiddenUntilTheAdmittedDocumentCanDraw() {
        val fixture = fixture()
        val browser = fixture.browser
        val shadow = shadowOf(browser)
        val visual = Shadow.extract<HtmlPreviewVisualStateShadow>(browser)
        assertEquals(View.INVISIBLE, browser.visibility)
        assertEquals(Color.BLACK, shadow.backgroundColor)
        shadow.webViewClient.onPageFinished(browser, browser.url)
        assertTrue(visual.callbacks.isEmpty())

        admit(fixture)
        shadow.webViewClient.onPageFinished(browser, "${fixture.document.origin}/probe.html")
        shadow.webViewClient.onPageFinished(browser, fixture.document.frameUrl)
        assertTrue(visual.callbacks.isEmpty())
        shadow.webViewClient.onPageFinished(browser, fixture.document.rootUrl)
        shadow.webViewClient.onPageFinished(browser, fixture.document.rootUrl)
        assertEquals(1, visual.callbacks.size)
        assertEquals(View.INVISIBLE, browser.visibility)
        assertEquals(0, fixture.ready.get())

        visual.complete()
        visual.complete()
        assertEquals(View.VISIBLE, browser.visibility)
        assertEquals(1, fixture.ready.get())
        assertEquals(0, fixture.failures.get())
    }

    @Test
    fun visualCompletionAfterReleaseOrNavigationCannotRevealTheBrowser() {
        listOf(false, true).forEach { release ->
            val fixture = fixture()
            admit(fixture)
            val browser = fixture.browser
            shadowOf(browser).webViewClient.onPageFinished(browser, fixture.document.rootUrl)
            if (release) browser.release() else browser.loadUrl("${fixture.document.origin}/other.html")
            Shadow.extract<HtmlPreviewVisualStateShadow>(browser).complete()
            assertEquals(0, fixture.ready.get())
            assertEquals(View.INVISIBLE, browser.visibility)
        }
    }

    @Test
    fun mainFrameLoadFailureReleasesTheBrowserWithoutAReadySignal() {
        val fixture = fixture()
        admit(fixture)
        val browser = fixture.browser
        val client = shadowOf(browser).webViewClient
        val error = io.mockk.mockk<android.webkit.WebResourceError>()
        io.mockk.every { error.errorCode } returns android.webkit.WebViewClient.ERROR_UNKNOWN
        io.mockk.every { error.description } returns "Failed"
        client.onReceivedError(browser, request(fixture.document.frameUrl), error)
        assertEquals(0, fixture.failures.get())
        client.onPageFinished(browser, fixture.document.rootUrl)
        client.onReceivedError(browser, request(fixture.document.rootUrl, mainFrame = true), error)
        client.onReceivedError(browser, request(fixture.document.rootUrl, mainFrame = true), error)
        Shadow.extract<HtmlPreviewVisualStateShadow>(browser).complete()
        assertEquals(1, fixture.failures.get())
        assertEquals(0, fixture.ready.get())
        assertTrue(shadowOf(browser).wasDestroyCalled())
    }

    private fun admit(fixture: Fixture) {
        val shadow = shadowOf(fixture.browser)
        shadow.webChromeClient.onReceivedTitle(fixture.browser, HtmlPreviewProbe.COMPLETE_TITLE)
        requireNotNull(shadow.lastEvaluatedJavascriptCallback).onReceiveValue(HtmlPreviewProbe.ACCEPTED_RESULT)
    }

    private fun fixture(): Fixture {
        val document = HtmlPreviewDocument("<p>Generated content</p>")
        val resources = HtmlPreviewResources(document, emptyMap(), "#000000", "#FFFFFF")
        val failures = AtomicInteger()
        val unsupported = AtomicInteger()
        val ready = AtomicInteger()
        val browser = HtmlPreviewWebView(controller.get(), document, resources,
            onFailure = { failures.incrementAndGet() }, onUnsupported = { unsupported.incrementAndGet() },
            backgroundColor = Color.BLACK, onReady = { ready.incrementAndGet() })
        parent.addView(browser)
        return Fixture(document, resources, browser, failures, unsupported, ready).also { fixtures += it }
    }

    private fun request(
        url: String,
        method: String = "GET",
        mainFrame: Boolean = false,
    ): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun getMethod(): String = method
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private data class Fixture(
        val document: HtmlPreviewDocument,
        val resources: HtmlPreviewResources,
        val browser: HtmlPreviewWebView,
        val failures: AtomicInteger,
        val unsupported: AtomicInteger,
        val ready: AtomicInteger,
    )
}

/** Hold only the platform visual callback; production admission and visibility code still execute. */
@Implements(WebView::class)
class HtmlPreviewVisualStateShadow : ShadowWebView() {
    val callbacks = mutableListOf<Pair<Long, WebView.VisualStateCallback>>()

    @Implementation
    protected fun postVisualStateCallback(requestId: Long, callback: WebView.VisualStateCallback) {
        callbacks += requestId to callback
    }

    fun complete() {
        val (requestId, callback) = callbacks.single()
        callback.onComplete(requestId)
    }
}
