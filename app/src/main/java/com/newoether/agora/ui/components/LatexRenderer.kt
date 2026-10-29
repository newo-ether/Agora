package com.newoether.agora.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.scilab.forge.jlatexmath.DefaultTeXFont
import org.scilab.forge.jlatexmath.TeXConstants
import ru.noties.jlatexmath.JLatexMathDrawable
import com.newoether.agora.util.DebugLog
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

// The log tag for this file's own diagnostics.
private const val TAG = "LatexRenderer"

/**
 * One parsed piece of message text. A LaTeX span also keeps [source]: its exact original slice,
 * delimiters included, plus any whitespace removed next to an inline formula, so copying the
 * rendered formula reproduces the original text.
 */
data class LatexSpan(
    val isLatex: Boolean,
    val content: String,
    val display: Boolean = false,
    val source: String = content,
)

// ── Patterns ──────────────────────────────────────────────────────────

/** Greek letters (U+0370–U+03FF) and math symbols (U+2200–U+2AFF). */
private val MATH_UNICODE = Regex("""[Ͱ-Ͽ∀-⫿]""")

/** LaTeX command — backslash + letters, e.g. \frac \alpha \text \sum. */
private val LATEX_COMMAND = Regex("""\\[a-zA-Z]+""")

/** Escaped dollar sign — strong LaTeX signal (not used in prose). */
private val ESCAPED_DOLLAR = Regex("""\\\$""")

/** Math structural markers — sub/superscript braces, begin/end. */
private val MATH_STRUCTURE = Regex("""[\^_]\{|\\begin|\\end""")

/** Pure number or percentage explicitly wrapped in $...$ */
private val PURE_NUM = Regex("""^-?\d+(\.\d+)?\\?%?$""")

/** Math operators — presence strongly suggests LaTeX, not prose. */
private val MATH_OPS = Regex("""[+\-*/=<>|^_]""")

/** English prose stop-words — signals natural language, not math. */
private val PROSE_WORD = Regex(
    """\b(and|or|the|a|an|in|on|to|for|of|is|are|was|were|be|it|we|you|he|she|they|""" +
    """costs?|price|dollar|another|other|this|that|each|per|total|only|about|""" +
    """more|less|than|with|from|by|can|will|would|should|could|may|not|but|""" +
    """one|two|three|four|five|ten|new|old|some|all|any|just|also|has|have|had|what)\b""",
    RegexOption.IGNORE_CASE
)

/** Sentence-ending punctuation followed by space — strong prose signal. */
private val SENTENCE_PUNCT = Regex("""[.!?;:]["») \t]""")

private const val LATEX_URL_PREFIX = "latex://"
private const val LATEX_URL_INLINE = "inline/"
private const val LATEX_URL_DISPLAY = "display/"
private val LATEX_BASE64 = Base64.UrlSafe

internal data class LatexImageRequest(
    val latex: String,
    val display: Boolean,
    val source: String,
)

private data class ProtectedRange(
    val start: Int,
    val endExclusive: Int,
)

private fun findMarkdownProtectedRanges(src: String): List<ProtectedRange> {
    val ranges = mutableListOf<ProtectedRange>()
    var i = 0
    while (i < src.length) {
        if (src.startsWith("```", i)) {
            val end = src.indexOf("```", startIndex = i + 3)
            val endExclusive = if (end >= 0) end + 3 else src.length
            ranges.add(ProtectedRange(i, endExclusive))
            i = endExclusive
            continue
        }

        if (src[i] == '`') {
            val lineEnd = src.indexOf('\n', startIndex = i + 1)
                .let { if (it >= 0) it else src.length }
            val end = src.indexOf('`', startIndex = i + 1)
            val endExclusive = if (end >= 0 && end < lineEnd) end + 1 else lineEnd
            ranges.add(ProtectedRange(i, endExclusive))
            i = endExclusive
            continue
        }

        i++
    }
    return ranges
}

// ── Unicode gate ──────────────────────────────────────────────────────

/**
 * Returns true if every non-ASCII character in [s] is safely inside `{…}`,
 * OR is a Greek / math-symbol Unicode codepoint.
 * This permits braced non-ASCII content and commands such as `\alpha` while rejecting bare prose.
 */
private fun nonAsciiInsideBraces(s: String): Boolean {
    var depth = 0
    var esc = false
    for (ch in s) {
        if (esc) { esc = false; continue }
        if (ch == '\\') { esc = true; continue }
        if (ch == '{') { depth++; continue }
        if (ch == '}' && depth > 0) { depth--; continue }
        if (ch.code > 127 && depth == 0 && !MATH_UNICODE.matches(ch.toString()) && ch.isLetter()) {
            return false
        }
    }
    return true
}

