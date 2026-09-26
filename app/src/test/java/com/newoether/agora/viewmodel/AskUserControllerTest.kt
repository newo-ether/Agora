package com.newoether.agora.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskUserControllerTest {

    @Test
    fun `an answer resolves the waiting call and clears the request`() = runTest {
        val controller = AskUserController()
        val request = controller.open(
            conversationId = "c1",
            question = "Which one?",
            options = listOf("A", "B"),
            allowMultiple = false,
        )
        val answer = async { controller.awaitAnswer(request) }
        runCurrent()
        assertEquals(listOf(request), controller.requests.value)

        controller.submit(request.id, listOf("B"))

        assertEquals(listOf("B"), answer.await().choices)
        assertTrue(answer.await().answered)
        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `skipping reports no answer instead of an empty choice`() = runTest {
        val controller = AskUserController()
        val request = controller.open("c1", "Which one?", listOf("A"), allowMultiple = false)
        val answer = async { controller.awaitAnswer(request) }
        runCurrent()

        controller.dismiss(request.id)

        assertFalse(answer.await().answered)
        assertTrue(answer.await().choices.isEmpty())
        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `a question waits indefinitely because no timeout may answer it`() = runTest {
        val controller = AskUserController()
        val request = controller.open("c1", "Which one?", listOf("A", "B"), allowMultiple = false)
        val answer = async { controller.awaitAnswer(request) }
        runCurrent()

        advanceTimeBy(24L * 60 * 60 * 1_000)
        runCurrent()
        assertFalse(answer.isCompleted)
        assertEquals(listOf(request), controller.requests.value)

        controller.submit(request.id, listOf("A"))
        assertEquals(listOf("A"), answer.await().choices)
    }

    @Test
    fun `an answer carrying a stale request id decides nothing`() = runTest {
        val controller = AskUserController()
        val first = controller.open("c1", "First?", listOf("A"), allowMultiple = false)
        val firstAnswer = async { controller.awaitAnswer(first) }
        runCurrent()
        controller.submit(first.id, listOf("A"))
        firstAnswer.await()

        val second = controller.open("c1", "Second?", listOf("B"), allowMultiple = false)
        val secondAnswer = async { controller.awaitAnswer(second) }
        runCurrent()

        controller.submit(first.id, listOf("A"))
        runCurrent()
        assertFalse(secondAnswer.isCompleted)
        assertEquals(listOf(second), controller.requests.value)

        controller.submit(second.id, listOf("B"))
        assertEquals(listOf("B"), secondAnswer.await().choices)
    }

    @Test
    fun `requests keep the order they were asked in and stay addressable by id`() = runTest {
        val controller = AskUserController()
        val first = controller.open("c1", "First?", listOf("A"), allowMultiple = false)
        val second = controller.open("c2", "Second?", listOf("B", "C"), allowMultiple = true)

        assertEquals(listOf(first, second), controller.requests.value)
        assertEquals(second, controller.requestById(second.id))
        assertEquals(listOf("B", "C"), controller.requestById(second.id)?.options)

        controller.dismiss(first.id)
        assertEquals(listOf(second), controller.requests.value)
        assertEquals(null, controller.requestById(first.id))
    }
}
