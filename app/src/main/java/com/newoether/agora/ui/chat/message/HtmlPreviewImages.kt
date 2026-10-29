package com.newoether.agora.ui.chat.message

import org.intellij.markdown.html.entities.Entities

/** Finds quoted img/src attributes without interpreting strings in comments or raw-text elements. */
internal fun rewriteHtmlPreviewImages(source: String, capability: (String) -> String?): String {
    val result = StringBuilder(source.length)
    var cursor = 0
    var rawText: String? = null
    while (cursor < source.length) {
        val raw = rawText
        val start = if (raw == null) source.indexOf('<', cursor)
            else source.indexOf("</$raw", cursor, ignoreCase = true)
        if (start < 0) { result.append(source, cursor, source.length); break }
        result.append(source, cursor, start)
        if (source.startsWith("<!--", start)) {
            val closing = source.indexOf("-->", start + 4)
            val end = if (closing < 0) source.length else closing + 3
            result.append(source, start, end)
            cursor = end
            continue
        }
        var end = start + 1
        var quote: Char? = null
        while (end < source.length) {
            val char = source[end]
            if (quote != null) { if (char == quote) quote = null }
            else if (char == '\'' || char == '"') quote = char
            else if (char == '>') break
            end++
        }
        if (end == source.length) { result.append(source, start, end); break }
        end++
        val closing = source.getOrNull(start + 1) == '/'
        val nameStart = start + if (closing) 2 else 1
        var nameEnd = nameStart
        while (nameEnd < end && !source[nameEnd].isWhitespace() && source[nameEnd] !in "/>" ) nameEnd++
        val name = source.substring(nameStart, nameEnd).lowercase()
        if (raw != null) {
            result.append(source, start, end)
            if (closing && name == raw) rawText = null
        } else {
            if (!closing && name == "img") {
                result.append(rewriteImageTag(source.substring(start, end), nameEnd - start, capability))
            } else result.append(source, start, end)
            if (!closing && name in setOf("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes")) {
                rawText = name
            }
            if (!closing && name == "plaintext") {
                result.append(source, end, source.length)
                break
            }
        }
        cursor = end
    }
    return result.toString()
}

private fun rewriteImageTag(tag: String, nameEnd: Int, capability: (String) -> String?): String {
    var cursor = nameEnd
    while (cursor < tag.length - 1) {
        while (cursor < tag.length && (tag[cursor].isWhitespace() || tag[cursor] == '/')) cursor++
        val start = cursor
        while (cursor < tag.length && !tag[cursor].isWhitespace() && tag[cursor] !in "=/>" ) cursor++
        val name = tag.substring(start, cursor)
        if (cursor == start) break
        while (cursor < tag.length && tag[cursor].isWhitespace()) cursor++
        if (tag.getOrNull(cursor) != '=') continue
        cursor++
        while (cursor < tag.length && tag[cursor].isWhitespace()) cursor++
        val quote = tag.getOrNull(cursor)
        if (quote == '\'' || quote == '"') {
            val valueStart = ++cursor
            while (cursor < tag.length && tag[cursor] != quote) cursor++
            if (name.equals("src", true)) {
                val link = decodeImageSource(tag.substring(valueStart, cursor))
                val url = capability(link) ?: return tag
                return tag.substring(0, valueStart) + url + tag.substring(cursor)
            }
            cursor++
        } else {
            while (cursor < tag.length && !tag[cursor].isWhitespace() && tag[cursor] != '>') cursor++
            // HTML ignores later duplicate attributes, including a quoted src after an unquoted one.
            if (name.equals("src", true)) return tag
        }
    }
    return tag
}

private val IMAGE_SOURCE_ENTITY = Regex("&(?:#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]+);")

/** Decode a single attribute pass without Markdown's output escaping or backslash processing. */
private fun decodeImageSource(value: String): String = IMAGE_SOURCE_ENTITY.replace(value) { match ->
    val entity = match.value
    val codePoint = when {
        entity.startsWith("&#x", ignoreCase = true) -> entity.substring(3, entity.length - 1).toIntOrNull(16)
        entity.startsWith("&#") -> entity.substring(2, entity.length - 1).toIntOrNull()
        else -> Entities.map[entity]
    }
    when {
        codePoint == null -> entity
        codePoint == 0 || codePoint in 0xd800..0xdfff || !Character.isValidCodePoint(codePoint) -> "\uFFFD"
        else -> String(Character.toChars(codePoint))
    }
}
