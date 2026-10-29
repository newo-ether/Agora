package com.newoether.agora.ui.chat.message

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Scheme for a Markdown image whose bytes live on a configured shell device. */
internal const val SHELL_MEDIA_SCHEME = "shell://"

/** Device name the shell tools use for the on-device sandbox. */
internal const val LOCAL_SANDBOX_SERVER = "Local Sandbox"

/**
 * One parsed `shell://<server>/<path>` destination. [server] matches a shell device name
 * case-insensitively; [path] is the absolute path on that device.
 */
internal data class ShellMediaLink(val server: String, val path: String) {
    /** Identity of the file across spellings that differ only in server case or angle brackets. */
    val key: String get() = "${server.lowercase()}\u0000$path"
}

/**
 * Parses `shell://<server>/<path>`. Both parts are percent-decoded as UTF-8, so a space is `%20`.
 * A path whose first segment is a drive (`F:/out/a.png`) stays a Windows path; any other path is
 * absolute from `/`. Returns null for every other link and for malformed escapes.
 */
internal fun parseShellMediaLink(link: String): ShellMediaLink? {
    val raw = link.trim().removeSurrounding("<", ">")
    if (!raw.regionMatches(0, SHELL_MEDIA_SCHEME, 0, SHELL_MEDIA_SCHEME.length, ignoreCase = true)) return null
    val rest = raw.substring(SHELL_MEDIA_SCHEME.length)
    val slash = rest.indexOf('/')
    if (slash <= 0) return null
    val server = percentDecode(rest.substring(0, slash))?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val tail = percentDecode(rest.substring(slash + 1)) ?: return null
    if (tail.isBlank() || '\u0000' in tail) return null
    val drive = tail.length >= 2 && tail[0].isLetter() && tail[1] == ':'
    val path = if (drive || tail.startsWith('/')) tail else "/$tail"
    return ShellMediaLink(server, path)
}

/** Strict RFC 3986 percent decoding; `+` stays literal because these are paths, not forms. */
private fun percentDecode(value: String): String? {
    if ('%' !in value) return value
    val bytes = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%') {
            if (index + 2 >= value.length) return null
            val high = Character.digit(value[index + 1], 16)
            val low = Character.digit(value[index + 2], 16)
            if (high < 0 || low < 0) return null
            bytes.write(high * 16 + low)
            index += 3
        } else {
            // Copy the literal run at once so surrogate pairs encode as one code point.
            val end = value.indexOf('%', index).takeIf { it >= 0 } ?: value.length
            bytes.write(value.substring(index, end).toByteArray(Charsets.UTF_8))
            index = end
        }
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
