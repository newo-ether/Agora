package com.newoether.agora.api

import java.io.EOFException
import java.io.IOException
import okio.BufferedSource

/** Bounds decoded HTTP bytes, including unknown-length and compressed responses. */
fun BufferedSource.readBoundedWireText(limit: Long): String {
    require(limit > 0 && limit < Long.MAX_VALUE)
    if (request(limit + 1)) throw IOException("Response exceeds the $limit-byte transport limit")
    return readUtf8()
}

fun BufferedSource.readBoundedWireLine(limit: Long): String? {
    require(limit > 0 && limit < Long.MAX_VALUE)
    if (exhausted()) return null
    return try {
        readUtf8LineStrict(limit)
    } catch (_: EOFException) {
        if (buffer.size > limit) throw IOException("Event exceeds the $limit-byte transport limit")
        throw IOException("Incomplete encrypted event line")
    }
}
