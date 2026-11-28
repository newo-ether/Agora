package com.newoether.agora.tool

import com.newoether.agora.api.HttpClient
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.util.ShellClient
import com.newoether.agora.util.transferPost
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val TRANSFER_CHUNK_BYTES = 256 * 1024
internal const val TRANSFER_MAX_BYTES = 1L shl 30

/** Owns the byte-preserving copy boundary; Provider arguments and results contain no file bytes. */
internal suspend fun transferShellFile(
    source: FileTransferEndpoint,
    sourcePath: String,
    destination: FileTransferEndpoint,
    destinationPath: String,
    overwrite: Boolean,
): String = withContext(Dispatchers.IO) {
    source.checkSupport()
    destination.checkSupport()
    val info = source.info(sourcePath)
    val actualSource = info.transferString("path")
    val size = info.transferLong("size")
    val hash = info.transferString("sha256")
    require(size in 0..TRANSFER_MAX_BYTES && hash.matches(Regex("[a-fA-F0-9]{64}"))) {
        "Invalid transfer source metadata"
    }
    if (source.sameLocation(destination, actualSource, destinationPath)) {
        return@withContext buildJsonObject {
            put("type", "file_transfer"); put("ok", true); put("status", "unchanged")
            put("source_server", source.name); put("source_path", actualSource)
            put("destination_server", destination.name); put("destination_path", actualSource)
            put("bytes", size); put("source_sha256", hash); put("destination_sha256", hash)
        }.toString()
    }
    val spool = File.createTempFile("agora-transfer-", ".tmp", source.tempDirectory)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        FileOutputStream(spool).use { output ->
            while (offset < size) {
                currentCoroutineContext().ensureActive()
                val chunk = source.read(actualSource, offset, size)
                check(chunk.size == minOf(TRANSFER_CHUNK_BYTES.toLong(), size - offset).toInt()) {
                    "Source returned an invalid transfer chunk"
                }
                output.write(chunk)
                digest.update(chunk)
                offset += chunk.size
            }
            output.fd.sync()
        }
        check(digest.digest().transferHex().equals(hash, ignoreCase = true)) {
            "Source changed during transfer; destination was not opened"
        }
        val receipt = destination.publish(spool, destinationPath, size, hash, overwrite)
        check(receipt.transferString("state") == "committed" &&
            receipt.transferLong("size") == size && receipt.transferString("sha256").equals(hash, true)) {
            "Invalid destination transfer receipt; inspect destination before retrying"
        }
        buildJsonObject {
            put("type", "file_transfer")
            put("ok", true)
            put("source_server", source.name)
            put("source_path", actualSource)
            put("destination_server", destination.name)
            put("destination_path", receipt.transferString("path"))
            put("bytes", size)
            put("source_sha256", hash.lowercase())
            put("destination_sha256", receipt.transferString("sha256").lowercase())
            put("status", receipt["status"]?.jsonPrimitive?.content ?: "copied")
        }.toString()
    } finally {
        spool.delete()
    }
}

