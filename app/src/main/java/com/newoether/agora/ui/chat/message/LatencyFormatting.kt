package com.newoether.agora.ui.chat.message

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale

/** MeasureFormat renders the units, so they follow the device locale. */
internal fun formatLatency(millis: Long, locale: Locale = Locale.getDefault()): String {
    val measure = if (millis < 1_000L) {
        Measure(millis.toInt(), MeasureUnit.MILLISECOND)
    } else {
        Measure(millis / 1_000.0, MeasureUnit.SECOND)
    }
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).formatMeasures(measure)
}
