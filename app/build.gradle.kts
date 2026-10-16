plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Build-time bytecode fix for the Android 15 removeFirst()/removeLast() crash (see build-logic).
    id("buildlogic.removefirstlast-fix")
}

import java.util.Properties

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("local.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.reader())
}

android {
    namespace = "com.newoether.agora"
    testOptions.unitTests.isIncludeAndroidResources = true
    // Robolectric installs Conscrypt as the first security provider for the whole test JVM;
    // on JDK 17+ its TLS server needs reflective access to java.net (InetAddress.holder).
    testOptions.unitTests.all { it.jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED") }
    compileSdk {
        version = release(36)
    }

    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.newoether.agora"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "2.1.0"


        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
                targets += listOf("agora_proot")
            }
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", "."))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    val hasKeystore = keystoreProperties.getProperty("storeFile", ".").let { it != "." }
    val releaseSigning = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")

    buildTypes {
        debug {
            applicationIdSuffix = ".screenshots"
            versionNameSuffix = "-screenshots"
            ndk {
                abiFilters.clear()
                abiFilters += "x86_64"
            }
        }
        release {
            signingConfig = releaseSigning
            // R8 shrinks and optimizes release code; keep rules live in proguard-rules.pro.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("diag") {
            // Debuggable release build: same application id and signing key as release, so it
            // installs over a production install without losing data, while DebugLog stays on.
            matchingFallbacks += "release"
            isDebuggable = true
            signingConfig = releaseSigning
            isMinifyEnabled = false
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
        }
        create("fdroid") {
            dimension = "store"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
    }
    // The app switches locales at runtime without Play Feature Delivery. Keep every
    // packaged translation available instead of letting App Bundles split languages.
    bundle {
        language {
            enableSplit = false
        }
    }

    // Extract .so files to disk for ProcessBuilder exec (Kai approach)
    @Suppress("UnstableApiUsage")
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // We vendor a matched, self-built ONNX Runtime (libonnxruntime.so +
            // libonnxruntime4j_jni.so, ORT 1.28.2, NDK r28c, arm64-v8a) under
            // src/main/jniLibs. It is version-matched to sherpa-onnx's bundled core so a
            // future STT dependency shares ONE libonnxruntime.so in-process. pickFirst keeps
            // that single native lib if another dependency also ships it.
            pickFirsts += "**/libonnxruntime.so"
        }
        resources {
            // bcprov, bcutil and bcpkix each ship the same BouncyCastle (MIT) license text.
            pickFirsts += "META-INF/LICENSE.md"
            // The DJL 'tokenizers' jar bundles desktop native libs as resources
            // (osx/win/linux); on Android the native libs come from tokenizer-native's
            // jniLibs, so strip the desktop copies to avoid packaging junk.
            excludes += "native/lib/osx-**"
            excludes += "native/lib/win-**"
            excludes += "native/lib/linux-**"
            excludes += "**/*.dylib"
            excludes += "native/lib/tokenizers.properties"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// Proot binaries (libproot_exec.so, libproot_loader.so, libtalloc.so) are
// built via GNUmakefile (see .build-proot/) and placed directly in jniLibs.
// No CMake target is needed — the binaries are manually managed prebuilts.
// talloc is built with SONAME=libtalloc.so (no version) so AGP packaging works.

tasks.register<Copy>("copyPlayApk") {
    from("build/outputs/apk/play/release")
    into("release")
    include("*.apk")
}

tasks.register<Copy>("copyFdroidApk") {
    from("build/outputs/apk/fdroid/release")
    into("release")
    include("*.apk")
}

tasks.register<Copy>("copyPlayBundle") {
    from("build/outputs/bundle/playRelease")
    into("release")
    include("*.aab")
}

// Bundle the user manual (docs/en/*.md) into app assets so the on-device `introspect` tool can
// read and semantically search it at runtime. docs/en stays the single source of truth.
tasks.register<Copy>("copyManualAssets") {
    from("${rootProject.projectDir}/docs/en") {
        include("*.md")
    }
    into("src/main/assets/manual")
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn("copyManualAssets")
    }
    tasks.named("assemblePlayRelease") {
        finalizedBy("copyPlayApk")
    }
    tasks.named("assembleFdroidRelease") {
        finalizedBy("copyFdroidApk")
    }
    tasks.named("bundlePlayRelease") {
        finalizedBy("copyPlayBundle")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.compose.markdown)
    implementation(libs.jetbrains.markdown)
    implementation(libs.coil.compose)
    implementation(libs.jlatexmath.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.okhttp)
    implementation(libs.material.color.utilities)
    implementation(libs.lottie.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.jsch)
    implementation(libs.commons.compress)
    // PDFBox-Android bundles the bouncycastle jdk15to18 family (bcprov, bcutil),
    // which collides with the app's jdk18on 1.86 (via bouncycastle.pkix). Drop the
    // stale family; the newer provider covers PDFBox's crypto use (encrypted PDFs).
    implementation(libs.pdfbox.android) {
        exclude(group = "org.bouncycastle")
    }
    // On-device ONNX embeddings. ORT Java API from a vendored, self-built jar (1.28.2) whose
    // matched native libs live in src/main/jniLibs (sherpa-onnx compatible for future STT) —
    // replaces the Maven com.microsoft.onnxruntime:onnxruntime-android. DJL supplies the
    // HuggingFace tokenizer (tokenizer.json); its Android native libs come from
    // tokenizer-native (desktop copies are excluded in packaging).
    implementation(files("libs/onnxruntime-1.28.2.jar"))
    // onnxruntime-genai 0.17.0 (on-device LLM chat). The Java API jar; its native libs
    // (libonnxruntime-genai*.so, libmat.so) are in jniLibs and dlopen the vendored ORT 1.28.2
    // via OrtGetApiBase (stable C ABI) — no second libonnxruntime.so.
    implementation(files("libs/onnxruntime-genai-0.17.0.jar"))
    implementation(libs.djl.tokenizers) {
        exclude(group = "ai.djl.huggingface", module = "tokenizers-native")
    }
    runtimeOnly(libs.djl.tokenizer.native.android)
    // WebUI server (stage 2): embedded HTTP with the pure-Kotlin CIO engine.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    // Builds the WebUI's self-signed TLS certificate.
    implementation(libs.bouncycastle.pkix)
    testImplementation(libs.ktor.server.test.host)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.whenTaskAdded {
    if (name.contains("ArtProfile") || name.contains("BaselineProfile") || name.contains("baselineProfile")) {
        enabled = false
    }
    if (name.contains("StripDebugSymbols") || name.contains("MergeNativeDebugMetadata")) {
        enabled = false
    }
}
