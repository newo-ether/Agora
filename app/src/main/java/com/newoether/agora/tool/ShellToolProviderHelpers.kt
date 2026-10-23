package com.newoether.agora.tool

import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.util.SHELL_COMMAND_MAX_BYTES
import com.newoether.agora.util.SHELL_WORKDIR_MAX_BYTES

internal fun shellCommandValidationError(command: String, workdir: String): String? = when {
    command.toByteArray(Charsets.UTF_8).size > SHELL_COMMAND_MAX_BYTES ->
        "command exceeds 64KB limit"
    workdir.toByteArray(Charsets.UTF_8).size > SHELL_WORKDIR_MAX_BYTES ->
        "workdir exceeds 32KB limit"
    else -> null
}

internal fun targetsSharedStorage(path: String): Boolean {
    val normalized = path.trim().replace('\\', '/').replace(Regex("/+"), "/")
    return normalized == "/mnt/shared" || normalized.startsWith("/mnt/shared/")
}

/**
 * Explains an inaccessible local path in terms of Android's storage rules so the model can
 * recover instead of retrying blindly. Only meaningful for the local sandbox backend
 * (device == null); remote servers have their own permissions.
 */
internal fun storageHint(device: ShellDeviceConfig?, path: String): String {
    if (device != null) return ""
    val normalized = path.trim().replace('\\', '/').replace(Regex("/+"), "/")
    return when {
        targetsSharedStorage(normalized) ->
            " — /mnt/shared maps the device's shared storage; if access fails, the sandbox" +
                " may not have shared storage mounted (enable it in Agora's sandbox settings)" +
                " or Android denies the app All-Files access."
        normalized.startsWith("/sdcard") || normalized.startsWith("/storage/emulated") ->
            " — Android scoped storage blocks direct /sdcard access on Android 10+;" +
                " use /mnt/shared instead."
        else -> ""
    }
}
