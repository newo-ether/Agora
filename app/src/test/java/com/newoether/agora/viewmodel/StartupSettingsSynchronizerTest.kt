package com.newoether.agora.viewmodel

import com.newoether.agora.api.HttpClient
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupSettingsSynchronizerTest {
    @Test
    fun disabledOrBlankHostClearsProxy() {
        val fixture = ProxyFixture(enabled = false, host = "proxy")
        assertNull(currentProxyConfig(fixture.settings))

        fixture.enabled.value = true
        fixture.host.value = "  "
        assertNull(currentProxyConfig(fixture.settings))
    }

    @Test
    fun proxyMappingPreservesProtocolCredentialsAndNormalizedBypass() {
        val fixture = ProxyFixture(
            enabled = true,
            type = "SOCKS5",
            host = " proxy.local ",
            port = " 1080 ",
            username = "user",
            password = "secret",
            bypass = " localhost, 10.0.0.0/8\n\ninternal ",
        )

        assertEquals(
            HttpClient.ProxyConfig(
                type = HttpClient.ProxyType.SOCKS,
                host = "proxy.local",
                port = 1080,
                username = "user",
                password = "secret",
                bypass = listOf("localhost", "10.0.0.0/8", "internal"),
            ),
            currentProxyConfig(fixture.settings),
        )
        fixture.type.value = "HTTPS"
        assertEquals(HttpClient.ProxyType.HTTPS, currentProxyConfig(fixture.settings)?.type)
        fixture.type.value = "http"
        fixture.port.value = "invalid"
        assertEquals(HttpClient.ProxyType.HTTP, currentProxyConfig(fixture.settings)?.type)
        assertEquals(0, currentProxyConfig(fixture.settings)?.port)
    }

    @Test
    fun proxyCollectorAppliesInitialAndChangedSettings() = runTest {
        val fixture = ProxyFixture(enabled = true, host = "first")
        val applied = mutableListOf<HttpClient.ProxyConfig?>()
        ProxySettingsSynchronizer(fixture.settings, backgroundScope, applied::add).start()
        runCurrent()

        fixture.host.value = "second"
        runCurrent()

        assertEquals(listOf("first", "second"), applied.map { it?.host })
    }

    @Test
    fun localCatalogPublishesIdsAndPreservesExistingAliases() = runTest {
        val settings = mockk<SettingsRepository>()
        val models = MutableStateFlow(listOf(localModel("one", "First")))
        every { settings.localChatModels } returns models
        coEvery { settings.saveAvailableModels(any(), any()) } returns Unit
        coEvery { settings.synchronizeLocalModelAliases(any()) } returns Unit
        LocalModelCatalogSynchronizer(settings, backgroundScope).start()
        runCurrent()

        coVerify(exactly = 1) {
            settings.saveAvailableModels(Constants.PROVIDER_LOCAL, listOf("Local:one"))
        }
        coVerify(exactly = 1) {
            settings.synchronizeLocalModelAliases(mapOf("Local:one" to "First"))
        }
    }

    @Test
    fun aliasOnlyChangeDoesNotRewriteUnchangedModelIds() = runTest {
        val settings = mockk<SettingsRepository>()
        val models = MutableStateFlow(listOf(localModel("one", "First")))
        every { settings.localChatModels } returns models
        coEvery { settings.saveAvailableModels(any(), any()) } returns Unit
        coEvery { settings.synchronizeLocalModelAliases(any()) } returns Unit
        LocalModelCatalogSynchronizer(settings, backgroundScope).start()
        runCurrent()

        models.value = listOf(localModel("one", "Renamed"))
        runCurrent()

        coVerify(exactly = 1) {
            settings.saveAvailableModels(Constants.PROVIDER_LOCAL, listOf("Local:one"))
        }
        coVerify(exactly = 1) {
            settings.synchronizeLocalModelAliases(mapOf("Local:one" to "First"))
        }
        coVerify(exactly = 1) {
            settings.synchronizeLocalModelAliases(mapOf("Local:one" to "Renamed"))
        }
    }

    private class ProxyFixture(
        enabled: Boolean,
        type: String = "http",
        host: String,
        port: String = "8080",
        username: String = "",
        password: String = "",
        bypass: String = "",
    ) {
        val settings = mockk<SettingsRepository>()
        val enabled = MutableStateFlow(enabled)
        val type = MutableStateFlow(type)
        val host = MutableStateFlow(host)
        val port = MutableStateFlow(port)
        val username = MutableStateFlow(username)
        val password = MutableStateFlow(password)
        val bypass = MutableStateFlow(bypass)

        init {
            every { settings.proxyEnabled } returns this.enabled
            every { settings.proxyType } returns this.type
            every { settings.proxyHost } returns this.host
            every { settings.proxyPort } returns this.port
            every { settings.proxyUsername } returns this.username
            every { settings.proxyPassword } returns this.password
            every { settings.proxyBypass } returns this.bypass
        }
    }

    private companion object {
        fun localModel(modelId: String, alias: String) = LocalChatModelConfig(
            modelId = modelId,
            alias = alias,
        )
    }
}
