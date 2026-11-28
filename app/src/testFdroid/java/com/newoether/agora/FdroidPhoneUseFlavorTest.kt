package com.newoether.agora

import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The F-Droid build keeps Phone Use, so its permissions must stay declared. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FdroidPhoneUseFlavorTest {

    @Test
    fun phoneUseIsCompiledIntoTheFdroidBuild() {
        assertTrue(PHONE_USE_SUPPORTED)
    }

    @Test
    fun fdroidManifestKeepsPhoneUsePermissions() {
        val context = RuntimeEnvironment.getApplication()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()

        assertTrue("android.permission.SYSTEM_ALERT_WINDOW" in requested)
        assertTrue("android.permission.WRITE_SETTINGS" in requested)
        assertTrue("android.permission.QUERY_ALL_PACKAGES" in requested)
    }
}
