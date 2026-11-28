package com.newoether.agora.phone

import android.graphics.Bitmap
import android.graphics.Rect

/** Device effects are isolated from lease/admission decisions for no-effect refusal tests. */
internal interface PhoneDevice {
    suspend fun foreground(): String
    suspend fun screenshot(): Bitmap
    suspend fun nodes(): List<PhoneNode>
    suspend fun tap(x: Int, y: Int, durationMs: Long)
    suspend fun swipe(x: Int, y: Int, endX: Int, endY: Int, durationMs: Long)
    suspend fun type(text: String)
    suspend fun key(key: String)
    suspend fun launch(packageName: String)
    suspend fun apps(): List<PhoneApp>
    fun dimensions(): Triple<Int, Int, Int>
}

internal data class PhoneNode(
    val index: Int,
    val bounds: Rect,
    val text: String,
    val role: String,
    val enabled: Boolean,
    val selected: Boolean,
    val editable: Boolean,
    val password: Boolean,
)

internal data class PhoneApp(val packageName: String, val label: String)
internal class PhoneFailure(val code: String, detail: String = code) : Exception(detail)
