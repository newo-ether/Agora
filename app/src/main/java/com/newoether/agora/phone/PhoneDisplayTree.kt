package com.newoether.agora.phone

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.HandlerThread
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the background display's windows from inside the Shizuku UserService through a shell-owned
 * UiAutomation, so background mode needs no accessibility service. The `uiautomator dump` command
 * cannot do this: it only reads the active window, which stays on the phone's own display.
 *
 * Android allows one UiAutomation at a time, so the connection is held only while a display is open
 * and accessibility services keep running alongside it.
 */
internal class PhoneDisplayTree(private val context: Context) {
    private val lock = Any()
    private var automation: UiAutomation? = null
    private var thread: HandlerThread? = null

    private fun connected(): UiAutomation = automation ?: run {
        val connectionType = Class.forName("android.app.IUiAutomationConnection")
        val connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
        val created = if (android.os.Build.VERSION.SDK_INT >= 34) {
            UiAutomation::class.java.getConstructor(Context::class.java, connectionType)
                .newInstance(context, connection)
        } else {
            val looper = HandlerThread("AgoraDisplayTree").apply { start() }.also { thread = it }.looper
            UiAutomation::class.java.getConstructor(android.os.Looper::class.java, connectionType)
                .newInstance(looper, connection)
        }
        UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType)
            .invoke(created, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // Window lists are empty unless interactive windows are requested.
        created.serviceInfo = created.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        automation = created
        created
    }

    private fun windows(displayId: Int): List<AccessibilityWindowInfo> {
        val automation = connected()
        automation.clearCache()
        // The phone's own screen also works below API 30, where windowsOnAllDisplays is missing.
        return if (displayId == android.view.Display.DEFAULT_DISPLAY) automation.windows
        else automation.windowsOnAllDisplays.get(displayId).orEmpty()
    }

    private fun roots(displayId: Int): List<AccessibilityNodeInfo> = windows(displayId).mapNotNull { it.root }

    /** Package of the top application window, or null while the display is empty. */
    fun foreground(displayId: Int): String? = synchronized(lock) {
        val root = windows(displayId).firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            ?: return null
        try { root.packageName?.toString() } finally { root.recycle() }
    }

    /** The display's nodes as JSON; see [decodePhoneNodes]. */
    fun nodes(displayId: Int): String = synchronized(lock) { encodePhoneNodes(collectPhoneNodes(roots(displayId))) }

    /** Sets text on the focused field; false when no field on the display has input focus. */
    fun setText(displayId: Int, text: String): Boolean = synchronized(lock) {
        // A keyboard's own search box must never take the text meant for the app.
        val roots = windows(displayId).filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapNotNull { it.root }
        try {
            for (root in roots) {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
                try {
                    if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        })) throw IllegalStateException("text_input_failed")
                    return true
                } finally { focused.recycle() }
            }
            false
        } finally { roots.forEach { it.recycle() } }
    }

    fun close() = synchronized(lock) {
        automation?.let { value -> runCatching { UiAutomation::class.java.getMethod("disconnect").invoke(value) } }
        automation = null
        thread?.quitSafely()
        thread = null
    }
}

/** Interactive or labelled visible nodes, in traversal order. Recycles every root in [roots]. */
internal fun collectPhoneNodes(roots: List<AccessibilityNodeInfo>): List<PhoneNode> {
    val nodes = ArrayList<PhoneNode>()
    fun visit(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 64 || nodes.size >= 1000) return
        if (node.isVisibleToUser && (node.isClickable || node.isLongClickable || node.isEditable ||
                node.isScrollable || node.isFocusable || !node.text.isNullOrEmpty())) {
            val rect = Rect().also(node::getBoundsInScreen)
            if (!rect.isEmpty) nodes += PhoneNode(
                nodes.size, rect,
                if (node.isPassword) "" else (node.text ?: node.contentDescription)?.toString().orEmpty().take(500),
                node.className?.toString().orEmpty().substringAfterLast('.'),
                node.isEnabled, node.isSelected || node.isChecked, node.isEditable, node.isPassword,
            )
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> try { visit(child, depth + 1) } finally { child.recycle() } }
        }
    }
    try { roots.forEach { visit(it, 0) } } finally { roots.forEach { it.recycle() } }
    return nodes
}

internal fun encodePhoneNodes(nodes: List<PhoneNode>): String = JSONArray().apply {
    nodes.forEach { node ->
        put(JSONObject()
            .put("i", node.index).put("t", node.text).put("r", node.role)
            .put("b", JSONArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
            .put("e", node.enabled).put("s", node.selected).put("ed", node.editable).put("p", node.password))
    }
}.toString()

internal fun decodePhoneNodes(json: String): List<PhoneNode> {
    val array = JSONArray(json)
    return (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val b = item.getJSONArray("b")
        PhoneNode(item.getInt("i"), Rect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)),
            item.getString("t"), item.getString("r"), item.getBoolean("e"), item.getBoolean("s"),
            item.getBoolean("ed"), item.getBoolean("p"))
    }
}
