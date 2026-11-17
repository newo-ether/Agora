package com.newoether.agora.ui.chat.message

import android.content.res.Resources
import com.newoether.agora.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Transfer receipt and diagnostic parsing belongs to presentation, never transport retry logic. */
internal fun fileTransferEndpoint(server: String?, path: String?): String? =
    listOfNotNull(server?.takeIf(String::isNotBlank), path?.takeIf(String::isNotBlank))
        .takeIf { it.isNotEmpty() }?.joinToString(":")

internal fun validFileTransferReceipt(result: JsonObject?): Boolean {
    if ((result?.get("ok") as? JsonPrimitive)?.booleanOrNull != true) return false
    if (result.transferText("status") !in setOf("copied", "unchanged")) return false
    if (listOf("source_server", "source_path", "destination_server", "destination_path")
            .any { result.transferText(it).isNullOrBlank() }) return false
    val bytes = result?.get("bytes") as? JsonPrimitive ?: return false
    if (bytes.isString || bytes.longOrNull?.let { it >= 0 } != true) return false
    val source = result.transferText("source_sha256") ?: return false
    val destination = result.transferText("destination_sha256") ?: return false
    return source.matches(Regex("[a-fA-F0-9]{64}")) && source.equals(destination, ignoreCase = true)
}

internal fun fileTransferFailed(result: JsonObject?): Boolean =
    (result?.get("ok") as? JsonPrimitive)?.booleanOrNull == false ||
        (result?.get("failed") as? JsonPrimitive)?.booleanOrNull == true ||
        result?.get("error")?.let { error ->
            error is JsonObject || (error as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
        } == true

internal fun fileTransferError(result: JsonObject?, raw: String?): String? {
    fun detail(value: String?, depth: Int): String? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (depth >= 3 || !text.startsWith("{")) return text
        val envelope = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return text
        return errorDetail(envelope)?.let { detail(it, depth + 1) } ?: text
    }
    return detail(errorDetail(result), 0) ?: raw?.takeIf {
        it.isNotBlank() && runCatching { Json.parseToJsonElement(it) }.isFailure
    }
}

private fun errorDetail(result: JsonObject?): String? {
    val nested = result?.get("error") as? JsonObject
    return listOf(nested.transferText("message"), result.transferText("message"),
        result.transferText("reason"), result.transferText("detail"), nested.transferText("detail"),
        nested.transferText("code"), result.transferText("error"))
        .firstOrNull { !it.isNullOrBlank() }
}

internal fun JsonObject?.transferText(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun Resources.fileTransferSummary(presentation: ToolPresentation): String {
    val source = presentation.subject
    val destination = presentation.destination
    val route = listOfNotNull(source, destination).joinToString(" → ")
    return when (presentation.state) {
        ToolPresentationState.CALLING, ToolPresentationState.RUNNING -> if (route.isEmpty()) {
            getString(R.string.tool_transferring_file_default)
        } else getString(R.string.tool_transferring_file, route)
        ToolPresentationState.COMPLETED, ToolPresentationState.EMPTY -> when {
            presentation.outcome == "unchanged" -> getString(R.string.tool_transfer_unchanged, route)
            presentation.transferBytes != null -> getString(R.string.tool_transferred_file, route, presentation.transferBytes)
            else -> getString(R.string.tool_transfer_unconfirmed)
        }
        ToolPresentationState.FAILED -> toolFailureReasonSummary(presentation.errorMessage)
            ?: getString(if (presentation.outcome == "invalid_receipt") R.string.tool_transfer_unconfirmed
                else R.string.tool_transfer_failed)
        ToolPresentationState.STOPPED -> stoppedToolSummary(presentation)
        ToolPresentationState.BACKGROUND_RUNNING -> getString(R.string.tool_transfer_unconfirmed)
    }
}
