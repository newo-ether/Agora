package com.newoether.agora.data

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBackupV5BaselineTest {
    @Test
    fun unchangedConversationAndMediaAreRawCopiedWhileChangesAndDeletesAreRebuilt() {
        val directory = Files.createTempDirectory("agora-v5-baseline").toFile()
        val baselineFile = File(directory, "baseline.agora")
        val resultFile = File(directory, "result.agora")
        try {
            writeBaseline(baselineFile)
            NativeBackupV5Baseline.openOrNull(baselineFile).use { baseline ->
                requireNotNull(baseline)
                assertTrue(baseline.canReuse("same", 1L))
                assertFalse(baseline.canReuse("changed", 3L))
                assertFalse(baseline.canReuse("new", 4L))
                val copiedMedia = mutableSetOf<String>()
                val checksums = mutableMapOf<String, Long>()
                val indexEntries = mutableListOf<NativeConversationIndexEntry>()
                ZipArchiveOutputStream(resultFile).use { output ->
                    writeEntry(output, NativeBackupFormat.MANIFEST_ENTRY, MARKED_MANIFEST)
                    indexEntries += NativeBackupV5Writer.copyConversationFromBaseline(
                        zip = output,
                        baseline = baseline,
                        baselineEntry = requireNotNull(baseline.indexEntry("same")),
                        copiedMedia = copiedMedia,
                        checksums = checksums,
                    )
                    indexEntries += NativeBackupV5Writer.writeConversationEntry(
                        zip = output,
                        conversationId = "changed",
                        dataChangedAt = 3L,
                        conversationJson = """{"id":"changed","dataChangedAt":3}""",
                        runs = emptySequence<String>().iterator(),
                        messages = listOf("""{"id":"changed-message","conversationId":"changed"}""").iterator(),
                        loops = emptySequence<String>().iterator(),
                        mediaEntries = emptySet(),
                        scratchDir = directory,
                        checksums = checksums,
                    )
                    indexEntries += NativeBackupV5Writer.writeConversationEntry(
                        zip = output,
                        conversationId = "new",
                        dataChangedAt = 4L,
                        conversationJson = """{"id":"new","dataChangedAt":4}""",
                        runs = emptySequence<String>().iterator(),
                        messages = listOf("""{"id":"new-message","conversationId":"new"}""").iterator(),
                        loops = emptySequence<String>().iterator(),
                        mediaEntries = emptySet(),
                        scratchDir = directory,
                        checksums = checksums,
                    )
                    NativeBackupV5Writer.writeTasksEntry(output, emptySequence<String>().iterator())
                    NativeBackupV5Writer.writeConversationIndex(output, indexEntries)
                    NativeBackupV5Writer.writeRawChecksums(output, checksums)
                }
                assertEquals(setOf(MEDIA_ENTRY), copiedMedia)
                assertEquals(
                    setOf("same", "changed", "new").map(NativeBackupFormat::conversationEntry).toSet() + MEDIA_ENTRY,
                    checksums.keys,
                )
            }

            ZipFile.builder().setFile(baselineFile).get().use { oldZip ->
                ZipFile.builder().setFile(resultFile).get().use { newZip ->
                    val sameEntry = NativeBackupFormat.conversationEntry("same")
                    assertArrayEquals(raw(oldZip, sameEntry), raw(newZip, sameEntry))
                    assertArrayEquals(raw(oldZip, MEDIA_ENTRY), raw(newZip, MEDIA_ENTRY))
                    assertFalse(newZip.entries.asSequence().any { it.name == NativeBackupFormat.conversationEntry("deleted") })
                    val changed = newZip.getInputStream(newZip.getEntry(NativeBackupFormat.conversationEntry("changed")))
                        .bufferedReader().use { it.readText() }
                    assertTrue(changed.contains("changed-message"))
                    assertTrue(newZip.getEntry(NativeBackupFormat.conversationEntry("new")) != null)
                    val index = newZip.getInputStream(newZip.getEntry(NativeBackupFormat.CONVERSATION_INDEX_ENTRY))
                        .bufferedReader().use { Json.decodeFromString<NativeConversationIndex>(it.readText()) }
                    assertEquals(listOf("same", "changed", "new"), index.conversations.map { it.id })
                    // Every recorded checksum matches the stored bytes, and new items decompress intact.
                    val recorded = newZip.getInputStream(newZip.getEntry(NativeBackupFormat.RAW_CHECKSUMS_ENTRY))
                        .bufferedReader().use { Json.decodeFromString<NativeBackupRawChecksums>(it.readText()) }
                    recorded.entries.forEach { (name, crc) ->
                        assertEquals(name, crc, java.util.zip.CRC32().apply { update(raw(newZip, name)) }.value)
                    }
                    val newEntry = newZip.getEntry(NativeBackupFormat.conversationEntry("new"))
                    assertEquals(java.util.zip.ZipEntry.DEFLATED, newEntry.method)
                    val newPayload = newZip.getInputStream(newEntry).readBytes()
                    assertEquals(newEntry.size, newPayload.size.toLong())
                    assertEquals(newEntry.crc, java.util.zip.CRC32().apply { update(newPayload) }.value)
                }
            }
            // The rebuilt archive is itself a checksummed baseline: reuse needs no inflate, and a
            // damaged stored byte is caught while copying.
            NativeBackupV5Baseline.openOrNull(resultFile).use { baseline ->
                assertTrue(requireNotNull(baseline).canReuse("new", 4L))
            }
            val bytes = resultFile.readBytes()
            val newItem = ZipFile.builder().setFile(resultFile).get().use { zip ->
                zip.getEntry(NativeBackupFormat.conversationEntry("new")).dataOffset
            }
            bytes[newItem.toInt()] = (bytes[newItem.toInt()].toInt() xor 0x55).toByte()
            resultFile.writeBytes(bytes)
            NativeBackupV5Baseline.openOrNull(resultFile).use { baseline ->
                requireNotNull(baseline)
                assertTrue(baseline.canReuse("new", 4L))
                val failure = runCatching {
                    ZipArchiveOutputStream(File(directory, "next.agora")).use { output ->
                        baseline.copyRaw(NativeBackupFormat.conversationEntry("new"), output, mutableMapOf())
                    }
                }.exceptionOrNull()
                assertTrue(failure is BaselineDamagedException)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun damagedBaselineItemIsNotReused() {
        val directory = Files.createTempDirectory("agora-v5-damaged").toFile()
        val file = File(directory, "damaged.agora")
        try {
            val item = NativeBackupFormat.conversationEntry("same")
            val payload = "payload-same-stored"
            ZipArchiveOutputStream(file).use { output ->
                writeEntry(output, NativeBackupFormat.MANIFEST_ENTRY, MARKED_MANIFEST)
                // Stored, so the payload bytes sit in the file as is and can be damaged in place.
                output.putArchiveEntry(ZipArchiveEntry(item).apply { method = ZipEntry.STORED })
                output.write(payload.encodeToByteArray())
                output.closeArchiveEntry()
                val index = NativeConversationIndex(listOf(NativeConversationIndexEntry("same", 1L, item)))
                writeEntry(output, NativeBackupFormat.CONVERSATION_INDEX_ENTRY, Json.encodeToString(index))
            }
            NativeBackupV5Baseline.openOrNull(file).use { assertTrue(requireNotNull(it).canReuse("same", 1L)) }
            val bytes = file.readBytes()
            val at = String(bytes, Charsets.ISO_8859_1).indexOf(payload)
            assertTrue(at >= 0)
            bytes[at] = 'X'.code.toByte()
            file.writeBytes(bytes)
            NativeBackupV5Baseline.openOrNull(file).use { assertFalse(requireNotNull(it).canReuse("same", 1L)) }
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun backupWithoutIncrementalMarkerIsNotUsedAsBaseline() {
        val directory = Files.createTempDirectory("agora-v5-unmarked").toFile()
        val file = File(directory, "old.agora")
        try {
            writeBaseline(
                file,
                """{"agora_export_version":${NativeBackupFormat.CURRENT_VERSION},"app_version":"1.0","exported_at":"2026-09-27T05:14:46Z",""" +
                    """"categories":["conversations"],"has_api_keys":false}""",
            )
            assertNull(NativeBackupV5Baseline.openOrNull(file))
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun olderFormatBackupIsNotUsedAsBaseline() {
        // Items written by an older format lack newer message fields, so they must be rebuilt.
        val directory = Files.createTempDirectory("agora-older-format").toFile()
        val file = File(directory, "old.agora")
        try {
            writeBaseline(file, MARKED_MANIFEST.replace(
                "\"agora_export_version\":${NativeBackupFormat.CURRENT_VERSION}",
                "\"agora_export_version\":${NativeBackupFormat.CURRENT_VERSION - 1}",
            ))
            assertNull(NativeBackupV5Baseline.openOrNull(file))
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun invalidBaselineFallsBackToFullExport() {
        val file = Files.createTempFile("agora-invalid-baseline", ".agora").toFile()
        try {
            file.writeText("not a zip")
            assertNull(NativeBackupV5Baseline.openOrNull(file))
        } finally {
            file.delete()
        }
    }

    private fun writeBaseline(file: File, manifest: String = MARKED_MANIFEST) {
        ZipArchiveOutputStream(file).use { output ->
            writeEntry(output, NativeBackupFormat.MANIFEST_ENTRY, manifest)
            writeEntry(output, MEDIA_ENTRY, "media-bytes")
            val index = NativeConversationIndex(
                listOf(
                    NativeConversationIndexEntry("same", 1L, NativeBackupFormat.conversationEntry("same"), listOf(MEDIA_ENTRY)),
                    NativeConversationIndexEntry("changed", 2L, NativeBackupFormat.conversationEntry("changed")),
                    NativeConversationIndexEntry("deleted", 2L, NativeBackupFormat.conversationEntry("deleted")),
                ),
            )
            index.conversations.forEach { item -> writeEntry(output, item.entry, "payload-${item.id}") }
            writeEntry(output, NativeBackupFormat.CONVERSATION_INDEX_ENTRY, Json.encodeToString(index))
        }
    }

    private fun writeEntry(output: ZipArchiveOutputStream, name: String, value: String) {
        output.putArchiveEntry(ZipArchiveEntry(name))
        output.write(value.encodeToByteArray())
        output.closeArchiveEntry()
    }

    private fun raw(zip: ZipFile, name: String): ByteArray =
        zip.getRawInputStream(zip.getEntry(name)).use { it.readBytes() }

    private companion object {
        const val MEDIA_ENTRY = "media/images/same.png"
        // Shaped like a real export manifest, including keys the baseline does not read.
        val MARKED_MANIFEST =
            """{"agora_export_version":${NativeBackupFormat.CURRENT_VERSION},"app_version":"1.0","exported_at":"2026-09-27T05:14:46Z",""" +
                """"categories":["conversations"],"has_api_keys":false,""" +
                """"incremental_baseline":${NativeBackupFormat.INCREMENTAL_BASELINE_REVISION}}"""
    }
}
