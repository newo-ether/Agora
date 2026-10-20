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

# Keep embedded-View focus teardown consistent with AgoraApplication in optimized builds.
-assumevalues class androidx.compose.ui.ComposeUiFlags {
    public static boolean isViewFocusFixEnabled return true;
}
