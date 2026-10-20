package com.newoether.agora.ui.settings.datacontrol

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ResourceUpdatePreviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun updateIsExplicitAndLimitedToTheTwoResourceCategories() {
        val manifest = DataImporter.ImportManifest(version = 6)
        val preview = DataImporter.ImportPreview(manifest, memoryCount = 6, templatesPresent = true, settingsPresent = true)
        var result: Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>? = null
        compose.setContent { MaterialTheme { ImportPreviewDialog(manifest, preview, {}, { result = it }) } }
        compose.onAllNodesWithText("Update", substring = false).assertCountEquals(2)
        assertNull(result)
        compose.onAllNodesWithText("Update", substring = false)[0].performClick()
        compose.onAllNodesWithText("Update", substring = false)[1].performScrollTo().performClick()
        compose.onNodeWithText("Import", substring = false).performClick()
        assertEquals(DataImporter.ImportStrategy.UPDATE, result!![DataExporter.ExportCategory.TEMPLATES])
        assertEquals(DataImporter.ImportStrategy.UPDATE, result!![DataExporter.ExportCategory.MEMORIES])
        assertEquals(DataImporter.ImportStrategy.MERGE, result!![DataExporter.ExportCategory.SETTINGS])
    }
}
