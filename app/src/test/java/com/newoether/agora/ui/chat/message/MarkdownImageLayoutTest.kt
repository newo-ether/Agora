package com.newoether.agora.ui.chat.message

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.mutableStateOf
import coil.Coil
import coil.ImageLoader
import coil.EventListener
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.ErrorResult
import com.newoether.agora.model.ToolImageAttachment
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.newoether.agora.model.MarkdownImage
import com.mikepenz.markdown.compose.components.markdownComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1080dp-h1920dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownImageLayoutTest {
    @get:Rule val compose = createComposeRule()
    @After fun resetImageLoader() { Coil.reset() }

    @Test fun twoConsecutiveImagesReserveSeparateViewports() = verifyLayout(320f, 1f)

    @Test fun narrowViewportDoesNotOverlapAtLargeFontScale() = verifyLayout(210f, 1.6f)

    @Test fun loadedImagesKeepTheirOwnSpaceAtPhoneDensity() = verifyLayout(320f, 1f, 3f, true)

    @Test fun loadedImagesKeepTheirOwnSpaceAtNarrowLargeFont() = verifyLayout(210f, 1.6f, 2.5f, true)

    @Test fun realPngDecodesInComposedThumbnail() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "direct.png")
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        assertTrue(file.length() > 12)
        val events = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var lifecycle = "unknown"
        var observedOwner: androidx.lifecycle.LifecycleOwner? = null
        Coil.setImageLoader(ImageLoader.Builder(context).eventListener(object : EventListener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { events.add("loaded") }
            override fun onError(request: ImageRequest, result: ErrorResult) { events.add(result.throwable.toString()) }
        }).build())
        compose.setContent { MaterialTheme {
            observedOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            MarkdownImageThumbnail("direct", MarkdownImage(ToolImageAttachment(file.path, "image/png", file.length())), {})
        } }
        lifecycle = observedOwner?.lifecycle?.currentState?.name ?: "unobserved"
        try { awaitImageLoad { events.isNotEmpty() } }
        catch (error: Throwable) { throw AssertionError("lifecycle=$lifecycle fileBytes=${file.length()}", error) }
        assertEquals(listOf("loaded"), events.toList())
    }

    private fun verifyLayout(width: Float, fontScale: Float, density: Float = 1f, load: Boolean = false) {
        val first = "C:/workspace/My%20Project/first.png"
        val second = "C:/workspace/My%20Project/second.png"
        val bounds = mutableMapOf<String, Rect>()
        val images = mutableStateOf(mapOf(first to MarkdownImage(failed = !load), second to MarkdownImage(failed = !load)))
        val loaded = AtomicInteger()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val starts = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (load) Coil.setImageLoader(ImageLoader.Builder(context).eventListener(object : EventListener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { loaded.incrementAndGet() }
            override fun onStart(request: ImageRequest) { starts.add(request.data.toString()) }
            override fun onError(request: ImageRequest, result: ErrorResult) {
                if (request.data is String || request.data is File) failures.add(result.throwable.toString())
            }
        }).build())
        fun attachment(name: String, width: Int, height: Int): MarkdownImage {
            val file = File(context.cacheDir, name)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return MarkdownImage(ToolImageAttachment(file.path, "image/png", file.length(), width, height))
        }
        val content = "First frame:\n![first]($first)\n\nSecond frame:\n![second]($second)\n\nEnd."
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    val assets = rememberChatMarkdownAssets(
                        textColor = MaterialTheme.colorScheme.onSurface,
                        inlineImages = images.value,
                    )
                    Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                        val components = markdownComponents(
                                text = assets.components.text,
                                paragraph = assets.components.paragraph,
                                image = { model ->
                                    Box(Modifier.onGloballyPositioned {
                                        val link = markdownImageLink(model.content, model.node, null)!!
                                        bounds[link] = Rect(it.positionInRoot(), it.size.toSize())
                                        bounds["block:$link"] = bounds.getValue(link)
                                    }) { ScrollableDisplayLatexImage(model) }
                                },
                                inlineImage = { model ->
                                    Box(Modifier.onGloballyPositioned {
                                        bounds[model.content] = Rect(it.positionInRoot(), it.size.toSize())
                                        bounds["inline:${model.content}"] = bounds.getValue(model.content)
                                    }) { ChatMarkdownInlineImage(model) }
                                },
                        )
                        val context = assets.renderContext
                        IncrementalStreamingMarkdownContent(
                            content = content, isStreaming = false,
                            renderContext = ChatMarkdownRenderContext(
                                context.colors, context.typography, context.padding, components,
                                context.annotator, context.imageTransformer, context.flavour,
                                context.plainTextStyle, context.parseInlineDollarMath,
                            ),
                            modifier = Modifier.width(width.dp).onGloballyPositioned {
                                bounds["message"] = Rect(it.positionInRoot(), it.size.toSize())
                            },
                        )
                    }
                }
            }
        }
        compose.waitUntil(10_000) { bounds.containsKey(first) && bounds.containsKey(second) }
        compose.waitForIdle()
        var initial: List<Rect>? = null
        fun verifyBounds() = compose.runOnIdle {
            val a = requireNotNull(bounds[first])
            val b = requireNotNull(bounds[second])
            val side = minOf(width, 300f) * density
            assertEquals(side, a.width, 1f)
            assertEquals(side + 16f * density, a.height, 1f)
            assertEquals(side, b.width, 1f)
            assertEquals(side + 16f * density, b.height, 1f)
            assertTrue("Images overlap: $a / $b", a.bottom <= b.top)
            val message = requireNotNull(bounds["message"])
            assertTrue("Message did not reserve the final image: $message / $b", message.bottom >= b.bottom)
            val current = listOf(a, b, message)
            if (initial == null) initial = current else assertEquals("Loading changed message geometry", initial, current)
        }
        verifyBounds()
        if (load) {
            val firstImage = attachment("landscape.png", 640, 320)
            compose.runOnIdle { images.value = images.value + (first to firstImage) }
            try { awaitImageLoad { loaded.get() >= 1 || failures.isNotEmpty() } }
            catch (error: Throwable) { throw AssertionError("starts=$starts failures=$failures bounds=$bounds", error) }
            assertTrue(failures.toString(), failures.isEmpty())
            compose.waitForIdle()
            verifyBounds()
            val secondImage = attachment("portrait.png", 240, 480)
            compose.runOnIdle { images.value = images.value + (second to secondImage) }
            awaitImageLoad { loaded.get() >= 2 }
            compose.waitForIdle()
            verifyBounds()
        }
    }

    private fun awaitImageLoad(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            // Coil returns from IO through Android's main looper, independently
            // of Compose's frame clock. Robolectric leaves that looper paused.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            condition()
        }
    }
}
