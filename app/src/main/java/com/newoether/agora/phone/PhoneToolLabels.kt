package com.newoether.agora.phone

import com.newoether.agora.R

/** Shared lifecycle copy for native and WebUI tool cards; overlay action labels stay concise. */
internal fun phoneSummaryLabel(name: String, completed: Boolean): Int = when (name) {
    "phone_claim" -> if (completed) R.string.phone_done_claim else R.string.phone_running_claim
    "phone_release" -> if (completed) R.string.phone_done_release else R.string.phone_running_release
    "phone_screenshot" -> if (completed) R.string.phone_done_screenshot else R.string.phone_running_screenshot
    "phone_ui_tree" -> if (completed) R.string.phone_done_ui_tree else R.string.phone_running_ui_tree
    "phone_tap" -> if (completed) R.string.phone_done_tap else R.string.phone_running_tap
    "phone_long_press" -> if (completed) R.string.phone_done_long_press else R.string.phone_running_long_press
    "phone_swipe" -> if (completed) R.string.phone_done_swipe else R.string.phone_running_swipe
    "phone_type" -> if (completed) R.string.phone_done_type else R.string.phone_running_type
    "phone_key" -> if (completed) R.string.phone_done_key else R.string.phone_running_key
    "phone_app_launch" -> if (completed) R.string.phone_done_app_launch else R.string.phone_running_app_launch
    "phone_app_list" -> if (completed) R.string.phone_done_app_list else R.string.phone_running_app_list
    else -> R.string.tool_call_failed
}
