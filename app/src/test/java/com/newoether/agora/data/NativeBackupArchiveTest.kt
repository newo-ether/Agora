package com.newoether.agora.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.ArrayDeque
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeBackupArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsEntriesOnDemandAndDeletesTemporaryArchiveOnClose() {
        val archiveFile = temporaryFolder.newFile("backup.zip")
        val payload = "manifest".toByteArray()
        ZipOutputStream(archiveFile.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("folder/"))
            output.closeEntry()
            output.putNextEntry(ZipEntry("manifest.json"))
            output.write(payload)
            output.closeEntry()
        }
        val archive = NativeBackupArchive.open(archiveFile)

        assertTrue(archive.has("manifest.json"))
        assertEquals(payload.size.toLong(), archive.size("manifest.json"))
        assertArrayEquals(payload, archive.bytes("manifest.json"))
        assertArrayEquals(payload, archive.stream("manifest.json")!!.use { it.readBytes() })
        assertEquals(listOf("manifest.json"), archive.names())

        archive.close()
        assertFalse(archiveFile.exists())
    }

    @Test
    fun directSeekableChannelClosesWithoutDeletingSourceArchive() {
        val archiveFile = rawZip(
            "direct-source.agora",
            listOf(RawEntry("manifest.json", "manifest".toByteArray())),
        )
        val channel = FileInputStream(archiveFile).channel

        NativeBackupArchive.open(channel).use { archive ->
            assertTrue(archive.has("manifest.json"))
        }

        assertFalse(channel.isOpen)
        assertTrue(archiveFile.exists())
    }

    @Test
    fun rejectsNonSeekableSourceWithLocalDownloadInstruction() {
        val channel = NonSeekableChannel()

        val error = assertThrows(IOException::class.java) {
            NativeBackupArchive.open(channel)
        }

        assertTrue(error.message.orEmpty().contains("download it to local storage"))
        assertFalse(channel.isOpen)
    }

    @Test
    fun contextOpenUsesFileDescriptorWithoutWholeArchiveCacheCopy() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/data/NativeBackupArchive.kt",
        ).replace("\r\n", "\n")
        val contextOpen = source.substringAfter("fun open(context: Context, uri: Uri)")
            .substringBefore("internal fun open(")

        assertTrue(contextOpen.contains("openFileDescriptor(uri, \"r\")"))
        assertTrue(contextOpen.contains("ParcelFileDescriptor.AutoCloseInputStream"))
        assertFalse(contextOpen.contains("openInputStream(uri)"))
        assertFalse(contextOpen.contains("context.cacheDir"))
        assertFalse(contextOpen.contains("File.createTempFile"))
    }

    @Test
    fun rejectsUnsafeAbsoluteAndAmbiguousPathsAndDeletesTemporaryArchive() {
        val unsafeNames = listOf(
            "../manifest.json",
            "/manifest.json",
            "C:/manifest.json",
            "folder\\manifest.json",
            "folder/./manifest.json",
            "folder//manifest.json",
        )

        unsafeNames.forEachIndexed { index, name ->
            val file = rawZip("unsafe-$index.zip", listOf(RawEntry(name, byteArrayOf(1))))
            assertThrows("Expected rejection for $name", IOException::class.java) {
                NativeBackupArchive.open(file)
            }
            assertFalse(file.exists())
        }
    }

    @Test
    fun rejectsDuplicateFileDirectoryAmbiguityAndDirectoryData() {
        listOf(
            listOf(RawEntry("manifest.json", byteArrayOf(1)), RawEntry("manifest.json", byteArrayOf(2))),
            listOf(RawEntry("folder/", byteArrayOf()), RawEntry("folder", byteArrayOf())),
            listOf(RawEntry("folder", byteArrayOf()), RawEntry("folder/item", byteArrayOf())),
            listOf(RawEntry("folder", byteArrayOf()), RawEntry("folder/item/", byteArrayOf())),
            listOf(RawEntry("folder/", byteArrayOf(1))),
        ).forEachIndexed { index, entries ->
            val file = rawZip("duplicate-$index.zip", entries)
            assertThrows(IOException::class.java) { NativeBackupArchive.open(file) }
            assertFalse(file.exists())
        }
    }

    @Test
    fun metadataAggregateHonorsBoundaryWhileStreamedPayloadsAndResourcesRemainUncapped() {
        val conversationPayload = ByteArray(32) { it.toByte() }
        val acceptedFile = rawZip(
            "metadata-boundary.zip",
            listOf(
                RawEntry("manifest.json", byteArrayOf(1, 2, 3, 4)),
                RawEntry("memories/item.md", byteArrayOf(5, 6, 7, 8)),
                RawEntry(NativeBackupFormat.CONVERSATIONS_ENTRY, conversationPayload),
                RawEntry("media/videos/large", ByteArray(1024)),
            ),
        )
        NativeBackupArchive.open(acceptedFile, metadataLimitBytes = 8).use { archive ->
            assertArrayEquals(
                conversationPayload,
                archive.stream(NativeBackupFormat.CONVERSATIONS_ENTRY)!!.use { it.readBytes() },
            )
            assertThrows(IOException::class.java) {
                archive.bytes(NativeBackupFormat.CONVERSATIONS_ENTRY)
            }
            assertEquals(1024L, archive.size("media/videos/large"))
            assertEquals(
                1024L,
                archive.preflightImportResources(
                    conversationsSelected = true,
                    settingsSelected = false,
                    archiveVersion = NativeBackupFormat.CURRENT_VERSION,
                    destinationRoot = temporaryFolder.root,
                    availableBytes = { 1024L },
                ),
            )
        }

        val rejectedFile = rawZip(
            "metadata-over-limit.zip",
            listOf(
                RawEntry("manifest.json", byteArrayOf(1, 2, 3, 4)),
                RawEntry("settings.json", byteArrayOf(5, 6, 7, 8, 9)),
            ),
        )
        assertThrows(IOException::class.java) {
            NativeBackupArchive.open(rejectedFile, metadataLimitBytes = 8)
        }
        assertFalse(rejectedFile.exists())
    }

    @Test
    fun rejectsCorruptStreamedConversationCrcWhenItIsRead() {
        val file = rawZip(
            "bad-conversation-crc.zip",
            listOf(
                RawEntry(
                    NativeBackupFormat.CONVERSATIONS_ENTRY,
                    byteArrayOf(1, 2, 3),
                    crcOverride = 0L,
                ),
            ),
        )

        // Opening only reads the directory, so a preview stays cheap; the damage shows up once the
        // payload is read to its end.
        NativeBackupArchive.open(file).use { archive ->
            assertThrows(IOException::class.java) {
                archive.stream(NativeBackupFormat.CONVERSATIONS_ENTRY)!!.use { it.readBytes() }
            }
            assertThrows(IOException::class.java) {
                archive.stream(NativeBackupFormat.CONVERSATIONS_ENTRY)!!.use {
                    it.read()
                    it.readToEnd()
                }
            }
            // Closing after a partial read only closes, so an aborted import does not read the rest.
            archive.stream(NativeBackupFormat.CONVERSATIONS_ENTRY)!!.use { it.read() }
        }
        assertFalse(file.exists())
    }

    @Test
    fun rejectsCorruptMetadataCrcAndDeletesTemporaryArchive() {
        val file = rawZip(
            "bad-crc.zip",
            listOf(RawEntry("manifest.json", byteArrayOf(1, 2, 3), crcOverride = 0L)),
        )

        assertThrows(IOException::class.java) { NativeBackupArchive.open(file) }
        assertFalse(file.exists())
    }

    @Test
    fun v5PreflightCountsSpooledConversationData() {
        val file = rawZip(
            "spool-space.zip",
            listOf(
                RawEntry(NativeBackupFormat.conversationEntry("one"), ByteArray(10)),
                RawEntry(NativeBackupFormat.TASKS_ENTRY, ByteArray(4)),
                RawEntry("media/images/item", ByteArray(6)),
            ),
        )
        NativeBackupArchive.open(file).use { archive ->
            assertEquals(
                20L,
                archive.preflightImportResources(
                    conversationsSelected = true,
                    settingsSelected = false,
                    archiveVersion = NativeBackupFormat.CURRENT_VERSION,
                    destinationRoot = temporaryFolder.root,
                    availableBytes = { 20L },
                ),
            )
            assertThrows(IOException::class.java) {
                archive.preflightImportResources(
                    conversationsSelected = true,
                    settingsSelected = false,
                    archiveVersion = NativeBackupFormat.CURRENT_VERSION,
                    destinationRoot = temporaryFolder.root,
                    availableBytes = { 19L },
                )
            }
        }
    }
    @Test
    fun selectedResourcePreflightRejectsInsufficientSpace() {
        val file = rawZip(
            "resource-space.zip",
            listOf(RawEntry("media/images/item", ByteArray(16))),
        )
        NativeBackupArchive.open(file).use { archive ->
            assertThrows(IOException::class.java) {
                archive.preflightImportResources(
                    conversationsSelected = true,
                    settingsSelected = false,
                    archiveVersion = NativeBackupFormat.CURRENT_VERSION,
                    destinationRoot = temporaryFolder.root,
                    availableBytes = { 15L },
                )
            }
        }
    }

    @Test
    fun checkedCopyRejectsSizeCrcAndSpaceFailuresAndDeletesTargets() {
        val sizeTarget = File(temporaryFolder.root, "size-target")
        assertThrows(IOException::class.java) {
            NativeBackupArchive.copyStreamToFile(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                target = sizeTarget,
                declaredSize = 5L,
                availableBytes = { Long.MAX_VALUE },
            )
        }
        assertFalse(sizeTarget.exists())

        val overrunInput = CountingInputStream(ByteArray(32))
        val overrunTarget = File(temporaryFolder.root, "overrun-target")
        assertThrows(IOException::class.java) {
            NativeBackupArchive.copyStreamToFile(
                input = overrunInput,
                target = overrunTarget,
                declaredSize = 4L,
                availableBytes = { Long.MAX_VALUE },
                bufferBytes = 16,
            )
        }
        assertEquals(5, overrunInput.bytesRead)
        assertFalse(overrunTarget.exists())

        val crcTarget = File(temporaryFolder.root, "crc-target")
        assertThrows(IOException::class.java) {
            NativeBackupArchive.copyStreamToFile(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                target = crcTarget,
                declaredSize = 4L,
                expectedCrc = 0L,
                availableBytes = { Long.MAX_VALUE },
            )
        }
        assertFalse(crcTarget.exists())

        val preflightTarget = File(temporaryFolder.root, "preflight-target")
        assertThrows(IOException::class.java) {
            NativeBackupArchive.copyStreamToFile(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                target = preflightTarget,
                declaredSize = 4L,
                availableBytes = { 3L },
            )
        }
        assertFalse(preflightTarget.exists())

        val streamingTarget = File(temporaryFolder.root, "streaming-target")
        val available = ArrayDeque(listOf(4L, 0L))
        assertThrows(IOException::class.java) {
            NativeBackupArchive.copyStreamToFile(
                input = ByteArrayInputStream(ByteArray(8)),
                target = streamingTarget,
                availableBytes = { available.removeFirst() },
                bufferBytes = 4,
            )
        }
        assertFalse(streamingTarget.exists())
    }

    @Test
    fun importerPreflightsBeforeMutationAndExtractionUsesCheckedCopy() {
        val importer = sourceFile(
            "app/src/main/java/com/newoether/agora/data/DataImporter.kt",
        ).replace("\r\n", "\n")
        val importBody = importer.substringAfter("suspend fun import(")
        val preflight = importBody.indexOf("opened.preflightImportResources(")
        val staging = importBody.indexOf("stageConversationGraph(\n                            archive = opened,")
        val promptMutation = importBody.indexOf("settingsManager.saveSystemPrompts(promptPlan.prompts)")
        val graphMutation = importBody.indexOf("conversationGraphImporter.importConversationGraph(")
        assertTrue(preflight >= 0)
        // The whole conversation graph is staged and verified before the first write.
        assertTrue(staging > preflight)
        assertTrue(promptMutation > staging)
        assertTrue(graphMutation > promptMutation)
        val stage = importer.substringAfter("private suspend fun stageConversationGraph(")
            .substringBefore("suspend fun import(")
        assertTrue(stage.contains("conversationSettingsTransfers.completePendingImport()"))
        assertTrue(stage.contains("conversationMediaRestorer.restoreConversationMedia(archive)"))
        assertTrue(stage.contains("readConversationGraphHeaders("))
        val preflightBody = sourceFile(
            "app/src/main/java/com/newoether/agora/data/NativeBackupArchive.kt",
        ).replace("\r\n", "\n").substringAfter("fun preflightImportResources(")
            .substringBefore("fun copyTo(")
        // Resource contents are verified once, by the checked copy, not re-read in preflight.
        assertFalse(preflightBody.contains("getInputStream"))

        val fontRestore = importer.substringAfter("private fun restoreCustomFont(")
            .substringBefore("suspend fun import(")
        assertTrue(fontRestore.contains("archive.copyTo("))
        assertFalse(fontRestore.contains("archive.stream("))

        val media = sourceFile(
            "app/src/main/java/com/newoether/agora/data/NativeConversationMediaRestorer.kt",
        )
        assertTrue(media.contains("archive.copyTo(path, target)"))
        assertFalse(media.contains("archive.stream(path)"))
    }

    private fun rawZip(name: String, entries: List<RawEntry>): File {
        val file = temporaryFolder.newFile(name)
        val output = ByteArrayOutputStream()
        val records = entries.map { entry ->
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val crc = entry.crcOverride ?: CRC32().apply { update(entry.data) }.value
            val offset = output.size()
            output.int(0x04034b50L)
            output.short(20)
            output.short(0)
            output.short(0)
            output.short(0)
            output.short(0)
            output.int(crc)
            output.int(entry.data.size.toLong())
            output.int(entry.data.size.toLong())
            output.short(nameBytes.size)
            output.short(0)
            output.write(nameBytes)
            output.write(entry.data)
            RawRecord(nameBytes, entry.data.size, crc, offset)
        }
        val centralOffset = output.size()
        records.forEach { record ->
            output.int(0x02014b50L)
            output.short(20)
            output.short(20)
            output.short(0)
            output.short(0)
            output.short(0)
            output.short(0)
            output.int(record.crc)
            output.int(record.size.toLong())
            output.int(record.size.toLong())
            output.short(record.name.size)
            output.short(0)
            output.short(0)
            output.short(0)
            output.short(0)
            output.int(0)
            output.int(record.offset.toLong())
            output.write(record.name)
        }
        val centralSize = output.size() - centralOffset
        output.int(0x06054b50L)
        output.short(0)
        output.short(0)
        output.short(records.size)
        output.short(records.size)
        output.int(centralSize.toLong())
        output.int(centralOffset.toLong())
        output.short(0)
        file.writeBytes(output.toByteArray())
        return file
    }

    private fun ByteArrayOutputStream.short(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.int(value: Long) {
        repeat(4) { byte -> write((value ushr (byte * 8)).toInt() and 0xff) }
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::isFile)?.let { return it.readText() }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }

    private class NonSeekableChannel : SeekableByteChannel {
        private var open = true

        override fun read(destination: ByteBuffer): Int = throw IOException("not seekable")

        override fun write(source: ByteBuffer): Int = throw IOException("read only")

        override fun position(): Long = throw IOException("not seekable")

        override fun position(newPosition: Long): SeekableByteChannel =
            throw IOException("not seekable")

        override fun size(): Long = throw IOException("not seekable")

        override fun truncate(size: Long): SeekableByteChannel = throw IOException("read only")

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }
    }

    private class CountingInputStream(
        private val data: ByteArray,
    ) : java.io.InputStream() {
        var bytesRead: Int = 0
            private set

        override fun read(): Int =
            if (bytesRead >= data.size) {
                -1
            } else {
                data[bytesRead++].toInt() and 0xff
            }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= data.size) return -1
            val count = minOf(length, data.size - bytesRead)
            data.copyInto(buffer, offset, bytesRead, bytesRead + count)
            bytesRead += count
            return count
        }
    }

    private data class RawEntry(
        val name: String,
        val data: ByteArray,
        val crcOverride: Long? = null,
    )

    private data class RawRecord(
        val name: ByteArray,
        val size: Int,
        val crc: Long,
        val offset: Int,
    )
}
