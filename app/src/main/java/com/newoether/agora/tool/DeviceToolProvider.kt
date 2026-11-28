package com.newoether.agora.tool

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Phone-device utilities exposed to the model: device facts, clipboard, notifications,
 * date/time, intent launching, and location. All are local, fast, and dependency-free.
 * Location requires the user to grant the runtime permission; tools answer with an
 * actionable error message so the model can ask the user instead of retrying blindly.
 */
class DeviceToolProvider(private val app: Context) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(function = ToolFunction(
            name = "device_info",
            description = "Get device info: battery %, charging state, model, Android version, RAM, storage, network type.",
            parameters = ToolParameters(properties = emptyMap()),
        )),
        ToolDefinition(function = ToolFunction(
            name = "clipboard_copy",
            description = "Copy text to the device clipboard.",
            parameters = ToolParameters(
                properties = mapOf(
                    "text" to ToolProperty("string", "Text to place on the clipboard."),
                ),
                required = listOf("text"),
            ),
        )),
        ToolDefinition(function = ToolFunction(
            name = "clipboard_read",
            description = "Read the current clipboard text. Only works while Agora is in the foreground.",
            parameters = ToolParameters(properties = emptyMap()),
        )),
        ToolDefinition(function = ToolFunction(
            name = "send_notification",
            description = "Post a notification on this device. Use for reminders the user asked for, or to surface the result of a long background job.",
            parameters = ToolParameters(
                properties = mapOf(
                    "title" to ToolProperty("string", "Short notification title."),
                    "body" to ToolProperty("string", "Notification body text."),
                ),
                required = listOf("body"),
            ),
        )),
        ToolDefinition(function = ToolFunction(
            name = "datetime_now",
            description = "Get the current date and time: local timestamp, timezone with UTC offset, day of week, and epoch milliseconds. Use for any time-related question or time math.",
            parameters = ToolParameters(properties = emptyMap()),
        )),
        ToolDefinition(function = ToolFunction(
            name = "open_intent",
            description = "Open a URL, map location, dialer, email draft, or navigation. Supports https://, geo:, google.navigation:q=, tel:, mailto:, and similar URIs.",
            parameters = ToolParameters(
                properties = mapOf(
                    "uri" to ToolProperty("string", "URI to open, e.g. https://example.com, geo:0,0?q=pizza, tel:+15551234, mailto:a@b.c"),
                ),
                required = listOf("uri"),
            ),
        )),
        ToolDefinition(function = ToolFunction(
            name = "get_location",
            description = "Get the device's current location (latitude/longitude and accuracy). Call this FIRST when the user asks about nearby places or 'closest' anything, then use the coordinates with web_search.",
            parameters = ToolParameters(properties = emptyMap()),
        )),
    )

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        withContext(Dispatchers.IO) {
            try {
                executeDeviceTool(name, arguments)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }

    private suspend fun executeDeviceTool(name: String, arguments: String): String = when (name) {
        "device_info" -> deviceInfo()
        "clipboard_copy" -> clipboardCopy(extractText(arguments, "text"))
        "clipboard_read" -> clipboardRead()
        "send_notification" -> sendNotification(
            extractText(arguments, "title"),
            extractText(arguments, "body"),
        )
        "datetime_now" -> datetimeNow()
        "open_intent" -> openIntent(extractText(arguments, "uri"))
        "get_location" -> getLocation()
        else -> "Error: unknown device tool: $name"
    }

    private fun extractText(arguments: String, key: String): String = try {
        val args = kotlinx.serialization.json.Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
            arguments.ifBlank { "{}" },
        )
        (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    } catch (_: Exception) {
        ""
    }

    // ── device_info ────────────────────────────────────────

    private fun deviceInfo(): String {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it > 0 }
        val charging = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            ?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }

        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val ramUsedMb = ((memInfo.totalMem - memInfo.availMem) / (1024L * 1024L)).coerceAtLeast(0)
        val ramTotalMb = memInfo.totalMem / (1024L * 1024L)

        val stat = StatFs(app.filesDir.absolutePath)
        val storageFreeGb = stat.availableBytes / (1024L * 1024L * 1024L)
        val storageTotalGb = stat.totalBytes / (1024L * 1024L * 1024L)

        val network = networkType()

        return buildString {
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("uptime_hours: ${TimeUnitHours(SystemClock.elapsedRealtime())}")
            batteryPct?.let { pct ->
                append("battery: $pct%")
                if (charging == true) append(" (charging)")
                appendLine()
            }
            appendLine("ram_used_total_mb: $ramUsedMb/$ramTotalMb")
            appendLine("storage_free_total_gb: $storageFreeGb/$storageTotalGb")
            append("network: $network")
        }
    }

    private fun TimeUnitHours(millis: Long): String =
        String.format(Locale.US, "%.1f", millis / 3_600_000.0)

    private fun networkType(): String {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    // ── clipboard ──────────────────────────────────────────

    private fun clipboardCopy(text: String): String {
        if (text.isEmpty()) return "Error: text is required"
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "Error: clipboard unavailable"
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        return "Copied ${text.length} characters to the clipboard."
    }

    private fun clipboardRead(): String {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "Error: clipboard unavailable"
        val item = cm.primaryClip?.getItemAt(0) ?: return "Clipboard is empty."
        val text = item.text?.toString()
        if (text == null) {
            return "Clipboard holds non-text content (${cm.primaryClip?.description?.mimeTypeCount ?: 0} MIME types)."
        }
        if (text.length > 10_000) {
            return text.take(10_000) + "\n…(truncated at 10,000 characters)"
        }
        return text.ifEmpty { "Clipboard is empty." }
    }

    // ── notifications ──────────────────────────────────────

    private fun sendNotification(title: String, body: String): String {
        if (body.isBlank()) return "Error: body is required"
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "Error: notification service unavailable"
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "Error: notification permission not granted. Ask the user to enable notifications for Agora in system settings."
        }
        if (!nm.areNotificationsEnabled()) {
            return "Error: notifications are disabled for Agora. Ask the user to enable them in system settings."
        }
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agora", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val launchIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                app, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(app.applicationInfo.icon)
            .setContentTitle(title.ifBlank { "Agora" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()
        nm.notify(NOTIFICATION_TAG, NOTIFICATION_ID.incrementAndGet(), notification)
        return "Notification posted."
    }

    // ── datetime ───────────────────────────────────────────

    private fun datetimeNow(): String {
        val now = Date()
        val tz = TimeZone.getDefault()
        val offset = tz.getOffset(now.time)
        val offsetHours = offset / 3_600_000
        val offsetMinutes = (offset % 3_600_000) / 60_000
        val sign = if (offset >= 0) "+" else "-"
        val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = tz }
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.US).apply { timeZone = tz }
        return buildString {
            appendLine("local_time: ${local.format(now)}")
            appendLine("timezone: ${tz.id} (UTC$sign${"%02d".format(kotlin.math.abs(offsetHours))}:${"%02d".format(kotlin.math.abs(offsetMinutes))})")
            appendLine("day_of_week: ${dayOfWeek.format(now)}")
            append("epoch_ms: ${now.time}")
        }
    }

    // ── open_intent ────────────────────────────────────────

    private fun openIntent(uri: String): String {
        if (uri.isBlank()) return "Error: uri is required"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(app.packageManager) == null && Build.VERSION.SDK_INT < 30) {
                return "Error: no app handles this URI: $uri"
            }
            app.startActivity(intent)
            "Opened: $uri"
        } catch (e: android.content.ActivityNotFoundException) {
            "Error: no app handles this URI: $uri"
        }
    }

    // ── location ───────────────────────────────────────────

    private suspend fun getLocation(): String {
        val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return "Error: location permission not granted. Ask the user to grant Agora the Location permission in system settings, then retry."
        }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return "Error: location service unavailable"

        val lastKnown = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        val location = lastKnown ?: freshLocation(lm)
        location ?: return "Error: no location fix available yet. Ask the user to enable location services and try again in a few seconds."

        val ageSeconds = (System.currentTimeMillis() - location.time) / 1000
        return buildString {
            appendLine("latitude: ${location.latitude}")
            appendLine("longitude: ${location.longitude}")
            appendLine("accuracy_meters: ${location.accuracy.toInt()}")
            appendLine("provider: ${location.provider}")
            append("age_seconds: $ageSeconds")
        }
    }

    /** Triggers a single fresh fix and waits briefly; falls back to null on timeout. */
    private suspend fun freshLocation(lm: android.location.LocationManager): android.location.Location? {
        val result = java.util.concurrent.atomic.AtomicReference<android.location.Location?>(null)
        val listener = android.location.LocationListener { location -> result.set(location) }
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null
        return try {
            providers.forEach { runCatching { lm.requestSingleUpdate(it, listener, null) } }
            withTimeoutOrNull(10_000L) {
                while (result.get() == null) {
                    kotlinx.coroutines.delay(250)
                }
                result.get()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { lm.removeUpdates(listener) }
        }
    }

    companion object {
        private val TOOL_NAMES = setOf(
            "device_info",
            "clipboard_copy",
            "clipboard_read",
            "send_notification",
            "datetime_now",
            "open_intent",
            "get_location",
        )
        private const val CHANNEL_ID = "agora_tools"
        private const val NOTIFICATION_TAG = "agora_tool"
        private val NOTIFICATION_ID = java.util.concurrent.atomic.AtomicInteger(1000)
    }
}
