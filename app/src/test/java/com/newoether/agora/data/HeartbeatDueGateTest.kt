package com.newoether.agora.data

import com.newoether.agora.data.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatDueGateTest {
    private fun manager(heartbeatEnabled: Boolean, lastEpochMs: Long): HeartbeatManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.heartbeatEnabled } returns MutableStateFlow(heartbeatEnabled)
        every { settings.heartbeatIntervalMinutes } returns MutableStateFlow(5)
        every { settings.heartbeatActiveHoursStart } returns MutableStateFlow(0)
        every { settings.heartbeatActiveHoursEnd } returns MutableStateFlow(24)
        every { settings.heartbeatLastHeartbeatEpochMs } returns MutableStateFlow(lastEpochMs)
        return HeartbeatManager(
            settingsRepository = settings,
            memoryManager = mockk(relaxed = true),
            taskManager = mockk(relaxed = true),
            conversationRepository = mockk(relaxed = true),
            chatDao = mockk(relaxed = true),
        )
    }

    @Test
    fun heartbeatIsNeverDueWhenDisabledEvenIfIntervalElapsed() {
        val manager = manager(
            heartbeatEnabled = false,
            lastEpochMs = System.currentTimeMillis() - 10 * 60_000L,
        )
        assertFalse(manager.isHeartbeatDue())
    }

    @Test
    fun heartbeatIsDueWhenEnabledAndIntervalElapsed() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = System.currentTimeMillis() - 10 * 60_000L,
        )
        assertTrue(manager.isHeartbeatDue())
    }

    @Test
    fun heartbeatIsNotDueWhenEnabledButIntervalNotElapsed() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = System.currentTimeMillis(),
        )
        assertFalse(manager.isHeartbeatDue())
    }
}