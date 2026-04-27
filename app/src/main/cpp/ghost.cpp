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

// Direct Firebase Linkage (Emergency Fix)
extern "C" JNIEXPORT jboolean JNICALL Java_com_google_firebase_crashlytics_ndk_JniNativeApi_nativeInit(JNIEnv* env, jobject thiz, jobjectArray args, jobject obj) {
    LOGW("Chimera: Proxying Firebase nativeInit...");
    void* handle = dlopen(ORIGINAL_LIB_NAME, RTLD_NOW);
    if (handle) {
        auto orig_init = (jboolean (*)(JNIEnv*, jobject, jobjectArray, jobject))dlsym(handle, "Java_com_google_firebase_crashlytics_ndk_JniNativeApi_nativeInit");
        if (orig_init) return orig_init(env, thiz, args, obj);
    }
    return JNI_TRUE;
}

void load_java_payload(JNIEnv* env, jobject context) {
    LOGI("Chimera: Starting secure payload injection...");

    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAssetsMethod = env->GetMethodID(contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject assetManagerObj = env->CallObjectMethod(context, getAssetsMethod);
    AAssetManager* assetManager = AAssetManager_fromJava(env, assetManagerObj);

    AAsset* asset = AAssetManager_open(assetManager, PAYLOAD_ASSET_NAME, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Chimera: Critical Error! Payload asset missing.");
        return;
    }

    off_t size = AAsset_getLength(asset);
    const void* buffer = AAsset_getBuffer(asset);

    // Create Direct ByteBuffer
    jclass byteBufferClass = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocateDirectMethod = env->GetStaticMethodID(byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    jobject byteBuffer = env->CallStaticObjectMethod(byteBufferClass, allocateDirectMethod, (jint)size);
    void* directBuffer = env->GetDirectBufferAddress(byteBuffer);
    memcpy(directBuffer, buffer, (size_t)size);
    AAsset_close(asset);

    jmethodID getClassLoaderMethod = env->GetMethodID(contextClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject spotifyClassLoader = env->CallObjectMethod(context, getClassLoaderMethod);

    // Create InMemoryDexClassLoader
    jclass classLoaderClass = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jmethodID classLoaderCtor = env->GetMethodID(classLoaderClass, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject inMemoryClassLoader = env->NewObject(classLoaderClass, classLoaderCtor, byteBuffer, spotifyClassLoader);

    if (inMemoryClassLoader) {
        LOGI("Chimera: Virtual memory mapped.");

        // Use Context ClassLoader to bridge dependency gaps
        jclass threadClass = env->FindClass("java/lang/Thread");
        jmethodID currentThreadMethod = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
        jobject currentThread = env->CallStaticObjectMethod(threadClass, currentThreadMethod);
        jmethodID setContextClassLoaderMethod = env->GetMethodID(threadClass, "setContextClassLoader", "(Ljava/lang/ClassLoader;)V");
        env->CallVoidMethod(currentThread, setContextClassLoaderMethod, inMemoryClassLoader);

        jmethodID loadClassMethod = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring mainClassName = env->NewStringUTF(MAIN_HOOK_CLASS);
        jclass mainClass = (jclass)env->CallObjectMethod(inMemoryClassLoader, loadClassMethod, mainClassName);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("Chimera: ClassDiscovery: %s not found. Check if Xposed APIs are bundled.", MAIN_HOOK_CLASS);
            return;
        }

        if (mainClass) {
            jmethodID bootstrapMethod = env->GetStaticMethodID(mainClass, "nativeBootstrap", "(Landroid/content/Context;)V");
            if (bootstrapMethod) {
                env->CallStaticVoidMethod(mainClass, bootstrapMethod, context);
                LOGI("Chimera: [ BOOTSTRAP COMPLETE ]");
            }
        }
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_spotify_music_SpotifyApplication_initGhost(JNIEnv* env, jclass clazz, jobject context) {
    LOGI("Chimera: System breach successful.");

    // Safety link for Firebase
    dlopen(ORIGINAL_LIB_NAME, RTLD_NOW | RTLD_GLOBAL);

    load_java_payload(env, context);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    install_syscall_hooks();
    return JNI_VERSION_1_6;
}