// ── Content discriminator for $...$ ────────────────────────────────────

/**
 * Heuristic: given the trimmed content between a pair of `$` delimiters,
 * decide whether it is more likely LaTeX or natural-language prose.
 */
private fun isLikelyLatex(content: String): Boolean {
    val t = content.trim()
    if (t.isEmpty()) return false

    // Non-ASCII outside braces → prose (check BEFORE positive signals —
    // a rogue Chinese char must veto even if \mathrm or = is present)
    if (!nonAsciiInsideBraces(t)) return false

    // Strong positive signals — these are almost certainly LaTeX
    if (LATEX_COMMAND.containsMatchIn(t)) return true
    if (ESCAPED_DOLLAR.containsMatchIn(t)) return true
    if (MATH_STRUCTURE.containsMatchIn(t)) return true
    if (PURE_NUM.matches(t)) return true
    // Single character — math variable (not the article "a" / "A")
    if (t.length == 1 && t[0].isLetterOrDigit()) return true
    val hasMathOps = MATH_OPS.containsMatchIn(t)

    // Strong negative signals — these are almost certainly prose
    if (SENTENCE_PUNCT.containsMatchIn(t)) return false

    // Prose stop-words: fatal unless math operators are also present.
    // Single-letter matches are excluded — they are math variables.
    val words = t.split(Regex("""\s+"""))
    val hasProseWord = words.any { w -> w.length > 1 && PROSE_WORD.matches(w) }
    if (hasProseWord && !hasMathOps) return false

    // Many whitespace-delimited tokens → prose (unless math ops dominate)
    if (words.size > 5 && !hasMathOps) return false

    // No letters at all, no math ops, not pure number → not LaTeX
    // (catches "800–" where – is non-ASCII punctuation)
    if (t.none { it.isLetter() } && !hasMathOps) return false

    // Short, no prose signals — default to LaTeX
    return true
}

// ── Markdown escape ────────────────────────────────────────────────────

fun String.escapeDollarForMarkdown(): String = buildString {
    val src = this@escapeDollarForMarkdown
    val protectedRanges = findMarkdownProtectedRanges(src)
    var protectedIndex = 0
    var i = 0
    while (i < src.length) {
        while (protectedIndex < protectedRanges.size && protectedRanges[protectedIndex].endExclusive <= i) {
            protectedIndex++
        }
        val protected = protectedRanges.getOrNull(protectedIndex)
        if (protected != null && i >= protected.start) {
            append(src, i, protected.endExclusive)
            i = protected.endExclusive
            protectedIndex++
            continue
        }

        val ch = src[i]
        // Bare $ → \$ (but not already-escaped \$)
        if (ch == '$' && (i == 0 || src[i - 1] != '\\')) {
            append('\\')
        }
        append(ch)
        i++
    }
}

// ── Span parser ────────────────────────────────────────────────────────

