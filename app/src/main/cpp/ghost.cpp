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

void load_original_lib() {
    LOGI("Chimera: Attempting to load original library: %s", ORIGINAL_LIB_NAME);
    void* handle = dlopen(ORIGINAL_LIB_NAME, RTLD_NOW);
    if (handle) {
        LOGI("Chimera: Original library loaded successfully.");
    } else {
        LOGW("Chimera: Original library not found. Normal if not using Native Shim.");
    }
}

void load_java_payload(JNIEnv* env, jobject context) {
    LOGI("Chimera: Starting fileless Java payload loading...");

    // 1. Get AssetManager from context
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAssetsMethod = env->GetMethodID(contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject assetManagerObj = env->CallObjectMethod(context, getAssetsMethod);
    AAssetManager* assetManager = AAssetManager_fromJava(env, assetManagerObj);

    if (!assetManager) {
        LOGE("Chimera: Failed to get AssetManager.");
        return;
    }

    // 2. Read payload from assets
    AAsset* asset = AAssetManager_open(assetManager, PAYLOAD_ASSET_NAME, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Chimera: Payload asset '%s' not found.", PAYLOAD_ASSET_NAME);
        return;
    }

    off_t size = AAsset_getLength(asset);
    const void* buffer = AAsset_getBuffer(asset);
    LOGI("Chimera: Payload loaded in memory (%ld bytes).", size);

    // 3. Create InMemoryDexClassLoader (Android 8.0+)
    jclass byteBufferClass = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocateDirectMethod = env->GetStaticMethodID(byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    jobject byteBuffer = env->CallStaticObjectMethod(byteBufferClass, allocateDirectMethod, (jint)size);

    void* directBuffer = env->GetDirectBufferAddress(byteBuffer);
    memcpy(directBuffer, buffer, (size_t)size);
    AAsset_close(asset);

    jclass classLoaderClass = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jmethodID classLoaderCtor = env->GetMethodID(classLoaderClass, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");

    jmethodID getClassLoaderMethod = env->GetMethodID(contextClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parentClassLoader = env->CallObjectMethod(context, getClassLoaderMethod);

    jobject inMemoryClassLoader = env->NewObject(classLoaderClass, classLoaderCtor, byteBuffer, parentClassLoader);

    if (inMemoryClassLoader) {
        LOGI("Chimera: InMemoryDexClassLoader created successfully.");

        // 4. Bootstrap the module
        jmethodID loadClassMethod = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring mainClassName = env->NewStringUTF(MAIN_HOOK_CLASS);
        jclass mainClass = (jclass)env->CallObjectMethod(inMemoryClassLoader, loadClassMethod, mainClassName);

        if (mainClass) {
            jmethodID bootstrapMethod = env->GetStaticMethodID(mainClass, "nativeBootstrap", "(Landroid/content/Context;)V");
            if (bootstrapMethod) {
                env->CallStaticVoidMethod(mainClass, bootstrapMethod, context);
                LOGI("Chimera: Java Payload bootstrapped successfully.");
            } else {
                LOGE("Chimera: Failed to find nativeBootstrap method.");
            }
        } else {
            LOGE("Chimera: Failed to find MainHook class in payload.");
        }
    } else {
        LOGE("Chimera: Failed to create InMemoryDexClassLoader.");
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_spotify_music_SpotifyApplication_initGhost(JNIEnv* env, jclass clazz, jobject context) {
    LOGI("Chimera: initGhost called from SpotifyApplication.");
    load_java_payload(env, context);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("Chimera: Micro-Loader starting...");

    // Phase 1: Native Shim
    load_original_lib();

    // Phase 2: Dobby Syscall Virtualization
    install_syscall_hooks();

    LOGI("Chimera: Initialization completed.");
    return JNI_VERSION_1_6;
}
