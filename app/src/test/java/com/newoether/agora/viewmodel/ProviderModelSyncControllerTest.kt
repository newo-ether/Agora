package com.newoether.agora.viewmodel

import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ModelFetchTimeoutException
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.Constants
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderModelSyncControllerTest {
    @Test
    fun `full sync skips local and unconfigured providers then saves one successful fingerprint`() =
        runTest {
            val providers = registry()
            val settings = settings(
                available = mapOf("Cloud" to listOf("Cloud:model")),
                custom = setOf("Custom:model"),
                enabled = setOf("Cloud:model", "Custom:model", "Stale:model"),
            )
            every { providers.all } returns linkedMapOf(
                Constants.PROVIDER_LOCAL to mockk<LlmProvider>(),
                "Cloud" to mockk<LlmProvider>(),
                "Missing" to mockk<LlmProvider>(),
            )
            every { providers.ensureCustomProvidersRegistered() } just Runs
            every { settings.resolveActiveKey("Cloud") } returns "key"
            every { settings.resolveActiveKey("Missing") } returns ""
            every { providers.isConfigured("Cloud", "key") } returns true
            every { providers.isConfigured("Missing", "") } returns false
            coEvery { providers.fetchModelsForProvider("Cloud") } returns listOf("Cloud:model")
            every { providers.computeFingerprint() } returns "fingerprint"
            val completed = CompletableDeferred<ProviderModelSyncOutcome>()
            val controller = ProviderModelSyncController(providers, settings, this)

            controller.start(request()) { completed.complete(it) }
            val outcome = completed.await()

            assertEquals(1, outcome.successfulProviderCount)
            assertEquals(1, outcome.skippedProviderCount)
            assertEquals(emptyList<ProviderModelSyncFailure>(), outcome.failures)
            assertFalse(controller.isSyncing.value)
            coVerify(exactly = 1) { settings.saveAvailableModels("Missing", emptyList()) }
            verify(exactly = 1) {
                settings.setEnabledModels(setOf("Cloud:model", "Custom:model"))
            }
            coVerify(exactly = 1) {
                settings.saveLastModelsFetchFingerprint("fingerprint")
            }
            verify(exactly = 1) { providers.computeFingerprint() }
        }

    @Test
    fun `provider failure is typed and records the attempted fingerprint`() = runTest {
        val providers = registry()
        val settings = settings()
        every { providers.all } returns mapOf("Cloud" to mockk<LlmProvider>())
        every { providers.ensureCustomProvidersRegistered() } just Runs
        every { settings.resolveActiveKey("Cloud") } returns "key"
        every { providers.isConfigured("Cloud", "key") } returns true
        every { providers.computeFingerprint() } returns "attempted-fingerprint"
        coEvery { providers.fetchModelsForProvider("Cloud") } throws ModelFetchTimeoutException()
        val completed = CompletableDeferred<ProviderModelSyncOutcome>()
        val controller = ProviderModelSyncController(providers, settings, this)

        controller.start(request()) { completed.complete(it) }
        val outcome = completed.await()

        assertEquals(0, outcome.successfulProviderCount)
        assertEquals(0, outcome.skippedProviderCount)
        assertEquals(
            listOf(ProviderModelSyncFailure("Cloud", "timeout")),
            outcome.failures,
        )
        assertFalse(controller.isSyncing.value)
        coVerify(exactly = 1) {
            settings.saveLastModelsFetchFingerprint("attempted-fingerprint")
        }
    }

    @Test
    fun `global failure records the attempted fingerprint`() = runTest {
        val providers = registry()
        val settings = settings()
        every { providers.computeFingerprint() } returns "attempted-fingerprint"
        every { providers.ensureCustomProvidersRegistered() } throws IllegalStateException("broken")
        val completed = CompletableDeferred<ProviderModelSyncOutcome>()
        val controller = ProviderModelSyncController(providers, settings, this)

        controller.start(request()) { completed.complete(it) }
        val outcome = completed.await()

        assertEquals(
            listOf(ProviderModelSyncFailure("Models", "broken")),
            outcome.failures,
        )
        assertFalse(controller.isSyncing.value)
        coVerify(exactly = 1) {
            settings.saveLastModelsFetchFingerprint("attempted-fingerprint")
        }
        verify(exactly = 1) { providers.computeFingerprint() }
    }

    @Test
    fun `second request while active is rejected without duplicate provider work`() = runTest {
        val providers = registry()
        val settings = settings(available = mapOf("Cloud" to listOf("Cloud:model")))
        every { providers.all } returns mapOf("Cloud" to mockk<LlmProvider>())
        every { providers.ensureCustomProvidersRegistered() } just Runs
        every { settings.resolveActiveKey("Cloud") } returns "key"
        every { providers.isConfigured("Cloud", "key") } returns true
        val releaseProvider = CompletableDeferred<Unit>()
        coEvery { providers.fetchModelsForProvider("Cloud") } coAnswers {
            releaseProvider.await()
            listOf("Cloud:model")
        }
        every { providers.computeFingerprint() } returns "fingerprint"
        val outcomes = mutableListOf<ProviderModelSyncOutcome>()
        val controller = ProviderModelSyncController(providers, settings, this)

        controller.start(request()) { outcomes += it }
        runCurrent()
        assertTrue(controller.isSyncing.value)
        controller.start(request()) { outcomes += it }
        runCurrent()
        releaseProvider.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, outcomes.size)
        assertFalse(controller.isSyncing.value)
        coVerify(exactly = 1) { providers.fetchModelsForProvider("Cloud") }
    }

    @Test
    fun `scope cancellation clears admission and emits no completion`() = runTest {
        val providers = registry()
        val settings = settings()
        every { providers.all } returns mapOf("Cloud" to mockk<LlmProvider>())
        every { providers.ensureCustomProvidersRegistered() } just Runs
        every { settings.resolveActiveKey("Cloud") } returns "key"
        every { providers.isConfigured("Cloud", "key") } returns true
        every { providers.computeFingerprint() } returns "cancelled-fingerprint"
        coEvery { providers.fetchModelsForProvider("Cloud") } coAnswers { awaitCancellation() }
        val owner = SupervisorJob()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + owner)
        var completionCount = 0
        val controller = ProviderModelSyncController(providers, settings, scope)

        controller.start(request()) { completionCount++ }
        runCurrent()
        assertTrue(controller.isSyncing.value)
        owner.cancel()
        advanceUntilIdle()

        assertFalse(controller.isSyncing.value)
        assertEquals(0, completionCount)
        coVerify(exactly = 0) { settings.saveLastModelsFetchFingerprint(any()) }
    }

    @Test
    fun `built-in removed from the provider list is neither fetched nor cleared`() = runTest {
        val providers = registry(builtIns = setOf("Listed", "Hidden"))
        val settings = settings(listed = listOf("Listed"))
        every { providers.all } returns linkedMapOf(
            "Listed" to mockk<LlmProvider>(),
            "Hidden" to mockk<LlmProvider>(),
        )
        every { providers.ensureCustomProvidersRegistered() } just Runs
        every { settings.resolveActiveKey("Listed") } returns "key"
        every { providers.isConfigured("Listed", "key") } returns true
        coEvery { providers.fetchModelsForProvider("Listed") } returns listOf("Listed:model")
        every { providers.computeFingerprint() } returns "fingerprint"
        val completed = CompletableDeferred<ProviderModelSyncOutcome>()
        val controller = ProviderModelSyncController(providers, settings, this)

        controller.start(request()) { completed.complete(it) }
        val outcome = completed.await()

        assertEquals(1, outcome.successfulProviderCount)
        assertEquals(0, outcome.skippedProviderCount)
        coVerify(exactly = 0) { providers.fetchModelsForProvider("Hidden") }
        coVerify(exactly = 0) { settings.saveAvailableModels("Hidden", any()) }
        verify(exactly = 0) { settings.resolveActiveKey("Hidden") }
    }

    private fun registry(builtIns: Set<String> = emptySet()): ProviderRegistry =
        mockk<ProviderRegistry>().also { providers ->
            every { providers.isBuiltIn(any()) } answers { firstArg<String>() in builtIns }
        }

    private fun settings(
        available: Map<String, List<String>> = emptyMap(),
        custom: Set<String> = emptySet(),
        enabled: Set<String> = emptySet(),
        listed: List<String> = emptyList(),
    ): SettingsRepository = mockk<SettingsRepository>().also { settings ->
        every { settings.providerList } returns MutableStateFlow(listed)
        every { settings.customModels } returns MutableStateFlow(custom)
        every { settings.enabledModels } returns MutableStateFlow(enabled)
        coEvery { settings.getAvailableModels() } returns available
        coEvery { settings.saveAvailableModels(any(), any()) } returns Unit
        coEvery { settings.saveLastModelsFetchFingerprint(any()) } returns Unit
        every { settings.setEnabledModels(any()) } just Runs
    }

    private fun request() = ProviderModelSyncRequest(
        failureLabels = ModelSyncFailureLabels(
            noModels = "empty",
            timeout = "timeout",
            invalidResponse = "invalid",
            unknown = "unknown",
        ),
        globalProviderName = "Models",
    )
}
