package com.newoether.agora

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareIngressTest {
    private fun textShare(text: CharSequence = "shared text") =
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    private fun streamShare(uri: Uri = Uri.parse("content://media/1")) =
        Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)

    @Test
    fun readsSharedTextAndIgnoresOtherActions() {
        assertEquals("shared text", shareIntentPayload(textShare())?.text)
        assertNull(shareIntentPayload(null))
        assertNull(shareIntentPayload(Intent(Intent.ACTION_MAIN)))
        assertNull(shareIntentPayload(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")))
        assertNull(shareIntentPayload(textShare(" \n\t ")))
    }

    @Test
    fun readsSingleMultipleAndClipDataStreams() {
        assertEquals(
            listOf(Uri.parse("content://media/1")),
            shareIntentPayload(streamShare())?.uris,
        )
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            arrayListOf(Uri.parse("content://media/1"), Uri.parse("content://media/2")),
        )
        assertEquals(2, shareIntentPayload(multiple)?.uris?.size)
        // The chooser sometimes populates only ClipData.
        val clipOnly = textShare().apply {
            removeExtra(Intent.EXTRA_TEXT)
            clipData = ClipData.newRawUri("image", Uri.parse("content://media/3"))
        }
        assertEquals(listOf(Uri.parse("content://media/3")), shareIntentPayload(clipOnly)?.uris)
    }

    @Test
    fun identicalSharesRemainDistinctAndStaleConsumptionPreservesNewerShares() {
        val inbox = ShareInbox()
        inbox.enqueue(ShareRequest("first", "text", emptyList()))
        inbox.enqueue(ShareRequest("second", "text", emptyList()))
        assertEquals(listOf("first", "second"), inbox.requests.value.map { it.id })
        inbox.consume("first")
        inbox.consume("first")
        assertEquals(listOf("second"), inbox.requests.value.map { it.id })
    }

    @Test
    fun pendingSharesSurviveRecreationWithTextAttachmentsAndRejections() {
        val original = ShareInbox()
        original.enqueue(
            ShareRequest(
                id = "share",
                text = "caption",
                attachments = listOf(SharedAttachmentSource("/cache/a.png", "a.png", "image/png", 12L)),
                rejectedMimeTypes = listOf("application/zip"),
            ),
        )
        original.enqueue(ShareRequest("empty", null, emptyList(), listOf(null)))
        val restored = ShareInbox()
        restored.restore(original.save())
        assertEquals(original.requests.value, restored.requests.value)
        assertTrue(restored.requests.value.first().hasContent)
        assertFalse(restored.requests.value.last().hasContent)
    }

    @Test
    fun stagedFilesBecomeComposerAttachmentsOnlyWhenTheComposerAcceptsThem() {
        val image = sharedAttachment(
            SharedAttachmentSource("/cache/a.png", "a.png", "image/png", 10L),
            allowLocalSandbox = false,
        )
        assertEquals("/cache/a.png", image?.uri)
        assertEquals("image", image?.type)
        assertEquals("a.png", image?.fileName)
        val unsupported = sharedAttachment(
            SharedAttachmentSource("/cache/a.zip", "a.zip", "application/zip", 10L),
            allowLocalSandbox = false,
        )
        assertNull(unsupported)
        assertEquals(
            "file",
            sharedAttachment(
                SharedAttachmentSource("/cache/a.zip", "a.zip", "application/zip", 10L),
                allowLocalSandbox = true,
            )?.type,
        )
    }

    @Test
    fun forwardedShareCarriesOnlyTheStagedRequestIntoTheExistingTask() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val request = ShareRequest("share", "caption", emptyList())
        val forwarded = shareLaunchIntent(context, request)
        assertEquals(MainActivity::class.java.name, forwarded.component?.className)
        assertEquals(request, shareRequestFromJson(forwarded.getStringExtra(EXTRA_SHARE_REQUEST)!!))
        for (flag in listOf(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            Intent.FLAG_ACTIVITY_CLEAR_TOP,
            Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )) {
            assertEquals(flag, forwarded.flags and flag)
        }
    }

    @Test
    fun malformedForwardedRequestsAreDroppedInsteadOfCrashing() {
        assertNull(shareRequestFromJson("not json"))
        assertNull(shareRequestFromJson("{\"text\":\"missing id\"}"))
        org.junit.Assert.assertNotNull(shareRequestFromJson(ShareRequest("id", null, emptyList()).toJson()))
    }
}
