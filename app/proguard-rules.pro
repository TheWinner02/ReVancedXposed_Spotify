# Chimera Architecture Rules
-keep class io.github.chsbuffer.revancedxposed.MainHook {
    public static void nativeBootstrap(android.content.Context);
}
-keep class io.github.chsbuffer.revancedxposed.ChimeraEngine { *; }

# Prevent obfuscation of MainHook and its bootstrap method
-keepnames class io.github.chsbuffer.revancedxposed.MainHook

# Keep Xposed Framework classes (we are rootless, but using their API names)
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# DexKit and Dependencies
-keep class com.github.kyuubiran.ezxhelper.** { *; }
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# Protobuf Reflection
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# General Android entry points
-keepclassmembers class **.* {
    public <init>(android.content.Context, android.util.AttributeSet);
}
