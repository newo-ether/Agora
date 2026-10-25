package com.newoether.agora.phone

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.Xml
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

internal class AndroidPhoneDevice(
    private val context: Context,
    private val mode: String,
    private val privileged: PhonePrivilegedConnection,
) : PhoneDevice {
    private fun accessibility() = PhoneAccessibilityService.instance ?: throw PhoneFailure("permission_lost")
    private suspend fun command(value: String) = privileged.command(mode, value)
    override suspend fun foreground(): String {
        if (mode == "accessibility") return accessibility().foregroundPackage()
        val output = command("dumpsys activity activities").toString(Charsets.UTF_8)
        return Regex("(?:topResumedActivity|mResumedActivity|ResumedActivity)[^\\n]*? ([a-zA-Z0-9_.]+)/")
            .findAll(output).lastOrNull()?.groupValues?.get(1) ?: throw PhoneFailure("foreground_unknown")
    }
    override suspend fun screenshot(): Bitmap = if (mode == "accessibility") accessibility().capture()
        else command("screencap -p").let {
            BitmapFactory.decodeByteArray(it, 0, it.size) ?: throw PhoneFailure("screenshot_failed")
        }
    override suspend fun nodes(): List<PhoneNode> {
        if (mode == "accessibility") return accessibility().snapshot()
        val dump = command("uiautomator dump /dev/tty").toString(Charsets.UTF_8)
        val start = dump.indexOf("<?xml").takeIf { it >= 0 } ?: dump.indexOf("<hierarchy")
        val end = dump.lastIndexOf("</hierarchy>")
        if (start < 0 || end < start) throw PhoneFailure("ui_tree_unavailable")
        val parser = Xml.newPullParser().apply { setInput(dump.substring(start, end + 12).reader()) }
        val nodes = ArrayList<PhoneNode>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT && nodes.size < 1000) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                fun attribute(name: String) = parser.getAttributeValue(null, name).orEmpty()
                fun flag(name: String) = attribute(name) == "true"
                val bounds = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").matchEntire(attribute("bounds"))
                val text = attribute("text").ifBlank { attribute("content-desc") }
                if (bounds != null && (text.isNotBlank() || flag("clickable") || flag("focusable") || flag("scrollable"))) {
                    val values = bounds.groupValues.drop(1).map(String::toInt)
                    val password = flag("password")
                    nodes += PhoneNode(nodes.size, Rect(values[0], values[1], values[2], values[3]),
                        if (password) "" else text.take(500), attribute("class").substringAfterLast('.'),
                        flag("enabled"), flag("selected") || flag("checked"), attribute("class").endsWith("EditText"), password)
                }
            }
            parser.next()
        }
        return nodes
    }
    override suspend fun tap(x: Int, y: Int, durationMs: Long) {
        if (mode == "accessibility") accessibility().gesture(x, y, x, y, durationMs)
        else if (durationMs <= 100) command("input tap $x $y")
        else command("input swipe $x $y $x $y $durationMs")
    }
    override suspend fun swipe(x: Int, y: Int, endX: Int, endY: Int, durationMs: Long) {
        if (mode == "accessibility") accessibility().gesture(x, y, endX, endY, durationMs)
        else command("input swipe $x $y $endX $endY $durationMs")
    }
    override suspend fun type(text: String) {
        when (phoneInputPath(mode, text)) {
            "set_text" -> accessibility().setText(text)
            "clipboard" -> privileged.paste(mode, text)
            else -> command("input text ${phoneShellQuote(text.replace(" ", "%s"))}")
        }
    }
    override suspend fun key(key: String) {
        val code = when (key) { "back" -> 4; "home" -> 3; "recents" -> 187; else -> throw PhoneFailure("invalid_key") }
        if (mode == "accessibility") accessibility().globalKey(key) else command("input keyevent $code")
    }
    override suspend fun launch(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: throw PhoneFailure("app_unavailable")
        if (mode == "accessibility") withContext(Dispatchers.Main.immediate) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val component = intent.component?.flattenToString() ?: throw PhoneFailure("app_unavailable")
            command("am start --user current -n ${phoneShellQuote(component)} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
        }
    }
    override suspend fun apps(): List<PhoneApp> = withContext(Dispatchers.IO) {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { PhoneApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
    override fun dimensions(): Triple<Int, Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw PhoneFailure("display_unavailable")
        val point = android.graphics.Point().also(display::getRealSize)
        return Triple(point.x, point.y, display.rotation)
    }
}
