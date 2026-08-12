# AddonServer ProGuard Rules

# Keep Chaquopy Python runtime
-keep class chaquopy.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.CoroutineExceptionHandlerImpl

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Gson type adapters
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# Keep our config model classes
-keep class com.addonserver.ConfigManager$ProviderConfig { *; }

# Keep Telegram response models
-keep class com.addonserver.telegram.** { *; }

# Keep Python bridge methods
-keepclassmembers class com.addonserver.PythonBridge {
    public *;
}
