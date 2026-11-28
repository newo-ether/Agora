package com.newoether.agora.data.local

/** Shared bounds for durable message rows and semantic source projections. */
object DatabaseConstants {
    const val MAX_EMBEDDING_TEXT_LENGTH = 8000
    const val MAX_PERSISTED_SEGMENTS_BYTES = 600_000
    const val MAX_PERSISTED_TEXT_CHARS = 100_000
}
