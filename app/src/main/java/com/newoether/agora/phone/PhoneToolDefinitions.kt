package com.newoether.agora.phone

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext

internal fun phoneDefinitions(ctx: GenerationContext): List<ToolDefinition> {
    if (!ctx.phoneEnabled || !ctx.phoneReady || !phoneCallerAdmitted(ctx.automationCaller, ctx.phoneAllowAutomation)) return emptyList()
    val limits = if (ctx.phoneMode == "accessibility")
        "Accessibility screenshots require Android 11/API 30+ and are rate limited by Android. "
        else "UI trees use uiautomator dump and may take several seconds. "
    val common = "Coordinates use the latest screenshot size, never physical pixels. " +
        "Prefer node indices from the latest phone_ui_tree. A thin colored border is Agora's control indicator. " +
        "FLAG_SECURE windows capture black. $limits"
    val point = mapOf(
        "node" to ToolProperty("integer", "Preferred: index from the latest phone_ui_tree snapshot. Use node or x/y."),
        "x" to ToolProperty("number", "X in the latest screenshot."),
        "y" to ToolProperty("number", "Y in the latest screenshot."),
    )
    val screenshot = mapOf("screenshot" to ToolProperty("boolean", "Include a post-action screenshot after settling. Default true."))
    fun definition(name: String, text: String, properties: Map<String, ToolProperty> = emptyMap(), required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(name, text + " " +
            (if (name == "phone_claim") "" else "Requires phone_claim first. ") + common,
            ToolParameters(properties = properties, required = required)))
    return listOf(
        definition("phone_claim", "Acquire exclusive device control for this conversation and Run. Fails while locked or occupied. Idle lease is 300 seconds."),
        definition("phone_release", "Release your device control lease."),
        definition("phone_screenshot", "Capture the screen and return its coordinate width and height."),
        definition("phone_ui_tree", "Return a compressed interactive node snapshot. Indices remain stable only for this snapshot; refresh after UI changes."),
        definition("phone_tap", "Tap a node or coordinate.", point + screenshot),
        definition("phone_long_press", "Long press a node or coordinate.", point + screenshot +
            ("duration_ms" to ToolProperty("integer", "Hold time, 500 to 10000 ms. Default 800."))),
        definition("phone_swipe", "Swipe from x/y to end_x/end_y.", point.filterKeys { it != "node" } + screenshot + mapOf(
            "end_x" to ToolProperty("number", "End X in screenshot coordinates."),
            "end_y" to ToolProperty("number", "End Y in screenshot coordinates."),
            "duration_ms" to ToolProperty("integer", "Duration, 100 to 10000 ms. Default 400."),
        ), listOf("x", "y", "end_x", "end_y")),
        definition("phone_type", "Enter text into the focused field. Accessibility replaces text with ACTION_SET_TEXT. Privileged non-ASCII input pastes and restores the previous clipboard.",
            screenshot + ("text" to ToolProperty("string", "Text to enter.")), listOf("text")),
        definition("phone_key", "Press back, home, or recents.", screenshot + ("key" to ToolProperty("string", "back, home, or recents")), listOf("key")),
        definition("phone_app_launch", "Launch an allowed app by package name.", screenshot + ("package" to ToolProperty("string", "Installed package name.")), listOf("package")),
        definition("phone_app_list", "List launchable apps with labels and package names, filtered by app access control."),
    )
}
