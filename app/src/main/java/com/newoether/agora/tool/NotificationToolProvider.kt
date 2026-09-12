package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.NotificationRecord
import com.newoether.agora.data.NotificationReader
import com.newoether.agora.data.NotificationStore
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Tool provider for notification operations.
 * Provides tools: check_notifications, read_notification, search_notifications
 */
class NotificationToolProvider(
    private val notificationStore: NotificationStore,
    private val notificationReader: NotificationReader,
) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        // Only expose notification tools if the reader is supported (fdroid flavor with permission)
        if (!notificationReader.isSupported()) return emptyList()

        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "check_notifications",
                    description = "Check if there are new notifications in the heartbeat pending queue. Returns count and list of notifications.",
                    parameters = ToolParameters(properties = emptyMap())
                )
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "read_notification",
                    description = "Read the full content of a specific notification by its key (id).",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "key" to ToolProperty(
                                "string",
                                "The notification key (id) to read",
                            ),
                        ),
                        required = listOf("key")
                    )
                )
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "search_notifications",
                    description = "Search notifications by query text (searches app label, title, and text). Optionally filter by package name.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "query" to ToolProperty(
                                "string",
                                "Search query",
                            ),
                            "package_name" to ToolProperty(
                                "string",
                                "Optional package name to filter results",
                            ),
                            "limit" to ToolProperty(
                                "integer",
                                "Maximum results (default: 20, max: 50)",
                            ),
                        ),
                        required = listOf("query")
                    )
                )
            ),
        )
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        val argsStr = arguments.ifBlank { "{}" }
        val args = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        fun arg(key: String): String = (args[key] as? JsonPrimitive)?.content ?: ""

        when (name) {
            "check_notifications" -> {
                // Snapshot only — the queue is consumed by the heartbeat after a
                // successful run; checking here must not eat what the AI hasn't seen.
                val records = notificationStore.getPendingSnapshot()
                if (records.isEmpty()) {
                    "No new notifications in the pending queue."
                } else {
                    records.joinToString("\n\n") { record ->
                        "- **${record.appLabel}** (id: ${record.id}): ${record.preview}"
                    }.let {
                        "## New Notifications\nThese notifications arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.\n$it"
                    }
                }
            }
            "read_notification" -> {
                val key = arg("key")
                val record = notificationReader.getNotificationById(key)
                record?.let {
                    "Notification: ${it.appLabel}\nTitle: ${it.title}\nText: ${it.text}\nPosted: ${formatTimestamp(it.postedAt)}\nCategory: ${it.category ?: "unknown"}"
                } ?: "Notification with key '$key' not found."
            }
            "search_notifications" -> {
                val query = arg("query")
                val packageName = arg("package_name").takeIf { it.isNotBlank() }
                val limit = (arg("limit").toIntOrNull() ?: 20).coerceIn(1, 50)
                val records = notificationReader.searchNotifications(query, packageName, limit)
                if (records.isEmpty()) {
                    "No notifications found matching '$query'."
                } else {
                    records.joinToString("\n\n") { record ->
                        "ID: ${record.id}\nApp: ${record.appLabel}\nTitle: ${record.title}\nPreview: ${record.preview}\nPosted: ${formatTimestamp(record.postedAt)}"
                    }
                }
            }
            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in setOf(
        "check_notifications",
        "read_notification",
        "search_notifications"
    )

    companion object {
        private fun formatTimestamp(epochMs: Long): String {
            return java.time.Instant.ofEpochMilli(epochMs)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }
    }
}