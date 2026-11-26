package com.newoether.agora.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import kotlin.math.roundToInt

/** Pixel size and density of the background screen. */
internal data class PhoneBackgroundSpec(val width: Int, val height: Int, val dpi: Int) {
    /** Width of the short side in dp, which decides phone vs tablet layouts. */
    val shortDp: Int get() = minOf(width, height) * 160 / dpi
}

/** The main display in portrait terms. */
internal data class PhoneNativeDisplay(val short: Int, val long: Int, val dpi: Int)

/**
 * Resolves the background screen size. Density always stays at the phone's own value, so a larger
 * screen fits more content instead of drawing the same layout with more pixels.
 */
internal object PhoneBackgroundGeometry {
    val SIZES = listOf("native", "1.25", "1.5", "custom")
    /** Below this short side many apps clip or break their phone layout. */
    const val MIN_SHORT_DP = 320
    /** Bounds the ImageReader buffers; one RGBA frame at 4096x4096 is already 64 MB. */
    const val MAX_SIDE = 4096

    fun native(context: Context): PhoneNativeDisplay {
        val main = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw PhoneFailure("display_unavailable")
        val size = android.graphics.Point().also(main::getRealSize)
        val metrics = android.util.DisplayMetrics().also(main::getRealMetrics)
        return PhoneNativeDisplay(minOf(size.x, size.y), maxOf(size.x, size.y), metrics.densityDpi)
    }

    fun minShortPx(dpi: Int): Int = even((MIN_SHORT_DP * dpi + 159) / 160)

    fun resolve(native: PhoneNativeDisplay, settings: PhoneUseSettings): PhoneBackgroundSpec {
        val (short, long) = when (settings.backgroundSize) {
            "1.25" -> scaled(native, 1.25f)
            "1.5" -> scaled(native, 1.5f)
            "custom" -> if (settings.backgroundCustomShort > 0 && settings.backgroundCustomLong > 0) {
                settings.backgroundCustomShort to settings.backgroundCustomLong
            } else native.short to native.long
            else -> native.short to native.long
        }
        val s = even(short.coerceIn(minShortPx(native.dpi), MAX_SIDE))
        val l = even(long.coerceIn(s, MAX_SIDE))
        return if (settings.backgroundLandscape) PhoneBackgroundSpec(l, s, native.dpi)
            else PhoneBackgroundSpec(s, l, native.dpi)
    }

    private fun scaled(native: PhoneNativeDisplay, factor: Float) =
        (native.short * factor).roundToInt() to (native.long * factor).roundToInt()

    /** Rounds up to even; encoders and some compositors reject odd sizes. MAX_SIDE is even. */
    private fun even(value: Int): Int = minOf(MAX_SIDE, (value + 1) and 1.inv())
}
