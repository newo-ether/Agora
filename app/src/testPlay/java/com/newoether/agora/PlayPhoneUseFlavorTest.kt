package com.newoether.agora

import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The store build must not ship Phone Use or the sensitive permissions it needs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayPhoneUseFlavorTest {

    @Test
    fun phoneUseIsCompiledOutOfTheStoreBuild() {
        assertFalse(PHONE_USE_SUPPORTED)
    }

    @Test
    fun storeManifestDoesNotRequestPhoneUsePermissions() {
        val context = RuntimeEnvironment.getApplication()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()

        assertFalse("android.permission.SYSTEM_ALERT_WINDOW" in requested)
        assertFalse("android.permission.WRITE_SETTINGS" in requested)
        assertFalse("android.permission.QUERY_ALL_PACKAGES" in requested)
    }
}
