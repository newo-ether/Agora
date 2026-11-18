package com.newoether.agora.sandbox

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotRootfsInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun aptConfigurationReplacesImageSourcesAndUpdatesRootHome() {
        val rootfs = rootfs("debian")
        file(rootfs, "etc/apt/sources.list.d/debian.sources", "Types: deb\nSigned-By: /usr/share/keyrings/debian-archive-keyring.pgp\n")
        file(rootfs, "var/lib/apt/lists/deb.debian.org_debian_dists_trixie_InRelease", "stale")
        file(rootfs, "etc/passwd", "root:x:0:0:root:/root:/bin/bash\napt:x:42:42:apt:/nonexistent:/usr/sbin/nologin\n")

        ProotRootfsInstaller().configureRootfs(SandboxDistribution.DEBIAN, rootfs)

        assertFalse(File(rootfs, "etc/apt/sources.list.d/debian.sources").exists())
        assertEquals(
            "deb http://deb.debian.org/debian trixie main\n" +
                "deb http://deb.debian.org/debian trixie-updates main\n" +
                "deb http://security.debian.org/debian-security trixie-security main\n",
            File(rootfs, "etc/apt/sources.list").readText(),
        )
        assertFalse(File(rootfs, "var/lib/apt/lists/deb.debian.org_debian_dists_trixie_InRelease").exists())
        assertTrue(File(rootfs, "etc/passwd").readText().startsWith("root:x:0:0:root:/home/agora:/bin/bash\n"))
        assertTrue(File(rootfs, "etc/passwd").readText().contains("apt:x:42:42:apt:/nonexistent:/usr/sbin/nologin"))
        assertEquals("#!/bin/sh\nexit 101\n", File(rootfs, "usr/sbin/policy-rc.d").readText())
        assertTrue(File(rootfs, "usr/sbin/policy-rc.d").canExecute())
        val aptConfig = File(rootfs, "etc/apt/apt.conf.d/99agora").readText()
        assertTrue(aptConfig.contains("APT::Sandbox::User \"root\";"))
        assertTrue(aptConfig.contains("--force-confold"))
        assertFalse(aptConfig.contains("AllowUnauthenticated"))
        assertFalse(aptConfig.contains("Verify-Peer"))
        assertTrue(File(rootfs, "etc/profile.d/agora-sandbox.sh").readText().contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(File(rootfs, "var/lib/apt/lists/partial").isDirectory)
        assertTrue(File(rootfs, "home/agora").isDirectory)
    }

    @Test
    fun alpineConfigurationWritesMainAndCommunityWithoutAptPolicy() {
        val rootfs = rootfs("alpine")
        file(rootfs, "etc/apk/world", "alpine-baselayout\napk-tools\nbusybox\n")
        file(rootfs, "lib/apk/db/installed", "P:alpine-baselayout\nV:3.6.8-r0\n\nP:apk-tools\nV:2.14.6-r2\n\nP:busybox\nV:1.37.0-r8\n\n")

        ProotRootfsInstaller().configureRootfs(SandboxDistribution.ALPINE, rootfs)

        assertEquals(
            "http://dl-cdn.alpinelinux.org/alpine/v3.21/main\n" +
                "http://dl-cdn.alpinelinux.org/alpine/v3.21/community\n",
            File(rootfs, "etc/apk/repositories").readText(),
        )
        assertEquals("alpine-baselayout\napk-tools\nbusybox\n", File(rootfs, "etc/agora/base-world").readText())
        assertEquals("", File(rootfs, "etc/agora/explicit-packages").readText())
        assertTrue(File(rootfs, "var/cache/apk").isDirectory)
        assertFalse(File(rootfs, "etc/apt/apt.conf.d/99agora").exists())
        assertFalse(File(rootfs, "usr/sbin/policy-rc.d").exists())
    }

    @Test
    fun failedChecksumDoesNotExtractAndAlwaysRemovesDownload() = runBlocking {
        val parent = temporaryFolder.newFolder("checksum-parent")
        val destination = File(parent, "staging")
        val connection = connection(ByteArrayInputStream("not a rootfs".toByteArray()), 12)
        val progress = mutableListOf<Float?>()

        val result = runCatching {
            ProotRootfsInstaller { connection }.install(
                SandboxDistribution.UBUNTU,
                SandboxMirror.OFFICIAL,
                destination,
                progress = progress::add,
                output = {},
            )
        }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("SHA-256"))
        assertTrue(destination.list()!!.isEmpty())
        assertEquals(listOf("staging"), parent.list()!!.toList())
        assertTrue(connection.disconnected)
        assertNull(progress.last())
    }

    @Test
    fun cancellationDuringDownloadIsPropagatedAndRemovesArchive() {
        val parent = temporaryFolder.newFolder("cancel-parent")
        val destination = File(parent, "staging")
        val stream = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled download")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw CancellationException("cancelled download")
        }
        val connection = connection(stream, -1)
        val result = runCatching {
            runBlocking {
                ProotRootfsInstaller { connection }.install(
                    SandboxDistribution.DEBIAN,
                    SandboxMirror.OFFICIAL,
                    destination,
                    progress = {},
                    output = {},
                )
            }
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(connection.disconnected)
        assertEquals(listOf("staging"), parent.list()!!.toList())
        assertTrue(destination.list()!!.isEmpty())
    }

    @Test
    fun nonemptyStagingIsRejectedBeforeAnyNetworkOrMutation() = runBlocking {
        val destination = temporaryFolder.newFolder("occupied")
        val existing = file(destination, "user.txt", "keep")
        var connected = false
        val result = runCatching {
            ProotRootfsInstaller {
                connected = true
                error("Network must not be reached")
            }.install(
                SandboxDistribution.DEBIAN,
                SandboxMirror.OFFICIAL,
                destination,
                progress = {},
                output = {},
            )
        }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("empty"))
        assertFalse(connected)
        assertEquals("keep", existing.readText())
    }

    @Test
    fun httpFailureDisconnectsAndRemovesArchive() = runBlocking {
        val parent = temporaryFolder.newFolder("http-parent")
        val connection = connection(ByteArrayInputStream(ByteArray(0)), 0, 503)
        val result = runCatching {
            ProotRootfsInstaller { connection }.install(
                SandboxDistribution.DEBIAN,
                SandboxMirror.OFFICIAL,
                File(parent, "staging"),
                progress = {},
                output = {},
            )
        }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP 503"))
        assertTrue(connection.disconnected)
        assertEquals(listOf("staging"), parent.list()!!.toList())
    }

    @Test
    fun alreadyCancelledInstallDoesNotCreateStagingOrOpenNetwork() {
        val destination = File(temporaryFolder.root, "not-created")
        var connected = false
        val result = runCatching {
            runBlocking {
                currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                ProotRootfsInstaller {
                    connected = true
                    error("Network must not be reached")
                }.install(
                    SandboxDistribution.ALPINE,
                    SandboxMirror.OFFICIAL,
                    destination,
                    progress = {},
                    output = {},
                )
            }
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(connected)
        assertFalse(destination.exists())
    }

    @Test
    fun configurationReplacesRuntimeDnsLinkWithoutTouchingItsTarget() {
        val rootfs = rootfs("dns")
        val outside = temporaryFolder.newFile("host-resolv.conf").apply { writeText("host DNS") }
        val link = File(rootfs, "etc/resolv.conf")
        if (!createSymlinkOrSkip(link, outside)) return

        ProotRootfsInstaller().configureRootfs(SandboxDistribution.UBUNTU, rootfs)

        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertEquals("nameserver 8.8.8.8\nnameserver 1.1.1.1\n", link.readText())
        assertEquals("host DNS", outside.readText())
    }

    @Test
    fun configurationCreatesAbsoluteGuestLockTargetInsideRootfs() {
        val rootfs = rootfs("guest-lock")
        File(rootfs, "var").mkdirs()
        val link = File(rootfs, "var/lock")
        if (!createSymlinkOrSkip(link, File("/run/lock"))) return

        ProotRootfsInstaller().configureRootfs(SandboxDistribution.DEBIAN, rootfs)

        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(File(rootfs, "run/lock").isDirectory)
    }

    @Test
    fun configurationDoesNotFollowParentLinkOutsideStaging() {
        val rootfs = rootfs("escape")
        val outside = temporaryFolder.newFolder("outside-apt")
        file(rootfs, "etc/apt/apt.conf.d/placeholder", "")
        File(rootfs, "etc/apt/apt.conf.d/placeholder").delete()
        File(rootfs, "etc/apt/apt.conf.d").delete()
        if (!createSymlinkOrSkip(File(rootfs, "etc/apt/apt.conf.d"), outside)) return

        runCatching { ProotRootfsInstaller().configureRootfs(SandboxDistribution.DEBIAN, rootfs) }

        // Guest absolute links may resolve inside the rootfs or be rejected by the host path parser.
        assertTrue(outside.list()!!.isEmpty())
    }

    @Test
    fun pinnedRootfsSourcesPreserveAlpineAndUseGzipForEveryDistribution() {
        assertEquals("f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1", ProotRootfsInstaller.archiveSha256(SandboxDistribution.ALPINE))
        assertTrue(ProotRootfsInstaller.archiveUrl(SandboxDistribution.DEBIAN).contains("5994ab615f09f401eef80676e152b497e2a7b3ba"))
        SandboxDistribution.entries.forEach { distribution ->
            assertTrue(ProotRootfsInstaller.archiveUrl(distribution).startsWith("https://"))
            assertTrue(ProotRootfsInstaller.archiveUrl(distribution).endsWith(".tar.gz"))
            assertTrue(ProotRootfsInstaller.archiveSha256(distribution).matches(Regex("[a-f0-9]{64}")))
        }
    }

    @Test
    fun alpineRepositoryFollowsMirrorSelection() {
        val rootfs = rootfs("alpine-mirror")
        file(rootfs, "etc/apk/world", "alpine-baselayout\napk-tools\nbusybox\n")
        file(rootfs, "lib/apk/db/installed", "P:alpine-baselayout\nV:3.6.8-r0\n\nP:apk-tools\nV:2.14.6-r2\n\nP:busybox\nV:1.37.0-r8\n\n")

        ProotRootfsInstaller().configureRootfs(SandboxDistribution.ALPINE, rootfs, SandboxMirror.TUNA)

        assertEquals(
            "http://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/main\n" +
                "http://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/community\n",
            File(rootfs, "etc/apk/repositories").readText(),
        )
    }

    @Test
    fun ubuntuConfigurationEnablesUniverseOnUbuntuPorts() {
        val rootfs = rootfs("ubuntu")

        ProotRootfsInstaller().configureRootfs(SandboxDistribution.UBUNTU, rootfs, SandboxMirror.TUNA)
        val sources = File(rootfs, "etc/apt/sources.list").readText()

        assertTrue(sources.contains("deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble main restricted universe multiverse\n"))
        assertTrue(sources.contains("deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble-security main restricted universe multiverse\n"))
        assertFalse(File(rootfs, "etc/apt/sources.list.d").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun mirrorSelectsDownloadSourceAndKeepsOfficialFallbackLast() {
        assertTrue(
            ProotRootfsInstaller.archiveUrls(SandboxDistribution.DEBIAN, SandboxMirror.TUNA).let { urls ->
                urls.size == 3 && urls.first().startsWith("https://ghfast.top/") &&
                    urls.first().endsWith("/trixie/oci/blobs/rootfs.tar.gz") &&
                    urls.last() == "https://raw.githubusercontent.com/" +
                    "debuerreotype/docker-debian-artifacts/5994ab615f09f401eef80676e152b497e2a7b3ba/trixie/oci/blobs/rootfs.tar.gz"
            },
        )
        assertEquals(
            listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz",
                "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz",
            ),
            ProotRootfsInstaller.archiveUrls(SandboxDistribution.ALPINE, SandboxMirror.TUNA),
        )
        assertEquals(
            listOf("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"),
            ProotRootfsInstaller.archiveUrls(SandboxDistribution.UBUNTU, SandboxMirror.OFFICIAL),
        )
    }

    @Test
    fun installReportsDownloadBeforeVerificationAndExtraction() = runBlocking {
        val parent = temporaryFolder.newFolder("phase-parent")
        val phases = mutableListOf<RootfsInstallPhase>()
        val connection = connection(ByteArrayInputStream(ByteArray(0)), 0)

        runCatching {
            ProotRootfsInstaller { connection }.install(
                SandboxDistribution.ALPINE,
                SandboxMirror.OFFICIAL,
                File(parent, "staging"),
                phase = phases::add,
                progress = {},
                output = {},
            )
        }

        assertEquals(
            listOf(RootfsInstallPhase.DOWNLOADING, RootfsInstallPhase.VERIFYING),
            phases.take(2),
        )
    }

    private fun rootfs(name: String): File = temporaryFolder.newFolder(name).also {
        file(it, "etc/passwd", "root:x:0:0:root:/root:/bin/sh\n")
    }

    private fun file(root: File, path: String, text: String): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun createSymlinkOrSkip(link: File, target: File): Boolean {
        val result = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }
        org.junit.Assume.assumeTrue("Host does not permit symbolic links", result.isSuccess)
        return result.isSuccess
    }

    private fun connection(stream: InputStream, length: Long, code: Int = 200): FakeConnection =
        FakeConnection(stream, length, code)

    private class FakeConnection(
        private val stream: InputStream,
        private val length: Long,
        private val code: Int,
    ) : HttpURLConnection(URL("https://rootfs.invalid/archive.tar.gz")) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getContentLengthLong(): Long = length
        override fun getInputStream(): InputStream = stream
    }
}
