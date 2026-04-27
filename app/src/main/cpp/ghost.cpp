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

void load_original_lib_properly(JNIEnv* env, jobject context) {
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAppInfoMethod = env->GetMethodID(contextClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
    jobject appInfo = env->CallObjectMethod(context, getAppInfoMethod);

    jclass appInfoClass = env->GetObjectClass(appInfo);
    jfieldID nativeLibDirField = env->GetFieldID(appInfoClass, "nativeLibraryDir", "Ljava/lang/String;");
    jstring nativeLibDir = (jstring)env->GetObjectField(appInfo, nativeLibDirField);

    const char* dirChars = env->GetStringUTFChars(nativeLibDir, nullptr);
    std::string fullPath = std::string(dirChars) + "/" + ORIGINAL_LIB_NAME;
    env->ReleaseStringUTFChars(nativeLibDir, dirChars);

    jclass systemClass = env->FindClass("java/lang/System");
    jmethodID loadMethod = env->GetStaticMethodID(systemClass, "load", "(Ljava/lang/String;)V");
    jstring jPath = env->NewStringUTF(fullPath.c_str());
    env->CallStaticVoidMethod(systemClass, loadMethod, jPath);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Chimera: Critical failure loading %s", ORIGINAL_LIB_NAME);
    } else {
        LOGI("Chimera: Original library linked.");
    }
}

void load_java_payload(JNIEnv* env, jobject context) {
    LOGI("Chimera: Payload injection sequence started.");

    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAssetsMethod = env->GetMethodID(contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject assetManagerObj = env->CallObjectMethod(context, getAssetsMethod);
    AAssetManager* assetManager = AAssetManager_fromJava(env, assetManagerObj);

    AAsset* asset = AAssetManager_open(assetManager, PAYLOAD_ASSET_NAME, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Chimera: Payload asset '%s' not found.", PAYLOAD_ASSET_NAME);
        return;
    }

    off_t size = AAsset_getLength(asset);
    const void* buffer = AAsset_getBuffer(asset);
    LOGI("Chimera: Payload size: %ld bytes.", size);

    // Create Direct ByteBuffer
    jclass byteBufferClass = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocateDirectMethod = env->GetStaticMethodID(byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    jobject byteBuffer = env->CallStaticObjectMethod(byteBufferClass, allocateDirectMethod, (jint)size);
    void* directBuffer = env->GetDirectBufferAddress(byteBuffer);
    memcpy(directBuffer, buffer, (size_t)size);
    AAsset_close(asset);

    // Get Parent ClassLoader
    jmethodID getClassLoaderMethod = env->GetMethodID(contextClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parentClassLoader = env->CallObjectMethod(context, getClassLoaderMethod);

    // Create InMemoryDexClassLoader
    jclass classLoaderClass = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jmethodID classLoaderCtor = env->GetMethodID(classLoaderClass, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject inMemoryClassLoader = env->NewObject(classLoaderClass, classLoaderCtor, byteBuffer, parentClassLoader);

    if (inMemoryClassLoader) {
        LOGI("Chimera: Memory ClassLoader ready.");

        // Use Thread.currentThread().setContextClassLoader to help finding the class
        jclass threadClass = env->FindClass("java/lang/Thread");
        jmethodID currentThreadMethod = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
        jobject currentThread = env->CallStaticObjectMethod(threadClass, currentThreadMethod);
        jmethodID setContextClassLoaderMethod = env->GetMethodID(threadClass, "setContextClassLoader", "(Ljava/lang/ClassLoader;)V");
        env->CallVoidMethod(currentThread, setContextClassLoaderMethod, inMemoryClassLoader);

        // Try to load MainHook
        jmethodID loadClassMethod = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring mainClassName = env->NewStringUTF(MAIN_HOOK_CLASS);
        jclass mainClass = (jclass)env->CallObjectMethod(inMemoryClassLoader, loadClassMethod, mainClassName);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("Chimera: MainHook class discovery failed.");
            return;
        }

        if (mainClass) {
            jmethodID bootstrapMethod = env->GetStaticMethodID(mainClass, "nativeBootstrap", "(Landroid/content/Context;)V");
            if (bootstrapMethod) {
                env->CallStaticVoidMethod(mainClass, bootstrapMethod, context);
                LOGI("Chimera: SYSTEM ONLINE.");
            }
        }
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_spotify_music_SpotifyApplication_initGhost(JNIEnv* env, jclass clazz, jobject context) {
    LOGI("Chimera: initGhost called.");
    load_original_lib_properly(env, context);
    load_java_payload(env, context);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    install_syscall_hooks();
    return JNI_VERSION_1_6;
}
