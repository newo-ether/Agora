package com.newoether.agora.sandbox

import android.content.Context
import android.os.Build
import android.os.Environment
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.ShellFileEdit
import com.newoether.agora.util.ShellFileEditResult
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.util.ShellGrepOptions
import com.newoether.agora.util.ShellGrepResult
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProotSandboxManager(
    private val context: Context,
    private val settings: SettingsRepository,
) : SandboxManager {
    // One authority serializes rootfs selection, commands and filesystem/package mutations.
    private val mutationMutex = Mutex()
    private val operationLock = Any()
    private var resetting = false
    private var sandboxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selectionFile = File(context.filesDir, "sandbox-distribution")
    private val _distribution = MutableStateFlow(readDistribution())
    override val distribution: StateFlow<SandboxDistribution> = _distribution.asStateFlow()
    private val _terminalOutput = MutableStateFlow("")
    override val terminalOutput = _terminalOutput.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    override val isBusy = _isBusy.asStateFlow()
    private val _isInstallingRootfs = MutableStateFlow(false)
    override val isInstallingRootfs = _isInstallingRootfs.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    override val downloadProgress = _downloadProgress.asStateFlow()
    private val _packageList = MutableStateFlow<List<SandboxManager.PackageInfo>>(emptyList())
    override val packageList = _packageList.asStateFlow()
    private val snackbarMessages = Channel<String>(Channel.UNLIMITED)
    override val snackbarMessage = snackbarMessages.receiveAsFlow()
    override var pendingPkgName = ""
    override var lastError: String? = null

    private val homeMountDir = File(context.filesDir, "sandbox-home")
    private val homeMountPath = "/home/agora"
    private val sharedStorageMountPath = "/mnt/shared"
    private fun rootfs(distribution: SandboxDistribution = _distribution.value) =
        File(context.filesDir, "${distribution.id}-rootfs")

    private fun resolver(distribution: SandboxDistribution = _distribution.value) = SandboxPathResolver(
        rootfs(distribution), homeMountDir, homeMountPath, sharedStorageMountPath, ::sharedStorageHostDir,
    )

    private fun runner(distribution: SandboxDistribution = _distribution.value) =
        ProotProcessRunner(context, rootfs(distribution), homeMountDir, ::sharedStorageHostDir)

    private val shellJobs by lazy {
        SandboxJobStore(
            File(context.filesDir, "sandbox-jobs"), mutationMutex,
            processBuilder = { command, workdir ->
                check(prepareAvailability()) { lastError ?: "Sandbox not installed" }
                runner().builder(command, workdir)
            },
            recoverProcess = { identity -> runner().interruptOrphan(identity) },
        )
    }

    private fun readDistribution(): SandboxDistribution {
        if (!selectionFile.isFile) return SandboxDistribution.ALPINE
        val id = selectionFile.readText(Charsets.UTF_8).trim()
        return SandboxDistribution.entries.firstOrNull { it.id == id }
            ?: error("Unknown sandbox distribution: $id")
    }

    override suspend fun selectDistribution(distribution: SandboxDistribution): Boolean = withContext(Dispatchers.IO) {
        if (distribution == _distribution.value) return@withContext true
        try {
            shellJobs.whileIdle {
                check(mutationMutex.tryLock()) { "Sandbox files are in use" }
                try {
                    synchronized(operationLock) {
                        check(!resetting && !_isBusy.value && !_isInstallingRootfs.value) { "Sandbox operation is in progress" }
                        val temporary = File(context.filesDir, "sandbox-distribution.tmp")
                        try {
                            FileOutputStream(temporary).use { output ->
                                output.write(distribution.id.toByteArray(Charsets.UTF_8))
                                output.fd.sync()
                            }
                            Files.move(temporary.toPath(), selectionFile.toPath(),
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } finally {
                            temporary.delete()
                        }
                        _packageList.value = emptyList()
                        _terminalOutput.value = ""
                        pendingPkgName = ""
                        lastError = null
                        _distribution.value = distribution
                    }
                } finally {
                    mutationMutex.unlock()
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastError = if (error is IllegalStateException) {
                context.getString(R.string.sandbox_distribution_busy)
            } else error.message
            false
        }
    }

    private fun shellExists(distribution: SandboxDistribution): Boolean =
        runCatching { resolver(distribution).resolvePath("/bin/sh").isFile }.getOrDefault(false)

    private fun linkerExists(distribution: SandboxDistribution, pathsResolver: SandboxPathResolver = resolver(distribution)): Boolean {
        val paths = if (distribution.usesApt) {
            listOf("/lib/ld-linux-aarch64.so.1", "/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
                "/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        } else listOf("/lib/ld-musl-aarch64.so.1", "/usr/lib/ld-musl-aarch64.so.1")
        return paths.any { runCatching { pathsResolver.resolvePath(it).isFile }.getOrDefault(false) }
    }

    private fun ensureShell(): Boolean {
        val selected = _distribution.value
        if (shellExists(selected)) return true
        if (selected != SandboxDistribution.ALPINE) return false
        return try {
            val paths = resolver(selected)
            val busybox = paths.resolvePath("/bin/busybox")
            if (!busybox.isFile) return false
            val sh = File(rootfs(selected), "bin/sh")
            sh.delete()
            busybox.copyTo(sh)
            sh.setExecutable(true)
            true
        } catch (_: Exception) { false }
    }

    override fun isAvailableSync(): Boolean {
        val selected = _distribution.value
        return rootfs(selected).isDirectory && shellExists(selected) && linkerExists(selected)
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        shellJobs.recover()
        mutationMutex.withLock { prepareAvailability() }
    }

    /** Caller holds the mutation boundary, including these small legacy repair writes. */
    private fun prepareAvailability(): Boolean {
        if (!rootfs().isDirectory) { lastError = "Sandbox rootfs not installed"; return false }
        if (!ensureShell()) { lastError = "/bin/sh missing"; return false }
        if (!linkerExists(_distribution.value)) { lastError = "Sandbox dynamic linker missing"; return false }
        resolver().ensureSandboxMountTargets()
        if (_distribution.value.usesApt) aptPackages().prepare()
        else AlpinePackageMetadataStore(rootfs()).ensurePackageMetadata()
        val passwd = resolver().resolvePath("/etc/passwd")
        if (passwd.isFile) {
            val content = passwd.readText()
            val updated = content.replace(Regex("^(root:[^:]*:0:0:[^:]*:)/root(:)", RegexOption.MULTILINE),
                "$1/home/agora$2")
            if (updated != content) passwd.writeText(updated)
        }
        return true
    }

    override suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        shellJobs.recover()
        mutationMutex.withLock {
            val selected = _distribution.value
            val destination = rootfs(selected)
            val staging = File(context.filesDir, "${selected.id}-rootfs.installing")
            try {
                if (destination.exists()) {
                    check(isAvailableSync()) { "Existing sandbox is incomplete; reset this distribution before reinstalling" }
                    return@withLock true
                }
                deleteSandboxTree(staging)
                check(staging.mkdirs()) { "Cannot create sandbox staging directory" }
                ProotRootfsInstaller().install(selected, staging,
                    progress = { _downloadProgress.value = it },
                    output = { _terminalOutput.value += "$it\n" })
                val paths = SandboxPathResolver(staging, homeMountDir, homeMountPath,
                    sharedStorageMountPath, ::sharedStorageHostDir)
                check(paths.resolvePath("/bin/sh").isFile) { "Installed sandbox has no shell" }
                check(linkerExists(selected, paths)) { "Installed sandbox has no dynamic linker" }
                if (!selected.usesApt) {
                    AlpinePackageMetadataStore(staging).apply {
                        captureBaseWorld(force = true)
                        writeExplicitPackages(emptySet())
                    }
                }
                currentCoroutineContext().ensureActive()
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                check(prepareAvailability()) { lastError ?: "Installed sandbox is incomplete" }
                lastError = null
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error.message
                _terminalOutput.value += "Installation failed: ${error.message}\n"
                false
            } finally {
                withContext(NonCancellable) { deleteSandboxTree(staging) }
            }
        }
    }

    override fun installRootfs() {
        launchOperation(installing = true) {
            _terminalOutput.value = ""
            _downloadProgress.value = null
            _packageList.value = emptyList()
            if (install()) refreshPackageList()
        }
    }

    private fun launchOperation(installing: Boolean = false, action: suspend () -> Unit) {
        synchronized(operationLock) {
            if (resetting || _isBusy.value || _isInstallingRootfs.value) return
            _isBusy.value = true
            _isInstallingRootfs.value = installing
            sandboxScope.launch(start = CoroutineStart.DEFAULT) {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastError = error.message
                    _terminalOutput.value += "Error: ${error.message}\n"
                    emitSnackbar(context.getString(R.string.sandbox_snackbar_error, error.message ?: ""))
                } finally {
                    synchronized(operationLock) {
                        _isBusy.value = false
                        _isInstallingRootfs.value = false
                        _downloadProgress.value = null
                    }
                }
            }
        }
    }

    override fun installPackage(name: String) = launchOperation {
        _terminalOutput.value = ""
        val ok = apkInstall(name) { _terminalOutput.value += "$it\n" }
        refreshPackageList()
        _terminalOutput.value += if (ok) "✓ Done\n" else "✗ Failed\n"
        emitSnackbar(context.getString(if (ok) R.string.sandbox_snackbar_installed
            else R.string.sandbox_snackbar_install_failed, name))
    }

    override fun removePackage(name: String) = launchOperation {
        _terminalOutput.value = ""
        val ok = apkDelete(name)
        refreshPackageList()
        emitSnackbar(context.getString(if (ok) R.string.sandbox_snackbar_removed
            else R.string.sandbox_snackbar_remove_failed, name))
    }

    override fun upgradePackages() = launchOperation {
        _terminalOutput.value = ""
        val upgraded = apkUpgrade { _terminalOutput.value += "$it\n" }
        refreshPackageList()
        emitSnackbar(when {
            lastError != null -> context.getString(R.string.sandbox_snackbar_upgrade_failed)
            upgraded > 0 -> context.getString(R.string.sandbox_snackbar_upgrade_done, upgraded)
            else -> context.getString(R.string.sandbox_snackbar_upgrade_none)
        })
    }

    private fun emitSnackbar(message: String) = check(snackbarMessages.trySend(message).isSuccess)

    override suspend fun refreshPackageList() = withContext(Dispatchers.IO) {
        shellJobs.recover()
        mutationMutex.withLock { _packageList.value = if (prepareAvailability()) readPackages() else emptyList() }
    }

    override fun getSandboxHomeDir(): File = homeMountDir
    override fun resolveHostFile(path: String): File = resolver().resolvePath(path)
    override fun resolveHostFile(path: String, distribution: SandboxDistribution): File =
        resolver(distribution).resolvePath(path)

    override suspend fun reset(): Boolean = shellJobs.reset {
        val previous = synchronized(operationLock) {
            resetting = true
            _isBusy.value = true
            sandboxScope.also { it.cancel() }
        }
        previous.coroutineContext[Job]?.join()
        synchronized(operationLock) { _isBusy.value = true }
        mutationMutex.withLock {
            try {
                deleteSandboxTree(rootfs())
                _terminalOutput.value = ""
                _packageList.value = emptyList()
                lastError = null
                emitSnackbar(context.getString(R.string.sandbox_snackbar_reset))
                true
            } catch (error: Exception) {
                lastError = error.message
                emitSnackbar(context.getString(R.string.sandbox_snackbar_reset_failed))
                false
            } finally {
                synchronized(operationLock) {
                    sandboxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    resetting = false
                    _isBusy.value = false
                    _isInstallingRootfs.value = false
                }
            }
        }
    }

    private suspend fun executeRaw(command: String, workdir: String = homeMountPath,
        timeoutMs: Int = 30000): SandboxManager.SandboxResult {
        return try {
            SandboxProcessRunner.execute(runner().builder(command, workdir), timeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SandboxManager.SandboxResult("", error.message ?: "proot failed", -1)
        }
    }

    override suspend fun startJob(command: String, workdir: String, timeoutMs: Int, description: String): String {
        check(isAvailableSync()) { "Sandbox not installed" }
        return shellJobs.start(command, workdir, timeoutMs, description)
    }
    override suspend fun listJobs(offset: Int, limit: Int): String = shellJobs.list(offset, limit)
    override suspend fun getJob(jobId: String): String = shellJobs.get(jobId)
    override suspend fun stopJob(jobId: String): String = shellJobs.stop(jobId)
    override suspend fun acknowledgeJob(jobId: String): String = shellJobs.acknowledge(jobId)

    override suspend fun executeCommand(command: String, workdir: String, timeoutMs: Int): SandboxManager.SandboxResult =
        withContext(Dispatchers.IO) {
            shellJobs.recover()
            mutationMutex.withLock {
                if (!prepareAvailability()) return@withLock SandboxManager.SandboxResult("", "Sandbox not installed", -1)
                executeRaw(command, workdir.ifBlank { homeMountPath }, timeoutMs)
            }
        }

    override suspend fun fileRead(path: String, offset: Long, limit: Long): ShellFileReadResult =
        resolver().fileRead(path, offset, limit)

    override suspend fun fileWrite(path: String, content: String, expectedSha256: String): String? {
        shellJobs.recover()
        return mutationMutex.withLock {
            resolver().fileWrite(path, content, expectedSha256)
        }
    }

    override suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Pair<List<String>, Boolean> =
        resolver().fileGlob(pattern, basePath, homeMountPath, depth)

    override suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String,
        options: ShellGrepOptions): Result<ShellGrepResult> =
        resolver().fileGrep(pattern, basePath, homeMountPath, fileGlob, options)

    override suspend fun fileEdit(path: String, edits: List<ShellFileEdit>, expectedSha256: String): ShellFileEditResult {
        shellJobs.recover()
        return mutationMutex.withLock { resolver().fileEdit(path, edits, expectedSha256) }
    }

    private fun alpinePackages() = ProotAlpinePackages(rootfs(), context.filesDir) { command, timeout ->
        executeRaw(command, timeoutMs = timeout)
    }
    private fun aptPackages() = ProotAptPackages(rootfs()) { command, timeout ->
        executeRaw(command, timeoutMs = timeout)
    }
    private fun readPackages(): List<SandboxManager.PackageInfo> =
        if (_distribution.value.usesApt) aptPackages().list() else alpinePackages().list()

    override suspend fun apkList(): List<SandboxManager.PackageInfo> = withContext(Dispatchers.IO) {
        shellJobs.recover()
        mutationMutex.withLock { if (prepareAvailability()) readPackages() else emptyList() }
    }

    override suspend fun apkInstall(packageName: String, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        shellJobs.recover()
        mutationMutex.withLock {
            if (!prepareAvailability()) return@withLock false
            if (_distribution.value.usesApt) aptPackages().let {
                it.install(packageName, onProgress).also { _ -> lastError = it.lastError }
            } else alpinePackages().let {
                it.install(packageName, onProgress).also { _ -> lastError = it.lastError }
            }
        }
    }

    override suspend fun apkDelete(packageName: String): Boolean = withContext(Dispatchers.IO) {
        shellJobs.recover()
        mutationMutex.withLock {
            if (!prepareAvailability()) return@withLock false
            val output: (String) -> Unit = { _terminalOutput.value += "$it\n" }
            if (_distribution.value.usesApt) aptPackages().let {
                it.remove(packageName, output).also { _ -> lastError = it.lastError }
            } else alpinePackages().let {
                it.remove(packageName, output).also { _ -> lastError = it.lastError }
            }
        }
    }

    override suspend fun apkUpgrade(onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        shellJobs.recover()
        mutationMutex.withLock {
            if (!prepareAvailability()) return@withLock 0
            if (_distribution.value.usesApt) aptPackages().let {
                it.upgrade(onProgress).also { _ -> lastError = it.lastError }
            } else alpinePackages().let {
                it.upgrade(onProgress).also { _ -> lastError = it.lastError }
            }
        }
    }

    override suspend fun getDiskUsageMB(): Long = withContext(Dispatchers.IO) {
        sandboxPrivateUsageBytes(rootfs(), homeMountDir) / (1024 * 1024)
    }

    private fun sharedStorageHostDir(): File? {
        if (!settings.sandboxSharedStorageEnabled.value) return null
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return Environment.getExternalStorageDirectory()?.canonicalFile?.takeIf { it.isDirectory && it.canRead() }
    }
}
