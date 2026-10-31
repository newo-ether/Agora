package com.newoether.agora.sandbox

import android.content.Context
import android.os.Build
import android.os.Environment
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.SHELL_COMMAND_OUTPUT_MAX_BYTES
import com.newoether.agora.util.ShellFileEdit
import com.newoether.agora.util.ShellFileEditResult
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.util.ShellGrepOptions
import com.newoether.agora.util.ShellGrepResult
import com.newoether.agora.util.readBoundedShellOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
class ProotSandboxManager(
    private val context: Context,
    private val settings: SettingsRepository,
) : SandboxManager {

    // Serializes every state-mutating operation against the shared Alpine rootfs. The sandbox
    // filesystem is process-global (one rootfs/home shared across all conversations and across
    // the foreground + headless engines), so without this, two parallel shell/file operations
    // on different conversations could corrupt lib/apk/db/installed, /etc/apk/world, or lose
    // updates to a file both are editing. Read-only ops (fileRead/fileGlob/fileGrep/apkList) are
    // intentionally NOT serialized — they read snapshots and don't mutate state.
    private val mutationMutex = Mutex()

    // Pin to the stable v3.21 branch to match the downloaded minirootfs (3.21.0). Using edge here
    // caused `apk upgrade` to pull divergent packages (e.g. yash-binsh vs busybox-binsh /bin/sh
    // conflict) and rotates signing keys; the stable branch avoids both.
    private val alpineMirror = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main"
    // Base rootfs is fetched on-device at install time (not bundled in the APK), then verified
    // against this pinned SHA-256 before extraction. Stable v3.21 release URL.
    private val rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"
    private val rootfsSha256 = "f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1"
    private var sandboxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _terminalOutput = MutableStateFlow("")
    override val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()
    private val _isInstallingRootfs = MutableStateFlow(false)
    override val isInstallingRootfs: StateFlow<Boolean> = _isInstallingRootfs.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    override val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()
    private val _packageList = MutableStateFlow<List<SandboxManager.PackageInfo>>(emptyList())
    override val packageList: StateFlow<List<SandboxManager.PackageInfo>> = _packageList.asStateFlow()

    override suspend fun refreshPackageList() {
        if (isAvailable()) _packageList.value = apkList()
    }
    private val snackbarMessages = Channel<String>(Channel.UNLIMITED)
    override val snackbarMessage = snackbarMessages.receiveAsFlow()
    override var pendingPkgName: String = ""

    private fun emitSnackbar(message: String) = check(snackbarMessages.trySend(message).isSuccess)

    private val rootfsDir: File = File(context.filesDir, "alpine-rootfs")
    private val homeMountDir: File = File(context.filesDir, "sandbox-home")
    private val homeMountPath = "/home/agora"
    private val sharedStorageMountPath = "/mnt/shared"
    private val packageMetadata = AlpinePackageMetadataStore(rootfsDir)
    private val pathResolver = SandboxPathResolver(
        rootfsDir = rootfsDir,
        homeMountDir = homeMountDir,
        homeMountPath = homeMountPath,
        sharedStorageMountPath = sharedStorageMountPath,
        sharedStorageHostDir = { sharedStorageHostDir() },
    )

    private val prootExecPath: String by lazy {
        // Force System.loadLibrary to trigger extraction from APK.
        // Without this, the .so may not be in nativeLibraryDir at runtime.
        try { System.loadLibrary("agora_proot") } catch (_: Throwable) {}
        "${context.applicationInfo.nativeLibraryDir}/libproot_exec.so"
    }

    override var lastError: String? = null

    /**
     * Rewrite root's home entry in /etc/passwd from /root to /home/agora.
     * Some programs call getpwuid(0) instead of reading $HOME, so the passwd
     * entry must match the HOME env var for consistent behaviour (shell, git, SSH, etc.).
     * This is a direct file edit — no proot needed, idempotent, and fast.
     */
    private fun ensureRootHome() {
        val passwdFile = File(rootfsDir, "etc/passwd")
        if (!passwdFile.isFile) return
        val content = passwdFile.readText()
        if ("root:x:0:0:root:/home/agora:" in content) return // already correct
        val updated = content.replace(
            Regex("^(root:x:0:0:root:)/root(:)", RegexOption.MULTILINE),
            "$1/home/agora$2"
        )
        if (updated != content) {
            passwdFile.writeText(updated)
        }
    }

    private fun ensureShell(): Boolean {
        val sh = File(rootfsDir, "bin/sh")
        if (sh.exists()) return true
        try {
            val busybox = File(rootfsDir, "bin/busybox")
            if (busybox.isFile && busybox.canRead()) {
                // Delete broken symlink if present (exists()=false but symlink entry exists)
                sh.delete()
                busybox.copyTo(sh, false); sh.setExecutable(true)
                return true
            }
        } catch (_: Throwable) { sh.delete() }
        return false
    }

    override fun isAvailableSync(): Boolean {
        if (!rootfsDir.isDirectory) return false
        if (!File(rootfsDir, "bin/sh").exists()) return false
        return listOf("lib/ld-musl-aarch64.so.1", "usr/lib/ld-musl-aarch64.so.1")
            .map { File(rootfsDir, it) }.any { it.exists() }
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock { prepareAvailability() }
    }

    /** Caller holds the rootfs mutation boundary, including its small repair writes. */
    private fun prepareAvailability(): Boolean {
        if (!rootfsDir.isDirectory) { lastError = "rootfs not found: ${rootfsDir.absolutePath}"; return false }
        if (!ensureShell()) { lastError = "/bin/sh missing"; return false }
        val linker = listOf("lib/ld-musl-aarch64.so.1", "usr/lib/ld-musl-aarch64.so.1").map { File(rootfsDir, it) }.any { it.exists() }
        if (!linker) { lastError = "musl linker missing"; return false }
        ensureSandboxMountTargets()
        ensurePackageMetadata()
        ensureRootHome()
        return true
    }

    override suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            try {
                if (rootfsDir.exists()) { rootfsDir.deleteRecursively(); if (rootfsDir.exists()) { error("Cannot delete stale rootfs") } }
                rootfsDir.mkdirs()

                val tmpTar = File(context.filesDir, "alpine-rootfs.tar.gz")
                try {
                    // Fetch the base rootfs on-device (not shipped in the APK) and verify its checksum.
                    _terminalOutput.value += "Downloading Alpine minirootfs…\n"
                    downloadRootfs(rootfsUrl, tmpTar)
                    // Switch the bar to indeterminate while we extract.
                    _downloadProgress.value = null
                    _terminalOutput.value += "Extracting rootfs…\n"
                    java.util.zip.GZIPInputStream(tmpTar.inputStream()).use { gz ->
                        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar -> extractTarEntries(tar, rootfsDir) }
                    }
                } finally { tmpTar.delete() }

                File(rootfsDir, "tmp").mkdirs()
                File(rootfsDir, "run").mkdirs()
                ensureSandboxMountTargets()
                listOf("var/cache/apk", "etc/apk/cache", "var/lock").forEach { File(rootfsDir, it).mkdirs() }
                val rc = File(rootfsDir, "etc/resolv.conf"); rc.parentFile?.mkdirs()
                rc.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                // Alpine repository config
                val repos = File(rootfsDir, "etc/apk/repositories"); repos.parentFile?.mkdirs()
                repos.writeText("$alpineMirror\n")
                // Ensure all binaries are executable recursively
                listOf("bin", "usr/bin", "sbin", "usr/sbin", "usr/libexec").forEach { dir ->
                    val d = File(rootfsDir, dir)
                    if (d.isDirectory) d.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true) }
                }
                // No auto `apk upgrade` here: the freshly-downloaded minirootfs is already a coherent
                // pinned release. Running upgrade immediately makes apk re-resolve /bin/sh and dead-locks
                // on the busybox-binsh vs yash-binsh `cmd:sh` conflict. Packages upgrade on demand.
                captureBaseWorld(force = true)
                writeExplicitPackages(emptySet())
                prepareAvailability()
            } catch (e: Throwable) { e.printStackTrace(); lastError = e.message; false }
        }
    }

    override fun installRootfs() {
        if (_isInstallingRootfs.value) return
        sandboxScope.launch {
            _isInstallingRootfs.value = true
            _downloadProgress.value = null
            _terminalOutput.value = ""
            _packageList.value = emptyList()
            try {
                // NOTE: don't call reset() here — it cancels sandboxScope (i.e. this very
                // coroutine). install() already wipes any stale rootfs before extracting.
                val ok = install()
                if (ok) refreshPackageList()
            } catch (e: Throwable) {
                e.printStackTrace(); lastError = e.message
            } finally {
                _isInstallingRootfs.value = false
                _downloadProgress.value = null
            }
        }
    }

    /** Download [url] to [dest], streaming SHA-256 + progress, then verify against [rootfsSha256]. */
    private suspend fun downloadRootfs(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} fetching rootfs")
            val total = conn.contentLengthLong
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        downloaded += n
                        _downloadProgress.value = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(rootfsSha256, ignoreCase = true)) {
                dest.delete()
                error("rootfs checksum mismatch (expected $rootfsSha256, got $hex)")
            }
        } finally { conn.disconnect() }
    }

    private suspend fun ensureShellForDisplay() = withContext(NonCancellable) {
        mutationMutex.withLock { ensureShell() }
    }

    override fun installPackage(name: String) {
        if (_isBusy.value) return
        sandboxScope.launch {
            _terminalOutput.value = ""
            _isBusy.value = true
            lastError = null
            try {
                val ok = apkInstall(name) { _terminalOutput.value += it + "\n" }
                ensureShellForDisplay()
                _packageList.value = apkList()
                _terminalOutput.value += if (ok) "✓ Installed $name\n" else "✗ Failed\n"
                emitSnackbar(if (ok) context.getString(R.string.sandbox_snackbar_installed, name) else context.getString(R.string.sandbox_snackbar_install_failed, name))
            } catch (e: Throwable) { ensureShellForDisplay()
                _packageList.value = apkList()
                _terminalOutput.value += "✗ Error: ${e.message}\n"
                emitSnackbar(context.getString(R.string.sandbox_snackbar_error, e.message ?: ""))
            } finally { _isBusy.value = false }
        }
    }

    override fun removePackage(name: String) {
        if (_isBusy.value) return
        sandboxScope.launch {
            _terminalOutput.value = ""
            _isBusy.value = true
            lastError = null
            try {
                val ok = apkDelete(name)
                _terminalOutput.value += if (ok) "✓ Removed $name\n" else "✗ Failed to remove $name\n"
                emitSnackbar(if (ok) context.getString(R.string.sandbox_snackbar_removed, name) else context.getString(R.string.sandbox_snackbar_remove_failed, name))
            } catch (e: Throwable) {
                _terminalOutput.value += "✗ Error: ${e.message}\n"
                emitSnackbar(context.getString(R.string.sandbox_snackbar_error, e.message ?: ""))
            } finally { ensureShellForDisplay(); _isBusy.value = false; _packageList.value = apkList() }
        }
    }

    override fun upgradePackages() {
        if (_isBusy.value) return
        sandboxScope.launch {
            _terminalOutput.value = ""
            _isBusy.value = true
            lastError = null
            try {
                val upgraded = apkUpgrade { _terminalOutput.value += it + "\n" }
                ensureShellForDisplay()
                _packageList.value = apkList()
                val ok = lastError == null
                _terminalOutput.value += when {
                    upgraded > 0 -> "✓ Upgraded $upgraded packages\n"
                    ok -> "✓ Packages already up to date\n"
                    else -> "✗ Upgrade failed\n"
                }
                emitSnackbar(when {
                    upgraded > 0 -> context.getString(R.string.sandbox_snackbar_upgrade_done, upgraded)
                    ok -> context.getString(R.string.sandbox_snackbar_upgrade_none)
                    else -> context.getString(R.string.sandbox_snackbar_upgrade_failed)
                })
            } catch (e: Throwable) {
                _terminalOutput.value += "✗ Error: ${e.message}\n"
                emitSnackbar(context.getString(R.string.sandbox_snackbar_error, e.message ?: ""))
            } finally { ensureShellForDisplay(); _isBusy.value = false; _packageList.value = apkList() }
        }
    }

    override fun getSandboxHomeDir(): File? = homeMountDir

    override fun resolveHostFile(path: String): File = resolvePath(path)

    override suspend fun reset(): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        // Quiesce manager jobs before deletion; cancellation alone does not finish their IO.
        val previousScope = sandboxScope
        previousScope.cancel()
        previousScope.coroutineContext[Job]?.join()
        mutationMutex.withLock {
            // New jobs can only enter after reset releases the same rootfs boundary.
            sandboxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            _terminalOutput.value = ""
            _packageList.value = emptyList()
            try {
                for (i in 1..3) {
                    rootfsDir.deleteRecursively()
                    if (!rootfsDir.exists()) break
                    kotlinx.coroutines.delay(200)
                }
                check(!rootfsDir.exists()) { "Cannot delete sandbox rootfs" }
                prootBin.delete()
                emitSnackbar(context.getString(R.string.sandbox_snackbar_reset))
                true
            } catch (e: Throwable) { emitSnackbar(context.getString(R.string.sandbox_snackbar_reset_failed)); false }
        }
    }

    // ── Shell Execution ─────────────────────────────────

    /** Path to proot binary, extracted from assets — Termux-style. */
    private val prootBin: File = File(context.filesDir, "bin/proot")

    private val prootPath: String by lazy {
        "${context.applicationInfo.nativeLibraryDir}/libproot_exec.so"
    }

    // Copy libtalloc.so -> libtalloc.so.2 in writable dir for linker resolution.
    // Android linker searches by exact filename, not SONAME.
    // Kai's proot DT_NEEDED is "libtalloc.so.2" but jniLibs has "libtalloc.so".
    private val tallocDir: File by lazy {
        File(context.filesDir, "lib").apply { mkdirs() }
    }
    private fun ensureTalloc(): String {
        val src = File(context.applicationInfo.nativeLibraryDir, "libtalloc.so")
        val dst = File(tallocDir, "libtalloc.so.2")
        if (!dst.exists() && src.exists()) {
            src.copyTo(dst)
        }
        return tallocDir.absolutePath
    }

    private suspend fun executeRaw(command: String, workdir: String = homeMountPath, timeoutMs: Int = 30000): SandboxManager.SandboxResult {
        ensureShell()
        ensureSandboxMountTargets()
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }.absolutePath
        val resolvedWorkdir = workdir.ifBlank { homeMountPath }
        val args = mutableListOf(prootPath,
            "--rootfs=" + rootfsDir.absolutePath,
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=${homeMountDir.absolutePath}:$homeMountPath",
        ).apply {
            sharedStorageHostDir()?.let { host ->
                add("--bind=${host.absolutePath}:$sharedStorageMountPath")
            }
            addAll(listOf(
            "-w", resolvedWorkdir,
            "-0", "--link2symlink", "--kill-on-exit", "-L",
            "/bin/sh", "-c", command
            ))
        }
        return try {
            val libDir = context.applicationInfo.nativeLibraryDir
            val tallocLibDir = ensureTalloc()
            val ldPath = "$tallocLibDir:$libDir"
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            pb.environment()["PROOT_LOADER"] = "$libDir/libproot_loader.so"
            pb.environment()["PROOT_TMP_DIR"] = tmpDir
            pb.environment()["HOME"] = homeMountPath
            pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            val p = pb.start()
            coroutineScope {
                val output = async(Dispatchers.IO) {
                    p.inputStream.use { it.readBoundedShellOutput() }
                }
                try {
                    val exitCode = withTimeoutOrNull(timeoutMs.toLong().coerceAtLeast(1L)) {
                        var code: Int? = null
                        while (code == null) {
                            code = runCatching { p.exitValue() }.getOrNull()
                            if (code == null) delay(PROCESS_POLL_INTERVAL_MS)
                        }
                        code
                    }
                    if (exitCode == null) {
                        p.destroy()
                        runCatching { p.inputStream.close() }
                        val partial = try {
                            withTimeoutOrNull(PROCESS_OUTPUT_CLOSE_GRACE_MS) { output.await() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        output.cancel()
                        SandboxManager.SandboxResult(
                            stdout = partial?.first.orEmpty(),
                            stderr = "Timed out",
                            exitCode = -1,
                            warning = partial?.second?.takeIf { it }?.let {
                                "Local Sandbox output was truncated at $SHELL_COMMAND_OUTPUT_MAX_BYTES UTF-8 bytes."
                            },
                        )
                    } else {
                        val (stdout, truncated) = output.await()
                        SandboxManager.SandboxResult(
                            stdout = stdout,
                            stderr = "",
                            exitCode = exitCode,
                            warning = truncated.takeIf { it }?.let {
                                "Local Sandbox output was truncated at $SHELL_COMMAND_OUTPUT_MAX_BYTES UTF-8 bytes."
                            },
                        )
                    }
                } catch (error: Throwable) {
                    p.destroy()
                    runCatching { p.inputStream.close() }
                    output.cancel()
                    throw error
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            SandboxManager.SandboxResult("", e.message ?: "proot failed", -1)
        }
    }

    override suspend fun executeCommand(
        command: String,
        workdir: String,
        timeoutMs: Int,
    ): SandboxManager.SandboxResult = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (!prepareAvailability()) return@withLock SandboxManager.SandboxResult("", "Sandbox not installed", -1)
            executeRaw(command, workdir.ifBlank { homeMountPath }, timeoutMs)
        }
    }

    // ── File Operations ────────────────────────────────

    override suspend fun fileRead(
        path: String,
        offset: Long,
        limit: Long,
    ): ShellFileReadResult = pathResolver.fileRead(path, offset, limit)

    override suspend fun fileWrite(path: String, content: String, expectedSha256: String): String? =
        pathResolver.fileWrite(path, content, mutationMutex, expectedSha256)

    override suspend fun fileGlob(
        pattern: String,
        basePath: String,
        depth: Int?,
    ): Pair<List<String>, Boolean> = pathResolver.fileGlob(pattern, basePath, homeMountPath, depth)

    override suspend fun fileGrep(
        pattern: String,
        basePath: String,
        fileGlob: String,
        options: ShellGrepOptions,
    ): Result<ShellGrepResult> =
        pathResolver.fileGrep(pattern, basePath, homeMountPath, fileGlob, options)

    override suspend fun fileEdit(
        path: String,
        edits: List<ShellFileEdit>,
        expectedSha256: String,
    ): ShellFileEditResult =
        pathResolver.fileEdit(path, edits, mutationMutex, expectedSha256)

    // ── Package Management ──────────────────────────────
    // Downloads target + all transitive deps + stale base-package upgrades via
    // Android HTTP (works with VPN/Clash), then single apk add --no-network.

    override suspend fun apkInstall(packageName: String, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (!prepareAvailability()) { onProgress("Sandbox not installed"); return@withLock false }
            val requested = try {
                sanitizePackageName(packageName)
            } catch (e: IllegalArgumentException) {
                onProgress("FAIL: ${e.message}")
                lastError = e.message
                return@withLock false
            }
            lastError = null
            ensurePackageMetadata()

            // 1. Download + parse repo index
            onProgress("Fetching package index...")
            val indexUrl = "$alpineMirror/aarch64/APKINDEX.tar.gz"
            val indexFile = File(context.filesDir, "APKINDEX.tar.gz")
            try {
                val conn = URL(indexUrl).openConnection() as HttpURLConnection
                onProgress("Connecting to ${conn.url.host}...")
                val code = conn.responseCode
                onProgress("HTTP $code (${conn.contentLength} bytes)")
                if (code != 200) { onProgress("FAIL: HTTP $code"); lastError = "HTTP $code from $indexUrl"; return@withLock false }
                conn.inputStream.use { i -> indexFile.outputStream().use { o -> i.copyTo(o) } }
            }
            catch (e: Throwable) { onProgress("FAIL: ${e.javaClass.simpleName}: ${e.message}"); lastError = "${e.javaClass.simpleName}: ${e.message}"; return@withLock false }

            val installed = readInstalledVersions()
            val repoPkgs: Map<String, FullPkgEntry>
            val soToPkg: Map<String, String>
            try {
                val (r, s) = parseFullApkIndex(indexFile, preferredPackages = installed.keys)
                repoPkgs = r; soToPkg = s
            } catch (e: Throwable) {
                onProgress("FAIL: parse index — ${e.javaClass.simpleName}: ${e.message}")
                lastError = "Parse index: ${e.message}"; indexFile.delete(); return@withLock false
            } finally { indexFile.delete() }

            if (requested !in repoPkgs) {
                onProgress("FAIL: package '$requested' not found in index")
                lastError = "Not found: $requested"; return@withLock false
            }

            // 3. Recursively resolve target + transitive deps.
            // Install if missing; upgrade if repo is newer; NEVER downgrade.
            // Downgrading breaks version constraints of packages that were
            // compiled against a newer version in the rootfs.
            val toInstall = collectAlpinePackageChanges(listOf(requested), repoPkgs, soToPkg, installed)
            onProgress("${toInstall.size} packages to install")

            if (toInstall.isEmpty()) {
                addExplicitPackage(requested)
                onProgress("$requested is already installed and up to date.")
                return@withLock true
            }

            // 4. Download all .apk files
            val tmpDir = File(rootfsDir, "tmp"); tmpDir.listFiles()?.forEach { it.delete() }; tmpDir.mkdirs()
            val paths = mutableListOf<String>()
            for (name in toInstall) {
                val ver = repoPkgs[name]?.version ?: continue
                val fn = "$name-$ver.apk"; val f = File(context.filesDir, fn)
                if (!f.exists() || f.length() == 0L) {
                    onProgress("Downloading $fn...")
                    try {
                        val conn = URL("$alpineMirror/aarch64/$fn").openConnection() as HttpURLConnection
                        if (conn.responseCode != 200) { onProgress("HTTP ${conn.responseCode}"); lastError = "HTTP ${conn.responseCode}: $fn"; tmpDir.listFiles()?.forEach { it.delete() }; return@withLock false }
                        conn.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    } catch (ex: Throwable) { onProgress("FAIL: ${ex.message}"); lastError = "Download: ${ex.message}"; tmpDir.listFiles()?.forEach { it.delete() }; return@withLock false }
                }
                val dst = File(tmpDir, fn); f.copyTo(dst, true); f.delete(); paths.add("/tmp/$fn")
            }

            // Install the complete dependency closure as one apk transaction.
            // Splitting shell providers first leaves the main transaction incomplete.
            onProgress("Installing ${paths.size} packages...")
            val result = if (paths.isNotEmpty()) {
                executeRaw("apk add --allow-untrusted --no-network ${paths.joinToString(" ") { shellQuote(it) }}", timeoutMs = 120000)
            } else {
                SandboxManager.SandboxResult("", "", 0)
            }
            onProgress(result.stdout)
            if (result.stderr.isNotBlank()) onProgress(result.stderr)
            onProgress("apk exit code: ${result.exitCode}")
            tmpDir.listFiles()?.forEach { it.delete() }
            // Verify install — apk may return non-zero on minor post-install script errors
            val installedOk = requested in readInstalledVersions()
            if (!installedOk) { lastError = result.stderr.ifBlank { result.stdout }; return@withLock false }
            addExplicitPackage(requested)
            true
        }
    }

    override suspend fun apkList(): List<SandboxManager.PackageInfo> = withContext(Dispatchers.IO) {
        if (!isAvailable()) { _terminalOutput.value += "[apkList: isAvailable=false]\n"; return@withContext emptyList() }
        try {
            val db = File(rootfsDir, "lib/apk/db/installed")
            if (!db.exists()) { _terminalOutput.value += "[apkList: DB not found at ${db.absolutePath}]\n"; return@withContext emptyList() }
            val content = db.readText(Charsets.UTF_8)
            val pkgs = mutableListOf<SandboxManager.PackageInfo>()
            var n = ""; var v = ""; var d = ""
            content.lines().forEach { line ->
                if (line.startsWith("P:")) n = line.substring(2).trim()
                else if (line.startsWith("V:")) v = line.substring(2).trim()
                else if (line.startsWith("T:")) d = line.substring(2).trim()
                else if (line.isBlank()) { if (n.isNotBlank()) { pkgs.add(SandboxManager.PackageInfo(name = n, version = v, description = d)); n = ""; v = ""; d = "" } }
            }
            if (n.isNotBlank()) pkgs.add(SandboxManager.PackageInfo(name = n, version = v, description = d))
            if (pkgs.isEmpty()) _terminalOutput.value += "[apkList: parsed 0 from ${content.length}B]\n"
            pkgs
        } catch (e: Throwable) { _terminalOutput.value += "[apkList: ${e.message}]\n"; emptyList() }
    }

    override suspend fun apkDelete(packageName: String): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (!prepareAvailability()) { _terminalOutput.value += "Sandbox not available\n"; return@withLock false }
            val requested = try {
                sanitizePackageName(packageName)
            } catch (e: IllegalArgumentException) {
                _terminalOutput.value += "FAIL: ${e.message}\n"
                lastError = e.message
                return@withLock false
            }
            lastError = null
            ensurePackageMetadata()
            val installedBefore = readInstalledVersions()
            _terminalOutput.value += "DB has package: ${requested in installedBefore}\n"
            if (requested !in installedBefore) {
                val explicit = readExplicitPackages().apply { remove(requested) }
                writeExplicitPackages(explicit)
                normalizeWorld(explicit)
                return@withLock true
            }

            if (packageMetadata.isBasePackage(requested)) {
                lastError = "Refusing to remove base package: $requested"
                _terminalOutput.value += "${lastError}\n"
                return@withLock false
            }

            val previousExplicit = readExplicitPackages()
            val nextExplicit = previousExplicit.toMutableSet().apply { remove(requested) }.toSet()
            writeExplicitPackages(nextExplicit)
            normalizeWorld(nextExplicit)

            _terminalOutput.value += "Running: apk del $requested\n"
            val result = executeRaw("apk del ${shellQuote(requested)}", timeoutMs = 60000)
            _terminalOutput.value += result.stdout
            _terminalOutput.value += if (result.exitCode == 0) "Exit: 0\n" else "Exit: ${result.exitCode}\n"
            val removed = requested !in readInstalledVersions()
            if (!removed) {
                writeExplicitPackages(previousExplicit)
                normalizeWorld(previousExplicit)
                lastError = result.stderr.ifBlank { result.stdout }.ifBlank { "Package was not removed: $requested" }
                return@withLock false
            }
            normalizeWorld()
            result.exitCode == 0 || removed
        }
    }

    override suspend fun apkUpgrade(onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (!prepareAvailability()) return@withLock 0
            lastError = null
            ensurePackageMetadata()

            // 1. Download + parse APKINDEX
            onProgress("Fetching package index...")
            val indexUrl = "$alpineMirror/aarch64/APKINDEX.tar.gz"
            val indexFile = File(context.filesDir, "APKINDEX_UPGRADE.tar.gz")
            try {
                val conn = URL(indexUrl).openConnection() as HttpURLConnection
                if (conn.responseCode != 200) { onProgress("HTTP ${conn.responseCode}"); lastError = "HTTP ${conn.responseCode} from $indexUrl"; return@withLock 0 }
                conn.inputStream.use { i -> indexFile.outputStream().use { o -> i.copyTo(o) } }
            } catch (e: Throwable) { onProgress("FAIL: ${e.message}"); lastError = e.message; return@withLock 0 }

            val installed = readInstalledVersions()
            val repoPkgs: Map<String, FullPkgEntry>
            val soToPkg: Map<String, String>
            try {
                val (r, s) = parseFullApkIndex(indexFile, preferredPackages = installed.keys)
                repoPkgs = r; soToPkg = s
            } catch (e: Throwable) {
                onProgress("FAIL: parse index — ${e.javaClass.simpleName}: ${e.message}"); lastError = "Parse index: ${e.message}"; indexFile.delete(); return@withLock 0
            } finally { indexFile.delete() }

            // 3. Collect installed packages where repo has a newer version
            val toUpgrade = linkedSetOf<String>()
            for ((name, instVer) in installed) {
                val repoEntry = repoPkgs[name] ?: continue
                if (compareAlpineVersions(repoEntry.version, instVer) > 0) toUpgrade.add(name)
            }
            if (toUpgrade.isEmpty()) { onProgress("All packages up to date."); return@withLock 0 }

            // 4. Recursively add transitive deps of upgradable packages
            val toInstall = collectAlpinePackageChanges(toUpgrade, repoPkgs, soToPkg, installed)
            onProgress("${toInstall.size} packages to upgrade")

            // 5. Download + install (same pattern as apkInstall)
            val tmpDir = File(rootfsDir, "tmp"); tmpDir.listFiles()?.forEach { it.delete() }; tmpDir.mkdirs()
            val paths = mutableListOf<String>()
            for (name in toInstall) {
                val ver = repoPkgs[name]?.version ?: continue
                val fn = "$name-$ver.apk"; val f = File(context.filesDir, fn)
                if (!f.exists() || f.length() == 0L) {
                    onProgress("Downloading $fn...")
                    try {
                        val conn = URL("$alpineMirror/aarch64/$fn").openConnection() as HttpURLConnection
                        if (conn.responseCode != 200) {
                            onProgress("HTTP ${conn.responseCode}")
                            lastError = "HTTP ${conn.responseCode}: $fn"
                            tmpDir.listFiles()?.forEach { it.delete() }
                            return@withLock 0
                        }
                        conn.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    } catch (ex: Throwable) { onProgress("FAIL: ${ex.message}"); lastError = "Download: ${ex.message}"; tmpDir.listFiles()?.forEach { it.delete() }; return@withLock 0 }
                }
                val dst = File(tmpDir, fn); f.copyTo(dst, true); f.delete(); paths.add("/tmp/$fn")
            }

            onProgress("Installing ${paths.size} packages...")
            val result = executeRaw("apk add --allow-untrusted --no-network ${paths.joinToString(" ") { shellQuote(it) }}", timeoutMs = 300000)
            onProgress(result.stdout)
            if (result.stderr.isNotBlank()) onProgress(result.stderr)
            onProgress("apk exit code: ${result.exitCode}")
            tmpDir.listFiles()?.forEach { it.delete() }
            normalizeWorld()
            val after = readInstalledVersions()
            val upgradedCount = toUpgrade.count { name ->
                val beforeVersion = installed[name]
                val afterVersion = after[name]
                beforeVersion != null && afterVersion != null && compareAlpineVersions(afterVersion, beforeVersion) > 0
            }
            if (result.exitCode != 0 && upgradedCount == 0) {
                lastError = result.stderr.ifBlank { result.stdout }.ifBlank { "Upgrade failed" }
                return@withLock 0
            }
            upgradedCount
        }
    }

    override suspend fun getDiskUsageMB(): Long = withContext(Dispatchers.IO) {
        try { rootfsDir.walkTopDown().sumOf { it.length() } / (1024 * 1024) } catch (_: Throwable) { 0L }
    }

    // ── Helpers ────────────────────────────────────────

    private fun sanitizePackageName(packageName: String): String =
        packageMetadata.sanitizePackageName(packageName)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun readInstalledVersions(): LinkedHashMap<String, String> = packageMetadata.readInstalledVersions()
    private fun captureBaseWorld(force: Boolean = false) = packageMetadata.captureBaseWorld(force)
    private fun readExplicitPackages(): LinkedHashSet<String> = packageMetadata.readExplicitPackages()
    private fun writeExplicitPackages(packages: Collection<String>) = packageMetadata.writeExplicitPackages(packages)
    private fun ensurePackageMetadata() = packageMetadata.ensurePackageMetadata()
    private fun normalizeWorld(explicitPackages: Set<String> = readExplicitPackages()) = packageMetadata.normalizeWorld(explicitPackages)
    private fun addExplicitPackage(packageName: String) = packageMetadata.addExplicitPackage(packageName)

    private fun ensureSandboxMountTargets() = pathResolver.ensureSandboxMountTargets()
    private fun resolveSandboxPath(path: String): ResolvedSandboxPath = pathResolver.resolveSandboxPath(path)
    private fun resolvePath(path: String): File = pathResolver.resolvePath(path)

    private fun sharedStorageHostDir(): File? {
        if (!settings.sandboxSharedStorageEnabled.value) return null
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return null
        return Environment.getExternalStorageDirectory()
            ?.canonicalFile
            ?.takeIf { it.isDirectory && it.canRead() }
    }

    private companion object {
        const val PROCESS_POLL_INTERVAL_MS = 25L
        const val PROCESS_OUTPUT_CLOSE_GRACE_MS = 250L
    }
}
