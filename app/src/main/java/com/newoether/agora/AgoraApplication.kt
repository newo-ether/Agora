package com.newoether.agora

import android.app.Application
import com.newoether.agora.api.util.tokens.bpe.O200kBase
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.di.AppContainer
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.util.CrashReporter
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application entry point. Installs the crash reporter before any other component runs so
 * that crashes occurring during startup are captured as well.
 *
 * Owns the process-scoped AppContainer, but publishes it only after the durable database has
 * passed compatibility checks, supported migrations, and Room schema validation.
 */
class AgoraApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val startupGate = DatabaseStartupGate(
        inspectDatabase = {
            withContext(Dispatchers.IO) {
                ChatDatabase.inspectCompatibility(this@AgoraApplication)
            }
        },
        openResource = {
            withContext(Dispatchers.IO) {
                val database = ChatDatabase.build(this@AgoraApplication)
                AppContainer(this@AgoraApplication, database)
            }
        },
        closeResource = { container -> container.database.close() },
        deleteDatabase = {
            withContext(Dispatchers.IO) {
                val databasePath = getDatabasePath(ChatDatabase.DB_NAME)
                !databasePath.exists() ||
                    this@AgoraApplication.deleteDatabase(ChatDatabase.DB_NAME)
            }
        },
        reportFailure = { error ->
            DebugLog.e(
                "AgoraApplication",
                "Database startup gate failed closed",
                error,
            )
        },
    )

    val databaseStartupState: StateFlow<DatabaseStartupState>
        get() = startupGate.state

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // The context indicator counts remote models' text with a real vocabulary once it is in
        // memory. Loading it in its own job keeps the first estimate off the heuristic without
        // delaying the database gate behind it.
        startupScope.launch { O200kBase.ensureLoaded(this@AgoraApplication) }
        // Preload the on-device STT recognizer so the first dictation has no model-load latency.
        // Its own job (off the main thread); no-op when the model isn't installed. Unlike AIOPE,
        // which builds and frees the recognizer per session, Agora keeps it warm via SttEngineHolder.
        startupScope.launch {
            try {
                com.newoether.agora.stt.SttEngineHolder.preload(this@AgoraApplication)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.e("AgoraApplication", "STT preload failed closed", error)
            }
        }
        startupScope.launch {
            try {
                DeveloperDiagnostics.initialize(noBackupFilesDir, startupScope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.e(
                    "AgoraApplication",
                    "Diagnostic capture initialization failed closed",
                    error,
                )
            }
            startupGate.initialize()
        }
    }

    suspend fun awaitDatabaseStartup(): DatabaseStartupState =
        startupGate.awaitState()

    suspend fun awaitContainer(): AppContainer? =
        startupGate.awaitReadyResource()

    fun requireContainer(): AppContainer =
        startupGate.requireReadyResource()

    suspend fun clearIncompatibleDatabase(): Boolean =
        startupGate.clearBlockedDatabase()
}
