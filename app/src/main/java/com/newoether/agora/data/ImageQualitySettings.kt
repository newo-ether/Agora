package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Presets leave the retained custom values available when switching back to custom mode. */
data class ImageQualitySettings(
    val mode: String = BALANCED,
    val customMaxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
    val customJpegQuality: Int = DEFAULT_JPEG_QUALITY,
) {
    val maxLongEdge: Int
        get() = when (normalizeMode(mode)) {
            LIGHT -> LIGHT_MAX_LONG_EDGE
            HIGH -> HIGH_MAX_LONG_EDGE
            CUSTOM -> normalizeMaxLongEdge(customMaxLongEdge)
            else -> DEFAULT_MAX_LONG_EDGE
        }

    val jpegQuality: Int
        get() = when (normalizeMode(mode)) {
            LIGHT -> LIGHT_JPEG_QUALITY
            HIGH -> HIGH_JPEG_QUALITY
            CUSTOM -> normalizeJpegQuality(customJpegQuality)
            else -> DEFAULT_JPEG_QUALITY
        }

    companion object {
        const val LIGHT = "light"
        const val BALANCED = "balanced"
        const val HIGH = "high"
        const val CUSTOM = "custom"
        const val DEFAULT_MAX_LONG_EDGE = 2048
        const val DEFAULT_JPEG_QUALITY = 80
        const val LIGHT_MAX_LONG_EDGE = 1024
        const val LIGHT_JPEG_QUALITY = 75
        const val HIGH_MAX_LONG_EDGE = 4096
        const val HIGH_JPEG_QUALITY = 95
        const val MIN_LONG_EDGE = 128
        const val MAX_LONG_EDGE = 8192
        const val MIN_JPEG_QUALITY = 1
        const val MAX_JPEG_QUALITY = 100

        fun normalizeMode(mode: String?): String = when (mode) {
            LIGHT, BALANCED, HIGH, CUSTOM -> mode
            else -> BALANCED
        }

        fun normalizeMaxLongEdge(value: Int?): Int =
            (value ?: DEFAULT_MAX_LONG_EDGE).coerceIn(MIN_LONG_EDGE, MAX_LONG_EDGE)

        fun normalizeJpegQuality(value: Int?): Int =
            (value ?: DEFAULT_JPEG_QUALITY).coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)
    }
}

internal val IMAGE_QUALITY_MODE = stringPreferencesKey("image_quality_mode")
internal val IMAGE_QUALITY_MAX_LONG_EDGE = intPreferencesKey("image_quality_max_long_edge")
internal val IMAGE_QUALITY_JPEG_QUALITY = intPreferencesKey("image_quality_jpeg_quality")

/** Publishes one preferences snapshot and patches only supplied fields in one transaction. */
class ImageQualitySettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<ImageQualitySettings> = dataStore.data.map { preferences ->
        ImageQualitySettings(
            mode = ImageQualitySettings.normalizeMode(preferences[IMAGE_QUALITY_MODE]),
            customMaxLongEdge = ImageQualitySettings.normalizeMaxLongEdge(preferences[IMAGE_QUALITY_MAX_LONG_EDGE]),
            customJpegQuality = ImageQualitySettings.normalizeJpegQuality(preferences[IMAGE_QUALITY_JPEG_QUALITY]),
        )
    }

    suspend fun update(
        mode: String? = null,
        customMaxLongEdge: Int? = null,
        customJpegQuality: Int? = null,
    ) {
        if (mode == null && customMaxLongEdge == null && customJpegQuality == null) return
        dataStore.edit { preferences ->
            mode?.let { preferences[IMAGE_QUALITY_MODE] = ImageQualitySettings.normalizeMode(it) }
            customMaxLongEdge?.let {
                preferences[IMAGE_QUALITY_MAX_LONG_EDGE] = ImageQualitySettings.normalizeMaxLongEdge(it)
            }
            customJpegQuality?.let {
                preferences[IMAGE_QUALITY_JPEG_QUALITY] = ImageQualitySettings.normalizeJpegQuality(it)
            }
        }
    }
}
