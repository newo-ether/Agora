package com.newoether.agora.ui.remote

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.remote.RemoteFailure
import com.newoether.agora.remote.RemoteNotice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RemoteErrorPresentationTest {
    private fun context(tag: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        })
    }

    @Test fun knownCodesFollowTheUiLocaleAndKeepDiagnosticsOutOfTheSnackbar() {
        val notice = RemoteNotice("read_failed", RemoteFailure.SERVICE, 1,
            "Original desktop owner is unavailable", "desktop_unavailable")
        val english = remoteNoticeMessage(context("en"), notice)
        val chinese = remoteNoticeMessage(context("zh-CN"), notice)
        assertTrue(english.contains("Open Codex"))
        assertTrue(chinese.contains("目标电脑"))
        assertFalse(chinese.contains("Original desktop owner"))
        assertTrue(remoteNoticeMessage(context("zh-TW"), notice).contains("目標電腦"))
        assertTrue(notice.canRetryRead)
    }

    @Test fun transportFailuresSelectTheMatchingLocalizedSnackbar() {
        val failures = listOf(
            com.newoether.agora.util.ConchAuthenticationException("token") to com.newoether.agora.R.string.remote_auth_failed,
            com.newoether.agora.util.ConchServiceException("gateway") to com.newoether.agora.R.string.remote_service_failed,
            com.newoether.agora.util.ConchChannelException("frame") to com.newoether.agora.R.string.remote_protocol_failed,
            java.net.ConnectException("refused") to com.newoether.agora.R.string.remote_network_failed,
            java.net.SocketTimeoutException("timeout") to com.newoether.agora.R.string.remote_network_failed,
            java.net.UnknownHostException("unreachable") to com.newoether.agora.R.string.remote_network_failed,
        )
        for (locale in listOf("en", "zh-CN", "zh-TW")) for ((error, resource) in failures) {
            val localized = context(locale)
            val notice = RemoteNotice("connect_failed", com.newoether.agora.remote.classifyRemoteFailure(error), 1)
            assertEquals(localized.getString(resource), remoteNoticeMessage(localized, notice))
        }
    }

    @Test fun authenticationFailureDoesNotClaimTheCredentialIsWrongOrSuggestResending() {
        for (code in listOf(null, "authentication_failed")) {
            val notice = RemoteNotice("send_failed", RemoteFailure.AUTHENTICATION, 1, code = code)
            val english = remoteNoticeMessage(context("en"), notice)
            assertTrue(english.contains("could not authenticate this request"))
            assertTrue(english.contains("does not necessarily mean"))
            assertTrue(remoteNoticeMessage(context("zh-CN"), notice).contains("不一定表示访问令牌有误"))
            assertFalse(notice.canRetryRead)
        }
    }

    @Test fun unknownAndLegacyErrorsKeepProviderDetailsWithALocalizedHeading() {
        for (code in listOf(null, "native_error", "future_server_code")) {
            val notice = RemoteNotice("send_failed", RemoteFailure.SERVICE, 1, "Provider limit: 429", code)
            val text = remoteNoticeMessage(context("zh-CN"), notice)
            assertTrue(text.contains("Provider limit: 429"))
            assertTrue(text.contains("Codex 未能"))
            assertFalse(notice.canRetryRead)
        }
    }

    @Test fun uncertainDeliveryUsesAShortLocalizedNoticeWithoutAnAction() {
        val notice = RemoteNotice("send_failed", RemoteFailure.SERVICE, 1, code = "delivery_unconfirmed")
        val expectedByLocale = linkedMapOf(
            "en" to "Could not confirm that the message was sent.",
            "ar" to "تعذر تأكيد إرسال الرسالة.",
            "de" to "Es konnte nicht bestätigt werden, dass die Nachricht gesendet wurde.",
            "es" to "No se pudo confirmar que el mensaje se haya enviado.",
            "fr" to "Impossible de confirmer que le message a été envoyé.",
            "ja" to "メッセージが送信されたことを確認できませんでした。",
            "ko" to "메시지가 전송되었는지 확인할 수 없습니다.",
            "pt-BR" to "Não foi possível confirmar que a mensagem foi enviada.",
            "ru" to "Не удалось подтвердить отправку сообщения.",
            "vi" to "Không thể xác nhận tin nhắn đã được gửi.",
            "zh-CN" to "未能确认发送成功。",
            "zh-TW" to "未能確認傳送成功。",
        )
        expectedByLocale.forEach { (tag, expected) ->
            assertEquals(tag, expected, remoteNoticeMessage(context(tag), notice))
        }
        assertFalse(notice.canRetryRead)
    }
}
