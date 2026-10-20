package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.util.Constants
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ApiConnectionNoticeTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val connectivity = mockk<ConnectivityManager>()
    private val context = mockk<Context>()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowToast.reset()
        every { context.applicationContext } answers { app }
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.activeNetwork } returns null
    }
    @After fun cleanup() { Dispatchers.resetMain(); ShadowToast.reset() }

    @Test fun disconnectedApiShowsOneToastAcrossRetriesAndToolContinuations() = runTest {
        val notice = ApiConnectionNotice(context, Constants.PROVIDER_DEEPSEEK, "https://api.deepseek.com")
        repeat(3) { notice.showIfDisconnected() }
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(app.getString(R.string.api_connection_required), ShadowToast.getTextOfLatestToast())
    }
    @Test fun connectedApiOnlyNotifiesIfTheConnectionLaterDrops() = runTest {
        every { connectivity.activeNetwork } returns mockk<Network>()
        val notice = ApiConnectionNotice(context, Constants.PROVIDER_DEEPSEEK, null)
        notice.showIfDisconnected()
        assertEquals(0, ShadowToast.shownToastCount())
        every { connectivity.activeNetwork } returns null
        notice.showIfDisconnected()
        assertEquals(1, ShadowToast.shownToastCount())
    }
    @Test fun onDeviceModelsAndLoopbackApisDoNotRequireWifi() = runTest {
        ApiConnectionNotice(context, Constants.PROVIDER_LOCAL, null).showIfDisconnected()
        for (url in listOf("http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080"))
            ApiConnectionNotice(context, "Custom", url).showIfDisconnected()
        assertEquals(0, ShadowToast.shownToastCount())
    }
    @Test fun unavailableNetworkStateDoesNotInventADisconnection() = runTest {
        every { context.getSystemService(ConnectivityManager::class.java) } throws SecurityException("unavailable")
        ApiConnectionNotice(context, Constants.PROVIDER_DEEPSEEK, null).showIfDisconnected()
        assertEquals(0, ShadowToast.shownToastCount())
    }
}
