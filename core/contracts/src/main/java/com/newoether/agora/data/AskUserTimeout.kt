package com.newoether.agora.data

const val DEFAULT_ASK_USER_TIMEOUT_MINUTES = 5
val ASK_USER_TIMEOUT_PRESETS = listOf(1, 2, 5, 10, 15, 30, 60)

fun normalizeAskUserTimeoutMinutes(value: Int?): Int =
    value?.takeIf { it in ASK_USER_TIMEOUT_PRESETS } ?: DEFAULT_ASK_USER_TIMEOUT_MINUTES
