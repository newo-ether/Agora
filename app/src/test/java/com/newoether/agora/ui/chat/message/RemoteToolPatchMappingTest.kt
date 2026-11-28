package com.newoether.agora.ui.chat.message

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteToolPatchMappingTest {
    private fun map(patch: String) = mapRemoteToolDetail("codex-cli", "apply_patch", patch, "done", "succeeded")
    private fun body(patch: String) = map(patch)!!.body as ToolDetailBody.Edit

    @Test fun singleUpdateUsesOnlyFileMetadataAndActualDiffLines() {
        val patch = "*** Begin Patch\n*** Update File: F:\\repo\\src\\a.kt\n@@ class A\n kept\n-old\n+new\n*** End of File\n*** End Patch\n"
        val body = body(patch)
        assertTrue(body.hunks.isEmpty())
        assertNull(body.path)
        val file = body.files.single()
        assertEquals(ToolDetailPath("a.kt", "F:\\repo\\src"), file.path)
        assertEquals(ToolDetailEditOperation.UPDATE, file.operation)
        assertNull(file.moveTo)
        assertEquals(listOf("kept", "old", "new"), file.hunks.single().lines.map { it.text })
        assertEquals(listOf(ToolDetailDiffKind.CONTEXT, ToolDetailDiffKind.REMOVED, ToolDetailDiffKind.ADDED),
            file.hunks.single().lines.map { it.kind })
        assertNull(file.hunks.single().index)
    }

    @Test fun multipleFilesKeepTheirOwnHeadersOperationsAndHunksInOrder() {
        val patch = """
            *** Begin Patch
            *** Update File: /repo/src/a.kt
            @@
            -before
            +after
            *** Add File: /repo/new/b.kt
            +first
            ++literal
            +
            *** Delete File: /repo/old/c.kt
            *** Update File: /repo/from.kt
            *** Move to: /repo/destination/to.kt
            @@
            -source
            +destination
            *** End Patch
        """.trimIndent()
        val files = body(patch).files
        assertEquals(listOf("a.kt", "b.kt", "c.kt", "from.kt"), files.map { it.path.name })
        assertEquals(listOf(ToolDetailEditOperation.UPDATE, ToolDetailEditOperation.ADD,
            ToolDetailEditOperation.DELETE, ToolDetailEditOperation.MOVE), files.map { it.operation })
        assertEquals(listOf("first", "+literal", ""), files[1].hunks.single().lines.map { it.text })
        assertTrue(files[2].hunks.isEmpty())
        assertEquals(ToolDetailPath("to.kt", "/repo/destination"), files[3].moveTo)
        assertTrue(files.flatMap { it.hunks }.flatMap { it.lines }.none { it.text.startsWith("*** ") })
    }

    @Test fun hunkMarkersOnlySeparateNativeHunksAndNeverBecomeTitles() {
        val patch = "*** Begin Patch\n*** Update File: a.kt\n@@ first anchor\n-one\n+two\n@@ second anchor\n-three\n+four\n*** End Patch"
        val hunks = body(patch).files.single().hunks
        assertEquals(listOf(1, 2), hunks.map { it.index })
        assertEquals(listOf(listOf("one", "two"), listOf("three", "four")), hunks.map { it.lines.map { line -> line.text } })
    }

    @Test fun protocolLikeTextInsideCodeIsPreserved() {
        val patch = "*** Begin Patch\n*** Update File: a.kt\n@@\n-*** Add File: code\n+@@ code\n --- code\n*** End Patch"
        assertEquals(listOf("*** Add File: code", "@@ code", "--- code"),
            body(patch).files.single().hunks.single().lines.map { it.text })
        val empty = body("*** Begin Patch\n*** Add File: empty.kt\n*** Delete File: gone.kt\n*** End Patch")
        assertEquals(2, empty.files.size)
        assertTrue(empty.files.all { it.hunks.isEmpty() })
    }

    @Test fun malformedOrUnsupportedSectionFallsBackForTheWholeBatch() {
        val valid = "*** Add File: good.kt\n+keep\n"
        val invalid = listOf(
            "*** Unknown File: bad.kt\n+x\n", "*** Update File: bad.kt\n@@\n",
            "*** Update File: bad.kt\n@@\n+x\n@@\n", "*** Update File: bad.kt\n+x\n",
            "*** Add File: bad.kt\n-removed\n", "*** Delete File: bad.kt\n+unexpected\n",
            "*** Update File: bad.kt\n*** Move to: \n@@\n+x\n",
            "*** Add File: bad.kt\n*** Move to: moved.kt\n+x\n",
            "*** Update File: bad.kt\n@@\n+x\n*** Unknown\n",
            "*** Update File: bad.kt\n@@\n+x\n*** End of File\nunconsumed\n",
        )
        for (section in invalid) assertNull(section, map("*** Begin Patch\n$valid$section*** End Patch"))
        for (patch in listOf("*** Begin Patch\n$valid", "*** Begin Patch\n*** End Patch", "bad")) assertNull(map(patch))
    }

    @Test fun codexUnifiedMetadataAndMultipleHunksAreConsumedWithoutStrippingCodePrefixes() {
        val diff = "diff --git a/a.kt b/a.kt\nindex abc..def 100644\n--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n--- literal\n+++ literal\n@@ -5 +5 @@\n-old\n+new\n\\ No newline at end of file\n"
        val changes = buildJsonArray { add(buildJsonObject {
            put("path", "a.kt"); put("kind", "update"); put("diff", diff)
        }) }
        val hunks = remoteFileChanges(changes)!!.files.single().hunks
        assertEquals(listOf(1, 2), hunks.map { it.index })
        assertEquals(listOf("-- literal", "++ literal", "old", "new"), hunks.flatMap { it.lines }.map { it.text })
    }

    @Test fun rawJsonAndInputEnvelopesKeepReceiptAndUnconsumedMetadata() {
        val patch = "*** Begin Patch\n*** Update File: a.kt\n@@\n-old\n+new\n*** End Patch"
        val payloads = listOf(patch, JsonPrimitive(patch).toString(), buildJsonObject { put("input", patch) }.toString())
        for (payload in payloads) {
            val detail = mapRemoteToolDetail("codex-desktop", "apply_patch", payload, "receipt", "running")!!
            assertEquals(listOf("receipt"), detail.supplemental)
            assertEquals("a.kt", (detail.body as ToolDetailBody.Edit).files.single().path.name)
        }
        val input = buildJsonObject { put("patch", patch); put("extra", "retained") }.toString()
        assertEquals("{\"extra\":\"retained\"}", map(input)!!.arguments)
    }
}
