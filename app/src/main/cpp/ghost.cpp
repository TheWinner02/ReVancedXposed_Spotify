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

#define LOG_TAG "FirebaseCrashlytics"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define ORIGINAL_LIB_NAME "libcrashlytics_orig.so"
#define PAYLOAD_ASSET_NAME "settings.bin"
#define BOOTSTRAP_CLASS "io.github.chsbuffer.revancedxposed.ChimeraEngine"

// External declarations
void install_syscall_hooks();
extern "C" void set_maps_cache_path(const char* path);
extern "C" bool register_main_hook_natives(JNIEnv* env, jclass clazz);

extern "C" JNIEXPORT jboolean JNICALL Java_com_google_firebase_crashlytics_ndk_JniNativeApi_nativeInit(JNIEnv* env, jobject thiz, jobjectArray args, jobject obj) {
    LOGW("SystemCore: Proxying native init...");
    void* handle = dlopen(ORIGINAL_LIB_NAME, RTLD_NOW);
    if (handle) {
        auto orig_init = (jboolean (*)(JNIEnv*, jobject, jobjectArray, jobject))dlsym(handle, "Java_com_google_firebase_crashlytics_ndk_JniNativeApi_nativeInit");
        if (orig_init) return orig_init(env, thiz, args, obj);
    }
    return JNI_TRUE;
}

void load_java_payload(JNIEnv* env, jobject context) {
    LOGI("SystemCore: Payload sequence started.");

    jclass contextClass = env->GetObjectClass(context);

    // Early Stealth: Set maps cache path
    jmethodID getCacheDirMethod = env->GetMethodID(contextClass, "getCacheDir", "()Ljava/io/File;");
    jobject cacheDirFile = env->CallObjectMethod(context, getCacheDirMethod);
    if (cacheDirFile) {
        jclass fileClass = env->GetObjectClass(cacheDirFile);
        jmethodID getAbsolutePathMethod = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
        jstring cachePath = (jstring)env->CallObjectMethod(cacheDirFile, getAbsolutePathMethod);
        const char* pathStr = env->GetStringUTFChars(cachePath, nullptr);
        set_maps_cache_path(pathStr);
        env->ReleaseStringUTFChars(cachePath, pathStr);
    }

    jmethodID getAssetsMethod = env->GetMethodID(contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject assetManagerObj = env->CallObjectMethod(context, getAssetsMethod);
    AAssetManager* assetManager = AAssetManager_fromJava(env, assetManagerObj);

    AAsset* asset = AAssetManager_open(assetManager, PAYLOAD_ASSET_NAME, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("SystemCore: Resource missing: %s", PAYLOAD_ASSET_NAME);
        return;
    }

    off_t size = AAsset_getLength(asset);
    const void* buffer = AAsset_getBuffer(asset);

    // Allocate Direct ByteBuffer for raw DEX loading
    jclass byteBufferClass = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocateDirectMethod = env->GetStaticMethodID(byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    jobject byteBuffer = env->CallStaticObjectMethod(byteBufferClass, allocateDirectMethod, (jint)size);
    void* directBuffer = env->GetDirectBufferAddress(byteBuffer);
    memcpy(directBuffer, buffer, (size_t)size);
    AAsset_close(asset);

    // Create ByteBuffer array (required for API 27+)
    jobjectArray byteBufferArray = env->NewObjectArray(1, byteBufferClass, byteBuffer);
    env->SetObjectArrayElement(byteBufferArray, 0, byteBuffer);

    // Get Parent ClassLoader (Spotify)
    jmethodID getClassLoaderMethod = env->GetMethodID(contextClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parentClassLoader = env->CallObjectMethod(context, getClassLoaderMethod);

    // Get Native Library Directory (To allow loading libpine.so, libdexkit.so)
    jmethodID getAppInfoMethod = env->GetMethodID(contextClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
    jobject appInfoObj = env->CallObjectMethod(context, getAppInfoMethod);
    jclass appInfoClass = env->GetObjectClass(appInfoObj);
    jfieldID nativeLibDirField = env->GetFieldID(appInfoClass, "nativeLibraryDir", "Ljava/lang/String;");
    jstring nativeLibDir = (jstring)env->GetObjectField(appInfoObj, nativeLibDirField);

    // Create InMemoryDexClassLoader (Supports Raw DEX Bytecode + Native Libs)
    // Constructor: (ByteBuffer[] dexBuffers, String librarySearchPath, ClassLoader parent)
    jclass classLoaderClass = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jmethodID classLoaderCtor = env->GetMethodID(classLoaderClass, "<init>", "([Ljava/nio/ByteBuffer;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    jobject inMemoryClassLoader = env->NewObject(classLoaderClass, classLoaderCtor, byteBufferArray, nativeLibDir, parentClassLoader);

    if (inMemoryClassLoader) {
        LOGI("SystemCore: ClassLoader initialized.");

        // Discovery attempt
        jmethodID loadClassMethod = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring mainClassName = env->NewStringUTF(BOOTSTRAP_CLASS);

        jclass mainClass = (jclass)env->CallObjectMethod(inMemoryClassLoader, loadClassMethod, mainClassName);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("SystemCore: Bootstrap resolution failed: %s", BOOTSTRAP_CLASS);
            return;
        }

        // Register Natives for MainHook
        jstring mainHookName = env->NewStringUTF("io.github.chsbuffer.revancedxposed.MainHook");
        jclass mainHookClass = (jclass)env->CallObjectMethod(inMemoryClassLoader, loadClassMethod, mainHookName);
        if (mainHookClass) {
            register_main_hook_natives(env, mainHookClass);
        } else {
            LOGE("SystemCore: Native linking failure");
        }

        if (mainClass) {
            jmethodID bootstrapMethod = env->GetStaticMethodID(mainClass, "nativeBootstrap", "(Landroid/content/Context;)V");
            if (bootstrapMethod) {
                env->CallStaticVoidMethod(mainClass, bootstrapMethod, context);
                LOGI("SystemCore: [ ONLINE ]");
            }
        }
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_spotify_music_SpotifyApplication_initGhost(JNIEnv* env, jclass clazz, jobject context) {
    LOGI("SystemCore: Bootstrap sequence.");
    dlopen(ORIGINAL_LIB_NAME, RTLD_NOW | RTLD_GLOBAL);
    load_java_payload(env, context);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    install_syscall_hooks();
    return JNI_VERSION_1_6;
}
