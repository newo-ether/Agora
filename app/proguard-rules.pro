# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.newoether.agora.**$$serializer { *; }
-keepclassmembers class com.newoether.agora.** { *** Companion; }
-keepclasseswithmembers class com.newoether.agora.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }

# JSch (SSH/SFTP)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# JLaTeXMath creates environment macro handlers and resolves executeMacro by name.
-keep class org.scilab.forge.jlatexmath.NewCommandMacro {
    public <init>();
    public java.lang.String executeMacro(org.scilab.forge.jlatexmath.TeXParser, java.lang.String[]);
}

# Compose
-dontwarn androidx.compose.**

# JNI (llama): native code reads these classes' fields and constructors, and calls the
# callback methods, by name.
-keep class com.newoether.agora.api.NativeChatCallback { *; }
-keep class * implements com.newoether.agora.api.NativeChatCallback { *; }
-keep class com.newoether.agora.api.ChatTemplateToolCall { *; }
-keep class com.newoether.agora.api.ChatTemplateMessage { *; }
-keep class com.newoether.agora.api.ChatTemplateTool { *; }
-keep class com.newoether.agora.api.LlamaChatTemplateRequest { *; }
-keep class com.newoether.agora.api.ChatTemplateGrammarTrigger { *; }
-keep class com.newoether.agora.api.LlamaChatTemplateResult { *; }
-keepclasseswithmembernames class com.newoether.agora.** { native <methods>; }
# Reflection: flavor sandbox factories (AppContainer) and the screenshot fixture (MainActivity).
-keep class com.newoether.agora.sandbox.FdroidSandboxManagerFactory { <init>(...); }
-keep class com.newoether.agora.sandbox.PlaySandboxManagerFactory { <init>(...); }
-keep class com.newoether.agora.screenshot.ScreenshotFixture { *; }# Ktor server: JVM-only debug detection (DevelopmentMode), absent on Android.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# PDFBox-Android references an optional JPEG2000 decoder (gemalto jp2) only used to
# extract JP2-encoded images from PDFs. We only extract text, so the dependency is
# intentionally absent; silence the missing-class warning.
-dontwarn com.gemalto.jp2.**

# ONNX Runtime (vendored onnxruntime-1.28.2.jar) + onnxruntime-genai.
# The native JNI (libonnxruntime4j_jni.so / libonnxruntime-genai-jni.so) resolves these Java
# classes, their fields, constructors and methods BY NAME via FindClass/GetMethodID/GetFieldID
# during OrtSession.run and generation. R8 has no visibility into those native references, so
# without keeping them it strips e.g. ai.onnxruntime.TensorInfo and the run() aborts with
# "JNI DETECTED ERROR IN APPLICATION: java_class == null" (ClassNotFoundException: TensorInfo).
# Keep the whole ORT + genai Java surface.
-keep class ai.onnxruntime.** { *; }
-keep class ai.onnxruntime.genai.** { *; }
-keep class com.microsoft.applications.events.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.genai.** { *; }
-dontwarn ai.onnxruntime.**

# sherpa-onnx (on-device STT). Its JNI (libsherpa-onnx-jni.so) reads the Kotlin config classes'
# FIELDS by exact name via GetFieldID (e.g. OnlineModelConfig.decodingMethod) and calls
# constructors/methods by name. R8 can't see those native references, so without keeping the
# whole com.k2fsa.sherpa.onnx surface it renames/strips those fields and OnlineRecognizer
# construction aborts with "Failed to get field ID for decodingMethod". Keep names and members.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