fun parseLatexSpans(
    text: String,
    parseInlineDollarMath: Boolean = false,
): List<LatexSpan> {
    val spans = mutableListOf<LatexSpan>()
    val buf = StringBuilder()
    val protectedRanges = findMarkdownProtectedRanges(text)
    var protectedIndex = 0
    var i = 0
    while (i < text.length) {
        while (protectedIndex < protectedRanges.size && protectedRanges[protectedIndex].endExclusive <= i) {
            protectedIndex++
        }
        val protected = protectedRanges.getOrNull(protectedIndex)
        if (protected != null && i >= protected.start) {
            buf.append(text, i, protected.endExclusive)
            i = protected.endExclusive
            protectedIndex++
            continue
        }

        // $$ display math
        if (text.startsWith("$$", i)) {
            val end = text.indexOf("$$", startIndex = i + 2)
            if (end >= 0) {
                val latex = text.substring(i + 2, end).trim()
                if (latex.isNotBlank() && nonAsciiInsideBraces(latex)) {
                    if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                    spans.add(LatexSpan(true, latex, true, text.substring(i, end + 2)))
                    i = end + 2
                    continue
                } else if (latex.isNotBlank()) {
                    // Invalid display candidate. Keep recovery local so a stray
                    // "$$" cannot consume a later valid display opener/closer.
                    buf.append("$$")
                    i += 2
                    continue
                }
                buf.append("$$")
                i += 2
                continue
            }
        }

        // \[ display math
        if (text.startsWith("\\[", i)) {
            val end = text.indexOf("\\]", startIndex = i + 2)
            if (end >= 0) {
                val latex = text.substring(i + 2, end).trim()
                if (latex.isNotBlank() && nonAsciiInsideBraces(latex)) {
                    if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                    spans.add(LatexSpan(true, latex, true, text.substring(i, end + 2)))
                    i = end + 2
                    continue
                } else if (latex.isNotBlank()) {
                    buf.append("\\[")
                    i += 2
                    continue
                }
                buf.append("\\[")
                i += 2
                continue
            }
        }

        // \( inline math
        if (text.startsWith("\\(", i)) {
            val end = text.indexOf("\\)", startIndex = i + 2)
            if (end >= 0) {
                val latex = text.substring(i + 2, end).trim()
                if (latex.isNotBlank() && nonAsciiInsideBraces(latex)) {
                    if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                    spans.add(LatexSpan(true, latex, false, text.substring(i, end + 2)))
                    i = end + 2
                    continue
                } else if (latex.isNotBlank()) {
                    buf.append("\\(")
                    i += 2
                    continue
                }
                buf.append("\\(")
                i += 2
                continue
            }
        }

        // $ inline math — skip if preceded by \ (escaped)
        if (parseInlineDollarMath && text[i] == '$' && !text.startsWith("$$", i)) {
            val prevChar = if (i > 0) text[i - 1] else ' '
            if (prevChar != '\\') {
                // Find real closing $ on the same line (skip escaped \$)
                val newline = text.indexOf('\n', startIndex = i + 1)
                val lineEnd = if (newline < 0) text.length else newline
                var end = text.indexOf('$', startIndex = i + 1)
                while (end in (i + 1)..<lineEnd && text[end - 1] == '\\') {
                    end = text.indexOf('$', startIndex = end + 1)
                }
                if (end in (i + 1)..<lineEnd) {
                    val latex = text.substring(i + 1, end).trim()
                    if (latex.isNotEmpty() && isLikelyLatex(latex)) {
                        if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                        spans.add(LatexSpan(true, latex, false, text.substring(i, end + 1)))
                        i = end + 1
                        continue
                    }
                    // Content looks like prose → only treat the opening $ as literal.
                    // Don't consume the closing $ — it may belong to a valid LaTeX pair later.
                    buf.append('$')
                    i++
                    continue
                }

                // No real same-line closing $ → treat $ as literal (could be a dollar amount)
                buf.append('$')
                i++
                continue
            }
        }

        // Escaped \$ → literal $
        if (text.startsWith("\\$", i)) {
            buf.append('$')
            i += 2
            continue
        }

        buf.append(text[i])
        i++
    }
    if (buf.isNotEmpty()) spans.add(LatexSpan(false, buf.toString()))

    // Escape $ in non-LaTeX spans for markdown compatibility
    for (idx in spans.indices) {
        val span = spans[idx]
        if (!span.isLatex) {
            spans[idx] = span.copy(content = span.content.escapeDollarForMarkdown())
        }
    }

    // Trim whitespace around inline LaTeX spans. The removed whitespace moves into the formula's
    // source so a copy of the rendered text still equals the original.
    for (idx in spans.indices) {
        val span = spans[idx]
        if (span.isLatex && !span.display) {
            var source = span.source
            if (idx > 0) {
                val prev = spans[idx - 1]
                if (!prev.isLatex) {
                    val trimmed = prev.content.trimEnd()
                    source = prev.content.substring(trimmed.length) + source
                    spans[idx - 1] = prev.copy(content = trimmed)
                }
            }
            if (idx + 1 < spans.size) {
                val next = spans[idx + 1]
                if (!next.isLatex) {
                    val trimmed = next.content.trimStart()
                    source += next.content.substring(0, next.content.length - trimmed.length)
                    spans[idx + 1] = next.copy(content = trimmed)
                }
            }
            spans[idx] = span.copy(source = source)
        }
    }

    return spans
}

// ── Rendering ──────────────────────────────────────────────────────────

