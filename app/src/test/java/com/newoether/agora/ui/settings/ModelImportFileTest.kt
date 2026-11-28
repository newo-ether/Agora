package com.newoether.agora.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelImportFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun metadataUsesTheSavedPathAndActualByteLength() {
        val file = temporary.newFile("saved-model.gguf").apply { writeBytes(ByteArray(4096)) }
        assertEquals(ModelImportFileInfo("saved-model.gguf", 4096), readModelImportFile(file.path))
        file.appendBytes(ByteArray(1024))
        assertEquals(5120L, readModelImportFile(file.path).sizeBytes)
    }

    @Test fun missingFileAndDirectoryAreUnavailableWhileZeroByteFilesHaveKnownSize() {
        val file = temporary.newFile("model.gguf")
        assertEquals(0L, readModelImportFile(file.path).sizeBytes)
        check(file.delete())
        assertEquals("model.gguf", readModelImportFile(file.path).name)
        assertNull(readModelImportFile(file.path).sizeBytes)
        assertNull(readModelImportFile(temporary.root.path).sizeBytes)
    }
}
