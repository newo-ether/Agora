package com.newoether.agora

import android.app.Application
import android.content.Intent
import android.text.SpannableString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProcessTextIngressTest {
    private fun selection(text: CharSequence = "selected text") = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)

    @Test fun acceptsStyledSelectionAndPreservesItsExactTextInReadOnlyOrEditableSources() {
        for (readOnly in listOf(true, false)) {
            val intent = selection(SpannableString("  selected\ntext  "))
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly)
            assertEquals("  selected\ntext  ", selectedProcessText(intent))
        }
    }

    @Test fun ignoresOtherActionsMissingTextBlankTextAndOtherMimeTypes() {
        assertNull(selectedProcessText(null))
        assertNull(selectedProcessText(Intent(Intent.ACTION_MAIN)))
        assertNull(selectedProcessText(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "shared")))
        assertNull(selectedProcessText(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")))
        assertNull(selectedProcessText(selection(" \n\t")))
        assertNull(selectedProcessText(selection().setType("image/png")))
    }

    @Test fun identicalNewIntentsRemainDistinctAndStaleConsumptionPreservesNewerSelections() {
        val inbox = ProcessTextInbox()
        assertTrue(inbox.receive(selection()))
        assertTrue(inbox.receive(selection()))
        val (first, second) = inbox.requests.value
        assertNotEquals(first.id, second.id)
        assertEquals(first.text, second.text)
        inbox.consume(first.id)
        inbox.consume(first.id)
        assertEquals(listOf(second), inbox.requests.value)
    }

    @Test fun pendingSelectionsSurviveRecreationAndConsumedSelectionsDoNotReplay() {
        val original = ProcessTextInbox()
        original.receive(selection("first"))
        original.receive(selection("second"))
        val first = original.requests.value.first()
        original.consume(first.id)
        val restored = ProcessTextInbox()
        restored.restore(original.save())
        assertEquals(original.requests.value, restored.requests.value)
        restored.consume(restored.requests.value.single().id)
        val empty = ProcessTextInbox()
        empty.restore(restored.save())
        assertTrue(empty.requests.value.isEmpty())
    }

    @Test fun forwardsOnlyTextToExistingMainTaskWithoutAnAutomaticSendContract() {
        val context = RuntimeEnvironment.getApplication()
        val forwarded = processTextLaunchIntent(context, "selection")
        assertEquals(MainActivity::class.java.name, forwarded.component?.className)
        assertEquals("selection", selectedProcessText(forwarded))
        assertEquals(setOf(Intent.EXTRA_PROCESS_TEXT), forwarded.extras?.keySet())
        for (flag in listOf(Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_CLEAR_TOP,
            Intent.FLAG_ACTIVITY_SINGLE_TOP)) {
            assertEquals(flag, forwarded.flags and flag)
        }
    }
}
