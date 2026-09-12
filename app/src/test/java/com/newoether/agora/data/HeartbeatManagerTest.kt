package com.newoether.agora.data

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.HeartbeatLogEntity
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartbeatManagerTest {

    private fun manager(dao: ChatDao) = HeartbeatManager(
        settingsRepository = mockk(relaxed = true),
        memoryManager = mockk(relaxed = true),
        taskManager = mockk(relaxed = true),
        conversationRepository = mockk(relaxed = true),
        chatDao = dao,
    )

    @Test
    fun recordHeartbeatCapsErrorAtMaxLoggedChars() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        val hugeError = "x".repeat(HeartbeatManager.MAX_LOGGED_ERROR_CHARS * 10)

        manager(dao).recordHeartbeat(success = false, error = hugeError)

        coVerify(exactly = 1) {
            dao.insertHeartbeatLog(
                match {
                    !it.success &&
                        it.error?.length == HeartbeatManager.MAX_LOGGED_ERROR_CHARS
                },
            )
        }
    }

    @Test
    fun recordHeartbeatKeepsShortErrorIntact() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)

        manager(dao).recordHeartbeat(success = false, error = "Generation failed")

        coVerify(exactly = 1) {
            dao.insertHeartbeatLog(match { it.error == "Generation failed" })
        }
    }

    @Test
    fun recordHeartbeatSuccessStoresNullError() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)

        manager(dao).recordHeartbeat(success = true)

        coVerify(exactly = 1) {
            dao.insertHeartbeatLog(match { it.success && it.error == null })
        }
    }
}
