#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <dlfcn.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "dobby.h"

#define LOG_TAG "GHOST_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define ORIGINAL_LIB_NAME "libcrashlytics_orig.so"
#define PAYLOAD_ASSET_NAME "settings.bin"
#define MAIN_HOOK_CLASS "io/github/chsbuffer/revancedxposed/MainHook"

// External declarations
void install_syscall_hooks();

// Helper to load the original library via Java's System.load
// This ensures JNI methods are properly registered in the JVM
void load_original_lib_properly(JNIEnv* env, jobject context) {
    LOGI("Chimera: Resolving original library path...");

    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAppInfoMethod = env->GetMethodID(contextClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
    jobject appInfo = env->CallObjectMethod(context, getAppInfoMethod);

    jclass appInfoClass = env->GetObjectClass(appInfo);
    jfieldID nativeLibDirField = env->GetFieldID(appInfoClass, "nativeLibraryDir", "Ljava/lang/String;");
    jstring nativeLibDir = (jstring)env->GetObjectField(appInfo, nativeLibDirField);

    const char* dirChars = env->GetStringUTFChars(nativeLibDir, nullptr);
    std::string fullPath = std::string(dirChars) + "/" + ORIGINAL_LIB_NAME;
    env->ReleaseStringUTFChars(nativeLibDir, dirChars);

    LOGI("Chimera: Loading original library via System.load: %s", fullPath.c_str());

    jclass systemClass = env->FindClass("java/lang/System");
    jmethodID loadMethod = env->GetStaticMethodID(systemClass, "load", "(Ljava/lang/String;)V");
    jstring jPath = env->NewStringUTF(fullPath.c_str());

    env->CallStaticVoidMethod(systemClass, loadMethod, jPath);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("Chimera: Failed to load original library via System.load. Stability may be compromised.");
    } else {
        LOGI("Chimera: Original library linked successfully to JVM.");
    }
}

void load_java_payload(JNIEnv* env, jobject context) {
    LOGI("Chimera: Starting fileless Java payload loading...");

    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAssetsMethod = env->GetMethodID(contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject assetManagerObj = env->CallObjectMethod(context, getAssetsMethod);
    AAssetManager* assetManager = AAssetManager_fromJava(env, assetManagerObj);

    if (!assetManager) {
        LOGE("Chimera: Failed to get AssetManager.");
        return;
    }

    AAsset* asset = AAssetManager_open(assetManager, PAYLOAD_ASSET_NAME, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Chimera: Payload asset '%s' not found.", PAYLOAD_ASSET_NAME);
        return;
    }

    off_t size = AAsset_getLength(asset);
    const void* buffer = AAsset_getBuffer(asset);
    LOGI("Chimera: Payload loaded in memory (%ld bytes).", size);

    // Create Direct ByteBuffer
    jclass byteBufferClass = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocateDirectMethod = env->GetStaticMethodID(byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    jobject byteBuffer = env->CallStaticObjectMethod(byteBufferClass, allocateDirectMethod, (jint)size);
    void* directBuffer = env->GetDirectBufferAddress(byteBuffer);
    memcpy(directBuffer, buffer, (size_t)size);
    AAsset_close(asset);

    jmethodID getClassLoaderMethod = env->GetMethodID(contextClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject spotifyClassLoader = env->CallObjectMethod(context, getClassLoaderMethod);

    jclass classLoaderClass = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jmethodID classLoaderCtor = env->GetMethodID(classLoaderClass, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject inMemoryClassLoader = env->NewObject(classLoaderClass, classLoaderCtor, byteBuffer, spotifyClassLoader);

    if (inMemoryClassLoader) {
        LOGI("Chimera: InMemoryDexClassLoader created successfully.");
        jmethodID loadClassMethod = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring mainClassName = env->NewStringUTF(MAIN_HOOK_CLASS);
        jclass mainClass = (jclass)env->CallObjectMethod(inMemoryClassLoader, loadClassMethod, mainClassName);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("Chimera: MainHook class not found in memory.");
            return;
        }

        if (mainClass) {
            jmethodID bootstrapMethod = env->GetStaticMethodID(mainClass, "nativeBootstrap", "(Landroid/content/Context;)V");
            if (bootstrapMethod) {
                env->CallStaticVoidMethod(mainClass, bootstrapMethod, context);
                LOGI("Chimera: Java Payload bootstrapped successfully.");
            }
        }
    } else {
        LOGE("Chimera: Failed to create InMemoryDexClassLoader.");
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_spotify_music_SpotifyApplication_initGhost(JNIEnv* env, jclass clazz, jobject context) {
    LOGI("Chimera: initGhost triggered.");

    // 1. First, load the original library properly so Firebase doesn't crash
    load_original_lib_properly(env, context);

    // 2. Then, load our mod payload into RAM
    load_java_payload(env, context);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("Chimera: Micro-Loader starting...");

    // Initialize syscall hooks immediately (Dobby)
    install_syscall_hooks();

    LOGI("Chimera: Native initialization completed.");
    return JNI_VERSION_1_6;
}
