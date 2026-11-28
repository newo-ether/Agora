package com.newoether.agora.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Only successful Local Sandbox generation descriptors establish runtime image ownership. */
fun sandboxGeneratedOutputImages(toolName: String?, result: String?): List<JsonObject> {
    if (toolName != "image_generate") return emptyList()
    val descriptor = runCatching { Json.parseToJsonElement(result.orEmpty()) as? JsonObject }.getOrNull()
        ?: return emptyList()
    fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.content
    if (descriptor.string("type") != "image_generation" || descriptor.string("status") != "ok") return emptyList()
    val images = descriptor["images"] as? JsonArray ?: return emptyList()
    if (images.isEmpty() || !images.all { value ->
        val image = value as? JsonObject ?: return@all false
        image.string("server") == "Local Sandbox" &&
            image.string("path")?.startsWith("/home/agora/attachments/") == true
    }) return emptyList()
    return images.map { it as JsonObject }
}
