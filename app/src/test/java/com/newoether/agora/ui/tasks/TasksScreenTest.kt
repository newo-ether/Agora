package com.newoether.agora.ui.tasks

import com.newoether.agora.automation.TaskManager
import com.newoether.agora.model.ChatConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksScreenTest {
    @Test
    fun initialTaskResolution_onlyAppliesWhileThatTaskOwnsNavigation() {
        assertTrue(shouldApplyInitialTaskResolution("task-1", "task-1"))
        assertFalse(shouldApplyInitialTaskResolution("task-1", "new-task"))
        assertFalse(shouldApplyInitialTaskResolution("task-1", null))
    }

    @Test
    fun missingTaskFallback_onlyAppliesToTheActiveResolvedDestination() {
        assertTrue(
            shouldClearMissingTaskDestination(
                renderedTaskId = "task-1",
                activeTaskId = "task-1",
                resolvingInitialTaskId = null,
            ),
        )
        assertFalse(
            shouldClearMissingTaskDestination(
                renderedTaskId = "outgoing-new-task",
                activeTaskId = "task-1",
                resolvingInitialTaskId = null,
            ),
        )
        assertFalse(
            shouldClearMissingTaskDestination(
                renderedTaskId = "task-1",
                activeTaskId = "task-1",
                resolvingInitialTaskId = "task-1",
            ),
        )
    }

    @Test
    fun returningHistoryKeepsTheExactRetainedFirstFrameWhileLiveDataBuffers() {
        val retained = listOf(execution("history-1"))
        val changedLive = listOf(execution("history-1"), execution("history-2"))

        assertSame(
            retained,
            taskExecutionHistoryForPresentation(
                previewPhase = TaskHistoryPreviewPhase.RETURNING,
                retained = retained,
                live = changedLive,
            ),
        )
    }

    @Test
    fun settledHistoryUsesLiveDataAndFallsBackToRetainedUntilItArrives() {
        val retained = listOf(execution("history-1"))
        val changedLive = listOf(execution("history-2"))

        assertSame(
            retained,
            taskExecutionHistoryForPresentation(
                previewPhase = TaskHistoryPreviewPhase.IDLE,
                retained = retained,
                live = null,
            ),
        )
        assertSame(
            changedLive,
            taskExecutionHistoryForPresentation(
                previewPhase = TaskHistoryPreviewPhase.IDLE,
                retained = retained,
                live = changedLive,
            ),
        )
    }

    @Test
    fun countdown_clampsExpiredRunsToZero() {
        assertEquals("0:00", formatTaskCountdown(-1L))
        assertEquals("0:00", formatTaskCountdown(0L))
    }

    @Test
    fun countdown_roundsUpPartialSeconds() {
        assertEquals("0:01", formatTaskCountdown(1L))
        assertEquals("0:43", formatTaskCountdown(42_001L))
    }

    @Test
    fun countdown_includesHoursWithoutWrappingAtOneDay() {
        assertEquals("1:02:03", formatTaskCountdown(3_723_000L))
        assertEquals("25:00:00", formatTaskCountdown(90_000_000L))
    }

    @Test
    fun scheduleEditorMode_detectsStructuredAndCustomSchedules() {
        assertEquals(
            ScheduleEditorMode.DAILY,
            initialScheduleEditorMode("30 9 * * *", null),
        )
        assertEquals(
            ScheduleEditorMode.CUSTOM,
            initialScheduleEditorMode("0 */2 * * *", null),
        )
        assertEquals(
            ScheduleEditorMode.CUSTOM,
            initialScheduleEditorMode("temporarily incomplete", null),
        )
    }

    @Test
    fun customScheduleDraft_mustBeNonBlankAndValid() {
        assertFalse(isScheduleDraftValid(ScheduleEditorMode.CUSTOM, ""))
        assertFalse(isScheduleDraftValid(ScheduleEditorMode.CUSTOM, "0 9 *"))
        assertTrue(isScheduleDraftValid(ScheduleEditorMode.CUSTOM, "0 9 * * *"))
        assertTrue(isScheduleDraftValid(ScheduleEditorMode.DAILY, ""))
    }

    @Test
    fun yearlyMonthDay_allowsLeapDayAndClampsShortMonths() {
        assertEquals(29, daysInYearlyMonth(2))
        assertEquals(30, daysInYearlyMonth(4))
        assertEquals(31, daysInYearlyMonth(12))
    }

    private fun execution(id: String) = TaskManager.ExecutionSummary(
        conversation = ChatConversation(id = id, title = id),
        preview = id,
        status = null,
        timestamp = 123L,
    )
}
