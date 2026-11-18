package com.newoether.agora.sandbox

import com.newoether.agora.util.ShellFileEdit
import com.newoether.agora.util.ShellFileEditResult
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.util.ShellGrepOptions
import com.newoether.agora.util.ShellGrepResult
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * No-op stub for the Play Store flavor — Linux sandbox is not included.
 */
class PlaySandboxManager : SandboxManager {

    override val distribution: StateFlow<SandboxDistribution> = MutableStateFlow(SandboxDistribution.ALPINE)
    override suspend fun selectDistribution(distribution: SandboxDistribution): Boolean = false
    override val lastError: String? = null
    private val _terminalOutput = MutableStateFlow("Sandbox not available in this build.")
    override val terminalOutput: StateFlow<String> = _terminalOutput
    private val _isBusy = MutableStateFlow(false)
    override var pendingPkgName: String = ""
    override val isBusy: StateFlow<Boolean> = _isBusy
    private val _isInstallingRootfs = MutableStateFlow(false)
    override val isInstallingRootfs: StateFlow<Boolean> = _isInstallingRootfs
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    override val downloadProgress: StateFlow<Float?> = _downloadProgress
    private val _rootfsPhase = MutableStateFlow<RootfsInstallPhase?>(null)
    override val rootfsPhase: StateFlow<RootfsInstallPhase?> = _rootfsPhase
    override fun installRootfs() {}
    private val _packageList = MutableStateFlow<List<SandboxManager.PackageInfo>>(emptyList())
    override val packageList: StateFlow<List<SandboxManager.PackageInfo>> = _packageList
    override suspend fun refreshPackageList() {}
    override val snackbarMessage: Flow<String> = emptyFlow()
    override fun installPackage(name: String) {}
    override fun removePackage(name: String) {}
    override fun upgradePackages() {}

    override fun isAvailableSync(): Boolean = false

    override suspend fun isAvailable(): Boolean = false

    override suspend fun install(): Boolean = false

    override suspend fun executeCommand(
        command: String,
        workdir: String,
        timeoutMs: Int
    ): SandboxManager.SandboxResult = SandboxManager.SandboxResult(
        stdout = "",
        stderr = "Linux Sandbox is not available in this build.",
        exitCode = -1
    )

    override suspend fun fileRead(
        path: String,
        offset: Long,
        limit: Long
    ): ShellFileReadResult = throw UnsupportedOperationException("Sandbox not available")

    override suspend fun fileWrite(path: String, content: String, expectedSha256: String): String? =
        "Sandbox not available in this build"

    override suspend fun fileGlob(
        pattern: String,
        basePath: String,
        depth: Int?,
    ): Pair<List<String>, Boolean> = emptyList<String>() to false

    override suspend fun fileGrep(
        pattern: String,
        basePath: String,
        fileGlob: String,
        options: ShellGrepOptions,
    ): Result<ShellGrepResult> =
        Result.success(ShellGrepResult(matches = emptyList(), truncated = false))

    override suspend fun fileEdit(
        path: String,
        edits: List<ShellFileEdit>,
        expectedSha256: String,
    ): ShellFileEditResult = ShellFileEditResult(
        replacements = 0,
        error = "Sandbox not available in this build"
    )

    override suspend fun apkInstall(packageName: String, onProgress: (String) -> Unit): Boolean { onProgress("Sandbox not available"); return false }

    override suspend fun apkList(): List<SandboxManager.PackageInfo> = emptyList()

    override suspend fun apkDelete(packageName: String): Boolean = false

    override suspend fun apkUpgrade(onProgress: (String) -> Unit): Int = 0

    override suspend fun getDiskUsageMB(): Long = 0L

    override fun getSandboxHomeDir(): File? = null
    override suspend fun reset(): Boolean = false
}
