// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("buildlogic.kotlin-source-size")
    id("buildlogic.module-boundaries")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
