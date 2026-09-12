package com.newoether.agora.data

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.SmsDraftEntity
import com.newoether.agora.data.local.SmsDraftStatus
import com.newoether.agora.sms.SmsSendResult
import com.newoether.agora.sms.SmsSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsDraftStoreTest {

    private fun draft(id: String = "d1", status: SmsDraftStatus = SmsDraftStatus.PENDING) = SmsDraft(
        id = id,
        address = "+15551234567",
        body = "Hello",
        status = status,
    )

    /**
     * Real SmsDraftStore (so sendDraft's orchestration actually runs) with a mocked
     * DAO. The DAO's drafts flow carries the initial drafts, so the store's real
     * `drafts` property reflects them — stubbing the property itself does not work
     * because Kotlin reads the backing field directly inside the class.
     */
    private fun storeWith(vararg initial: SmsDraft): SmsDraftStore {
        val chatDao = mockk<ChatDao>(relaxed = true)
        every { chatDao.getSmsDraftsFlow() } returns MutableStateFlow(
            initial.map { draft ->
                SmsDraftEntity(
                    id = draft.id,
                    address = draft.address,
                    body = draft.body,
                    createdAtEpochMs = draft.createdAtEpochMs,
                    inReplyToSmsId = draft.inReplyToSmsId,
                    status = draft.status,
                    lastError = draft.lastError,
                )
            },
        )
        return spyk(SmsDraftStore(chatDao, mockk<ChatDatabase>(relaxed = true)))
    }

    @Test
    fun `sendDraft transitions PENDING to SENDING to SENT on success`() = runBlocking {
        val store = storeWith(draft("d1"))
        val sender = mockk<SmsSender>()
        coEvery { sender.sendSms("+15551234567", "Hello") } returns SmsSendResult.Success

        val result = store.sendDraft("d1", sender)

        assertTrue(result)
        coVerify(exactly = 1) { store.updateStatus("d1", SmsDraftStatus.SENDING, null) }
        coVerify(exactly = 1) { store.updateStatus("d1", SmsDraftStatus.SENT, null) }
    }

    @Test
    fun `sendDraft records failure reason`() = runBlocking {
        val store = storeWith(draft("d1"))
        val sender = mockk<SmsSender>()
        coEvery { sender.sendSms("+15551234567", "Hello") } returns SmsSendResult.Failure("No signal")

        val result = store.sendDraft("d1", sender)

        assertFalse(result)
        coVerify(exactly = 1) { store.updateStatus("d1", SmsDraftStatus.SENDING, null) }
        coVerify(exactly = 1) { store.updateStatus("d1", SmsDraftStatus.FAILED, "No signal") }
    }

    @Test
    fun `sendDraft refuses non-pending drafts`() = runBlocking {
        val store = storeWith(draft("d1", SmsDraftStatus.SENDING))
        val sender = mockk<SmsSender>()

        val result = store.sendDraft("d1", sender)

        assertFalse(result)
        coVerify(exactly = 0) { sender.sendSms(any(), any()) }
        coVerify(exactly = 0) { store.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `sendDraft returns false for unknown draft`() = runBlocking {
        val store = storeWith(draft("d1"))
        val sender = mockk<SmsSender>()

        val result = store.sendDraft("missing", sender)

        assertFalse(result)
        coVerify(exactly = 0) { sender.sendSms(any(), any()) }
    }
}