/** One concrete endpoint reuses either the Conch file envelope or sandbox mount resolution. */
internal class FileTransferEndpoint(
    val name: String,
    private val sandbox: SandboxManager? = null,
    private val client: ShellClient? = null,
    private val publishNoReplace: (File, File) -> Unit = AtomicFilePublish::noReplace,
) {
    val tempDirectory: File? get() = sandbox?.getSandboxHomeDir()

    fun sameLocation(other: FileTransferEndpoint, source: String, destination: String): Boolean {
        if (!name.equals(other.name, true)) return false
        return if (client == null && other.client == null) localFile(source) == other.localFile(destination)
        else source == destination
    }

    suspend fun checkSupport() {
        if (client != null) {
            val capabilities = client.transferPost("capabilities", "{}")
            check(capabilities.transferLong("chunk_bytes") == TRANSFER_CHUNK_BYTES.toLong() &&
                capabilities.transferLong("max_bytes") >= TRANSFER_MAX_BYTES) {
                "Incompatible Conch file_transfer protocol; update Conch"
            }
        } else check(sandbox?.isAvailable() == true) { "Local Sandbox is unavailable" }
    }

    private fun localFile(path: String): File {
        require(path.startsWith("/")) { "Local Sandbox transfer path must be absolute" }
        return checkNotNull(sandbox?.resolveHostFile(path)) { "Sandbox path resolution is unavailable" }.canonicalFile
    }

    suspend fun info(path: String): JsonObject {
        client?.let { return it.transferPost("info", buildJsonObject { put("path", path) }.toString()) }
        val file = localFile(path)
        require(file.isFile && file.length() <= TRANSFER_MAX_BYTES) { "Source must be a regular file of at most 1GiB" }
        val size = file.length()
        val modified = file.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= TRANSFER_MAX_BYTES) { "Source exceeds 1GiB transfer limit" }
                digest.update(buffer, 0, count)
            }
            check(total == size && file.length() == size && file.lastModified() == modified) { "Source changed while hashing" }
        }
        return buildJsonObject { put("path", path); put("size", size); put("sha256", digest.digest().transferHex()) }
    }

    suspend fun read(path: String, offset: Long, size: Long): ByteArray {
        val count = minOf(TRANSFER_CHUNK_BYTES.toLong(), size - offset).toInt()
        client?.let {
            val response = it.transferPost("read", buildJsonObject {
                put("path", path); put("offset", offset); put("size", size)
            }.toString())
            check(response.transferLong("offset") == offset && response.transferLong("size") == size) { "Invalid transfer read range" }
            val encoded = response["data"]?.jsonPrimitive?.content.orEmpty()
            check(encoded.length <= ((TRANSFER_CHUNK_BYTES + 2) / 3) * 4) { "Oversized transfer response chunk" }
            return Base64.getDecoder().decode(encoded)
        }
        return RandomAccessFile(localFile(path), "r").use { input ->
            check(input.length() == size) { "Source changed during transfer" }
            input.seek(offset)
            ByteArray(count).also { input.readFully(it) }
        }
    }

    suspend fun publish(spool: File, path: String, size: Long, hash: String, overwrite: Boolean): JsonObject {
        val remote = client ?: return publishLocal(spool, path, size, hash, overwrite)
        val id = UUID.randomUUID().toString().replace("-", "")
        var committed = false
        try {
            val opened = remote.transferPost("begin", buildJsonObject {
                put("id", id); put("path", path); put("size", size); put("sha256", hash); put("overwrite", overwrite)
            }.toString())
            check(opened.transferString("id") == id && opened.transferString("state") == "writing") { "Invalid transfer staging receipt" }
            spool.inputStream().use { input ->
                val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
                var offset = 0L
                while (offset < size) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    check(count > 0) { "Transfer spool ended early" }
                    val next = offset + count
                    val payload = buildJsonObject {
                        put("id", id); put("offset", offset)
                        put("data", Base64.getEncoder().encodeToString(buffer.copyOf(count)))
                    }.toString()
                    val result = try {
                        remote.transferPost("chunk", payload)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) {
                        // Never replay a transmitted mutation. Query the server's accepted offset.
                        remote.transferPost("status", transferID(id)).also {
                            check(it.transferString("state") == "writing" && it.transferLong("offset") == next) {
                                "Transfer chunk outcome unresolved for $id; inspect destination before retrying: ${failure.message}"
                            }
                        }
                    }
                    check(result.transferLong("offset") == next) { "Invalid accepted transfer offset" }
                    offset = next
                }
            }
            val receipt = try {
                remote.transferPost("commit", transferID(id))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                try {
                    remote.transferPost("status", transferID(id)).also {
                        check(it.transferString("state") == "committed") {
                            "Transfer $id was not committed: ${failure.message}"
                        }
                    }
                } catch (statusFailure: Exception) {
                    if (statusFailure is CancellationException) throw statusFailure
                    error("Transfer $id commit outcome is unknown; inspect $name:$path before retrying. ${statusFailure.message}")
                }
            }
            check(receipt.transferString("id") == id && receipt.transferString("state") == "committed") {
                "Invalid destination transfer receipt for $id"
            }
            committed = true
            return receipt
        } finally {
            if (!committed) withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    // Stop cancelled the generation binding; cleanup owns a fresh bounded request.
                    HttpClient.withStreamScope(null) {
                        runCatching { remote.transferPost("abort", transferID(id), callTimeoutMillis = 5_000) }
                    }
                }
            }
        }
    }

    private suspend fun publishLocal(spool: File, path: String, size: Long, hash: String, overwrite: Boolean): JsonObject {
        val destination = localFile(path)
        require(destination.parentFile?.isDirectory == true) { "Destination parent must exist" }
        require(!destination.exists() || destination.isFile) { "Destination is not a regular file" }
        if (destination.exists() && !overwrite) error("Destination already exists; overwrite is false")
        val staging = File.createTempFile(".agora-transfer-", ".tmp", destination.parentFile)
        try {
            FileOutputStream(staging).use { output ->
                spool.inputStream().use { input ->
                    val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= size) { "Transfer spool size changed" }
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
            check(staging.length() == size) { "Local staging size mismatch" }
            val stagedHash = MessageDigest.getInstance("SHA-256")
            staging.inputStream().use { input ->
                val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= size) { "Local staging size mismatch" }
                    stagedHash.update(buffer, 0, count)
                }
                check(total == size && stagedHash.digest().transferHex().equals(hash, true)) {
                    "Local staging hash mismatch; destination was not published"
                }
            }
            currentCoroutineContext().ensureActive()
            if (overwrite) {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                publishNoReplace(staging, destination)
            }
            return buildJsonObject { put("path", path); put("size", size); put("sha256", hash); put("state", "committed") }
        } finally { staging.delete() }
    }
}

private fun transferID(id: String) = buildJsonObject { put("id", id) }.toString()
private fun ByteArray.transferHex(): String = joinToString("") { "%02x".format(it) }
internal fun JsonObject.transferString(key: String): String = this[key]?.jsonPrimitive?.content ?: error("Missing transfer $key")
internal fun JsonObject.transferLong(key: String): Long = transferString(key).toLongOrNull() ?: error("Invalid transfer $key")
