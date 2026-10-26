package com.newoether.agora.phone

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhoneActionStatusTest {
    @Test fun completionRetainsTheActionForExactlyOneSecond() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.update("phone_tap")
        status.update(null)
        runCurrent()
        advanceTimeBy(999)
        assertEquals("phone_tap", status.name.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(status.name.value)
    }

    @Test fun nextActionCancelsThinkingDeadlineAndWaitsOnlyForMinimumDwell() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.update("phone_tap")
        status.update(null)
        runCurrent()
        advanceTimeBy(50)
        val next = async { status.update("phone_swipe") }
        runCurrent()
        advanceTimeBy(149)
        runCurrent()
        assertFalse(next.isCompleted)
        assertEquals("phone_tap", status.name.value)
        advanceTimeBy(1)
        runCurrent()
        next.await()
        assertEquals("phone_swipe", status.name.value)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("phone_swipe", status.name.value)
        status.update(null)
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertNull(status.name.value)
    }

    @Test fun repeatedNamesRenewTheirOwnDwellWithoutAThinkingFlash() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.update("phone_tap")
        status.update(null)
        val second = async { status.update("phone_tap") }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        second.await()
        status.update(null)
        val third = async { status.update("phone_type") }
        runCurrent()
        advanceTimeBy(199)
        runCurrent()
        assertFalse(third.isCompleted)
        assertEquals("phone_tap", status.name.value)
        advanceTimeBy(1)
        runCurrent()
        third.await()
        assertEquals("phone_type", status.name.value)
    }

    @Test fun anActionAfterTheMinimumDwellSwitchesImmediately() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.update("phone_tap")
        advanceTimeBy(600)
        status.update(null)
        status.update("phone_key")
        assertEquals("phone_key", status.name.value)
    }

    @Test fun resetCancelsOldIdleAndRejectsAnOldDelayedAction() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.update("phone_tap")
        status.update(null)
        val old = async { runCatching { status.update("phone_swipe") }.exceptionOrNull() }
        runCurrent()
        status.reset()
        status.update("phone_type")
        advanceTimeBy(1200)
        runCurrent()
        assertTrue(old.await() is PhoneFailure)
        assertEquals("phone_type", status.name.value)
        status.reset()
        advanceTimeBy(1000)
        runCurrent()
        assertNull(status.name.value)
    }

    @Test fun cancelledMinimumDwellCanSettleAndAcceptTheNextAction() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.update("phone_tap")
        val waiting = async { status.update("phone_swipe") }
        runCurrent()
        waiting.cancel()
        waiting.join()
        status.update(null)
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertNull(status.name.value)
        status.update("phone_type")
        assertEquals("phone_type", status.name.value)
    }
}