fun renderLatexToBitmap(
    latex: String,
    textSize: Float = 48f,
    color: Int = 0xFF000000.toInt(),
    fallbackW: Int = 800,
    fallbackH: Int = 200,
    minW: Int = 0,
): Bitmap? {
    return try {
        val drawable = JLatexMathDrawable.builder(latex)
            .textSize(textSize)
            .color(color)
            .build()
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        val w = maxOf(iw.takeIf { it > 0 } ?: fallbackW, minW)
        val h = ih.takeIf { it > 0 } ?: fallbackH
        // Placeholders are centered on the text, so pad above or below until the TeX math axis
        // (where fraction bars and the minus sign sit) is the bitmap's vertical center.
        val padding = if (ih > 0) mathAxisCenteringPadding(drawable, textSize, h) else AxisPadding(0, 0)
        val bmp = Bitmap.createBitmap(w, h + padding.top + padding.bottom, Bitmap.Config.ARGB_8888)
        val canvas = FilledTextCanvas(bmp)
        canvas.translate(0f, padding.top.toFloat())
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bmp
    } catch (e: Exception) {
        // A swallowed exception here is what turns a formula back into its own source text, so the
        // failure has to reach the log or it is invisible: the caller only ever sees a null and
        // silently paints the raw LaTeX. Only the exception type and stack are logged, never the
        // formula itself.
        DebugLog.w(TAG, "latex render failed size=$textSize", e)
        null
    }
}

internal data class AxisPadding(val top: Int, val bottom: Int)

/**
 * The transparent rows to add so the math axis lands on the vertical center of a formula of
 * [height] pixels. The axis is the formula font's axis height above the icon's baseline.
 */
private fun mathAxisCenteringPadding(drawable: JLatexMathDrawable, textSize: Float, height: Int): AxisPadding {
    val icon = drawable.icon()
    val baselineY = icon.baseLine * height
    val axisHeight = DefaultTeXFont(textSize).getAxisHeight(TeXConstants.STYLE_DISPLAY) * textSize
    return axisCenteringPadding(axisY = baselineY - axisHeight, height = height)
}

/** Rows above and below that move [axisY] (pixels from the top) to the center of the result. */
internal fun axisCenteringPadding(axisY: Float, height: Int): AxisPadding {
    val above = axisY.coerceIn(0f, height.toFloat())
    val below = height - above
    return if (above < below) {
        AxisPadding(top = (below - above).roundToInt(), bottom = 0)
    } else {
        AxisPadding(top = 0, bottom = (above - below).roundToInt())
    }
}

fun canRenderLatex(latex: String): Boolean {
    return try {
        JLatexMathDrawable.builder(latex).textSize(48f).color(0).build()
        true
    } catch (_: Exception) { false }
}

// latex://<mode>/<base64 latex>/<base64 source>. URL-safe Base64 never contains '/'.
private fun encodeLatexUrl(latex: String, display: Boolean, source: String): String {
    val mode = if (display) LATEX_URL_DISPLAY else LATEX_URL_INLINE
    val encoded = LATEX_BASE64.encode(latex.toByteArray(Charsets.UTF_8))
    val encodedSource = LATEX_BASE64.encode(source.toByteArray(Charsets.UTF_8))
    return "$mode$encoded/$encodedSource"
}

private fun decodeLatexUrl(encoded: String): String {
    return LATEX_BASE64.decode(encoded).decodeToString()
}

private fun decodeLatexLink(link: String): LatexImageRequest? {
    if (!link.startsWith(LATEX_URL_PREFIX)) return null
    val payload = link.removePrefix(LATEX_URL_PREFIX)
    val (display, encoded) = when {
        payload.startsWith(LATEX_URL_DISPLAY) -> true to payload.removePrefix(LATEX_URL_DISPLAY)
        payload.startsWith(LATEX_URL_INLINE) -> false to payload.removePrefix(LATEX_URL_INLINE)
        else -> false to payload
    }
    val parts = encoded.split('/')
    if (parts.size != 2) return null
    return try {
        LatexImageRequest(decodeLatexUrl(parts[0]), display, decodeLatexUrl(parts[1]))
    } catch (_: Exception) {
        null
    }
}

internal fun isDisplayLatexLink(link: String?): Boolean {
    return link?.let(::decodeLatexLink)?.display == true
}

/** The original text a rendered formula stands for, or null when [link] is not a LaTeX link. */
internal fun latexSourceForLink(link: String): String? = decodeLatexLink(link)?.source

/**
 * The formula an image stands for, from its latex:// URL or from its source text. The markdown
 * renderer hands an inline image's alternate text, not its URL, to the image transformer, and a
 * formula's alternate text is its source (so copying it reproduces the original). The source
 * holds exactly one formula, so parsing it gives the same request its URL encodes.
 */
