package com.newoether.agora.templates

import com.newoether.agora.model.InteractiveAnswer
import org.junit.Assert.*
import org.junit.Test

class TemplateReferenceCatalogueTest {
    private fun template() = InteractiveTemplate(name = "Cases", sourceFolderId = "b5ea37fe-d405-4ba6-9a21-6f41d60a04bf",
        resourceFiles = listOf("Medicine/Cardiology/ztf-heart_failure.MD"), referencePrefix = "ztf-",
        answer = InteractiveAnswer("Cases", "Choose a case", "Choose", html = "<button>Start</button>"))
    @Test fun derivesReadableLabelsButDoesNotRenamePathsOrStripMedicalNumbers() {
        val reference = templateReferenceCatalogue(template().resourceFiles, "ztf-").single()
        assertEquals("heart failure", reference.label)
        assertEquals("Medicine/Cardiology/ztf-heart_failure.MD", reference.path)
        assertEquals(listOf("Medicine", "Cardiology"), reference.categories)
        assertEquals("Type 2 Diabetes", templateReferenceLabel("Type-2_Diabetes.md"))
        assertEquals("Type 2 Diabetes", templateReferenceLabel("Type-2_Diabetes.MD", "ztf-"))
    }
    @Test fun bridgeSnapshotIsEscapedAndBoundedAndNeverContainsAnArticleOrFolderUri() {
        val opened = injectTemplateSources(template().copy(resourceFiles = listOf("</script>.md")))
        assertFalse(opened.answer.jsExpressions.joinToString().contains("</script>"))
        assertEquals("</script>.md", templateReferenceSnapshot(opened.answer).single().path)
        assertFalse(opened.answer.encode().contains("content://"))
        assertEquals(emptyList<TemplateReference>(), templateReferenceSnapshot(template().answer))
    }
    @Test fun fullAiEditRoundTripKeepsIdentityAndLocalGrantAndAcceptsOldAnswerOnlyCode() {
        val original = template()
        val brief = templateAiEditingBrief(original)
        assertTrue(brief.contains("Websandbox.connection.remote.listReferenceFiles"))
        assertTrue(brief.contains("sourceFiles: [selected.path]"))
        assertFalse(brief.contains(original.sourceFolderId!!))
        val restored = applyTemplateEditCode(original, templateEditCode(original))
        assertEquals(original, restored)
        val answer = original.answer.copy(html = "<p>Updated</p>")
        val changed = applyTemplateEditCode(original, answer.encode())
        assertEquals(original.id, changed.id); assertEquals(original.sourceFolderId, changed.sourceFolderId)
        assertEquals(answer, changed.answer)
    }
    @Test fun threeHundredFileMenuFitsPortableSnapshotLimitsWithoutDuplicatingPathsInJavascript() {
        val paths = (1..300).map { "Medicine/Cardiology/Condition number $it.md" }
        val opened = injectTemplateSources(template().copy(resourceFiles = paths))
        assertEquals(300, templateReferenceSnapshot(opened.answer).size)
        assertEquals(opened, InteractiveTemplate.decode(opened.encode()))
        assertTrue(opened.answer.jsExpressions[1].contains("agoraReferences.map"))
    }
    @Test fun genericFilesKeepExactNamesAndRequestsRejectPathsOutsideTheConnectedFolder() {
        val paths = listOf("Data/catalogue.json", "Data/photo.png", "README", "unusual.custom")
        assertEquals(paths.toSet(), templateReferenceCatalogue(paths).map { it.path }.toSet())
        val original = template().copy(resourceFiles = paths)
        assertEquals(original, InteractiveTemplate.decode(original.encode()))
        for (method in listOf("listDirectory", "readTextFile", "readFile")) {
            val request = TemplateFileRequest.parse(method, kotlinx.serialization.json.Json.parseToJsonElement("{\"path\":\"Data/catalogue.json\"}"))
            assertEquals("Data/catalogue.json", request.path)
            assertThrows(IllegalArgumentException::class.java) { TemplateFileRequest.parse(method,
                kotlinx.serialization.json.Json.parseToJsonElement("{\"path\":\"../private\"}")) }
        }
        assertEquals("", TemplateFileRequest.parse("listDirectory", kotlinx.serialization.json.Json.parseToJsonElement("{\"path\":\"\"}")).path)
        assertThrows(IllegalArgumentException::class.java) { TemplateFileRequest.parse("writeFile", kotlinx.serialization.json.Json.parseToJsonElement("{\"path\":\"README\"}")) }
    }
    @Test fun strictUtf8AndFileLimitsAreSharedByNativeReferenceReads() {
        assertEquals("# Case", decodeTemplateReference("\uFEFF# Case".toByteArray()))
        for (bytes in listOf(byteArrayOf(0xC3.toByte(), 0x28), ByteArray(128001), byteArrayOf(0), "  ".toByteArray()))
            assertThrows(Exception::class.java) { decodeTemplateReference(bytes) }
    }
}
