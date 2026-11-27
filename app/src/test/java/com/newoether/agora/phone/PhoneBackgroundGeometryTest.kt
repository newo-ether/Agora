package com.newoether.agora.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneBackgroundGeometryTest {
    private val native = PhoneNativeDisplay(1080, 2400, 440)

    @Test fun phonePresetsKeepOneLayoutAndSupportLandscape() {
        val presets = linkedMapOf(
            "phone_hd" to PhoneBackgroundSpec(720, 1600, 320),
            "phone_fhd" to PhoneBackgroundSpec(1080, 2400, 480),
            "phone_qhd" to PhoneBackgroundSpec(1440, 3200, 640),
        )
        assertEquals(listOf("native", "phone_hd", "phone_fhd", "phone_qhd", "tablet", "custom"),
            PhoneBackgroundGeometry.SIZES)
        for ((id, expected) in presets) {
            val portrait = PhoneBackgroundGeometry.resolve(native, PhoneUseSettings(backgroundSize = id))
            assertEquals(id, expected, portrait)
            assertEquals(id, 360, portrait.shortDp)
            assertEquals(id, 800, portrait.heightDp)
            val landscape = PhoneBackgroundGeometry.resolve(native,
                PhoneUseSettings(backgroundSize = id, backgroundLandscape = true))
            assertEquals(id, PhoneBackgroundSpec(expected.height, expected.width, expected.dpi), landscape)
        }
    }

    @Test fun defaultIsHdWhileNativeTabletAndCustomKeepTheirIndependentGeometry() {
        assertEquals("phone_hd", PhoneBackgroundGeometry.DEFAULT_SIZE)
        assertEquals(PhoneBackgroundSpec(720, 1600, 320),
            PhoneBackgroundGeometry.resolve(native, PhoneUseSettings()))
        assertEquals(PhoneBackgroundSpec(1080, 2400, 440),
            PhoneBackgroundGeometry.resolve(native, PhoneUseSettings(backgroundSize = "native")))
        assertEquals(PhoneBackgroundSpec(1280, 2048, 320),
            PhoneBackgroundGeometry.resolve(native, PhoneUseSettings(backgroundSize = "tablet")))
        assertEquals(PhoneBackgroundSpec(900, 2000, 400), PhoneBackgroundGeometry.resolve(native,
            PhoneUseSettings(backgroundSize = "custom", backgroundCustomShort = 900,
                backgroundCustomLong = 2000, backgroundCustomDpi = 400)))
    }
}
