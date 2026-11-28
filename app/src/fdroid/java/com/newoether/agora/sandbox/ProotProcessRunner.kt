package com.newoether.agora.sandbox

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.delay

/** The caller owns the shared rootfs mutation lock. */
internal class ProotProcessRunner(
    private val context: Context,
    private val rootfsDir: File,
    private val homeMountDir: File,
    private val sharedStorageHostDir: () -> File?,
) {
    fun builder(command: String, workdir: String): ProcessBuilder {
        val home = "/home/agora"
        // Preserve native library extraction before constructing the executable command.
        try { System.loadLibrary("agora_proot") } catch (_: Throwable) {}
        val libraryDir = context.applicationInfo.nativeLibraryDir
        val tallocDir = File(context.filesDir, "lib").apply { mkdirs() }
        val talloc = File(tallocDir, "libtalloc.so.2")
        val source = File(libraryDir, "libtalloc.so")
        if (!talloc.exists() && source.exists()) source.copyTo(talloc)
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }
        val arguments = mutableListOf(
            "$libraryDir/libproot_exec.so", "--rootfs=${rootfsDir.absolutePath}",
            "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random",
            "--bind=${homeMountDir.absolutePath}:$home",
        )
        sharedStorageHostDir()?.let { arguments += "--bind=${it.absolutePath}:/mnt/shared" }
        arguments += listOf("-w", workdir.ifBlank { home }, "-0", "--link2symlink", "--kill-on-exit", "-L",
            "/bin/sh", "-c", command)
        return ProcessBuilder(arguments).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = "${tallocDir.absolutePath}:$libraryDir"
            environment()["PROOT_LOADER"] = "$libraryDir/libproot_loader.so"
            environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
            environment()["HOME"] = home
            environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            // Android sets TMPDIR to the app cache directory, which is outside the guest
            // rootfs; mktemp in maintainer scripts then fails before any package installs.
            environment()["TMPDIR"] = "/tmp"
            environment()["TMP"] = "/tmp"
        }
    }

    /** Do not signal a recycled PID. SIGTERM lets PRoot's handler kill its traced descendants. */
    suspend fun interruptOrphan(identity: SandboxProcessIdentity) {
        if (!identity.stillMatches()) return
        val executable = runCatching { File("/proc/${identity.pid}/exe").canonicalFile }.getOrNull()
        val expected = File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so").canonicalFile
        if (!identity.stillMatches()) return
        check(executable == expected) { "Cannot verify orphan PRoot executable; rootfs access remains blocked" }
        Os.kill(identity.pid, OsConstants.SIGTERM)
        repeat(80) {
            if (!identity.stillMatches()) return
            delay(25)
        }
        check(!identity.stillMatches()) { "Orphan PRoot did not settle; rootfs access remains blocked" }
    }
}
