package com.newoether.agora.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsPollerTest {

    private fun reader(
        supported: Boolean = true,
        permission: Boolean = true,
        newMessages: List<SmsMessageData> = emptyList(),
        maxId: Long = 0L,
    ): SmsReader = mockk<SmsReader>().apply {
        every { isSupported() } returns supported
        every { hasPermission() } returns permission
        coEvery { readNewMessages(any(), any()) } returns newMessages
        coEvery { currentMaxInboxId() } returns maxId
    }

    @Test
    fun `first poll seeds high-water mark and skips reading history`() = runBlocking {
        val store = mockk<SmsStore>(relaxed = true)
        coEvery { store.getSyncStateOnce() } returns SmsSyncState() // lastSeenId = 0
        val smsReader = reader(maxId = 42L)

        SmsPoller(store, smsReader).poll()

        coVerify(exactly = 1) {
            store.updateSyncState(match { state -> state.lastSeenId == 42L && state.lastError == null })
        }
        coVerify(exactly = 0) { smsReader.readNewMessages(any(), any()) }
        coVerify(exactly = 0) { store.saveMessages(any(), any()) }
    }

    @Test
    fun `revoked permission records error and does not read`() = runBlocking {
        val store = mockk<SmsStore>(relaxed = true)
        coEvery { store.getSyncStateOnce() } returns SmsSyncState(lastSeenId = 5L)
        val smsReader = reader(permission = false)

        SmsPoller(store, smsReader).poll()

        coVerify(exactly = 1) {
            store.updateSyncState(match { state -> state.lastError == "Permission not granted" })
        }
        coVerify(exactly = 0) { smsReader.readNewMessages(any(), any()) }
    }

    @Test
    fun `unsupported build is a no-op`() = runBlocking {
        val store = mockk<SmsStore>(relaxed = true)
        val smsReader = reader(supported = false)

        SmsPoller(store, smsReader).poll()

        coVerify(exactly = 0) { store.updateSyncState(any()) }
        coVerify(exactly = 0) { smsReader.readNewMessages(any(), any()) }
    }

    @Test
    fun `poll saves new messages and advances lastSeenId`() = runBlocking {
        val store = mockk<SmsStore>(relaxed = true)
        coEvery { store.getSyncStateOnce() } returns SmsSyncState(lastSeenId = 5L)
        val incoming = listOf(
            SmsMessageData(6L, "+1", 1L, "unread", "body6", read = false),
            SmsMessageData(7L, "+2", 2L, "read", "body7", read = true),
        )
        val smsReader = reader(newMessages = incoming)

        SmsPoller(store, smsReader).poll()

        coVerify(exactly = 1) {
            store.saveMessages(
                match { msgs -> msgs.map { it.id } == listOf(6L, 7L) },
                match { state ->
                    state.lastSeenId == 7L &&
                        state.unreadCount == 1 &&
                        state.lastSyncEpochMs > 0L &&
                        state.lastError == null
                },
            )
        }
    }

    @Test
    fun `poll without new messages updates timestamps only`() = runBlocking {
        val store = mockk<SmsStore>(relaxed = true)
        coEvery { store.getSyncStateOnce() } returns SmsSyncState(lastSeenId = 5L)
        val smsReader = reader(newMessages = emptyList())

        SmsPoller(store, smsReader).poll()

        coVerify(exactly = 0) { store.saveMessages(any(), any()) }
        coVerify(exactly = 1) {
            store.updateSyncState(match { state ->
                state.lastSeenId == 5L && state.lastError == null && state.lastAttemptEpochMs > 0L
            })
        }
    }

    @Test
    fun `reader exception records failure without crashing`() = runBlocking {
        val store = mockk<SmsStore>(relaxed = true)
        coEvery { store.getSyncStateOnce() } returns SmsSyncState(lastSeenId = 5L)
        val smsReader = mockk<SmsReader>().apply {
            every { isSupported() } returns true
            every { hasPermission() } returns true
            coEvery { readNewMessages(any(), any()) } throws RuntimeException("boom")
        }

        SmsPoller(store, smsReader).poll()

        coVerify(exactly = 1) {
            store.updateSyncState(match { state -> state.lastError == "boom" })
        }
    }
}