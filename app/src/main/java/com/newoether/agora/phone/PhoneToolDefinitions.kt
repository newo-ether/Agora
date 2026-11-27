package com.newoether.agora.phone

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext

internal fun phoneDefinitions(ctx: GenerationContext): List<ToolDefinition> {
    if (!ctx.phoneEnabled || !ctx.phoneReady || !phoneCallerAdmitted(ctx.automationCaller, ctx.phoneAllowAutomation)) return emptyList()
    val screenshotLimits = if (ctx.phoneMode == "accessibility")
        " Accessibility screenshots require Android 11/API 30+ and are rate limited by Android." else ""
    val uiTreeLimits = if (ctx.phoneMode == "accessibility") ""
        else " UI trees use uiautomator dump and may take several seconds."
    val point = mapOf(
        "node" to ToolProperty("integer", "Preferred: index from the latest phone_ui_tree snapshot. If node is given, x/y are ignored."),
        "x" to ToolProperty("number", "X in the latest screenshot."),
        "y" to ToolProperty("number", "Y in the latest screenshot."),
    )
    val screenshot = mapOf("screenshot" to ToolProperty("boolean", "Include a post-action screenshot after settling. Default false; set true when you need to see the result."))
    fun definition(name: String, text: String, properties: Map<String, ToolProperty> = emptyMap(), required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(name, text, ToolParameters(properties = properties, required = required)))
    return listOf(
        definition("phone_claim", "Acquire exclusive device control for this conversation and Run. Required before every other phone_* tool. Fails while locked or occupied. Idle lease is 300 seconds. A thin colored border indicates active control."),
        definition("phone_release", "Release your device control lease."),
        definition("phone_screenshot", "Capture the screen and return its coordinate width and height. Coordinates in later calls use this returned size, never physical pixels. Reuse known coordinates for consecutive actions; refresh when target positions change. Screen captures, including post-action screenshots, show FLAG_SECURE windows as black.$screenshotLimits"),
        definition("phone_ui_tree", "Return a compressed interactive node snapshot. Prefer node indices for supported actions. Reuse nodes while their positions stay valid without rereading the screen; refresh after layout changes.$uiTreeLimits"),
        definition("phone_tap", "Tap a node or coordinate.", point + screenshot),
        definition("phone_long_press", "Long press a node or coordinate.", point + screenshot +
            ("duration_ms" to ToolProperty("integer", "Hold time, 500 to 10000 ms. Default 800."))),
        definition("phone_swipe", "Swipe from x/y to end_x/end_y.", point.filterKeys { it != "node" } + screenshot + mapOf(
            "end_x" to ToolProperty("number", "End X in screenshot coordinates."),
            "end_y" to ToolProperty("number", "End Y in screenshot coordinates."),
            "duration_ms" to ToolProperty("integer", "Duration, 100 to 10000 ms. Default 400."),
        ), listOf("x", "y", "end_x", "end_y")),
        definition("phone_type", "Enter text into the focused field. Replaces the field's text with ACTION_SET_TEXT. Privileged modes fall back to key input for ASCII, or a paste that restores the previous clipboard, when the field rejects it.",
            screenshot + ("text" to ToolProperty("string", "Text to enter.")), listOf("text")),
        definition("phone_key", "Press back, home, or recents.", screenshot + ("key" to ToolProperty("string", "back, home, or recents")), listOf("key")),
        definition("phone_app_launch", "Launch an allowed app by package name.", screenshot + ("package" to ToolProperty("string", "Installed package name.")), listOf("package")),
        definition("phone_app_list", "List launchable apps with labels and package names, filtered by app access control."),
    )
}
