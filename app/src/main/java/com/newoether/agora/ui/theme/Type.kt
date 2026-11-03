package com.newoether.agora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.newoether.agora.R

val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.jetbrains_mono_bolditalic, FontWeight.Bold, FontStyle.Italic),
)

@OptIn(ExperimentalTextApi::class)
private fun miOutfitFont(weight: FontWeight) = Font(
    resId = R.font.mioutfit_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
    ),
)

val OutfitFamily = FontFamily(
    miOutfitFont(FontWeight.ExtraLight),
    miOutfitFont(FontWeight.Light),
    miOutfitFont(FontWeight.Normal),
    miOutfitFont(FontWeight.Medium),
    miOutfitFont(FontWeight.Bold),
)

// Geometric (modular) type scale: every distinct size is a term of a geometric
// sequence anchored at body = 16sp with common ratio r = 1.2 (minor third).
// Sizes: 11, 13, 16, 19, 23, 28, 33, 40, 48, 57. Line heights scale per tier
// (display 1.15× · headline 1.25× · title 1.3× · body 1.45× · label 1.4×).
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 66.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 48.sp,
        lineHeight = 55.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 33.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 35.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.5.sp
    )
)

// ─────────────────────────────────────────────────────────────────────────────
// ChatType — single source of truth for the chat surface's typographic scale.
//
// The chat page is an information-dense, immersive-reading surface and so uses a
// TIGHTER scale than the 1.2 (minor-third) geometric scale that drives Settings'
// big collapsing titles. Here the ratio is ~1.15 (major-second), anchored at the
// reading body (15sp). Outfit's tall x-height makes 15sp read like ~16sp Roboto.
//
// Five semantic tiers — never reach past them on a chat Text:
//   · Title   — brand 20 · sheet 19 · conversation 17   (the only sizes ≥17)
//   · Input   — 16 (slightly above body for a comfortable touch target)
//   · Body    — 15 (user + assistant message text; the anchor)
//   · Sub     — 13 (thought body; clearly subordinate to body)
//   · Meta    — 12 labels/status · 11 micro (token counts, badges)
//
// Hierarchy is carried by SIZE + WEIGHT + COLOR together: e.g. the collapsed
// "thought for Ns" eyebrow is meta(12) but Bold + primary, so it out-ranks the
// 13sp thought body it introduces despite being smaller. Call sites supply color.
/** Mutable font family for ChatType styles. Set from Theme.kt when font preference changes. */
internal var chatFontFamily: FontFamily = OutfitFamily

/** Windows narrower than this (a large system display size on a typical phone) use the compact
 *  tier: AgoraTheme scales every sp by [COMPACT_FONT_SCALE] (16 sp body -> 15 sp) and a few composer
 *  insets tighten. Pixel-class phones at the default display size are ~411 dp and stay regular. */
internal const val CHAT_COMPACT_WIDTH_DP = 400
internal const val COMPACT_FONT_SCALE = 15f / 16f

/** Snapshot state so readers recompose when the window width crosses the threshold. Set from
 *  AgoraTheme before its content composes. */
internal var chatCompactWidth by mutableStateOf(false)

object ChatType {

    // Title tier
    // Brand wordmark in the new-chat capsule: prominent in the empty state, one
    // clean step above the active-conversation title (20 → 15).
    val brandTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp)
    val sheetTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp)
    // Active-conversation title: one step below the brand wordmark (16 → 15),
    // Bold so it still reads as a title against the 15sp Normal body.
    val conversationTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp)

    // Active-conversation title when it stands alone (no token subtitle): a touch
    // smaller than the 20sp brand wordmark so a lone title doesn't read as loud.
    val conversationTitleSolo get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp)

    // Input tier
    val input get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.5.sp)

    // Body tier. The compact tier also tightens the line height ratio by 1 sp on top of the sp scale.
    val body get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = if (chatCompactWidth) 23.sp else 24.sp)
    val userBody get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = if (chatCompactWidth) 23.2.sp else 24.2.sp)
    val thoughtBody get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
    val thoughtTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp)
    val errorBody get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)

    // Meta tier
    val meta get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp)
    val metaNormal get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)
    val micro get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp)

    // Code / mono
    val code = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val thoughtCode = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)
    val thoughtCodeLarge = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)

    // Sheet
    val detailTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp)

    // Rating
    val ratingTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 35.sp)

    // Drawer
    val conversationsTitle get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 32.sp)
    val drawerButton get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    val drawerSearch get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp)

    // Assistant markdown headings — even ~1.15 steps; h1 reined in (22, not 24)
    // so the jump from h2 stays proportional and h1 doesn't shout over 15sp body.
    val mdH1 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp)
    val mdH2 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp)
    val mdH3 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp)
    val mdH4 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val mdH5 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp)
    val mdH6 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp)

    // Thought-block headings — one tier below assistant markdown.
    val thH1 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp)
    val thH2 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp)
    val thH3 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    val thH4 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp)
    val thH5 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp)
    val thH6 get() = TextStyle(fontFamily = chatFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
}
