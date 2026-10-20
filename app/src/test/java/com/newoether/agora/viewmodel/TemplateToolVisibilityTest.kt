package com.newoether.agora.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.repository.ConversationRepository
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TemplateToolVisibilityTest {
    @Test fun defaultAiToolDirectoryDoesNotOfferStandaloneTemplates() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val executor = GenerationToolExecutor.createDefault(app, mockk<ConversationRepository>(relaxed = true),
            MemoryManager(app), SkillManager(app), null, emptyList(), confirmShellCommand = { _, _, _ -> false })
        val definitions = executor.definitions(GenerationContext())
        assertFalse(definitions.any { it.function.name in setOf("list_interactive_templates", "open_interactive_template") })
        assertFalse(definitions.joinToString { it.function.description }.contains("templates:"))
        assertTrue(definitions.joinToString { it.function.description }.contains("skills:"))
    }
}
