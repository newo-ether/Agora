package com.newoether.agora.phone

import android.app.Application
import android.content.Context
import android.content.ServiceConnection
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhonePermissionBackendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @After fun tearDown() { Dispatchers.resetMain(); unmockkAll() }

    @Test fun rootSettingsProbeCannotOverwriteClaimAdmissionAndHeldEntrySkipsOverlay() = runTest {
        val backend = mockk<PhonePrivilegedConnection>()
        val permissions = PhonePermissions(context, backend)
        coEvery { backend.command("root", "id -u") } returns "0\n".toByteArray()
        assertTrue(permissions.claimRoot())
        coEvery { backend.command("root", "id -u") } throws PhoneFailure("permission_lost:root_denied")
        assertFalse(permissions.probeRoot())
        assertTrue(permissions.rootGranted)
        mockkStatic(android.provider.Settings::class)
        every { android.provider.Settings.canDrawOverlays(any()) } returns false
        val held = permissions.granted("root", includeOverlay = false)
        assertFalse(held.containsKey("overlay"))
        assertTrue(held.getValue("root"))
        verify(exactly = 0) { android.provider.Settings.canDrawOverlays(any()) }
        assertFalse(permissions.granted("root").getValue("overlay"))
        coVerify(exactly = 2) { backend.command("root", "id -u") }
    }
    @Test fun rootClaimTimeoutPreservesThePreviousAdmission() = runTest {
        val backend = mockk<PhonePrivilegedConnection>()
        val permissions = PhonePermissions(context, backend)
        coEvery { backend.command("root", "id -u") } returns "0\n".toByteArray()
        assertTrue(permissions.claimRoot())
        coEvery { backend.command("root", "id -u") } throws PhoneFailure("device_timeout")
        val error = runCatching { permissions.claimRoot() }.exceptionOrNull() as PhoneFailure
        assertEquals("device_timeout", error.code)
        assertTrue(permissions.rootGranted)
    }
    @Test fun oldShizukuBindingCannotDisconnectTheReplacementAndTimeoutKeepsItsCode() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Shizuku::class)
        every { Shizuku.pingBinder() } returns true
        every { Shizuku.isPreV11() } returns false
        every { Shizuku.checkSelfPermission() } returns android.content.pm.PackageManager.PERMISSION_GRANTED
        val callbacks = mutableListOf<ServiceConnection>()
        every { Shizuku.bindUserService(any(), capture(callbacks)) } just Runs
        every { Shizuku.unbindUserService(any(), any(), any()) } just Runs
        val releases = mutableListOf<Long>()
        val backend = PhonePrivilegedConnection(context, releases::add)
        backend.leaseEpoch = 1
        val old = async { backend.service() }
        runCurrent()
        backend.close(); old.cancel(); old.join()
        backend.leaseEpoch = 2
        val fresh = async { runCatching { backend.service() } }
        runCurrent()
        callbacks.first().onServiceDisconnected(null)
        assertTrue(releases.isEmpty())
        callbacks.last().onServiceDisconnected(null)
        assertEquals("permission_lost:shizuku_service_disconnected",
            (fresh.await().exceptionOrNull() as PhoneFailure).code)
        assertEquals(listOf(2L), releases)
    }
    @Test fun bindingTimeoutReturnsDeviceErrorWithoutPermissionRevocation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Shizuku::class)
        every { Shizuku.pingBinder() } returns true
        every { Shizuku.isPreV11() } returns false
        every { Shizuku.checkSelfPermission() } returns android.content.pm.PackageManager.PERMISSION_GRANTED
        every { Shizuku.bindUserService(any(), any()) } just Runs
        every { Shizuku.unbindUserService(any(), any(), any()) } just Runs
        val releases = mutableListOf<Long>()
        val backend = PhonePrivilegedConnection(context, releases::add)
        val result = async { runCatching { backend.service() } }
        runCurrent(); advanceTimeBy(15_000); runCurrent()
        assertEquals("device_timeout", (result.await().exceptionOrNull() as PhoneFailure).code)
        assertTrue(releases.isEmpty())
    }
    @Test fun pipeDeadlineClosesAStalledReadAndReturnsTimeout() = runTest {
        val descriptor = mockk<ParcelFileDescriptor>()
        val closed = CountDownLatch(1)
        every { descriptor.close() } answers { closed.countDown(); Unit }
        mockkConstructor(ParcelFileDescriptor.AutoCloseInputStream::class)
        every { anyConstructed<ParcelFileDescriptor.AutoCloseInputStream>().read(any<ByteArray>()) } answers {
            closed.await(5, TimeUnit.SECONDS)
            throw IOException("closed")
        }
        every { anyConstructed<ParcelFileDescriptor.AutoCloseInputStream>().close() } just Runs
        val result = async { runCatching { readPhonePipe(descriptor) } }
        runCurrent(); advanceTimeBy(35_000); runCurrent()
        assertEquals("device_timeout", (result.await().exceptionOrNull() as PhoneFailure).code)
        verify(exactly = 1) { descriptor.close() }
    }
    @Test fun accessibilityOldDestroyCannotDisconnectAReplacementInstance() {
        var disconnects = 0
        PhoneAccessibilityService.onDisconnected = { disconnects++ }
        val old = PhoneAccessibilityService()
        val fresh = PhoneAccessibilityService()
        val connected = PhoneAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }
        connected.invoke(old); connected.invoke(fresh)
        old.onDestroy()
        assertSame(fresh, PhoneAccessibilityService.instance)
        assertEquals(0, disconnects)
        fresh.onUnbind(null)
        fresh.onDestroy()
        assertNull(PhoneAccessibilityService.instance)
        assertEquals(1, disconnects)
        PhoneAccessibilityService.onDisconnected = null
    }
}
