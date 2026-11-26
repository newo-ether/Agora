package com.newoether.agora.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** Pixel size and density of the background screen. */
internal data class PhoneBackgroundSpec(val width: Int, val height: Int, val dpi: Int) {
    /** Width of the short side in dp, which decides phone vs tablet layouts. */
    val shortDp: Int get() = minOf(width, height) * 160 / dpi
    val widthDp: Int get() = width * 160 / dpi
    val heightDp: Int get() = height * 160 / dpi
}

/** The main display in portrait terms. */
internal data class PhoneNativeDisplay(val short: Int, val long: Int, val dpi: Int)

/**
 * Resolves the background screen. Pixels and density are both free: the dp size (px * 160 / dpi)
 * decides how much fits and which layout apps pick, the pixels decide how sharp screenshots are.
 */
internal object PhoneBackgroundGeometry {
    val SIZES = listOf("native", "tablet", "custom")
    /** Below this short side many apps clip or break their phone layout. */
    const val MIN_SHORT_DP = 320
    /** Bounds the ImageReader buffers; one RGBA frame at 4096x4096 is already 64 MB. */
    const val MAX_SIDE = 4096
    const val MIN_DPI = 120
    const val MAX_DPI = 640
    /**
     * 640 x 1024 dp: past the 600 dp tablet breakpoint, 16:10, and a 2048 px long side, so
     * screenshots under the default 2048 px image limit are sent without downscaling.
     */
    const val TABLET_SHORT = 1280
    const val TABLET_LONG = 2048
    const val TABLET_DPI = 320

    fun native(context: Context): PhoneNativeDisplay {
        val main = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw PhoneFailure("display_unavailable")
        val size = android.graphics.Point().also(main::getRealSize)
        val metrics = android.util.DisplayMetrics().also(main::getRealMetrics)
        return PhoneNativeDisplay(minOf(size.x, size.y), maxOf(size.x, size.y), metrics.densityDpi)
    }

    fun minShortPx(dpi: Int): Int = even((MIN_SHORT_DP * dpi + 159) / 160)

    fun resolve(native: PhoneNativeDisplay, settings: PhoneUseSettings): PhoneBackgroundSpec {
        val custom = settings.backgroundCustomShort > 0 && settings.backgroundCustomLong > 0
        val (short, long, dpi) = when (settings.backgroundSize) {
            "tablet" -> Triple(TABLET_SHORT, TABLET_LONG, TABLET_DPI)
            "custom" -> if (custom) {
                Triple(settings.backgroundCustomShort, settings.backgroundCustomLong,
                    settings.backgroundCustomDpi.takeIf { it > 0 }?.coerceIn(MIN_DPI, MAX_DPI) ?: native.dpi)
            } else Triple(native.short, native.long, native.dpi)
            else -> Triple(native.short, native.long, native.dpi)
        }
        // Only custom input is range-checked; the phone's own density is used as reported.
        val d = dpi
        val s = even(short.coerceIn(minShortPx(d), MAX_SIDE))
        val l = even(long.coerceIn(s, MAX_SIDE))
        return if (settings.backgroundLandscape) PhoneBackgroundSpec(l, s, d)
            else PhoneBackgroundSpec(s, l, d)
    }

    /** Rounds up to even; encoders and some compositors reject odd sizes. MAX_SIDE is even. */
    private fun even(value: Int): Int = minOf(MAX_SIDE, (value + 1) and 1.inv())
}
