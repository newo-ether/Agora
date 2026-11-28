package com.newoether.agora.viewmodel
import com.newoether.agora.data.repository.renameConfiguredProviderModelReferences

import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.CustomProviderIdentityMigration
import com.newoether.agora.data.CustomProviderIdentityPolicy
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProviderRegistryLifecycleTest {
    @Test
    fun awaitInitialSyncStartsOrderedLifecycleAndRepeatedStartsAreIdempotent() = runTest {
        val fixture = Fixture(this)
        val loadGate = CompletableDeferred<Unit>()
        val migration = CustomProviderIdentityMigration("Relay X", fixture.providerId)
        coEvery { fixture.settings.normalizeCustomProviderIdentities() } answers {
            fixture.events += "normalize"
            listOf(migration)
        }
        coEvery {
            fixture.conversations.renameConfiguredProviderModelReferences("Relay X", fixture.providerId)
        } answers {
            fixture.events += "migrate"
        }
        coEvery { fixture.settings.clearLegacyCustomProviderNames(listOf(migration)) } answers {
            fixture.events += "clear"
        }
        coEvery { fixture.settings.awaitInitialLoad() } coAnswers {
            fixture.events += "settings-wait"
            loadGate.await()
            fixture.events += "settings-ready"
        }
        coEvery { fixture.settings.getProviderBaseUrls() } answers {
            fixture.events += "base-urls"
            mapOf("Relay X" to "https://relay.invalid/v1")
        }

        val waiting = async {
            fixture.registry.awaitInitialSync()
            fixture.events += "ready"
        }
        runCurrent()

        assertEquals(
            listOf("normalize", "migrate", "clear", "settings-wait"),
            fixture.events,
        )
        assertFalse("Relay X" in fixture.registry.all)

        fixture.registry.ensureStarted()
        fixture.registry.ensureStarted()
        loadGate.complete(Unit)
        runCurrent()
        waiting.await()

        assertEquals(
            listOf(
                "normalize",
                "migrate",
                "clear",
                "settings-wait",
                "settings-ready",
                "base-urls",
                "ready",
            ),
            fixture.events,
        )
        assertNotNull(fixture.registry.getInstanceOrNull("Relay X"))
        coVerify(exactly = 1) { fixture.settings.normalizeCustomProviderIdentities() }
        fixture.close()
    }

    @Test
    fun failedRoomMigrationClearsOnlyCompletedMarkersAndNeverPublishesLiveMap() = runTest {
        val fixture = Fixture(this, aliases = listOf("Relay A", "Relay B"))
        val first = CustomProviderIdentityMigration("Relay A", fixture.providerId)
        val second = CustomProviderIdentityMigration("Relay B", fixture.secondProviderId)
        var cleared = emptyList<CustomProviderIdentityMigration>()
        coEvery { fixture.settings.normalizeCustomProviderIdentities() } returns listOf(first, second)
        coEvery {
            fixture.conversations.renameConfiguredProviderModelReferences("Relay A", fixture.providerId)
        } just Runs
        coEvery {
            fixture.conversations.renameConfiguredProviderModelReferences("Relay B", fixture.secondProviderId)
        } throws IllegalStateException("room migration failed")
        coEvery { fixture.settings.clearLegacyCustomProviderNames(any()) } answers {
            cleared = firstArg()
        }

        val result = async { runCatching { fixture.registry.awaitInitialSync() } }
        runCurrent()

        assertEquals("room migration failed", result.await().exceptionOrNull()?.message)
        assertEquals(listOf(first), cleared)
        assertFalse("Relay A" in fixture.registry.all)
        assertFalse("Relay B" in fixture.registry.all)
        coVerify(exactly = 0) { fixture.settings.awaitInitialLoad() }
        coVerify(exactly = 0) { fixture.settings.getProviderBaseUrls() }
        assertTrue(fixture.uncaught.any { it.message == "room migration failed" })
        fixture.close()
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        aliases: List<String> = listOf("Relay X"),
    ) {
        val providerId = CustomProviderIdentityPolicy.legacyId(aliases.first())
        val secondProviderId = CustomProviderIdentityPolicy.legacyId(aliases.last())
        val events = mutableListOf<String>()
        val uncaught = mutableListOf<Throwable>()
        val settings = mockk<SettingsRepository>()
        val conversations = mockk<ConversationRepository>()
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        private val scope = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> uncaught += error },
        )
        private val customProviders = MutableStateFlow(
            aliases.map { alias ->
                CustomProviderConfig(
                    name = alias,
                    id = CustomProviderIdentityPolicy.legacyId(alias),
                )
            },
        )
        val registry: ProviderRegistry

        init {
            every { settings.customProviders } returns customProviders
            every { settings.apiKeys } returns MutableStateFlow<List<ApiKeyEntry>>(emptyList())
            every { settings.activeApiKeyIds } returns MutableStateFlow(emptyMap())
            every { settings.providerBaseUrls } returns MutableStateFlow(emptyMap())
            registry = ProviderRegistry(
                settings = settings,
                conversations = conversations,
                localProvider = mockk<LocalProvider>(),
                scope = scope,
            )
        }

        fun close() {
            scope.cancel()
        }
    }
}
