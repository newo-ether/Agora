package com.newoether.agora.data

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile

/** A reused baseline entry no longer matches the checksum recorded when it was written. */
internal class BaselineDamagedException(entry: String) :
    IOException("Incremental baseline entry is damaged: $entry")

internal class NativeBackupV5Baseline private constructor(
    private val zip: ZipFile,
    val index: NativeConversationIndex,
    private val rawChecksums: Map<String, Long>,
) : Closeable {
    private val byId = index.conversations.associateBy { it.id }

    private val intactEntries = mutableMapOf<String, Boolean>()
    /**
     * True when [id]'s baseline item was written at [dataChangedAt] and its bytes can be trusted.
     * An entry with a recorded raw checksum is verified by [copyRaw] while it is copied, so nothing
     * is decompressed here. An entry without one must still decompress to its recorded size and
     * CRC, which keeps a damaged entry from being carried into every later backup.
     */
    fun canReuse(id: String, dataChangedAt: Long): Boolean {
        val item = byId[id]?.takeIf { it.dataChangedAt == dataChangedAt } ?: return false
        return isTrusted(item.entry) && item.mediaEntries.all(::isTrusted)
    }

    private fun isTrusted(name: String): Boolean =
        if (name in rawChecksums) zip.getEntry(name) != null else isIntact(name)

    private fun isIntact(name: String): Boolean = intactEntries.getOrPut(name) {
        val entry = zip.getEntry(name) ?: return@getOrPut false
        runCatching {
            val crc = CRC32()
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var total = 0L
            zip.getInputStream(entry).use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    crc.update(buffer, 0, count)
                    total += count
                }
            }
            total == entry.size && crc.value == entry.crc
        }.getOrDefault(false)
    }

    /**
     * Copies [name]'s stored bytes unchanged and records their CRC in [checksums]. When the
     * baseline recorded a checksum for the entry, a mismatch throws [BaselineDamagedException]
     * after the copy; the caller then rebuilds the whole backup without a baseline.
     */
    fun copyRaw(name: String, output: ZipArchiveOutputStream, checksums: MutableMap<String, Long>) {
        val entry = zip.getEntry(name) ?: throw IOException("Missing baseline entry: $name")
        val crc = CRC32()
        zip.getRawInputStream(entry).use { raw ->
            CheckedInputStream(BufferedInputStream(raw, COPY_BUFFER_BYTES), crc).use { checked ->
                output.addRawArchiveEntry(ZipArchiveEntry(entry), checked)
            }
        }
        val expected = rawChecksums[name]
        if (expected != null && expected != crc.value) throw BaselineDamagedException(name)
        checksums[name] = crc.value
    }

    fun indexEntry(id: String): NativeConversationIndexEntry? = byId[id]

    override fun close() = zip.close()

    companion object {
        private const val MAX_METADATA_BYTES = 16L * 1024L * 1024L
        private const val COPY_BUFFER_BYTES = 256 * 1024
        // Manifests carry fields such as app_version and exported_at that are not read here.
        private val baselineJson = Json { ignoreUnknownKeys = true }

        fun openOrNull(file: File?): NativeBackupV5Baseline? {
            if (file?.isFile != true) return null
            return runCatching {
                val zip = ZipFile.builder().setFile(file).get()
                try {
                    val manifest = zip.readJson<BaselineManifest>(NativeBackupFormat.MANIFEST_ENTRY)
                    require(manifest.version == NativeBackupFormat.CURRENT_VERSION)
                    require(
                        manifest.incrementalBaseline ==
                            NativeBackupFormat.INCREMENTAL_BASELINE_REVISION,
                    )
                    require("conversations" in manifest.categories)
                    val index = zip.readJson<NativeConversationIndex>(
                        NativeBackupFormat.CONVERSATION_INDEX_ENTRY,
                    )
                    require(index.conversations.map { it.id }.toSet().size == index.conversations.size)
                    index.conversations.forEach { item ->
                        require(item.entry == NativeBackupFormat.conversationEntry(item.id))
                        require(zip.getEntry(item.entry) != null)
                        item.mediaEntries.forEach { require(zip.getEntry(it) != null) }
                    }
                    // Unreadable checksums only cost speed: every entry is then inflate-checked.
                    val rawChecksums = zip.getEntry(NativeBackupFormat.RAW_CHECKSUMS_ENTRY)
                        ?.let {
                            runCatching {
                                zip.readJson<NativeBackupRawChecksums>(
                                    NativeBackupFormat.RAW_CHECKSUMS_ENTRY,
                                ).entries
                            }.getOrNull()
                        }
                        .orEmpty()
                    NativeBackupV5Baseline(zip, index, rawChecksums)
                } catch (error: Throwable) {
                    zip.close()
                    throw error
                }
            }.getOrNull()
        }

        private inline fun <reified T> ZipFile.readJson(name: String): T {
            val entry = getEntry(name) ?: throw IOException("Missing baseline entry: $name")
            require(entry.size in 0..MAX_METADATA_BYTES)
            val text = getInputStream(entry).bufferedReader().use { it.readText() }
            require(text.encodeToByteArray().size <= MAX_METADATA_BYTES)
            return baselineJson.decodeFromString(text)
        }
    }
}

@Serializable
private data class BaselineManifest(
    @SerialName("agora_export_version") val version: Int,
    val categories: List<String>,
    @SerialName("incremental_baseline") val incrementalBaseline: Int = 0,
)
