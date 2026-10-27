package com.newoether.agora.util

import android.content.Context
import com.newoether.agora.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object SearchResultFormatter {

    fun isRawSearchResult(text: String): Boolean {
        // Nearly every persisted user/assistant row is Markdown. Reject it before constructing a
        // JSON parser (and, previously, an exception) on every Room emission.
        val firstContent = text.indexOfFirst { character -> !character.isWhitespace() }
        if (firstContent < 0 || text[firstContent] != '{') return false
        return try {
            val type = Json.parseToJsonElement(text).jsonObject["type"]
                ?.let { (it as? JsonPrimitive)?.content }
                ?.let(com.newoether.agora.tool.BuiltInToolNames::canonical)
            type == "web_search" ||
                type == "conversation_search" ||
                type == "shell_exec" ||
                type == "shell_list" ||
                type == "memory_list" ||
                type == "skill_list" ||
                type == "conversation_list" ||
                type == "conversation_read"
        } catch (_: Exception) {
            false
        }
    }

    fun format(text: String, context: Context): String {
        if (!isRawSearchResult(text)) return text
        return try {
            val json = Json.parseToJsonElement(text).jsonObject
            val error = json["error"]?.let { (it as? JsonPrimitive)?.content }
            if (error != null) return formatError(json, error, context)
            when (json["type"]?.let { (it as? JsonPrimitive)?.content }?.let(com.newoether.agora.tool.BuiltInToolNames::canonical)) {
                "web_search" -> formatWebSearch(json, context)
                "conversation_search" -> formatConversationSearch(json, context)
                "conversation_list" -> formatConversationList(json, context)
                "conversation_read" -> formatConversationRead(json, context)
                "memory_list", "skill_list" -> formatMemoryList(json)
                "shell_list" -> formatShellList(json, context)
                "shell_exec" -> formatShellCommand(json, context)
                else -> text
            }
        } catch (_: Exception) {
            text
        }
    }

    fun getFirstLine(text: String, context: Context): String {
        val formatted = format(text, context)
        return formatted.lines().first().take(100)
    }

    private fun formatError(json: JsonObject, error: String, context: Context): String {
        val query = json["query"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        return when (error) {
            "no_query" -> context.getString(R.string.search_no_query)
            "no_results" -> context.getString(R.string.search_no_results)
            "no_response" -> context.getString(R.string.search_no_response)
            "no_api_key" -> {
                val provider = json["provider"]?.let { (it as? JsonPrimitive)?.content }
                    ?: "web search"
                context.getString(R.string.provider_no_keys, provider)
            }
            "search_error" -> {
                val msg = json["message"]?.let { (it as? JsonPrimitive)?.content } ?: context.getString(R.string.unknown)
                context.getString(R.string.search_error_format, msg)
            }
            else -> context.getString(R.string.search_error_format, error)
        }
    }

    private fun formatWebSearch(json: JsonObject, context: Context): String {
        val query = json["query"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val results = json["results"]?.jsonArray ?: return context.getString(R.string.search_no_results)
        if (results.isEmpty()) return context.getString(R.string.search_no_results)

        val untitled = context.getString(R.string.search_untitled)
        val body = results.take(10).mapIndexed { i, element ->
            val obj = element.jsonObject
            val title = (obj["title"] as? JsonPrimitive)?.content ?: untitled
            val url = (obj["url"] as? JsonPrimitive)?.content ?: ""
            val desc = (obj["description"] as? JsonPrimitive)?.content ?: ""
            "${i + 1}. $title\n   $url\n   $desc"
        }.joinToString("\n\n")

        val total = results.size
        val prefix = if (query.isNotBlank())
            context.getString(R.string.search_found_results, total, query)
        else
            context.getString(R.string.search_found_results_no_query, total)
        return "$prefix\n\n$body"
    }

    private fun formatConversationSearch(json: JsonObject, context: Context): String {
        val query = json["query"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val results = json["results"]?.jsonArray ?: return context.getString(R.string.search_no_matches, query)
        if (results.isEmpty()) return context.getString(R.string.search_no_matches, query)

        val userRole = context.getString(R.string.search_role_user)
        val modelRole = context.getString(R.string.search_role_model)
        val untitled = context.getString(R.string.search_untitled)

        val hasDetails = results.any { result ->
            result.jsonObject["messages"]?.jsonArray?.any { message ->
                "thinking" in message.jsonObject || "tool_calls" in message.jsonObject
            } == true
        }
        val visibleResults = if (hasDetails) results else results.take(5)
        val body = visibleResults.joinToString("\n\n") { element ->
            val obj = element.jsonObject
            val title = (obj["title"] as? JsonPrimitive)?.content ?: untitled
            val messages = obj["messages"]?.jsonArray
            val visibleMessages = if (hasDetails) messages else messages?.take(3)
            val previews = visibleMessages?.joinToString("\n") { msg ->
                val msgObj = msg.jsonObject
                val participant = (msgObj["participant"] as? JsonPrimitive)?.content ?: ""
                val role = if (participant == "USER") userRole else modelRole
                val text = (msgObj["text"] as? JsonPrimitive)?.content?.lines()?.first()?.take(200) ?: ""
                "$role: $text" + formatHistoryDetails(msgObj, context)
            } ?: ""
            "## $title\n$previews"
        }

        val total = results.sumOf { element ->
            (element.jsonObject["match_count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        }
        return context.getString(R.string.search_found_matches, total, query) + "\n\n$body"
    }

    private fun formatShellCommand(json: JsonObject, context: Context): String {
        val server = json["server"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val command = json["command"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val output = json["output"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val exitCode = json["exit_code"]?.let { (it as? JsonPrimitive)?.content }
        val error = json["error"]?.let { (it as? JsonPrimitive)?.content }
        val serverLine = if (server.isNotEmpty()) context.getString(R.string.shell_result_server, server) + "\n" else ""
        return if (error != null) {
            val msg = json["message"]?.let { (it as? JsonPrimitive)?.content } ?: ""
            "$serverLine${context.getString(R.string.shell_result_command, command)}\n${context.getString(R.string.shell_result_error, msg)}${if (output.isNotEmpty()) "\n\n$output" else ""}"
        } else {
            val code = exitCode?.toIntOrNull() ?: -1
            "$serverLine${context.getString(R.string.shell_result_command, command)}\n${context.getString(R.string.shell_result_exit_code, code)}\n\n$output"
        }
    }

    private fun formatMemoryList(json: JsonObject): String {
        val files = json["files"]?.jsonArray ?: return "No memory files."
        if (files.isEmpty()) return "No memory files."
        return files.joinToString("\n") { element ->
            val obj = element.jsonObject
            val name = (obj["name"] as? JsonPrimitive)?.content ?: ""
            val desc = (obj["description"] as? JsonPrimitive)?.content ?: ""
            if (desc.isNotEmpty()) "$name — $desc" else name
        }
    }

    private fun formatShellList(json: JsonObject, context: Context): String {
        val devices = json["devices"]?.jsonArray ?: return context.getString(R.string.shell_no_devices)
        if (devices.isEmpty()) return context.getString(R.string.shell_no_devices)
        val list = devices.joinToString("\n") { element ->
            val obj = element.jsonObject
            val name = (obj["name"] as? JsonPrimitive)?.content?.ifBlank { null } ?: "Untitled"
            val desc = (obj["description"] as? JsonPrimitive)?.content ?: ""
            if (desc.isNotEmpty()) "$name — $desc" else name
        }
        return list
    }

    private fun formatConversationList(json: JsonObject, context: Context): String {
        val offset = (json["offset"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val hasMore = (json["has_more"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        val conversations = json["conversations"]?.jsonArray
        if (conversations == null || conversations.isEmpty()) {
            return context.getString(R.string.conversation_list_empty)
        }

        val header = context.getString(R.string.conversation_list_header, conversations.size, offset + 1, offset + conversations.size)
        val body = conversations.joinToString("\n") { element ->
            val obj = element.jsonObject
            val id = (obj["id"] as? JsonPrimitive)?.content?.takeLast(8) ?: "?"
            val title = (obj["title"] as? JsonPrimitive)?.content ?: context.getString(R.string.search_untitled)
            val dateStr = (obj["timestamp"] as? JsonPrimitive)?.content ?: ""
            if (dateStr.isNotEmpty()) "- [$id] $title ($dateStr)" else "- [$id] $title"
        }
        val footer = if (hasMore) context.getString(R.string.conversation_list_more) else ""
        return "$header\n\n$body${if (footer.isNotEmpty()) "\n\n$footer" else ""}"
    }

    private fun formatHistoryDetails(message: JsonObject, context: Context): String = buildString {
        (message["details_error"] as? JsonPrimitive)?.content?.let { error ->
            append("\n\n${context.getString(R.string.conversation_details_error, error)}")
        }
        message["thinking"]?.jsonArray?.forEach { element ->
            val text = (element.jsonObject["text"] as? JsonPrimitive)?.content.orEmpty()
            if (text.isNotEmpty()) append("\n\n${context.getString(R.string.thinking)}:\n$text")
        }
        message["tool_calls"]?.jsonArray?.forEach { element ->
            val tool = element.jsonObject
            val name = (tool["display_name"] as? JsonPrimitive)?.content
                ?: (tool["name"] as? JsonPrimitive)?.content.orEmpty()
            val state = (tool["state"] as? JsonPrimitive)?.content.orEmpty()
            append("\n\n${context.getString(R.string.tool_context)}: $name")
            if (state.isNotEmpty()) append(" ($state)")
            (tool["arguments"] as? JsonPrimitive)?.content?.let { append("\n$it") }
            val result = (tool["result_text"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: (tool["result"] as? JsonPrimitive)?.content
            listOfNotNull(
                result,
                (tool["structured_result"] as? JsonPrimitive)?.content,
                (tool["image_transcription"] as? JsonPrimitive)?.content,
            ).filter { it.isNotBlank() }.distinct().forEach { append("\n$it") }
        }
        if ((message["tool_calls_has_more"] as? JsonPrimitive)?.content == "true") {
            val nextOffset = (message["tool_calls_next_offset"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            append("\n${context.getString(R.string.conversation_tools_more, nextOffset)}")
        }
    }

    private fun formatConversationRead(json: JsonObject, context: Context): String {
        val title = (json["title"] as? JsonPrimitive)?.content ?: context.getString(R.string.search_untitled)
        val offset = (json["offset"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val hasMore = (json["has_more"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        val messages = json["messages"]?.jsonArray
        val userRole = context.getString(R.string.search_role_user)
        val modelRole = context.getString(R.string.search_role_model)

        val header = context.getString(R.string.conversation_read_header, title)
        val body = if (messages != null && messages.isNotEmpty()) {
            messages.joinToString("\n\n") { element ->
                val obj = element.jsonObject
                val participant = (obj["participant"] as? JsonPrimitive)?.content ?: ""
                val role = if (participant == "USER") userRole else modelRole
                val text = (obj["text"] as? JsonPrimitive)?.content ?: ""
                val error = (obj["error"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != text }
                "$role: $text" + error?.let { "\n$it" }.orEmpty() + formatHistoryDetails(obj, context)
            }
        } else {
            context.getString(R.string.conversation_read_empty)
        }
        val pageInfo = if (messages.isNullOrEmpty()) {
            ""
        } else {
            "\n" + context.getString(R.string.conversation_read_page, offset + 1, offset + messages.size)
        }
        val footer = if (hasMore) context.getString(R.string.conversation_read_more) else ""
        return "$header$pageInfo\n\n$body${if (footer.isNotEmpty()) "\n\n$footer" else ""}"
    }
}
