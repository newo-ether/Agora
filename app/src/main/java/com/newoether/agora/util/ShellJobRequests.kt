package com.newoether.agora.util

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Wire payloads for durable shell jobs; transport and retry ownership stay in ShellClient. */
internal fun shellJobStartBody(
    command: String,
    timeoutMs: Int,
    workdir: String,
    description: String,
): String = buildJsonObject {
    put("command", command)
    put("timeout_ms", timeoutMs)
    put("workdir", workdir)
    put("description", description)
}.toString()

internal fun shellJobListBody(offset: Int, limit: Int): String = buildJsonObject {
    put("offset", offset)
    put("limit", limit)
}.toString()
