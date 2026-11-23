package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelImportItemTest {
    @get:Rule val compose = createComposeRule()

    @Test fun leftProgressCrossfadesWithoutMovingTheTitle() = verifyProgress(reduced = false)

    @Test fun reducedMotionKeepsTheSameSlotAndAllowedFade() = verifyProgress(reduced = true)

    private fun verifyProgress(reduced: Boolean) {
        var importing by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme {
                    ModelImportItem("Import model", "A GGUF model", Icons.Rounded.AutoAwesome,
                        importing, {}, Modifier.width(380.dp).testTag("import"))
                }
            }
        }
        val title = compose.onNodeWithText("Import model", useUnmergedTree = true)
        val beforeTitle = title.fetchSemanticsNode().boundsInRoot
        val leading = compose.onNodeWithTag("model-import-leading", useUnmergedTree = true)
        val beforeLeading = leading.fetchSemanticsNode().boundsInRoot
        assertTrue(beforeLeading.right < beforeTitle.left)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { importing = true }
        advance(64)
        compose.onNodeWithTag("model-import-progress", useUnmergedTree = true).assertExists()
        assertEquals(beforeLeading, leading.fetchSemanticsNode().boundsInRoot)
        assertEquals(beforeTitle, title.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("import").assertIsNotEnabled()
        advance(300)
        val progress = compose.onNodeWithTag("model-import-progress", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(beforeLeading, progress)
        compose.runOnIdle { importing = false }
        advance(64)
        compose.onNodeWithTag("model-import-progress", useUnmergedTree = true).assertExists()
        advance(300)
        compose.onNodeWithTag("model-import-progress", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(beforeTitle, title.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun importedMetadataChangesWithoutChangingTitleAndOptionalRemoveIsSeparate() {
        var file by mutableStateOf<ModelImportFileInfo?>(null)
        var importing by mutableStateOf(false)
        var clicks = 0
        var removes = 0
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            MaterialTheme {
                ModelImportItem("Vision Projector", "A matching mmproj file", Icons.Rounded.AutoAwesome,
                    importing, { clicks++ }, Modifier.width(380.dp).testTag("import"),
                    importedFile = file, importedEffect = "Image input enabled", onRemove = { removes++ })
            }
        }
        compose.onNodeWithText(context.getString(com.newoether.agora.R.string.model_import_not_imported,
            "A matching mmproj file"), useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(context.getString(com.newoether.agora.R.string.import_button))
            .performClick()
        assertEquals(1, clicks)
        compose.runOnIdle { file = ModelImportFileInfo("projector.mmproj", 4096) }
        val details = "projector.mmproj · ${android.text.format.Formatter.formatShortFileSize(context, 4096)}\nImage input enabled"
        compose.onNodeWithText("Vision Projector", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(details, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(context.getString(com.newoether.agora.R.string.import_button))
            .assertDoesNotExist()
        compose.onNodeWithTag("import").performClick()
        assertEquals(2, clicks)
        compose.onNodeWithContentDescription(context.getString(com.newoether.agora.R.string.remove)).performClick()
        assertEquals(1, removes)
        assertEquals(2, clicks)
        compose.runOnIdle { importing = true }
        compose.onNodeWithTag("import").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(com.newoether.agora.R.string.importing_label),
            useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(context.getString(com.newoether.agora.R.string.remove))
            .assertDoesNotExist()
    }

    @Test fun savedPathReadsFilenameAndSizeAgainWhenReopened() {
        val directory = java.nio.file.Files.createTempDirectory("model-import").toFile()
        val first = java.io.File(directory, "saved.gguf").apply { writeBytes(ByteArray(2048)) }
        var shown by mutableStateOf(true)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        fun details() = "saved.gguf · ${android.text.format.Formatter.formatShortFileSize(context, first.length())}"
        compose.setContent {
            MaterialTheme {
                if (shown) ModelImportItem("Chat Model (.gguf)", "A GGUF model", Icons.Rounded.AutoAwesome,
                    false, {}, filePath = first.path)
            }
        }
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(details(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(com.newoether.agora.R.string.remove))
                .assertDoesNotExist()
            compose.runOnIdle { shown = false }
            first.appendBytes(ByteArray(2048))
            compose.runOnIdle { shown = true }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(details(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Chat Model (.gguf)", useUnmergedTree = true).assertExists()
            compose.runOnIdle { shown = false }
            assertTrue(first.delete())
            compose.runOnIdle { shown = true }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(com.newoether.agora.R.string.model_import_file_missing),
                    useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Image input enabled", substring = true).assertDoesNotExist()
        } finally {
            compose.runOnIdle { shown = false }
            directory.deleteRecursively()
        }
    }

    @Test fun stalePathReadCannotReplaceNewFileMetadata() {
        val directory = java.nio.file.Files.createTempDirectory("model-import-replace").toFile()
        val first = java.io.File(directory, "old.gguf").apply { writeBytes(ByteArray(2048)) }
        val second = java.io.File(directory, "replacement.gguf").apply { writeBytes(ByteArray(4096)) }
        var path by mutableStateOf(first.path)
        var shown by mutableStateOf(true)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            MaterialTheme {
                if (shown) ModelImportItem("Embedding Model (.gguf)", "A GGUF model", Icons.Rounded.AutoAwesome,
                    false, {}, filePath = path)
            }
        }
        try {
            compose.runOnIdle { path = second.path }
            val details = "replacement.gguf · ${android.text.format.Formatter.formatShortFileSize(context, second.length())}"
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(details, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("old.gguf", substring = true).assertDoesNotExist()
        } finally {
            compose.runOnIdle { shown = false }
            directory.deleteRecursively()
        }
    }

    private fun advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }
}
