package com.newoether.agora.api.util

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

// Tests relocated to provider/cloud; app image tests retain their local runner.
class ImageWireTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquirePackage("javax.imageio.")
            .doNotAcquirePackage("com.sun.net.httpserver.")
            .doNotAcquirePackage("sun.net.httpserver.")
            .build()
}
