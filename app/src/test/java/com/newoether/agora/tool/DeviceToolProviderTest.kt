package com.newoether.agora.tool

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceToolProviderTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val provider = DeviceToolProvider(app)
    private val ctx = GenerationContext()

    // ── definitions ────────────────────────────────────────

    @Test
    fun definitionsExposeSevenDeviceTools() {
        val defs = provider.definitions(ctx)
        assertEquals(7, defs.size)
        assertEquals(
            setOf(
                "device_info",
                "clipboard_copy",
                "clipboard_read",
                "send_notification",
                "datetime_now",
                "open_intent",
                "get_location",
            ),
            defs.map { it.function.name }.toSet(),
        )
    }

    @Test
    fun definitionsDeclareRequiredParameters() {
        val defs = provider.definitions(ctx).associateBy { it.function.name }

        assertEquals(
            listOf("text"),
            defs.getValue("clipboard_copy").function.parameters.required,
        )
        assertEquals(
            listOf("body"),
            defs.getValue("send_notification").function.parameters.required,
        )
        assertEquals(
            listOf("uri"),
            defs.getValue("open_intent").function.parameters.required,
        )
        // Parameterless tools expose no properties.
        assertTrue(defs.getValue("device_info").function.parameters.properties.isEmpty())
        assertTrue(defs.getValue("datetime_now").function.parameters.properties.isEmpty())
        assertTrue(defs.getValue("get_location").function.parameters.properties.isEmpty())
    }

    // ── handles ────────────────────────────────────────────

    @Test
    fun handlesReturnsTrueForDeviceToolsOnly() {
        listOf(
            "device_info", "clipboard_copy", "clipboard_read",
            "send_notification", "datetime_now", "open_intent", "get_location",
        ).forEach { assertTrue(it, provider.handles(it)) }
        assertFalse(provider.handles("web_search"))
        assertFalse(provider.handles("execute_shell_command"))
        assertFalse(provider.handles(""))
    }

    @Test
    fun executeUnknownToolReturnsError() = runTest {
        val result = provider.execute("not_a_tool", "{}", ctx)
        assertTrue(result, result.contains("unknown device tool"))
    }

    // ── clipboard round trip ───────────────────────────────

    @Test
    fun clipboardCopyThenReadRoundTrips() = runTest {
        val copyResult = provider.execute(
            "clipboard_copy", """{"text":"hello world"}""", ctx,
        )
        assertTrue(copyResult, copyResult.contains("Copied"))
        assertTrue(copyResult, copyResult.contains("11")) // "hello world".length

        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("hello world", cm.primaryClip?.getItemAt(0)?.text?.toString())

        val readResult = provider.execute("clipboard_read", "{}", ctx)
        assertEquals("hello world", readResult)
    }

    @Test
    fun clipboardCopyEmptyTextReturnsError() = runTest {
        val result = provider.execute("clipboard_copy", """{"text":""}""", ctx)
        assertTrue(result, result.contains("text is required"))
    }

    @Test
    fun clipboardCopyMissingArgumentReturnsError() = runTest {
        // extractText tolerates missing keys and empty argument strings.
        val result = provider.execute("clipboard_copy", "{}", ctx)
        assertTrue(result, result.contains("text is required"))
    }

    @Test
    fun clipboardReadReportsEmptyClipboard() = runTest {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", ""))
        val result = provider.execute("clipboard_read", "{}", ctx)
        assertTrue(result, result.contains("empty"))
    }

    @Test
    fun clipboardReadTruncatesVeryLongText() = runTest {
        val long = "a".repeat(10_050)
        provider.execute("clipboard_copy", """{"text":"$long"}""", ctx)
        val result = provider.execute("clipboard_read", "{}", ctx)
        assertTrue(result, result.contains("truncated at 10,000 characters"))
        // The retained body is capped at the 10k prefix plus the truncation notice.
        assertTrue(result.length < 10_100)
    }

    // ── datetime ───────────────────────────────────────────

    @Test
    fun datetimeNowReportsStructuredFields() = runTest {
        val result = provider.execute("datetime_now", "{}", ctx)
        assertTrue(result, result.contains("local_time:"))
        assertTrue(result, result.contains("timezone:"))
        assertTrue(result, result.contains("day_of_week:"))
        assertTrue(result, result.contains("epoch_ms:"))
        // epoch_ms must be a positive long close to now.
        val epoch = result.substringAfter("epoch_ms: ").trim().toLong()
        assertTrue(epoch > 0L)
    }

    // ── device_info ────────────────────────────────────────

    @Test
    fun deviceInfoReportsStableKeys() = runTest {
        val result = provider.execute("device_info", "{}", ctx)
        assertTrue(result, result.contains("device:"))
        assertTrue(result, result.contains("android:"))
        assertTrue(result, result.contains("ram_used_total_mb:"))
        assertTrue(result, result.contains("storage_free_total_gb:"))
        assertTrue(result, result.contains("network:"))
    }

    // ── notifications ──────────────────────────────────────

    @Test
    fun sendNotificationBlankBodyReturnsError() = runTest {
        val result = provider.execute(
            "send_notification", """{"title":"hi","body":""}""", ctx,
        )
        assertTrue(result, result.contains("body is required"))
    }

    // ── open_intent ────────────────────────────────────────

    @Test
    fun openIntentBlankUriReturnsError() = runTest {
        val result = provider.execute("open_intent", """{"uri":""}""", ctx)
        assertTrue(result, result.contains("uri is required"))
    }

    // ── location ───────────────────────────────────────────

    @Test
    fun getLocationWithoutPermissionReturnsActionableError() = runTest {
        // Robolectric grants no runtime permissions by default, so the tool must
        // return an actionable error instead of throwing.
        val result = provider.execute("get_location", "{}", ctx)
        assertTrue(result, result.contains("location permission not granted"))
    }

    @Test
    fun executeNeverThrowsOnMalformedArguments() = runTest {
        // Malformed JSON degrades to an empty extracted value rather than crashing.
        val result = provider.execute("clipboard_copy", "not json at all", ctx)
        assertTrue(result, result.contains("text is required"))
    }
}
