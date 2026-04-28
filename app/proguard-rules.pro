# --- CHIMERA STEALH ARCHITECTURE RULES ---

# Impediamo a R8 di rinominare o eliminare il punto di ingresso
-keep class io.github.chsbuffer.revancedxposed.MainHook {
    public static void nativeBootstrap(android.content.Context);
    private static boolean isBootstrapped;
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}
-keepnames class io.github.chsbuffer.revancedxposed.MainHook

# Mantieni i log per il debug nativo
-keepclassmembers class io.github.chsbuffer.revancedxposed.MainHook$Companion {
    public void log(java.lang.String);
    public void log(java.lang.Throwable);
}

# Protezione totale per il motore Chimera e i suoi componenti
-keep class io.github.chsbuffer.revancedxposed.ChimeraEngine { *; }
-keep class io.github.chsbuffer.revancedxposed.spotify.** { *; }

# Mantieni le interfacce Xposed per la compatibilità binaria
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# DexKit e dipendenze (necessari per il mapping dinamico)
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# Protobuf (fondamentale per l'intercettazione dati)
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Mantieni i costruttori standard per l'iniezione UI
-keepclassmembers class **.* {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Impedisci la rimozione di metodi che potrebbero essere chiamati via JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
