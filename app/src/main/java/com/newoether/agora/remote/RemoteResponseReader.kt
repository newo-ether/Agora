package com.newoether.agora.remote

import okio.BufferedSource
import java.io.EOFException
import java.io.IOException

internal const val MAX_REMOTE_RESPONSE_BYTES = 4L * 1024L * 1024L

/** Bound bytes before UTF-8 decoding or JSON allocation, including chunked/unterminated input. */
internal fun BufferedSource.readRemoteResponse(): String {
    if (request(MAX_REMOTE_RESPONSE_BYTES + 1)) throw RemoteContentLimitException()
    return readUtf8()
}

internal fun BufferedSource.readRemoteEventLine(): String? {
    if (exhausted()) return null
    return try { readUtf8LineStrict(MAX_REMOTE_RESPONSE_BYTES) }
    catch (_: EOFException) {
        if (buffer.size > MAX_REMOTE_RESPONSE_BYTES) throw RemoteContentLimitException()
        throw IOException("Incomplete Filo event")
    }
}

internal class RemoteContentLimitException : java.io.IOException("Filo response exceeds the transport limit")
