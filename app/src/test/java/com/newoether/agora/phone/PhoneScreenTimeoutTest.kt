package com.newoether.agora.phone

import android.app.Activity
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSettings

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [PhoneScreenTimeoutTest.TimeoutSettingsShadow::class])
class PhoneScreenTimeoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val journal = AtomicFile(File(context.noBackupFilesDir, "phone-screen-timeout-test"))
    private val system = TimeoutSettingsShadow
    private var value: Int
        get() = system.readValue(context.contentResolver)
        set(value) { system.seedValue(context.contentResolver, value) }

    @Before fun setUp() {
        journal.delete()
        system.resetHooks()
        value = 30_000
    }

    @After fun tearDown() {
        journal.delete()
        system.resetHooks()
        unmockkAll()
    }

    private fun TestScope.controller(file: AtomicFile = journal) =
        PhoneScreenTimeout(context, file, StandardTestDispatcher(testScheduler))

    private suspend fun claim(control: PhoneScreenTimeout, epoch: Long = 1) = control.acquire(epoch) { true }
    private fun exists() = journal.baseFile.exists()

    @Test fun journalPrecedesMutationAndReleaseRestoresExactlyOnce() = runTest {
        system.afterWrite = { target -> if (target == PhoneScreenTimeout.MINIMUM_MS) assertTrue(exists()) }
        val control = controller()
        claim(control)
        assertEquals(PhoneScreenTimeout.MINIMUM_MS, value)
        claim(control)
        assertEquals(listOf(PhoneScreenTimeout.MINIMUM_MS), system.writes)
        assertTrue(control.release(1))
        assertEquals(30_000, value)
        assertFalse(exists())
        assertTrue(control.release(1))
        assertEquals(listOf(PhoneScreenTimeout.MINIMUM_MS, 30_000), system.writes)
    }

    @Test fun existingLongTimeoutIsNeverShortenedOrJournaled() = runTest {
        value = 3_600_000
        val control = controller()
        claim(control)
        assertEquals(3_600_000, value)
        assertFalse(exists())
        assertTrue(control.release(1))
        assertTrue(system.writes.isEmpty())
    }

    @Test fun userChangesDuringLeaseAreRetainedOnReleaseAndRecovery() = runTest {
        val control = controller()
        claim(control)
        value = 120_000
        assertTrue(control.release(1))
        assertEquals(120_000, value)
        assertFalse(exists())
        claim(control, 2)
        value = 60_000
        assertTrue(controller().recover())
        assertEquals(60_000, value)
        assertFalse(exists())
    }

    @Test fun processRestartRestoresAndNewClaimCapturesTheTrueOriginal() = runTest {
        claim(controller())
        val restarted = controller()
        claim(restarted, 2)
        assertEquals(PhoneScreenTimeout.MINIMUM_MS, value)
        assertTrue(restarted.release(2))
        assertEquals(30_000, value)
        assertFalse(exists())
        assertEquals(listOf(PhoneScreenTimeout.MINIMUM_MS, 30_000, PhoneScreenTimeout.MINIMUM_MS, 30_000), system.writes)
    }

    @Test fun oldEpochCleanupCannotRestoreAReplacementClaim() = runTest {
        val control = controller()
        claim(control)
        claim(control, 2)
        assertTrue(control.release(1))
        assertEquals(PhoneScreenTimeout.MINIMUM_MS, value)
        assertTrue(exists())
        assertTrue(control.release(2))
        assertEquals(30_000, value)
    }

    @Test fun recoveryDuringAnActiveLeaseDoesNotUndoTheExtension() = runTest {
        val control = controller()
        claim(control)
        assertTrue(control.recover())
        assertEquals(PhoneScreenTimeout.MINIMUM_MS, value)
        assertEquals(listOf(PhoneScreenTimeout.MINIMUM_MS), system.writes)
        assertTrue(control.release(1))
    }

    @Test fun failedRestoreAndPermissionLossKeepTheJournalForAnEventRetry() = runTest {
        val control = controller()
        claim(control)
        system.writable = false
        assertFalse(control.release(1))
        assertTrue(exists())
        assertEquals(PhoneScreenTimeout.MINIMUM_MS, value)
        assertFalse(controller().recover())
        system.writable = true
        system.writeResult = { target -> target != 30_000 }
        assertFalse(controller().recover())
        assertTrue(exists())
        system.writeResult = { true }
        assertTrue(controller().recover())
        assertEquals(30_000, value)
        assertFalse(exists())
    }

    @Test fun pendingRollbackFailureBlocksNewMutation() = runTest {
        val old = controller()
        claim(old)
        system.writable = false
        assertFalse(old.release(1))
        val error = runCatching { claim(controller(), 2) }.exceptionOrNull() as PhoneFailure
        assertEquals("screen_timeout_restore_failed", error.code)
        assertEquals(listOf(PhoneScreenTimeout.MINIMUM_MS), system.writes)
        assertTrue(exists())
    }

    @Test fun missingGrantFailsBeforeAnyMutationOrJournal() = runTest {
        system.writable = false
        val error = runCatching { claim(controller()) }.exceptionOrNull() as PhoneFailure
        assertEquals("permission_lost:not_ready:write_settings", error.code)
        assertEquals(30_000, value)
        assertFalse(exists())
        assertTrue(system.writes.isEmpty())
    }

    @Test fun failedExtensionClearsTheUnusedJournal() = runTest {
        system.writeResult = { false }
        val error = runCatching { claim(controller()) }.exceptionOrNull() as PhoneFailure
        assertEquals("screen_timeout_write_failed", error.code)
        assertEquals(30_000, value)
        assertFalse(exists())
    }

    @Test fun exceptionAfterAWriteRollsBackAndPreservesThePermissionCode() = runTest {
        system.afterWrite = { target -> if (target == PhoneScreenTimeout.MINIMUM_MS) throw SecurityException("denied") }
        val error = runCatching { claim(controller()) }.exceptionOrNull() as PhoneFailure
        assertEquals("permission_lost:write_settings_security_exception", error.code)
        assertEquals(30_000, value)
        assertFalse(exists())
    }

    @Test fun failedDurableJournalCannotChangeTheSystemValue() = runTest {
        val broken = mockk<AtomicFile>()
        every { broken.baseFile } returns journal.baseFile
        every { broken.startWrite() } throws IOException("disk full")
        every { broken.delete() } just Runs
        val error = runCatching { claim(controller(broken)) }.exceptionOrNull() as PhoneFailure
        assertEquals("screen_timeout_journal_failed", error.code)
        assertEquals(30_000, value)
        assertTrue(system.writes.isEmpty())
    }

    @Test fun failedJournalDeletionKeepsRecoverableStateWithoutRepeatingARestoredWrite() = runTest {
        val file = spyk(journal)
        var deletable = false
        every { file.delete() } answers { if (deletable) callOriginal() else Unit }
        val control = controller(file)
        claim(control)
        assertFalse(control.release(1))
        assertEquals(30_000, value)
        assertTrue(exists())
        val count = system.writes.size
        deletable = true
        assertTrue(control.recover())
        assertEquals(count, system.writes.size)
        assertFalse(exists())
    }

    @Test fun staleLeaseAfterWriteRestoresBeforeReturningAnError() = runTest {
        var current = true
        system.afterWrite = { target -> if (target == PhoneScreenTimeout.MINIMUM_MS) current = false }
        val error = runCatching { controller().acquire(1) { current } }.exceptionOrNull() as PhoneFailure
        assertEquals("not_claimed", error.code)
        assertEquals(30_000, value)
        assertFalse(exists())
    }

    @Test fun cancellationAfterWriteRestoresInANonCancellableBoundary() = runTest {
        val control = controller()
        val task = launch {
            system.afterWrite = { target -> if (target == PhoneScreenTimeout.MINIMUM_MS) throw CancellationException("stopped") }
            claim(control)
        }
        runCurrent()
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(30_000, value)
        assertFalse(exists())
    }

    @Test fun queuedReleaseAndNewClaimRemainSerialized() = runTest {
        val control = controller()
        claim(control)
        val released = async { control.release(1) }
        val replacement = async { claim(control, 2) }
        assertTrue(released.await())
        replacement.await()
        assertEquals(PhoneScreenTimeout.MINIMUM_MS, value)
        assertTrue(control.release(2))
        assertEquals(30_000, value)
    }

    @Test fun externalChangeBeforeWriteIsPreservedAndClaimFails() = runTest {
        var reads = 0
        system.beforeRead = { if (++reads == 2) value = 90_000 }
        val error = runCatching { claim(controller()) }.exceptionOrNull() as PhoneFailure
        assertEquals("screen_timeout_changed", error.code)
        assertEquals(90_000, value)
        assertTrue(system.writes.isEmpty())
        assertFalse(exists())
    }

    @Test fun startupAndResumeRestoreButNeverPollOrUndoALiveLease() = runTest {
        claim(controller())
        val application = mockk<Application>()
        lateinit var callbacks: Application.ActivityLifecycleCallbacks
        every { application.registerActivityLifecycleCallbacks(any()) } answers { callbacks = firstArg() }
        val restarted = controller()
        restarted.recoverOnAppEvents(application, backgroundScope)
        runCurrent()
        assertEquals(30_000, value)
        assertFalse(exists())
        restarted.recoverOnAppEvents(application, backgroundScope)
        verify(exactly = 1) { application.registerActivityLifecycleCallbacks(any()) }
        claim(restarted, 2)
        callbacks.onActivityResumed(mockk<Activity>())
        runCurrent()
        assertEquals(PhoneScreenTimeout.MINIMUM_MS, value)
        system.writable = false
        assertFalse(restarted.release(2))
        system.writable = true
        callbacks.onActivityResumed(mockk<Activity>())
        runCurrent()
        assertEquals(30_000, value)
        assertFalse(exists())
    }

    /** Adds fault hooks while keeping values in Robolectric's standard settings storage. */
    @Implements(Settings.System::class)
    class TimeoutSettingsShadow {
        private class Storage : ShadowSettings.ShadowSystem() {
            fun read(resolver: ContentResolver, name: String): Int = ShadowSettings.ShadowSystem.getInt(resolver, name)
            fun write(resolver: ContentResolver, name: String, value: Int): Boolean =
                ShadowSettings.ShadowSystem.putInt(resolver, name, value)
        }

        companion object {
            private val storage = Storage()
            var writable = true
            val writes = mutableListOf<Int>()
            var afterWrite: (Int) -> Unit = {}
            var writeResult: (Int) -> Boolean = { true }
            var beforeRead: () -> Unit = {}

            fun resetHooks() {
                writable = true
                writes.clear()
                afterWrite = {}
                writeResult = { true }
                beforeRead = {}
            }

            // Fixture changes bypass hooks so user writes are distinct from application writes.
            fun readValue(resolver: ContentResolver): Int = storage.read(resolver, Settings.System.SCREEN_OFF_TIMEOUT)
            fun seedValue(resolver: ContentResolver, value: Int) {
                check(storage.write(resolver, Settings.System.SCREEN_OFF_TIMEOUT, value))
            }

            @JvmStatic
            @Implementation(minSdk = 23)
            fun canWrite(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = writable

            @JvmStatic
            @Implementation
            fun getInt(resolver: ContentResolver, name: String): Int {
                if (name == Settings.System.SCREEN_OFF_TIMEOUT) beforeRead()
                return storage.read(resolver, name)
            }

            @JvmStatic
            @Implementation
            fun putInt(resolver: ContentResolver, name: String, value: Int): Boolean {
                if (name != Settings.System.SCREEN_OFF_TIMEOUT) return storage.write(resolver, name, value)
                if (!writeResult(value)) return false
                val success = storage.write(resolver, name, value)
                if (success) {
                    writes += value
                    afterWrite(value)
                }
                return success
            }
        }
    }
}
