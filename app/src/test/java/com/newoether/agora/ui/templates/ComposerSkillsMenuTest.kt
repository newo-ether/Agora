package com.newoether.agora.ui.templates

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.data.SkillManager
import com.newoether.agora.skills.*
import com.newoether.agora.templates.InteractiveTemplateRepository
import com.newoether.agora.ui.chat.bottombar.*
import com.newoether.agora.ui.motion.*
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerSkillsMenuTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = org.junit.rules.TemporaryFolder()

    @Test fun regularSlashShowsBriefSkillDescriptionAndSelectionOnlyPreparesTheDraft() = exercise(false)
    @Test fun einkSlashUsesTheSameSelectionWithStaticListPageControls() = exercise(true)

    private fun exercise(eInk: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = SkillManager(context)
        manager.listFiles().forEach { manager.deleteFile(it.name) }
        manager.saveDocument(SkillDocument("clinical-source-reader.md", "Full private instructions", SkillConfiguration("sources")),
            "Read selected clinical sources from the Alpine terminal")
        java.io.File(context.filesDir, "skill_db/unrelated.md").writeText("<!-- agora-skill/v1\ninvalid")
        val templates = InteractiveTemplateRepository(folder.root)
        templates.save(com.newoether.agora.templates.InteractiveTemplate(name = "Clinical source reader",
            answer = com.newoether.agora.model.InteractiveAnswer("Setup", "Choose inputs", "Select", html = "<p>Setup</p>")))
        var selected = ""
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(eInk, eInk)) {
                var text by remember { mutableStateOf("") }
                Column {
                    ComposerSkillMenu(text, true, skillManager = manager, onSelect = { selected = it; text = it })
                    BasicTextField(text, { text = it }, Modifier.testTag("input"))
                }
            }
        } }
        compose.onNodeWithTag("input").performTextInput("/so")
        compose.waitUntil(10000) { compose.onAllNodesWithText("/sources").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Read selected clinical sources from…").assertExists()
        compose.onNodeWithText("Full private instructions").assertDoesNotExist()
        compose.onNodeWithText("Clinical source reader").assertDoesNotExist()
        assertEquals("", selected)
        savePreview(if (eInk) "eink" else "regular")
        compose.onNodeWithText("/sources").performClick()
        compose.runOnIdle { assertEquals("/sources ", selected) }
    }

    private fun savePreview(name: String) = compose.runOnIdle {
        val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
        val view = activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) })
        java.io.File("build/reports/custom-skills/slash-$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
