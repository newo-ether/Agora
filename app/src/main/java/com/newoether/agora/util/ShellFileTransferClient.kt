package com.newoether.agora.util

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Transfer payloads share the existing authenticated envelope and mutation retry policy. */
internal suspend fun ShellClient.transferPost(
    action: String,
    payload: String,
    callTimeoutMillis: Long? = null,
): JsonObject {
    val response = try {
        encryptedPost("/file/transfer/$action", payload, callTimeoutMillis)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: NetworkHttpException) {
        if (failure.statusCode == 404 || failure.statusCode == 405) {
            throw IllegalStateException(
                "This Conch server does not support file_transfer; update Conch before transferring files.", failure,
            )
        }
        throw failure
    }
    val result = Json.parseToJsonElement(response).jsonObject
    result["error"]?.jsonPrimitive?.content?.let { error(it) }
    check(result["ok"]?.jsonPrimitive?.content == "true") { "Invalid Conch transfer response" }
    return result
}
