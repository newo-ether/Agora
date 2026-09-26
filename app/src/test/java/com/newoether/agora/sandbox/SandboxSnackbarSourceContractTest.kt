package com.newoether.agora.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxSnackbarSourceContractTest {
    @Test
    fun `Sandbox outcomes use one process-local buffered one-shot owner across flavors`() {
        val contract = sourceFile(
            "app/src/main/java/com/newoether/agora/sandbox/SandboxManager.kt",
        )
        val fdroid = sourceFile(
            "app/src/fdroid/java/com/newoether/agora/sandbox/ProotSandboxManager.kt",
        )
        val factoryContract = sourceFile(
            "app/src/main/java/com/newoether/agora/sandbox/SandboxManagerFactory.kt",
        )
        val fdroidFactory = sourceFile(
            "app/src/fdroid/java/com/newoether/agora/sandbox/FdroidSandboxManagerFactory.kt",
        )
        val play = sourceFile(
            "app/src/play/java/com/newoether/agora/sandbox/PlaySandboxManager.kt",
        )
        val activity = sourceFile("app/src/main/java/com/newoether/agora/MainActivity.kt")
        val chatViewModel = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt",
        )
        val uiContract = sourceFile("development/application-ui.md")
        val normalizedUiContract = Regex("\\s+").replace(uiContract, " ")
        val activityCollector = activity
            .substringAfter("viewModel.sandboxManager?.snackbarMessage?.collect { msg ->")
            .substringBefore("LaunchedEffect(Unit) {\n        var snackbarJob: Job? = null\n        viewModel.snackbarMessage.collect")
        val viewModelCleanup = chatViewModel
            .substringAfter("override fun onCleared() {")
            .substringBefore("/** Nullable on purpose")
        val reset = fdroid
            .substringAfter("override suspend fun reset(): Boolean")
            .substringBefore("// ── Shell Execution")

        assertTrue(contract.contains("val snackbarMessage: Flow<String>"))
        assertFalse(contract.contains("val snackbarMessage: StateFlow<String?>"))
        assertFalse(contract.contains("fun close()"))
        assertTrue(factoryContract.contains("process-shared manager"))
        assertTrue(factoryContract.contains("Consumers borrow that reference"))
        assertTrue(fdroidFactory.contains("private val shared by lazy"))
        assertTrue(fdroidFactory.contains("override fun create(): SandboxManager = shared"))
        assertFalse(viewModelCleanup.contains("sandboxManager"))

        assertTrue(fdroid.contains("Channel<String>(Channel.UNLIMITED)"))
        assertTrue(fdroid.contains("snackbarMessages.receiveAsFlow()"))
        assertTrue(fdroid.contains("snackbarMessages.trySend(message).isSuccess"))
        assertFalse(fdroid.contains("_snackbarMessage"))
        assertFalse(fdroid.contains("override fun close()"))
        assertEquals(3, Regex("override fun (installPackage|removePackage|upgradePackages)\\([^)]*\\) \\{\\n        if \\(_isBusy.value\\) return\\n        sandboxScope.launch \\{").findAll(fdroid).count())
        assertFalse(fdroid.substringBefore("override suspend fun reset(): Boolean").contains("sandboxScope.cancel()"))
        assertTrue(reset.contains("previousScope.cancel()"))
        assertTrue(reset.contains("previousScope.coroutineContext[Job]?.join()"))
        assertTrue(reset.contains("sandboxScope = CoroutineScope("))
        assertEquals(1, Regex("previousScope\\.cancel\\(\\)").findAll(reset).count())
        assertTrue(reset.indexOf("previousScope.cancel()") < reset.indexOf("previousScope.coroutineContext[Job]?.join()"))
        assertTrue(reset.indexOf("previousScope.coroutineContext[Job]?.join()") < reset.indexOf("mutationMutex.withLock"))
        assertTrue(reset.indexOf("mutationMutex.withLock") < reset.indexOf("rootfsDir.deleteRecursively()"))
        assertEquals(9, Regex("emitSnackbar\\(").findAll(fdroid).count())
        listOf(
            "sandbox_snackbar_installed",
            "sandbox_snackbar_install_failed",
            "sandbox_snackbar_removed",
            "sandbox_snackbar_remove_failed",
            "sandbox_snackbar_upgrade_done",
            "sandbox_snackbar_upgrade_none",
            "sandbox_snackbar_upgrade_failed",
            "sandbox_snackbar_error",
            "sandbox_snackbar_reset",
            "sandbox_snackbar_reset_failed",
        ).forEach { resource ->
            assertTrue(resource, fdroid.contains("R.string.$resource"))
        }

        assertTrue(play.contains("override val snackbarMessage: Flow<String> = emptyFlow()"))
        assertFalse(play.contains("MutableStateFlow<String?>"))
        assertFalse(play.contains("override fun close()"))

        assertTrue(activityCollector.contains("snackbarHostState.showSnackbar("))
        assertFalse(activityCollector.contains("msg != null"))
        assertFalse(activityCollector.contains("snackbarMessage.value"))
        // Interrupting display: the collector never awaits the visible Snackbar, it cancels it.
        assertTrue(activityCollector.contains("sandboxSnackbarJob?.cancel()"))
        assertTrue(activityCollector.contains("sandboxSnackbarJob = launch {"))
        assertTrue(
            activity.contains("var sandboxSnackbarJob: Job? = null"),
        )

        listOf(
            "process-local buffered one-shot events",
            "remains queued for the next collector",
            "consumed by one collector exactly once",
            "must not replay an outcome",
            "share the process lifetime owned by `AppContainer`'s",
            "must not cancel or close it when a consumer lifecycle ends",
            "Only an explicit Sandbox reset may cancel the manager scope",
            "reset must replace that scope before continuing",
            "not persisted",
            "Play flavor exposes an empty outcome stream",
            "Display is interrupting",
            "newest outcome is always the visible one",
        ).forEach { wording ->
            assertTrue(wording, normalizedUiContract.contains(wording))
        }
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
