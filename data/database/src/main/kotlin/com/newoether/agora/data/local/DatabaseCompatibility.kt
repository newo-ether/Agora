package com.newoether.agora.data.local

/**
 * Result of inspecting the durable database before Room is allowed to open it.
 *
 * Missing and supported databases may be opened. Future-version and unreadable
 * databases are fail-closed until the user explicitly clears them.
 */
sealed interface DatabaseCompatibility {
    data object Missing : DatabaseCompatibility

    data class Supported(
        val storedVersion: Int,
    ) : DatabaseCompatibility

    data class FutureVersion(
        val storedVersion: Int,
        val supportedVersion: Int,
    ) : DatabaseCompatibility

    data class Unreadable(
        val error: Throwable,
    ) : DatabaseCompatibility
}

val DatabaseCompatibility.canOpen: Boolean
    get() = this is DatabaseCompatibility.Missing ||
        this is DatabaseCompatibility.Supported

internal fun classifyDatabaseCompatibility(
    databaseExists: Boolean,
    currentVersion: Int,
    readStoredVersion: () -> Int,
): DatabaseCompatibility {
    if (!databaseExists) return DatabaseCompatibility.Missing
    return try {
        val storedVersion = readStoredVersion()
        if (storedVersion > currentVersion) {
            DatabaseCompatibility.FutureVersion(
                storedVersion = storedVersion,
                supportedVersion = currentVersion,
            )
        } else {
            DatabaseCompatibility.Supported(storedVersion)
        }
    } catch (error: Exception) {
        DatabaseCompatibility.Unreadable(error)
    }
}
