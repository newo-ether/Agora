package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.phone.PHONE_TOOL_NAMES
import com.newoether.agora.phone.PhoneUseIcon
import com.newoether.agora.phone.PhoneControlLease
import com.newoether.agora.phone.PhoneControlService
import com.newoether.agora.R
import org.robolectric.Robolectric
import com.newoether.agora.util.appLanguageResources
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneToolPresentationTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun everyPhoneToolUsesOneSharedLocalizedLifecycle() {
        for (locale in listOf("en", "ar", "de", "es", "fr", "ja", "ko", "pt-BR", "ru", "vi", "zh", "zh-TW")) {
            val resources = app.appLanguageResources(locale)
            for (name in PHONE_TOOL_NAMES) {
                val active = MessageSegment(type = "tool", toolName = name, toolArgs = "{",
                    toolState = ToolExecutionStates.CALLING)
                assertEquals(ToolKind.PHONE, ToolPresentationResolver.resolve(active).kind)
                assertFalse(resources.toolDisplayName(active).contains("phone_"))
                assertTrue(resources.toolSummary(active).endsWith("…"))
                assertEquals(resources.toolSummary(active), resources.toolSummary(active.copy(toolState = ToolExecutionStates.RUNNING)))
                val complete = active.copy(toolArgs = "{}", toolResult = """{"status":"ok"}""",
                    toolState = ToolExecutionStates.SUCCEEDED)
                assertEquals(ToolPresentationState.COMPLETED, ToolPresentationResolver.resolve(complete).state)
                assertFalse(resources.toolSummary(complete).endsWith("…"))
                assertNotEquals(resources.toolSummary(active), resources.toolSummary(complete))
            }
        }
    }

    @Test fun everyPhoneToolUsesTheSharedMobileHandIcon() {
        for (name in PHONE_TOOL_NAMES) {
            assertSame(PhoneUseIcon, toolHeaderIcon(ToolPresentationResolver.kindForToolName(name)))
        }
        assertNotEquals(PhoneUseIcon, toolHeaderIcon(ToolKind.UNKNOWN))
        assertEquals("mobile_hand", PhoneUseIcon.name)
    }
    @Test fun foregroundNotificationUsesTheMobileHandResource() {
        val service = Robolectric.buildService(PhoneControlService::class.java).get()
        val method = PhoneControlService::class.java.getDeclaredMethod(
            "notification", PhoneControlLease.Held::class.java,
        ).apply { isAccessible = true }
        val notification = method.invoke(service, null) as android.app.Notification
        assertEquals(R.drawable.ic_phone_use, notification.smallIcon.resId)
    }
    @Test fun titlesAndConcreteFailuresKeepTheirOwnMeaning() {
        val segment = MessageSegment(type = "tool", toolName = "phone_type", toolArgs = "{}",
            toolState = ToolExecutionStates.RUNNING)
        assertEquals("Type Text", app.appLanguageResources("en").toolDisplayName(segment))
        assertEquals("Typing…", app.appLanguageResources("en").toolSummary(segment))
        assertEquals("输入文本", app.appLanguageResources("zh").toolDisplayName(segment))
        val failed = segment.copy(toolState = ToolExecutionStates.FAILED,
            toolResult = """{"error":"app_blocked","message":"app blocked: private.package","detail":"private.package"}""")
        assertEquals(ToolPresentationState.FAILED, ToolPresentationResolver.resolve(failed).state)
        assertEquals("App blocked: private.package", app.appLanguageResources("en").toolSummary(failed))
    }
}
