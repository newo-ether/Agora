package com.newoether.agora.sharing

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.GenerationToolExecutor
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.repository.ConversationRepository
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Receiving shared content is independent of a foreign editor or processing service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharingBoundaryTest {
    @Test fun standardShareTypesStillResolveWithoutNotesComponents() {
        val app = RuntimeEnvironment.getApplication()
        val pm = app.packageManager
        for ((action, mime) in listOf(
            Intent.ACTION_SEND to "text/plain",
            Intent.ACTION_SEND to "image/jpeg",
            Intent.ACTION_SEND_MULTIPLE to "image/jpeg",
            Intent.ACTION_SEND to "application/epub+zip",
        )) {
            val targets = pm.queryIntentActivities(Intent(action).setType(mime), 0)
            assertTrue("$action $mime", targets.any {
                it.activityInfo.name == ShareTextActivity::class.java.name
            })
        }
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS)
        assertFalse(info.activities.orEmpty().any { it.name.startsWith("com.newoether.agora.notes.") })
        assertFalse(info.receivers.orEmpty().any { it.name.startsWith("com.newoether.agora.notes.") })
        assertTrue(pm.queryIntentActivities(Intent("com.kalamos.notes.action.ATTACH_DOCUMENT")
            .setType("application/vnd.kalamos.notes-document+zip"), 0).isEmpty())
        assertTrue(pm.queryBroadcastReceivers(Intent("com.kalamos.notes.action.PROCESS_DOCUMENT"), 0).isEmpty())
        assertTrue(pm.queryBroadcastReceivers(Intent("com.kalamos.notes.action.REPLAY_PROCESS_RESULT"), 0).isEmpty())
    }

    @Test fun defaultExecutorHasNoNotesToolCapability() {
        val executor = GenerationToolExecutor.createDefault(
            app = RuntimeEnvironment.getApplication(),
            conversations = mockk<ConversationRepository>(relaxed = true),
            memoryManager = mockk<MemoryManager>(relaxed = true),
            skillManager = mockk<SkillManager>(relaxed = true),
            sandboxFactory = null,
            additionalProviders = emptyList(),
            confirmShellCommand = { _, _, _ -> false },
        )
        val definitions = executor.definitions(GenerationContext())
        assertFalse(definitions.any { it.function.name in setOf("propose_notes_document", "replace_notes_handwriting") })
        assertFalse(definitions.any { "Kalamos" in it.function.description || "notes:" in it.function.description })
    }
}
