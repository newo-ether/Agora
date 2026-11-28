package com.newoether.agora.mcp

import com.newoether.agora.util.Constants
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Complete-entry pagination of the Registry's safe local server projection. */
internal fun listMcpServers(
    servers: List<JsonObject>,
    limit: Int = 20,
    offset: Int = 0,
    maxChars: Int = Constants.MAX_TOOL_RESULT_LENGTH,
): String {
    require(limit in 1..50 && offset >= 0)
    require(maxChars >= 512)
    val items = mutableListOf<JsonObject>()
    var next = offset.coerceAtMost(servers.size)

    fun envelope(entries: List<JsonObject>, nextOffset: Int): String = buildJsonObject {
        put("servers", JsonArray(entries))
        put("returned", entries.size)
        put("total", servers.size)
        put("next_offset", if (nextOffset < servers.size) JsonPrimitive(nextOffset) else JsonNull)
        put("warning", "Local state of enabled servers; listing does not connect or refresh. Availability may change before search or call.")
    }.toString()

    for (server in servers.drop(next).take(limit)) {
        if (envelope(items + server, next + 1).length <= maxChars) {
            items += server
            next++
        } else if (items.isNotEmpty()) {
            break
        } else {
            // Keep IDs exact. An oversized entry is skipped explicitly instead of being clipped.
            items += buildJsonObject {
                put("error", "server_entry_too_large")
                put("offset", next)
                put("message", "The complete server entry exceeds the tool-result size limit.")
            }
            next++
        }
    }
    return envelope(items, next)
}