internal fun latexImageRequest(linkOrSource: String): LatexImageRequest? =
    decodeLatexLink(linkOrSource) ?: latexRequestForSource(restoreFormulaLineBreaks(linkOrSource))

private fun latexRequestForSource(source: String): LatexImageRequest? {
    val spans = parseLatexSpans(source, parseInlineDollarMath = true)
    val formula = spans.singleOrNull { it.isLatex } ?: return null
    if (spans.any { !it.isLatex && it.content.isNotBlank() }) return null
    return LatexImageRequest(formula.content, formula.display, source)
}

fun latexToMarkdown(latexContent: String, display: Boolean, source: String): String {
    val image = "![latex]($LATEX_URL_PREFIX${encodeLatexUrl(latexContent, display, source)})"
    return if (display) "\n\n$image\n\n" else image
}

private fun renderTextToBitmap(text: String, textSize: Float, color: Int): Bitmap {
    val paint = android.graphics.Paint().apply {
        this.textSize = textSize * 0.6f
        this.color = color
        isAntiAlias = true
    }
    val fm = paint.fontMetrics
    val w = (paint.measureText(text) + 8f).toInt().coerceAtLeast(1)
    val h = ((fm.descent - fm.ascent) + 8f).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(0x00000000)
    val canvas = Canvas(bmp)
    canvas.drawText(text, 4f, -fm.ascent + 4f, paint)
    return bmp
}

internal data class LatexRenderKey(
    val latex: String,
    val textSize: Float,
    val color: Int,
)

internal object LatexBitmapCache {
    private const val MAX_ENTRIES = 128

    private val lock = Any()
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = mutableMapOf<LatexRenderKey, Deferred<Bitmap>>()
    private val bitmaps = object : LinkedHashMap<LatexRenderKey, Bitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LatexRenderKey, Bitmap>?): Boolean {
            return size > MAX_ENTRIES
        }
    }
    // Rendered sizes as snapshot state, so layout decisions that depend on a formula's size
    // recompose when its bitmap finishes. Sizes outlive evicted bitmaps; a re-render is identical.
    private val renderedSizes = mutableStateMapOf<LatexRenderKey, Size>()

    fun get(key: LatexRenderKey): Bitmap? = synchronized(lock) {
        bitmaps[key]
    }

    fun observedSize(key: LatexRenderKey): Size? = renderedSizes[key]

    fun renderAsync(key: LatexRenderKey): Deferred<Bitmap> = synchronized(lock) {
        bitmaps[key]?.let { return CompletableDeferred(it) }
        inFlight[key]?.let { return it }

        val deferred = CompletableDeferred<Bitmap>()
        inFlight[key] = deferred
        renderScope.launch {
            try {
                val rendered = renderBitmap(key)
                renderedSizes[key] = Size(rendered.width.toFloat(), rendered.height.toFloat())
                synchronized(lock) {
                    bitmaps[key] = rendered
                    if (inFlight[key] === deferred) {
                        inFlight.remove(key)
                    }
                }
                deferred.complete(rendered)
            } catch (e: CancellationException) {
                synchronized(lock) {
                    if (inFlight[key] === deferred) {
                        inFlight.remove(key)
                    }
                }
                deferred.completeExceptionally(e)
                throw e
            } catch (t: Throwable) {
                synchronized(lock) {
                    if (inFlight[key] === deferred) {
                        inFlight.remove(key)
                    }
                }
                deferred.completeExceptionally(t)
            }
        }
        deferred
    }

    suspend fun awaitRendered(key: LatexRenderKey): Bitmap {
        get(key)?.let { return it }
        return renderAsync(key).await()
    }

    private fun renderBitmap(key: LatexRenderKey): Bitmap {
        val fw = (key.textSize * 10).toInt()
        val fh = (key.textSize * 2).toInt()
        return renderLatexToBitmap(
            key.latex,
            key.textSize,
            key.color,
            fallbackW = fw,
            fallbackH = fh,
            minW = 0,
        ) ?: renderTextToBitmap("$${key.latex}$", key.textSize, key.color)
    }
}

internal fun estimateLatexPlaceholderSize(latex: String, textSize: Float, display: Boolean): Size {
    val charCount = latex.length.coerceAtLeast(1)
    val minWidth = textSize * if (display) 4f else 1.4f
    val maxWidth = textSize * if (display) 18f else 8f
    val width = (charCount * textSize * if (display) 0.46f else 0.38f)
        .coerceIn(minWidth, maxWidth)
    val height = textSize * if (display) 1.45f else 1.05f
    return Size(width, height)
}
