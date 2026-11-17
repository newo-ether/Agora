package com.newoether.agora.sandbox

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** Downloads and prepares an empty staging directory. The manager owns publication and rollback. */
internal class ProotRootfsInstaller(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun install(
        distribution: SandboxDistribution,
        destination: File,
        progress: (Float?) -> Unit,
        output: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(!Files.isSymbolicLink(destination.toPath())) { "Rootfs staging directory must not be a symbolic link" }
        check(destination.isDirectory || destination.mkdirs()) { "Cannot create rootfs staging directory" }
        check(destination.list()?.isEmpty() == true) { "Rootfs staging directory must be empty" }
        val archive = File.createTempFile("agora-${distribution.id}-", ".tar.gz", destination.parentFile)
        try {
            output("Downloading ${distribution.displayName} ${distribution.versionLabel} rootfs…\n")
            download(distribution, archive, progress)
            progress(null)
            output("Extracting rootfs…\n")
            val context = currentCoroutineContext()
            CancellationInputStream(archive.inputStream().buffered(), context).use { input ->
                GZIPInputStream(input).use { gzip ->
                    // Check cancellation for uncompressed reads too, including highly compressed files.
                    TarArchiveInputStream(CancellationInputStream(gzip, context)).use { tar ->
                        extractTarEntries(tar, destination, checkActive = { context.ensureActive() })
                    }
                }
            }
            context.ensureActive()
            configureRootfs(distribution, destination)
            context.ensureActive()
            output("${distribution.displayName} rootfs prepared.\n")
        } finally {
            archive.delete()
            progress(null)
        }
    }

    private suspend fun download(
        distribution: SandboxDistribution,
        archive: File,
        progress: (Float?) -> Unit,
    ) {
        val context = currentCoroutineContext()
        val connection = openConnection(URL(archiveUrl(distribution)))
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept-Encoding", "identity")
            context.ensureActive()
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "HTTP ${connection.responseCode} fetching rootfs"
            }
            val length = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            progress(if (length > 0) 0f else null)
            CancellationInputStream(connection.inputStream, context).use { input ->
                archive.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        progress(if (length > 0) (downloaded.toFloat() / length).coerceIn(0f, 1f) else null)
                    }
                }
            }
            context.ensureActive()
            check(length < 0 || downloaded == length) { "Rootfs download was truncated" }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == archiveSha256(distribution)) { "Rootfs SHA-256 verification failed" }
        } finally {
            connection.disconnect()
        }
    }

    internal fun configureRootfs(distribution: SandboxDistribution, rootfs: File) {
        listOf("tmp", "run", "dev", "proc", "sys", "home/agora", "mnt/shared", "var/lock").forEach {
            directory(rootfs, it)
        }
        directory(rootfs, "tmp").apply {
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        writeFile(rootfs, "etc/resolv.conf", "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        val passwd = configurationFile(rootfs, "etc/passwd")
        check(passwd.isFile) { "Rootfs is missing /etc/passwd" }
        val updatedPasswd = passwd.readText().lineSequence().joinToString("\n") { line ->
            val fields = line.split(':').toMutableList()
            if (fields.size >= 7 && fields[0] == "root" && fields[2] == "0") {
                fields[5] = "/home/agora"
                fields.joinToString(":")
            } else {
                line
            }
        }
        writeFile(rootfs, "etc/passwd", updatedPasswd)
        if (distribution.usesApt) {
            configureApt(rootfs)
        } else {
            listOf("var/cache/apk", "etc/apk/cache").forEach { directory(rootfs, it) }
            writeFile(rootfs, "etc/apk/repositories", "https://dl-cdn.alpinelinux.org/alpine/v3.21/main\n")
            AlpinePackageMetadataStore(rootfs).apply {
                captureBaseWorld(force = true)
                writeExplicitPackages(emptySet())
            }
        }
    }

    private fun configureApt(rootfs: File) {
        listOf("var/lib/apt/lists/partial", "var/cache/apt/archives/partial", "var/lib/dpkg/updates").forEach {
            directory(rootfs, it)
        }
        // PRoot cannot perform real setuid sandboxing. Retain apt's signed repository verification.
        writeFile(rootfs, "etc/apt/apt.conf.d/99agora", """
            APT::Sandbox::User "root";
            Dpkg::Use-Pty "0";
            Dpkg::Options {
                "--force-confdef";
                "--force-confold";
            };
        """.trimIndent() + "\n")
        // Standard Debian/Ubuntu package helpers consult this before trying to start daemons.
        writeFile(rootfs, "usr/sbin/policy-rc.d", "#!/bin/sh\nexit 101\n", executable = true)
        // Login shells receive the same noninteractive defaults used by the manager's apt commands.
        writeFile(rootfs, "etc/profile.d/agora-sandbox.sh", """
            export DEBIAN_FRONTEND=noninteractive
            export DEBCONF_NONINTERACTIVE_SEEN=true
            export container=proot
        """.trimIndent() + "\n")
        // Both verified images already contain architecture-correct, signed stable apt sources.
        // Capture the initial package set before staging can admit user commands.
        ProotAptPackages.prepare(rootfs)
    }

    private fun directory(rootfs: File, path: String): File {
        val resolver = SandboxPathResolver(rootfs, rootfs, "", "") { null }
        val file = resolver.resolvePath("/$path")
        requireContained(rootfs, file.canonicalFile)
        check(file.isDirectory || file.mkdirs()) { "Cannot create rootfs directory /$path" }
        return file
    }

    private fun configurationFile(rootfs: File, path: String): File {
        val parent = directory(rootfs, path.substringBeforeLast('/', ""))
        val file = File(parent, path.substringAfterLast('/'))
        // resolv.conf may point at a runtime file. Never follow a guest absolute link on the host.
        if (Files.isSymbolicLink(file.toPath())) {
            check(file.delete()) { "Cannot replace rootfs configuration link /$path" }
        }
        requireContained(rootfs, file.canonicalFile)
        return file
    }

    private fun writeFile(rootfs: File, path: String, content: String, executable: Boolean = false) {
        configurationFile(rootfs, path).apply {
            writeText(content)
            setReadable(true, false)
            setWritable(true, true)
            if (executable) check(setExecutable(true, false)) { "Cannot make /$path executable" }
        }
    }

    private fun requireContained(rootfs: File, file: File) {
        val root = rootfs.canonicalFile.toPath()
        require(file.toPath().startsWith(root)) { "Rootfs configuration path escapes staging directory" }
    }

    private class CancellationInputStream(input: InputStream, private val context: CoroutineContext) : FilterInputStream(input) {
        override fun read(): Int {
            context.ensureActive()
            return `in`.read().also { context.ensureActive() }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            context.ensureActive()
            return `in`.read(buffer, offset, length).also { context.ensureActive() }
        }

        override fun skip(count: Long): Long {
            context.ensureActive()
            return `in`.skip(count).also { context.ensureActive() }
        }
    }

    companion object {
        internal fun archiveUrl(distribution: SandboxDistribution): String = when (distribution) {
            SandboxDistribution.ALPINE -> "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"
            // Official Debian container rootfs; commit and OCI layer digest pin the arm64 image.
            SandboxDistribution.DEBIAN -> "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/5994ab615f09f401eef80676e152b497e2a7b3ba/trixie/oci/blobs/rootfs.tar.gz"
            SandboxDistribution.UBUNTU -> "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
        }

        internal fun archiveSha256(distribution: SandboxDistribution): String = when (distribution) {
            SandboxDistribution.ALPINE -> "f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1"
            // trixie/oci/blobs/image-manifest.json at the same fixed commit.
            SandboxDistribution.DEBIAN -> "db6281b9e7f663fba4d694153c7a8fb2db45a64e85f34ab224626044fb4cabcb"
            // Canonical's releases/24.04/release/SHA256SUMS.
            SandboxDistribution.UBUNTU -> "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
        }
    }
}
