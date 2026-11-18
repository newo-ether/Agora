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
        mirror: SandboxMirror,
        destination: File,
        phase: (RootfsInstallPhase) -> Unit = {},
        progress: (Float?) -> Unit,
        output: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(!Files.isSymbolicLink(destination.toPath())) { "Rootfs staging directory must not be a symbolic link" }
        check(destination.isDirectory || destination.mkdirs()) { "Cannot create rootfs staging directory" }
        check(destination.list()?.isEmpty() == true) { "Rootfs staging directory must be empty" }
        val archive = File.createTempFile("agora-${distribution.id}-", ".tar.gz", destination.parentFile)
        try {
            phase(RootfsInstallPhase.DOWNLOADING)
            output("Downloading ${distribution.displayName} ${distribution.versionLabel} rootfs…\n")
            val downloaded = download(distribution, mirror, archive, progress, output)
            progress(null)
            phase(RootfsInstallPhase.VERIFYING)
            val expected = archiveSha256(distribution)
            check(downloaded.sha256 == expected) {
                "Rootfs SHA-256 verification failed: expected $expected, got ${downloaded.sha256}"
            }
            output("Verified ${downloaded.bytes} bytes; SHA-256 $expected.\n")
            phase(RootfsInstallPhase.EXTRACTING)
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
            phase(RootfsInstallPhase.VALIDATING)
            configureRootfs(distribution, destination, mirror)
            context.ensureActive()
            verifyPackageIndex(distribution, mirror, output)
            context.ensureActive()
            output("${distribution.displayName} rootfs prepared and verified.\n")
        } finally {
            archive.delete()
            progress(null)
        }
    }

    private data class DownloadResult(val bytes: Long, val sha256: String)

    /** Tries each configured source for this mirror; a checksum mismatch is never retried. */
    private suspend fun download(
        distribution: SandboxDistribution,
        mirror: SandboxMirror,
        archive: File,
        progress: (Float?) -> Unit,
        output: (String) -> Unit,
    ): DownloadResult {
        var lastError: Exception? = null
        for (url in archiveUrls(distribution, mirror)) {
            currentCoroutineContext().ensureActive()
            try {
                return downloadFrom(url, archive, progress, output)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                output("Download attempt failed: ${error.message}\n")
                archive.delete()
                progress(null)
            }
        }
        throw lastError ?: IllegalStateException("No rootfs source configured")
    }

    private suspend fun downloadFrom(
        url: String,
        archive: File,
        progress: (Float?) -> Unit,
        output: (String) -> Unit,
    ): DownloadResult {
        val context = currentCoroutineContext()
        val connection = openConnection(URL(url))
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept-Encoding", "identity")
            context.ensureActive()
            connection.connect()
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code fetching rootfs" }
            val length = connection.contentLengthLong
            output("Source: ${connection.url.host}${connection.url.path}\n")
            output("Expected size: ${if (length >= 0) "$length bytes" else "unknown"}\n")
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            progress(if (length > 0) 0f else null)
            CancellationInputStream(connection.inputStream, context).use { input ->
                archive.outputStream().buffered().use { outputStream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        outputStream.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        progress(if (length > 0) (downloaded.toFloat() / length).coerceIn(0f, 1f) else null)
                    }
                }
            }
            context.ensureActive()
            check(length < 0 || downloaded == length) {
                "Rootfs download was truncated ($downloaded of $length bytes)"
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            return DownloadResult(downloaded, actual)
        } finally {
            connection.disconnect()
        }
    }

    /** Probes the configured package index so a bad mirror is visible before the first install. */
    private suspend fun verifyPackageIndex(
        distribution: SandboxDistribution,
        mirror: SandboxMirror,
        output: (String) -> Unit,
    ) {
        val url = packageIndexUrl(distribution, mirror)
        currentCoroutineContext().ensureActive()
        try {
            val connection = openConnection(URL(url))
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
                connection.requestMethod = "HEAD"
                connection.setRequestProperty("Accept-Encoding", "identity")
                val code = connection.responseCode
                val length = connection.contentLengthLong
                if (code == HttpURLConnection.HTTP_OK) {
                    output(
                        "Package index verified: HTTP 200, " +
                            (if (length >= 0) "$length bytes" else "length unknown") + ".\n",
                    )
                } else {
                    output("Warning: package index returned HTTP $code: $url\n")
                }
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            output("Warning: package index check failed: ${error.message}\n")
        }
    }

    internal fun configureRootfs(
        distribution: SandboxDistribution,
        rootfs: File,
        mirror: SandboxMirror = SandboxMirror.OFFICIAL,
    ) {
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
            configureApt(rootfs, distribution, mirror)
        } else {
            listOf("var/cache/apk", "etc/apk/cache").forEach { directory(rootfs, it) }
            writeFile(rootfs, "etc/apk/repositories", repositoriesFileContent(mirror))
            AlpinePackageMetadataStore(rootfs).apply {
                captureBaseWorld(force = true)
                writeExplicitPackages(emptySet())
            }
        }
    }

    /** Rewrites only the package sources of an installed rootfs after a mirror change. */
    internal fun configurePackageSources(
        distribution: SandboxDistribution,
        rootfs: File,
        mirror: SandboxMirror,
    ) {
        if (!rootfs.isDirectory) return
        if (distribution.usesApt) {
            configureAptSources(rootfs, distribution, mirror)
        } else {
            writeFile(rootfs, "etc/apk/repositories", repositoriesFileContent(mirror))
            directory(rootfs, "var/cache/apk")
            directory(rootfs, "etc/apk/cache")
        }
    }

    private fun configureApt(rootfs: File, distribution: SandboxDistribution, mirror: SandboxMirror) {
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
        configureAptSources(rootfs, distribution, mirror)
        // Capture the initial package set before staging can admit user commands.
        ProotAptPackages.prepare(rootfs)
    }

    /**
     * Replaces the image's deb822 or distro-provided sources with one explicit list. Ubuntu's
     * arm64 archive lives on ubuntu-ports and its desktop packages need universe, so both are
     * written instead of relying on whatever the base image happened to enable.
     */
    private fun configureAptSources(rootfs: File, distribution: SandboxDistribution, mirror: SandboxMirror) {
        val sourcesDir = directory(rootfs, "etc/apt/sources.list.d")
        sourcesDir.listFiles()?.forEach { stale ->
            if (Files.isSymbolicLink(stale.toPath())) check(stale.delete()) { "Cannot remove stale apt source" }
            else check(stale.deleteRecursively()) { "Cannot remove stale apt source" }
        }
        writeFile(rootfs, "etc/apt/sources.list", aptSourcesFileContent(distribution, mirror))
        // Lists fetched from the previous sources must not shadow the new ones.
        val lists = directory(rootfs, "var/lib/apt/lists")
        lists.listFiles()?.forEach { cached ->
            if (cached.name == "partial" || cached.name == "lock") return@forEach
            if (Files.isSymbolicLink(cached.toPath())) cached.delete()
            else cached.deleteRecursively()
        }
    }

    private fun alpineRepository(mirror: SandboxMirror): String = when (mirror) {
        SandboxMirror.TUNA -> "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/main"
        SandboxMirror.OFFICIAL -> "https://dl-cdn.alpinelinux.org/alpine/v3.21/main"
    }

    private fun repositoriesFileContent(mirror: SandboxMirror): String = alpineRepository(mirror) + "\n"

    private fun debianBase(mirror: SandboxMirror): String = when (mirror) {
        SandboxMirror.TUNA -> "https://mirrors.tuna.tsinghua.edu.cn/debian"
        SandboxMirror.OFFICIAL -> "https://deb.debian.org/debian"
    }

    private fun debianSecurityBase(mirror: SandboxMirror): String = when (mirror) {
        SandboxMirror.TUNA -> "https://mirrors.tuna.tsinghua.edu.cn/debian-security"
        SandboxMirror.OFFICIAL -> "https://security.debian.org/debian-security"
    }

    private fun ubuntuPortsBase(mirror: SandboxMirror): String = when (mirror) {
        SandboxMirror.TUNA -> "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
        SandboxMirror.OFFICIAL -> "http://ports.ubuntu.com/ubuntu-ports"
    }

    private fun aptSourcesFileContent(
        distribution: SandboxDistribution,
        mirror: SandboxMirror,
    ): String = when (distribution) {
        SandboxDistribution.DEBIAN -> buildString {
            val base = debianBase(mirror)
            appendLine("deb $base trixie main")
            appendLine("deb $base trixie-updates main")
            appendLine("deb ${debianSecurityBase(mirror)} trixie-security main")
        }
        SandboxDistribution.UBUNTU -> buildString {
            val base = ubuntuPortsBase(mirror)
            val suites = listOf("noble", "noble-updates", "noble-security")
            suites.forEach { appendLine("deb $base $it main restricted universe multiverse") }
        }
        SandboxDistribution.ALPINE -> error("Alpine uses APK repositories")
    }

    private fun packageIndexUrl(distribution: SandboxDistribution, mirror: SandboxMirror): String =
        when (distribution) {
            SandboxDistribution.ALPINE -> "${alpineRepository(mirror)}/aarch64/APKINDEX.tar.gz"
            SandboxDistribution.DEBIAN -> "${debianBase(mirror)}/dists/trixie/Release"
            SandboxDistribution.UBUNTU -> "${ubuntuPortsBase(mirror)}/dists/noble/Release"
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
        private const val DEBIAN_ROOTFS_PATH =
            "debuerreotype/docker-debian-artifacts/5994ab615f09f401eef80676e152b497e2a7b3ba/trixie/oci/blobs/rootfs.tar.gz"

        internal fun archiveUrl(distribution: SandboxDistribution, mirror: SandboxMirror = SandboxMirror.OFFICIAL): String =
            archiveUrls(distribution, mirror).first()

        internal fun archiveUrls(
            distribution: SandboxDistribution,
            mirror: SandboxMirror,
        ): List<String> = when (mirror) {
            SandboxMirror.OFFICIAL -> listOf(officialArchiveUrl(distribution))
            SandboxMirror.TUNA -> when (distribution) {
                SandboxDistribution.ALPINE -> listOf(
                    "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz",
                    officialArchiveUrl(distribution),
                )
                SandboxDistribution.UBUNTU -> listOf(
                    "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                    officialArchiveUrl(distribution),
                )
                // TUNA does not mirror the Debian OCI blobs; these proxies front the same pinned file.
                SandboxDistribution.DEBIAN -> listOf(
                    "https://ghfast.top/https://raw.githubusercontent.com/$DEBIAN_ROOTFS_PATH",
                    "https://ghproxy.net/https://raw.githubusercontent.com/$DEBIAN_ROOTFS_PATH",
                    officialArchiveUrl(distribution),
                )
            }
        }

        private fun officialArchiveUrl(distribution: SandboxDistribution): String = when (distribution) {
            SandboxDistribution.ALPINE -> "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"
            // Official Debian container rootfs; commit and OCI layer digest pin the arm64 image.
            SandboxDistribution.DEBIAN -> "https://raw.githubusercontent.com/$DEBIAN_ROOTFS_PATH"
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
