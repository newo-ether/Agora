package com.newoether.agora.api

/** Utilities for protocol-specific API version paths on custom endpoint base URLs. */
object BaseUrlResolver {
    /**
     * Matches an API version segment anywhere in the path: `/v1`, `/v1beta`,
     * `/compatible-mode/v1`, `/v2`, … If present, the URL is already canonical and
     * must not have `/v1` appended.
     */
    private val VERSION_SEGMENT = Regex("""/v\d""")
    private val TRAILING_VERSION_SEGMENT = Regex("""/v\d[A-Za-z0-9._-]*$""", RegexOption.IGNORE_CASE)

    fun hasVersionSegment(url: String): Boolean =
        VERSION_SEGMENT.containsMatchIn(url.trimEnd('/'))

    /** Appends `/v1` unless the URL is blank or already carries a version segment. */
    fun withV1(url: String): String {
        val trimmed = url.trimEnd('/')
        return if (trimmed.isBlank() || hasVersionSegment(trimmed)) trimmed else "$trimmed/v1"
    }

    /**
     * Removes only a terminal API version segment. This intentionally leaves URLs such as
     * `/v1/proxy` untouched: only the old sync behavior could have appended a version at the end.
     */
    fun withoutTrailingVersion(url: String): String? {
        val trimmed = url.trimEnd('/')
        val match = TRAILING_VERSION_SEGMENT.find(trimmed) ?: return null
        return trimmed.removeRange(match.range).trimEnd('/').takeIf { it.isNotBlank() }
    }
}
