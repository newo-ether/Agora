package com.newoether.agora.sandbox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object SandboxSharedStorageAccess {
    /**
     * Whether the sandbox setting currently mounts shared storage. The mount and this app's direct
     * reads of `/storage/emulated/0` follow one switch, so a path the model writes into a shell
     * command and the same path tapped in a chat message cannot disagree.
     */
    @Volatile
    private var sharedStorageMounted = false

    val isSharedStorageMounted: Boolean get() = sharedStorageMounted

    fun bindSharedStorageSetting(enabled: Flow<Boolean>, scope: CoroutineScope) {
        scope.launch { enabled.collect { sharedStorageMounted = it } }
    }

    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun settingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .takeIf { it.resolveActivity(context.packageManager) != null }
    }

    val legacyPermissions: Array<String>
        get() = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
}
