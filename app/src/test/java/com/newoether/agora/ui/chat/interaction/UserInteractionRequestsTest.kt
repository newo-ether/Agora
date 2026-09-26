package com.newoether.agora.ui.chat.interaction

import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ShellConfirmationController
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Test

class UserInteractionRequestsTest {

    @Test
    fun `a conversation shows its own questions and the ones with no conversation`() {
        val mine = question(id = 1, conversationId = "c1")
        val other = question(id = 2, conversationId = "c2")
        val unowned = question(id = 3, conversationId = null)

        val interactions = userInteractions("c1", listOf(mine, other, unowned), null)

        assertEquals(
            listOf("question:1", "question:3"),
            interactions.map { it.key },
        )
    }

    @Test
    fun `the shell confirmation is always last so the oldest question is answered first`() {
        val first = question(id = 7, conversationId = "c1")
        val pending = ShellConfirmationController.PendingShellCommand(
            id = 7,
            server = "tinybox",
            summary = "ls",
            deferred = CompletableDeferred(),
        )

        val interactions = userInteractions("c1", listOf(first), pending)

        // Both controllers number requests independently, so identical ids must stay distinct.
        assertEquals(listOf("question:7", "shell:7"), interactions.map { it.key })
    }

    @Test
    fun `nothing pending produces no cards`() {
        assertEquals(emptyList<UserInteraction>(), userInteractions("c1", emptyList(), null))
    }

    private fun question(id: Long, conversationId: String?) = AskUserController.Request(
        id = id,
        conversationId = conversationId,
        question = "Which one?",
        options = listOf("A", "B"),
        allowMultiple = false,
    )
}
