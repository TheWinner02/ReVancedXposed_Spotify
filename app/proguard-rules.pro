# --- CHIMERA STEALTH ARCHITECTURE RULES ---

# Protezione totale per il core del modulo e i suoi componenti
# Manteniamo tutto il package per evitare crash di riflessione o caricamento dinamico
-keep class io.github.chsbuffer.revancedxposed.** { *; }

# Mantieni il Bridge e Pine (essenziali per l'hooking)
-keep class top.canyie.pine.** { *; }
-dontwarn top.canyie.pine.**

# Kotlin Serialization & Protobuf (prevenzione crash Ln; <init>)
# Manteniamo gli attributi necessari per la serializzazione a runtime
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions

-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}

-keepclassmembers class io.github.chsbuffer.revancedxposed.** {
    *** Companion;
    *** $serializer;
}

-keep class kotlinx.serialization.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# DexKit (necessario per il mapping dinamico dei fingerprint)
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# Impedisce a R8 di usare nomi troppo brevi (a, b, c...) che collidono con Spotify
# Spostiamo le classi non 'kept' in un package interno
-repackageclasses io.github.chsbuffer.revancedxposed.internal
-allowaccessmodification

# Mantieni i costruttori standard per l'iniezione UI
-keepclassmembers class **.* {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Impedisci la rimozione di metodi nativi chiamati via JNI (libghost.so)
-keepclasseswithmembernames class * {
    native <methods>;
}
