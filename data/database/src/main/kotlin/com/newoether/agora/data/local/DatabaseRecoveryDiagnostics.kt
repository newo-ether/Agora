package com.newoether.agora.data.local

/** Recovery diagnostics contain only the generated conversation ID, fixed stage labels and timings. */
object DatabaseRecoveryDiagnostics {
    fun recoveryStage(
        conversationId: String,
        stage: String,
        elapsedMs: Long,
        rows: Int? = null,
    ) {
        runCatching {
            val count = if (rows != null) "rows=$rows " else ""
            android.util.Log.i(
                "RecoveryDiagnostics",
                "conversation=$conversationId stage=$stage ${count}elapsedMs=$elapsedMs",
            )
        }
    }
}
