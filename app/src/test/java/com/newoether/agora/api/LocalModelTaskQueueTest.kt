package com.newoether.agora.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalModelTaskQueueTest {
    @Test
    fun `waiters run in strict fifo order without overlap`() = runTest {
        val queue = LocalModelTaskQueue()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var active = 0

        launch {
            queue.run {
                assertEquals(0, active++)
                events += "first-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                events += "first-end"
                active--
            }
        }
        firstStarted.await()
        launch {
            queue.run {
                assertEquals(0, active++)
                events += "second"
                active--
            }
        }
        launch {
            queue.run {
                assertEquals(0, active++)
                events += "third"
                active--
            }
        }

        runCurrent()
        assertEquals(listOf("first-start"), events)
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first-start", "first-end", "second", "third"), events)
        assertEquals(0, active)
    }

    @Test
    fun `cancelled waiter is removed without disturbing later work`() = runTest {
        var arrivals = 0
        var idleTransitions = 0
        val queue = LocalModelTaskQueue(
            onTaskArrived = { arrivals++ },
            onQueueIdle = { idleTransitions++ },
        )
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        launch {
            queue.run {
                events += "first"
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val cancelled = launch { queue.run { events += "cancelled" } }
        launch { queue.run { events += "last" } }

        runCurrent()
        cancelled.cancelAndJoin()
        assertEquals(0, idleTransitions)
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first", "last"), events)
        assertEquals(3, arrivals)
        assertEquals(1, idleTransitions)
    }

    @Test
    fun `conditional idle maintenance shares the task permit`() = runTest {
        var idleSignals = 0
        val queue = LocalModelTaskQueue(onQueueIdle = { idleSignals++ })
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        launch {
            queue.run {
                events += "task-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                events += "task-end"
            }
        }
        firstStarted.await()
        assertFalse(queue.signalIdleIfEmpty())
        assertEquals(0, idleSignals)
        val maintenance = launch {
            assertTrue(queue.runIfIdle { events += "maintenance" })
        }

        runCurrent()
        assertEquals(listOf("task-start"), events)
        releaseFirst.complete(Unit)
        maintenance.join()

        assertEquals(listOf("task-start", "task-end", "maintenance"), events)
        assertEquals(1, idleSignals)
    }

    @Test
    fun `only context constructing parameters belong to resident identity`() {
        val path = "C:/models/model.gguf"

        assertEquals(
            LocalModelIdentity.Chat(path, 2048, 4, "q8_0"),
            LocalModelIdentity.Chat(path, 2048, 4, "q8_0"),
        )
        assertNotEquals(
            LocalModelIdentity.Chat(path, 2048, 4, "q8_0"),
            LocalModelIdentity.Chat(path, 4096, 4, "q8_0"),
        )
        assertNotEquals(
            LocalModelIdentity.Chat(path, 2048, 4, "q8_0"),
            LocalModelIdentity.Chat(path, 2048, 8, "q8_0"),
        )
        assertNotEquals(
            LocalModelIdentity.Chat(path, 2048, 4, "q8_0"),
            LocalModelIdentity.Chat(path, 2048, 4, "f16"),
        )
        assertNotEquals(
            LocalModelIdentity.Chat(path, 2048, 4, "q8_0") as LocalModelIdentity,
            LocalModelIdentity.Embedding(path),
        )
    }
}
