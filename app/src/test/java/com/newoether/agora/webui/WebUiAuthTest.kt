package com.newoether.agora.webui

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiAuthTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun closeStores() = runBlocking {
        scopes.forEach { it.cancel(); it.coroutineContext[Job]!!.join() }
    }
    // Low cost keeps the suite fast; the production default is checked separately.
    private val hasher = WebUiPasswordHasher(iterations = 1_000)

    @Test
    fun hashVerifiesOnlyTheOriginalPasswordAndUsesAFreshSalt() {
        val first = hasher.hash("correct horse")
        val second = hasher.hash("correct horse")

        assertTrue(hasher.verify("correct horse", first))
        assertFalse(hasher.verify("correct horsf", first))
        assertNotEquals(first, second)
        assertFalse(first.contains("correct horse"))
    }

    @Test
    fun malformedStoredHashesNeverVerify() {
        listOf(
            "",
            "plain-text",
            "pbkdf2-sha256\$abc\$c2FsdA\$aGFzaA",
            "pbkdf2-sha256\$1000\$\$aGFzaA",
            "md5\$1000\$c2FsdA\$aGFzaA",
        ).forEach { stored -> assertFalse(stored, hasher.verify("x", stored)) }
    }

    @Test
    fun productionCostIsAtLeast100kIterations() {
        assertTrue(WebUiPasswordHasher.DEFAULT_ITERATIONS >= 100_000)
    }

    @Test
    fun correctPasswordCreatesAValidSessionUntilLogout() = runBlocking {
        val auth = auth(hash = hasher.hash("pw"))

        val result = auth.login("pw") as WebUiLoginResult.Success

        assertTrue(auth.isValidSession(result.sessionToken))
        assertFalse(auth.isValidSession("forged"))
        assertFalse(auth.isValidSession(null))
        auth.logout(result.sessionToken)
        assertFalse(auth.isValidSession(result.sessionToken))
    }

    @Test
    fun missingPasswordRefusesEveryLogin() = runBlocking {
        assertEquals(WebUiLoginResult.NotConfigured, auth(hash = null).login("anything"))
    }

    @Test
    fun tenFailuresLockEveryAttemptForFiveMinutesThenTheCounterRestarts() = runBlocking {
        var now = 1_000L
        val auth = auth(hash = hasher.hash("pw"), clock = { now })

        repeat(9) { index ->
            assertEquals(WebUiLoginResult.WrongPassword(9 - index), auth.login("bad"))
        }
        val locked = auth.login("bad") as WebUiLoginResult.LockedOut
        assertEquals(1_000L + 5 * 60 * 1000L, locked.untilMillis)
        // A correct password is refused too while locked.
        assertEquals(locked, auth.login("pw"))

        now = locked.untilMillis
        assertEquals(WebUiLoginResult.WrongPassword(9), auth.login("bad"))
        assertTrue(auth.login("pw") is WebUiLoginResult.Success)
        // A success resets the count.
        assertEquals(WebUiLoginResult.WrongPassword(9), auth.login("bad"))
    }

    @Test
    fun revokingAllSessionsSignsEveryBrowserOut() = runBlocking {
        val auth = auth(hash = hasher.hash("pw"))
        val first = (auth.login("pw") as WebUiLoginResult.Success).sessionToken
        val second = (auth.login("pw") as WebUiLoginResult.Success).sessionToken

        assertNotEquals(first, second)
        auth.revokeAllSessions()

        assertFalse(auth.isValidSession(first))
        assertFalse(auth.isValidSession(second))
    }

    @Test fun sessionsSurviveDiskReopenAndLogoutOnlyRevokesItsOwnToken() = runBlocking {
        val path = temporary.root.resolve("restart.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        fun open(scope: CoroutineScope) = WebUiSettingsStore(PreferenceDataStoreFactory.create(
            scope = scope, storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path.absolutePath.toPath() },
        ))
        val initial = open(firstScope)
        initial.savePasswordHash(hasher.hash("pw"))
        val auth = WebUiAuth(initial, hasher)
        val tokens = coroutineScope { (0 until 12).map { async { (auth.login("pw") as WebUiLoginResult.Success).sessionToken } }.awaitAll() }
        assertEquals(12, initial.sessionDigests.first().size)
        assertTrue(tokens.none { it in initial.sessionDigests.first() })
        firstScope.cancel(); firstScope.coroutineContext[Job]!!.join()
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val reopened = open(nextScope)
        val restored = WebUiAuth(reopened, hasher)
        tokens.forEach { assertTrue(restored.isValidSession(it)) }
        val ended = async(start = CoroutineStart.UNDISPATCHED) { restored.awaitSessionEnd(tokens.first()) }
        restored.logout(tokens.first())
        withTimeout(5000) { ended.await() }
        assertFalse(restored.isValidSession(tokens.first()))
        tokens.drop(1).forEach { assertTrue(restored.isValidSession(it)) }
        reopened.savePasswordHash(hasher.hash("new"))
        tokens.forEach { assertFalse(restored.isValidSession(it)) }
    }

    @Test fun passwordReplacementAtomicallyRejectsAnOldLoginCommit() = runBlocking {
        val store = store(hasher.hash("pw"))
        val previous = store.passwordHash.first()!!
        val auth = WebUiAuth(store, hasher)
        val token = (auth.login("pw") as WebUiLoginResult.Success).sessionToken
        store.savePasswordHash(hasher.hash("new"))
        assertFalse(store.addSession(previous, "stale-digest"))
        assertTrue(store.sessionDigests.first().isEmpty())
        assertFalse(auth.isValidSession(token))
        assertTrue(auth.login("new") is WebUiLoginResult.Success)
    }

    private fun store(hash: String?): WebUiSettingsStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val path = temporary.root.resolve("store-${scopes.size}.preferences_pb")
        return WebUiSettingsStore(PreferenceDataStoreFactory.create(scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path.absolutePath.toPath() },
        )).also { if (hash != null) runBlocking { it.savePasswordHash(hash) } }
    }

    private fun auth(hash: String?, clock: () -> Long = { 0L }) =
        WebUiAuth(store(hash), hasher = hasher, clock = clock)
}
